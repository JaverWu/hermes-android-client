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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        private const val CHANNEL_ID = "hermes_run_high"
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
        BitmapFactory.decodeResource(resources, R.mipmap.logo)
    }
    private var approvalHandled = false
    private var lastPersist = 0L

    /** 看门狗：最后一次收到流式活动的时刻；用于检测 SSE 假死（服务端发了 running 后再无事件）。 */
    @Volatile private var lastActivityTs = 0L
    /** 标记本轮是否已经结算（finalize/error/approval），保证幂等，避免看门狗与正常完成重复收尾。 */
    private var finalized = false
    /** 卡死看门狗协程句柄，每轮对话启动一个，结算时取消。 */
    private var stallJob: Job? = null
    /** 无活动多久判定为卡死（150s，略大于 SSE readTimeout 的 120s，避免误杀正常心跳）。 */
    private val STALL_TIMEOUT_MS = 150_000L

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
        finalized = false
        lastActivityTs = System.currentTimeMillis()
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
        // 防止 ActiveRunState.isStreaming 卡在 true：scope.cancel 可能中断
        // finalizeTurn/handleError 的协程，导致 reset() 没被调到，后续发消息全部被静默忽略
        ActiveRunState.reset()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===================== 一轮对话（带断线重试） =====================

    /** 最多重试次数（首轮 + 重试），应对切后台 / 网络切换导致的瞬时断链。 */
    private val MAX_RETRIES = 2

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
        // 每次收到任意事件都刷新 lastActivityTs，供看门狗判断是否卡死。
        fun touch() { lastActivityTs = System.currentTimeMillis() }
        val onDelta: (String) -> Unit = {
            touch()
            buffer.append(it)
            ActiveRunState.appendContent(it)
            persistThrottled()
        }
        val onThinking: (String) -> Unit = {
            touch()
            reasoningBuffer.append(it)
            ActiveRunState.appendThinking(it)
        }
        val onStatus: (String, String) -> Unit = { et, _ ->
            touch()
            ActiveRunState.setStatus(et)
            updateNotification(ActiveRunState.status.value)
        }
        val onToolProgress: (ToolProgressEvent) -> Unit = { ev ->
            touch()
            ActiveRunState.upsertTool(ToolCall(ev.id, ev.emoji, ev.title, ev.status, ev.preview, true))
            ActiveRunState.setToolStatus(ev.title)
            updateNotification(ActiveRunState.status.value)
            persistThrottled()
        }

        var attempt = 0

        // 卡死看门狗：若服务端发了 running 等事件后再无后续（无完成事件、无 [DONE]），
        // SSE 连接会因心跳注释一直活着，readUtf8Line 永久阻塞 → isStreaming 卡死。
        // 这里每 15s 检查一次"距上次活动是否超过 STALL_TIMEOUT_MS"，超时则强制收尾。
        stallJob?.cancel()
        stallJob = scope.launch {
            while (isActive) {
                delay(15_000)
                if (finalized) break
                val last = lastActivityTs
                if (last == 0L) continue
                if (ActiveRunState.isStreaming.value &&
                    System.currentTimeMillis() - last > STALL_TIMEOUT_MS) {
                    Log.w("RunWatcher", "STALL: no activity ${System.currentTimeMillis() - last}ms, force finalize")
                    forceFinalize()
                    break
                }
            }
        }

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

            // 已收到部分内容时不再重试——重试会丢弃已有内容且生成全新回复，
            // 在 Doze 环境下重试大概率再次失败。直接保存已有部分回复。
            if (buffer.isNotEmpty()) {
                Log.i("RunWatcher", "Connection lost but buffer has ${buffer.length} chars, saving partial content")
                finalizeTurn(partialWarning = true)
                return
            }

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
        finalized = false
        lastActivityTs = System.currentTimeMillis()
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
            if (m.id == assistantId) continue  // 跳过当前 streaming 占位，避免部分回复污染历史
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

    /**
     * 看门狗触发：SSE 卡死（服务端发了 running 后再无完成事件/[DONE]）时，
     * 用部分内容强制收尾，避免 isStreaming 永远为 true 卡死整个对话流程。
     */
    private fun forceFinalize() {
        Log.w("RunWatcher", "forceFinalize: stall timeout, finalizing turn with partial warning")
        finalizeTurn(partialWarning = true)
    }

    /** 把仍停留在 running/started 的工具归一为 completed，避免落库后一直显示"进行中"。 */
    private fun currentToolJsonNormalized(id: String?): String {
        if (id == null) return ""
        val list = ActiveRunState.toolCalls.value[id] ?: return ""
        val normalized = list.map { tc ->
            if (tc.status.equals("running", true) || tc.status.equals("started", true))
                tc.copy(status = "completed") else tc
        }
        return normalized.toJsonString()
    }

    private fun finalizeTurn(partialWarning: Boolean = false) {
        if (finalized) { Log.w("RunWatcher", "finalizeTurn already done, skip"); return }
        finalized = true
        stallJob?.cancel("finalized")
        val id = assistantId ?: return finishService()
        val bufferContent = buffer.toString()
        val reasoning = reasoningBuffer.toString()
        val toolJson = currentToolJsonNormalized(id)
        Log.i("RunWatcher", "finalizeTurn: buffer=${bufferContent.length} chars, reasoning=${reasoning.length} chars, tools=${toolJson.length} chars, partial=$partialWarning")

        // 用 runBlocking 确保 DB 写入完成后再 finishService，避免 scope.cancel 取消写入
        runBlocking {
            val row = db.messageDao().getById(id)
            if (row != null) {
                // buffer 为空时用 DB 已有内容作为 fallback（重试场景：resetBuffers 清了 buffer 但 DB 有上一轮的内容）
                val finalContent = bufferContent.ifBlank { row.content }
                val finalReasoning = reasoning.ifBlank { row.reasoning }
                val finalToolJson = toolJson.ifBlank { row.toolCallsJson }

                if (finalContent.isBlank() && finalReasoning.isBlank() && finalToolJson.isBlank()) {
                    Log.i("RunWatcher", "finalizeTurn: empty turn, deleting placeholder")
                    db.messageDao().deleteById(id)
                } else {
                    Log.i("RunWatcher", "finalizeTurn: saving ${finalContent.length} chars to DB (buffer=${bufferContent.length}, dbFallback=${row.content.length})")
                    db.messageDao().update(
                        row.copy(content = finalContent, reasoning = finalReasoning, toolCallsJson = finalToolJson, isStreaming = false)
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
        val notifTitle = if (partialWarning) "Hermes 回复了你（可能不完整）" else "Hermes 回复了你"
        val notifText = if (partialWarning) {
            "（可能不完整）${bufferContent.take(100)}".ifBlank { "回复可能不完整，点击查看" }
        } else {
            bufferContent.take(120).ifBlank { "点击查看完整回复" }
        }
        if (isAppInForeground()) {
            cancelNotification() // 前台：用户已看到 UI，移除陈旧的进行中通知
        } else {
            notifyDone(notifTitle, notifText)
        }
        finishService()
    }

    private fun handleApproval(req: ApprovalRequest) {
        if (finalized) { Log.w("RunWatcher", "handleApproval already done, skip"); return }
        finalized = true
        stallJob?.cancel("finalized")
        approvalHandled = true
        val id = assistantId ?: return
        val bufferContent = buffer.toString()
        val reasoning = reasoningBuffer.toString()
        val toolJson = currentToolJsonNormalized(id)
        Log.i("RunWatcher", "handleApproval: title=${req.title}")
        runBlocking {
            val row = db.messageDao().getById(id)
            if (row != null) {
                val finalContent = bufferContent.ifBlank { row.content }
                val finalReasoning = reasoning.ifBlank { row.reasoning }
                val finalToolJson = toolJson.ifBlank { row.toolCallsJson }
                if (finalContent.isBlank() && finalReasoning.isBlank() && finalToolJson.isBlank()) {
                    db.messageDao().deleteById(id)
                } else {
                    db.messageDao().update(
                        row.copy(content = finalContent, reasoning = finalReasoning, toolCallsJson = finalToolJson, isStreaming = false)
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
        cancelNotification() // 审批 UI 在聊天内展示，移除进行中通知即可
        finishService()
    }

    private fun handleError(e: Throwable) {
        if (finalized) { Log.w("RunWatcher", "handleError already done, skip"); return }
        finalized = true
        stallJob?.cancel("finalized")
        Log.e("RunWatcher", "handleError: ${e.javaClass.simpleName}: ${e.message}")
        val id = assistantId
        val bufferContent = buffer.toString()
        val reasoning = reasoningBuffer.toString()
        val toolJson = currentToolJsonNormalized(id)

        runBlocking {
            if (id != null) {
                val row = db.messageDao().getById(id)
                if (row != null) {
                    // buffer 为空时用 DB 已有内容作为 fallback
                    val finalContent = bufferContent.ifBlank { row.content }
                    val finalReasoning = reasoning.ifBlank { row.reasoning }
                    val finalToolJson = toolJson.ifBlank { row.toolCallsJson }

                    if (finalContent.isBlank() && finalReasoning.isBlank() && finalToolJson.isBlank()) {
                        Log.i("RunWatcher", "handleError: empty turn, deleting placeholder")
                        db.messageDao().deleteById(id)
                    } else {
                        Log.i("RunWatcher", "handleError: saving ${finalContent.length} chars to DB (buffer=${bufferContent.length}, dbFallback=${row.content.length})")
                        db.messageDao().update(
                            row.copy(content = finalContent, reasoning = finalReasoning, toolCallsJson = finalToolJson, isStreaming = false)
                        )
                    }
                }
            }
            // 直接同步插入系统消息（不用 postSystemMessage 的 scope.launch，避免被 cancel）
            db.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId!!,
                    role = MessageEntity.ROLE_SYSTEM,
                    content = "请求出错：${e.message ?: e.javaClass.simpleName}",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        ActiveRunState.reset()
        if (isAppInForeground()) {
            cancelNotification()
        } else {
            notifyDone("Hermes 请求出错", e.message ?: e.javaClass.simpleName)
        }
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
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    enableVibration(true)
                    enableLights(true)
                }
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
            .setSmallIcon(R.mipmap.logo)
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
            .setSmallIcon(R.mipmap.logo)
            .setLargeIcon(logoBitmap)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, notif)
    }

    /** 主动移除进行中的通知（前台聊天时，UI 已展示最终内容，无需在通知栏保留陈旧提示）。 */
    private fun cancelNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(NOTIF_ID)
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

    /** 检查当前 App 是否处于前台（用户正在看界面），前台时不弹完成通知 */
    private fun isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val processes = am.runningAppProcesses ?: return false
        for (process in processes) {
            if (process.processName == packageName) {
                return process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        }
        return false
    }

    private fun finishService() {
        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        stopForeground(Service.STOP_FOREGROUND_DETACH) // 保留通知（已替换为"回复了你"/错误）
        stopSelf()
    }
}
