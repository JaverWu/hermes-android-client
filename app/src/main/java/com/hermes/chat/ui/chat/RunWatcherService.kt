package com.hermes.chat.ui.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermes.chat.HermesApplication
import com.hermes.chat.R
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.remote.ApprovalDetector
import com.hermes.chat.data.model.ApprovalRequest
import com.hermes.chat.data.model.ChatMessage
import com.hermes.chat.data.model.ToolCall
import com.hermes.chat.data.model.ToolProgressEvent
import com.hermes.chat.data.model.toJsonString
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.data.remote.HermesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 前台 Service：承接一次对话轮次的流式接收。
 *
 * 为什么需要它：SSE 必须保持连接才能收到流式回复，而后端不会主动推送到"已关闭的客户端"。
 * 把流式接收放在进程级的前台 Service 里，用户发完消息即可切走/锁屏——回复照样落库，
 * 完成时还会弹一条本地通知，点开即回到对应会话。
 */
class RunWatcherService : Service() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_ASSISTANT_ID = "assistant_id"
        private const val CHANNEL_ID = "hermes_run"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = HermesApi()
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository

    private var conversationId: String? = null
    private var assistantId: String? = null
    private val buffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val logoBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ic_logo_large)
    }
    private var approvalHandled = false
    private var lastPersist = 0L

    /**
     * 后台/锁屏时保持 CPU 唤醒。前台 Service 只保活进程，锁屏/挂后台后 CPU 仍可能休眠，
     * 导致 SSE 的 readUtf8Line 阻塞线程被挂起、数据到了也读不到。acquire 带 10 分钟超时兜底防泄漏。
     */
    private val wakeLock by lazy {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hermes:SSE")
    }

    override fun onCreate() {
        super.onCreate()
        db = (application as HermesApplication).database
        settings = SettingsRepository(application)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cid = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        val aid = intent?.getStringExtra(EXTRA_ASSISTANT_ID)
        Log.i("RunWatcher", "onStartCommand cid=$cid aid=$aid")
        if (cid == null || aid == null) {
            Log.w("RunWatcher", "Missing extras, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        conversationId = cid
        assistantId = aid
        ActiveRunState.begin(aid)
        startForeground(NOTIF_ID, buildNotification("Hermes 正在处理…", ongoing = true))
        // 保活 CPU：前台 Service 只保活进程，锁屏/挂后台后 CPU 仍可能休眠，
        // 导致 SSE 的 readUtf8Line 阻塞线程被挂起、数据到了也读不到。
        if (wakeLock.isHeld.not()) {
            wakeLock.acquire(10 * 60 * 1000L) // 10 分钟超时保护，防止泄漏
        }
        scope.launch { runTurn() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===================== 一轮对话（带断线重试） =====================

    /** 最多重试次数（首轮 + 重试），应对切后台 / 网络切换导致的瞬时断链。 */
    private val MAX_RETRIES = 4

    private suspend fun runTurn() {
        val base = settings.baseUrl
        val key = settings.apiKey
        val model = settings.model
        Log.i("RunWatcher", "runTurn start base=${base.take(40)} model=$model configured=${settings.isConfigured()}")
        if (!settings.isConfigured()) {
            Log.w("RunWatcher", "Not configured, aborting")
            postSystemMessage("请先在「设置」中填写 API 地址和 Key。")
            finishService()
            return
        }

        // 流式回调：写入缓冲 + ActiveRunState + 节流落库（每轮复用同一组回调）
        val onDelta: (String) -> Unit = {
            buffer.append(it)
            ActiveRunState.appendContent(it)
            persistThrottled()
        }
        val onThinking: (String) -> Unit = {
            reasoningBuffer.append(it)
            ActiveRunState.appendThinking(it)
        }
        val onStatus: (String, String) -> Unit = { et, _ ->
            ActiveRunState.setStatus(et)
            updateNotification(ActiveRunState.status.value)
        }
        val onToolProgress: (ToolProgressEvent) -> Unit = { ev ->
            ActiveRunState.upsertTool(ToolCall(ev.id, ev.emoji, ev.title, ev.status, ev.preview, true))
            persistThrottled()
        }

        var attempt = 0

        while (attempt <= MAX_RETRIES) {
            attempt++
            Log.i("RunWatcher", "=== attempt $attempt / $MAX_RETRIES ===")
            resetBuffers() // 清空缓冲与实时态，从干净状态重新拉流

            // 用 CompletableDeferred 等待本轮真正结束（run 模式子协程也覆盖）
            val done = CompletableDeferred<Unit>()

            var succeeded = false
            var approvalEnded = false
            var caught: Throwable? = null

            val onDone: () -> Unit = {
                Log.i("RunWatcher", "onDone called (attempt $attempt)")
                succeeded = true
                if (!done.isCompleted) done.complete(Unit)
            }
            val onError: (Throwable) -> Unit = { e ->
                Log.i("RunWatcher", "onError called (attempt $attempt): ${e.javaClass.simpleName}: ${e.message}")
                caught = e
                if (!done.isCompleted) done.complete(Unit)
            }
            // 审批会终结本轮并直接结束 Service，不再重试
            val onApproval: (ApprovalRequest) -> Unit = { req ->
                Log.i("RunWatcher", "onApproval called (attempt $attempt)")
                approvalEnded = true
                succeeded = true
                handleApproval(req)
                if (!done.isCompleted) done.complete(Unit)
            }

            val history = buildHistory()
            Log.i("RunWatcher", "history size=${history.size}, last msg role=${history.lastOrNull()?.role}")
            try {
                if (settings.runMode) {
                    Log.i("RunWatcher", "runMode=true, calling createRun")
                    api.createRun(
                        baseUrl = base, apiKey = key, model = model, messages = history,
                        onRunId = { runId ->
                            Log.i("RunWatcher", "createRun got runId=$runId")
                            scope.launch {
                                api.subscribeRunEvents(
                                    baseUrl = base, apiKey = key, runId = runId,
                                    onDelta = onDelta, onThinking = onThinking, onStatus = onStatus,
                                    onToolProgress = onToolProgress, onApproval = onApproval,
                                    onDone = onDone, onError = onError
                                )
                            }
                        },
                        onError = onError
                    )
                } else {
                    Log.i("RunWatcher", "runMode=false, calling streamChat")
                    api.streamChat(
                        baseUrl = base, apiKey = key, model = model, messages = history,
                        onDelta = onDelta, onThinking = onThinking, onStatus = onStatus,
                        onToolProgress = onToolProgress, onApproval = onApproval,
                        onDone = onDone, onError = onError
                    )
                }
            } catch (e: Exception) {
                Log.e("RunWatcher", "streamChat/createRun threw (attempt $attempt): ${e.javaClass.simpleName}: ${e.message}")
                caught = e
                if (!done.isCompleted) done.complete(Unit)
            }

            Log.i("RunWatcher", "awaiting done (attempt $attempt)")
            done.await()
            Log.i("RunWatcher", "done returned (attempt $attempt), succeeded=$succeeded, approvalEnded=$approvalEnded, caught=${caught?.javaClass?.simpleName}")

            if (approvalEnded) { Log.i("RunWatcher", "approval ended, returning"); return }
            if (succeeded) { Log.i("RunWatcher", "succeeded, finalizing turn"); finalizeTurn(); return }

            // 本轮失败：非瞬时错误（鉴权 / 服务器错误等）或重试耗尽 → 真正判失败
            if (!isTransientNetworkError(caught) || attempt >= MAX_RETRIES) {
                Log.w("RunWatcher", "Non-transient error or exhausted retries: ${caught?.javaClass?.simpleName}: ${caught?.message}")
                handleError(caught ?: java.io.IOException("未知错误"))
                return
            }
            // 瞬时网络错误（如 software caused connection abort / 切后台断链）：退避后重试
            Log.i("RunWatcher", "Transient error, retrying after delay (attempt $attempt)")
            updateNotification("网络中断，正在重连… ($attempt/$MAX_RETRIES)")
            delay(backoffMillis(attempt))
        }
    }

    /** 重置本轮缓冲与共享实时态，使重试从干净状态重新开始。 */
    private fun resetBuffers() {
        buffer.setLength(0)
        reasoningBuffer.setLength(0)
        lastPersist = 0L
        approvalHandled = false
        ActiveRunState.reset()
        ActiveRunState.begin(assistantId!!)
    }

    /** 判断是否为可重试的瞬时网络错误（切后台 / 网络切换 / 心跳超时等）。 */
    private fun isTransientNetworkError(e: Throwable?): Boolean {
        if (e == null) return false
        val msg = (e.message ?: "").lowercase()
        val isNetType = e is java.net.SocketException
            || e is java.net.SocketTimeoutException
            || e is java.io.EOFException
            || e is java.net.ProtocolException
        return isNetType
            || msg.contains("connection abort")
            || msg.contains("connection reset")
            || msg.contains("broken pipe")
            || msg.contains("software caused")
            || msg.contains("econn")
            || msg.contains("timeout")
            || msg.contains("unexpected end of stream")
            || msg.contains("failed to connect")
            || msg.contains("network is unreachable")
            || msg.contains("stream ended without any data") // 空流：连接关闭但无数据，应重试而非当成功
    }

    /** 指数退避：1s, 2s, 4s, 8s（封顶 8s）。 */
    private fun backoffMillis(attempt: Int): Long {
        val base = 1000L * (1 shl (attempt - 1))
        return minOf(base, 8000L)
    }

    private suspend fun buildHistory(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val sys = settings.systemPrompt
        if (sys.isNotBlank()) list.add(ChatMessage("system", sys))
        val msgs = db.messageDao().getByConversation(conversationId!!).first()
        Log.i("RunWatcher", "buildHistory: fetched ${msgs.size} messages from DB")
        for (m in msgs) {
            when (m.role) {
                MessageEntity.ROLE_USER -> {
                    Log.i("RunWatcher", "  user msg: ${m.content.take(40)}...")
                    list.add(ChatMessage("user", m.content))
                }
                MessageEntity.ROLE_ASSISTANT ->
                    if (m.content.isNotBlank()) list.add(ChatMessage("assistant", m.content))
            }
        }
        Log.i("RunWatcher", "buildHistory: built ${list.size} messages (including system=${if (sys.isNotBlank()) 1 else 0})")
        return list
    }

    // ===================== 落库（节流） =====================

    private fun persistThrottled() {
        val id = assistantId ?: return
        val ts = System.currentTimeMillis()
        if (ts - lastPersist < 250 && buffer.isNotEmpty()) return
        lastPersist = ts
        val content = buffer.toString()
        val reasoning = reasoningBuffer.toString()
        val toolJson = ActiveRunState.toolCalls.value[id]?.toJsonString() ?: ""
        scope.launch {
            db.messageDao().getById(id)?.let { row ->
                db.messageDao().update(
                    row.copy(content = content, reasoning = reasoning, toolCallsJson = toolJson, isStreaming = true)
                )
            }
        }
    }

    // ===================== 结束 / 异常 =====================

    private fun finalizeTurn() {
        val id = assistantId ?: return finishService()
        val content = buffer.toString()
        val reasoning = reasoningBuffer.toString()
        val toolJson = ActiveRunState.toolCalls.value[id]?.toJsonString() ?: ""
        Log.i("RunWatcher", "finalizeTurn: content=${content.length} chars, reasoning=${reasoning.length} chars, tools=${toolJson.length} chars")

        scope.launch {
            db.messageDao().getById(id)?.let { row ->
                if (content.isBlank() && reasoning.isBlank() && toolJson.isBlank()) {
                    Log.i("RunWatcher", "finalizeTurn: empty turn, deleting placeholder")
                    db.messageDao().deleteById(id)
                } else {
                    db.messageDao().update(
                        row.copy(content = content, reasoning = reasoning, toolCallsJson = toolJson, isStreaming = false)
                    )
                }
            }
            // 文本兜底：本回合没有结构化审批时，检测助手正文里的指令
            if (!approvalHandled) {
                val last = db.messageDao().getByConversation(conversationId!!).first()
                    .lastOrNull { it.role == MessageEntity.ROLE_ASSISTANT && it.content.isNotBlank() }
                if (last != null) {
                    val opts = ApprovalDetector.detect(last.content)
                    if (opts.isNotEmpty()) {
                        db.messageDao().insert(
                            MessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId!!,
                                role = MessageEntity.ROLE_APPROVAL,
                                content = last.content,
                                createdAt = System.currentTimeMillis(),
                                type = MessageEntity.TYPE_APPROVAL,
                                optionsJson = opts.joinToString("\n"),
                                approvalTitle = "Hermes 请求你的确认",
                                approvalDetail = last.content,
                                approvalStatus = MessageEntity.STATUS_PENDING
                            )
                        )
                    }
                }
            }
            // 刷新会话更新时间
            db.conversationDao().getById(conversationId!!)?.let { conv ->
                db.conversationDao().update(conv.copy(updatedAt = System.currentTimeMillis()))
            }
        }

        ActiveRunState.reset()
        notifyDone("Hermes 回复了你", content.take(120).ifBlank { "点击查看完整回复" })
        finishService()
    }

    private fun handleApproval(req: ApprovalRequest) {
        approvalHandled = true
        val id = assistantId ?: return
        val content = buffer.toString()
        val reasoning = reasoningBuffer.toString()
        val toolJson = ActiveRunState.toolCalls.value[id]?.toJsonString() ?: ""
        Log.i("RunWatcher", "handleApproval: title=${req.title}")
        scope.launch {
            db.messageDao().getById(id)?.let { row ->
                if (content.isBlank() && reasoning.isBlank() && toolJson.isBlank()) {
                    db.messageDao().deleteById(id)
                } else {
                    db.messageDao().update(
                        row.copy(content = content, reasoning = reasoning, toolCallsJson = toolJson, isStreaming = false)
                    )
                }
            }
            db.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId!!,
                    role = MessageEntity.ROLE_APPROVAL,
                    content = req.detail,
                    createdAt = System.currentTimeMillis(),
                    type = MessageEntity.TYPE_APPROVAL,
                    optionsJson = req.options.joinToString("\n"),
                    approvalTitle = req.title,
                    approvalDetail = req.detail,
                    approvalStatus = MessageEntity.STATUS_PENDING
                )
            )
        }
        ActiveRunState.reset()
        finishService()
    }

    private fun handleError(e: Throwable) {
        Log.e("RunWatcher", "handleError: ${e.javaClass.simpleName}: ${e.message}")
        val id = assistantId
        val content = buffer.toString()
        val reasoning = reasoningBuffer.toString()
        val toolJson = if (id != null) ActiveRunState.toolCalls.value[id]?.toJsonString() ?: "" else ""
        scope.launch {
            if (id != null) {
                db.messageDao().getById(id)?.let { row ->
                    if (content.isBlank() && reasoning.isBlank() && toolJson.isBlank()) {
                        db.messageDao().deleteById(id)
                    } else {
                        db.messageDao().update(
                            row.copy(content = content, reasoning = reasoning, toolCallsJson = toolJson, isStreaming = false)
                        )
                    }
                }
            }
            postSystemMessage("请求出错：${e.message ?: e.javaClass.simpleName}")
        }
        ActiveRunState.reset()
        notifyDone("Hermes 请求出错", e.message ?: e.javaClass.simpleName)
        finishService()
    }

    // ===================== 通知 =====================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = getString(R.string.notification_channel_desc) }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): android.app.Notification {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(logoBitmap)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text, ongoing = true))
    }

    private fun notifyDone(title: String, text: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        val pi = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(logoBitmap)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, notif)
    }

    private fun postSystemMessage(text: String) {
        scope.launch {
            db.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId!!,
                    role = MessageEntity.ROLE_SYSTEM,
                    content = text,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun finishService() {
        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        stopForeground(Service.STOP_FOREGROUND_DETACH) // 保留通知（已替换为"回复了你"/错误）
        stopSelf()
    }
}
