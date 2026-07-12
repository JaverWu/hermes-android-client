package com.hermes.chat.ui.chat

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chat.HermesApplication
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.model.ToolCall
import com.hermes.chat.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db: AppDatabase = (application as HermesApplication).database
    private val settings = SettingsRepository(application)

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages

    /** 实时态全部委托给 [ActiveRunState]：流式接收已移入前台 Service，与 Activity 生命周期解耦 */
    val isStreaming: StateFlow<Boolean> get() = ActiveRunState.isStreaming
    val thinkingContent: StateFlow<String> get() = ActiveRunState.thinking
    val statusText: StateFlow<String> get() = ActiveRunState.status
    val liveContent: StateFlow<String> get() = ActiveRunState.content
    val toolCalls: StateFlow<Map<String, List<ToolCall>>> get() = ActiveRunState.toolCalls
    val streamingAssistantId: StateFlow<String?> get() = ActiveRunState.streamingAssistantId

    private val _runMode = MutableStateFlow(settings.runMode)
    val runMode: StateFlow<Boolean> = _runMode

    private var conversationId: String? = null
    private var observedCid: String? = null
    private var initialized = false

    /**
     * 发送流水线专用作用域：故意不与 ViewModel 生命周期绑定。
     * 若用 viewModelScope，用户发完消息立即退出 Activity 时 onCleared() 会取消协程，
     * 导致 startForegroundService 来不及执行 → 助手占位行(isStreaming=true)已写库但 Service 没起来 → 永久卡住不回复。
     * 用独立 sendScope 保证：一旦决定发送，前台 Service 一定被拉起，后台流式才能接管。
     */
    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(existingConversationId: String?) {
        if (initialized) return
        initialized = true
        if (existingConversationId != null) {
            conversationId = existingConversationId
            observeConversation(existingConversationId)
        }
    }

    fun toggleRunMode() {
        val next = !settings.runMode
        settings.runMode = next
        _runMode.value = next
    }

    private fun observeConversation(cid: String) {
        if (observedCid == cid) return
        observedCid = cid
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().getByConversation(cid).collect { list ->
                // 清理"中断流式遗留的空助手消息"：无内容/无推理/无工具且非进行中
                val cleaned = list.toMutableList()
                while (cleaned.isNotEmpty()) {
                    val last = cleaned.last()
                    if (last.role == MessageEntity.ROLE_ASSISTANT &&
                        last.content.isBlank() && last.reasoning.isBlank() && last.toolCalls().isEmpty() &&
                        !last.isStreaming
                    ) {
                        cleaned.removeAt(cleaned.lastIndex)
                    } else break
                }
                _messages.value = cleaned
            }
        }
    }

    private fun newId() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    private fun appendMessage(m: MessageEntity) {
        _messages.value = _messages.value + m
    }

    // ===================== 草稿：按会话缓存未发送输入 =====================

    fun loadDraft(): String = conversationId?.let { settings.getDraft(it) } ?: ""

    fun saveDraft(text: String) {
        conversationId?.let { settings.setDraft(it, text) }
    }

    private fun clearDraft() {
        conversationId?.let { settings.clearDraft(it) }
    }

    // ===================== 发送 =====================

    fun sendUserMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (ActiveRunState.isStreaming.value) {
            // 上一次 Service 被 cancel 时可能没调 reset()，isStreaming 卡在 true
            // 检查是否真的有 Service 在跑：如果没有，强制 reset 并继续
            val serviceRunning = isRunWatcherServiceRunning()
            Log.w("ChatVM", "sendUserMessage blocked: isStreaming=true, serviceRunning=$serviceRunning")
            if (!serviceRunning) {
                Log.w("ChatVM", "Stale isStreaming detected, force resetting ActiveRunState")
                ActiveRunState.reset()
            } else {
                return
            }
        }
        clearDraft()
        // assistantId 在主线程先生成（UUID 线程安全），确保 catch 中也能精准清理占位行
        val assistantId = newId()
        // 用 sendScope（非 viewModelScope）：即使用户发完立即退出 Activity、ViewModel 被
        // onCleared 取消，这段逻辑也一定会跑到 startForegroundService，后台流式才能正常接管。
        sendScope.launch {
            try {
                ensureConversation(content)
                val cid = conversationId!!
                observeConversation(cid)

                val userMsg = MessageEntity(
                    id = newId(),
                    conversationId = cid,
                    role = MessageEntity.ROLE_USER,
                    content = content,
                    createdAt = now()
                )
                db.messageDao().insert(userMsg)
                appendMessage(userMsg)

                // 先落一条"进行中"的助手占位消息，再由前台 Service 接管流式写入
                val assistantMsg = MessageEntity(
                    id = assistantId,
                    conversationId = cid,
                    role = MessageEntity.ROLE_ASSISTANT,
                    content = "",
                    createdAt = now(),
                    isStreaming = true
                )
                db.messageDao().insert(assistantMsg)
                appendMessage(assistantMsg)

                ActiveRunState.begin(assistantId)

                // 启动前台 Service：流式接收移到进程级，发完即可切走/锁屏，回复照样落库 + 弹通知
                val intent = Intent(getApplication(), RunWatcherService::class.java).apply {
                    putExtra(RunWatcherService.EXTRA_CONVERSATION_ID, cid)
                    putExtra(RunWatcherService.EXTRA_ASSISTANT_ID, assistantId)
                }
                Log.i("ChatVM", "startForegroundService cid=$cid aid=$assistantId")
                ContextCompat.startForegroundService(getApplication(), intent)
            } catch (e: Exception) {
                Log.e("ChatVM", "sendUserMessage FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                // 任何失败都必须清理占位行 + 复位，避免 isStreaming 永久卡死
                try { db.messageDao().deleteById(assistantId) } catch (_: Exception) {}
                ActiveRunState.reset()
                val cid = conversationId ?: return@launch
                db.messageDao().insert(
                    MessageEntity(
                        id = newId(),
                        conversationId = cid,
                        role = MessageEntity.ROLE_SYSTEM,
                        content = "发送失败：${e.message ?: e.javaClass.simpleName}",
                        createdAt = now()
                    )
                )
            }
        }
    }

    fun respondToApproval(messageId: String, option: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = db.messageDao().getById(messageId)
            if (msg != null && msg.approvalStatus == MessageEntity.STATUS_PENDING) {
                db.messageDao().update(msg.copy(approvalStatus = MessageEntity.STATUS_RESOLVED))
            }
        }
        sendUserMessage(option)
    }

    /** 删除某条消息（长按菜单触发） */
    fun deleteMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().deleteById(id)
        }
    }

    private suspend fun ensureConversation(firstUserText: String) {
        if (conversationId != null) return
        val id = newId()
        conversationId = id
        db.conversationDao().insert(
            ConversationEntity(
                id = id,
                title = firstUserText.take(40),
                createdAt = now(),
                updatedAt = now()
            )
        )
    }

    /** 检查 RunWatcherService 是否正在运行（用于检测 isStreaming 假死状态） */
    private fun isRunWatcherServiceRunning(): Boolean {
        val mgr = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return mgr.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == RunWatcherService::class.java.name }
    }
}
