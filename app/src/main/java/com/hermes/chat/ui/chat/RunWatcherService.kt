package com.hermes.chat.ui.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
    private var approvalHandled = false
    private var lastPersist = 0L

    override fun onCreate() {
        super.onCreate()
        db = (application as HermesApplication).database
        settings = SettingsRepository(application)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        assistantId = intent?.getStringExtra(EXTRA_ASSISTANT_ID)
        if (conversationId == null || assistantId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ActiveRunState.begin(assistantId!!)
        startForeground(NOTIF_ID, buildNotification("Hermes 正在处理…", ongoing = true))
        scope.launch { runTurn() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===================== 一轮对话 =====================

    private suspend fun runTurn() {
        val base = settings.baseUrl
        val key = settings.apiKey
        val model = settings.model
        if (!settings.isConfigured()) {
            postSystemMessage("请先在「设置」中填写 API 地址和 Key。")
            finishService()
            return
        }

        val history = buildHistory()

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
        val onApproval: (ApprovalRequest) -> Unit = { handleApproval(it) }
        val onDone: () -> Unit = { finalizeTurn() }
        val onError: (Throwable) -> Unit = { handleError(it) }

        try {
            if (settings.runMode) {
                api.createRun(
                    baseUrl = base, apiKey = key, model = model, messages = history,
                    onRunId = { runId ->
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
                api.streamChat(
                    baseUrl = base, apiKey = key, model = model, messages = history,
                    onDelta = onDelta, onThinking = onThinking, onStatus = onStatus,
                    onToolProgress = onToolProgress, onApproval = onApproval,
                    onDone = onDone, onError = onError
                )
            }
        } catch (e: Exception) {
            handleError(e)
        }
    }

    private suspend fun buildHistory(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val sys = settings.systemPrompt
        if (sys.isNotBlank()) list.add(ChatMessage("system", sys))
        val msgs = db.messageDao().getByConversation(conversationId!!).first()
        for (m in msgs) {
            when (m.role) {
                MessageEntity.ROLE_USER ->
                    list.add(ChatMessage("user", m.content))
                MessageEntity.ROLE_ASSISTANT ->
                    if (m.content.isNotBlank()) list.add(ChatMessage("assistant", m.content))
            }
        }
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
        stopForeground(Service.STOP_FOREGROUND_DETACH) // 保留通知（已替换为"回复了你"/错误）
        stopSelf()
    }
}
