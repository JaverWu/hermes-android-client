package com.hermes.chat.ui.chat

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chat.HermesApplication
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.model.ToolCall
import com.hermes.chat.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
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
        if (content.isEmpty() || ActiveRunState.isStreaming.value) return
        clearDraft()
        viewModelScope.launch(Dispatchers.IO) {
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
            val assistantId = newId()
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
            ContextCompat.startForegroundService(getApplication(), intent)
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
}
