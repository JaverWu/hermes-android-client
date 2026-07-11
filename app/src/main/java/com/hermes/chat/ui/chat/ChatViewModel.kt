package com.hermes.chat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chat.HermesApplication
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.model.ApprovalRequest
import com.hermes.chat.data.model.ChatMessage
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.data.remote.ApprovalDetector
import com.hermes.chat.data.remote.HermesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db: AppDatabase = (application as HermesApplication).database
    private val settings = SettingsRepository(application)
    private val api = HermesApi()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    /** 当前正在流式输出的助手消息 ID，供 Adapter 判断打字指示器 */
    var streamingAssistantId: String? = null
        private set

    private var conversationId: String? = null
    private val streamingBuffer = StringBuilder()
    private var approvalHandledThisTurn = false
    private var streamingTurnId = 0
    private var initialized = false

    fun init(existingConversationId: String?) {
        if (initialized) return
        initialized = true
        if (existingConversationId != null) {
            conversationId = existingConversationId
            viewModelScope.launch(Dispatchers.IO) {
                _messages.value = db.messageDao().getByConversation(existingConversationId).first()
            }
        }
    }

    private fun newId() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    private fun appendMessage(m: MessageEntity) {
        _messages.value = _messages.value + m
    }

    private fun updateMessage(updated: MessageEntity) {
        _messages.value = _messages.value.map { if (it.id == updated.id) updated else it }
    }

    private fun removeMessage(id: String) {
        _messages.value = _messages.value.filter { it.id != id }
    }

    fun sendUserMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _isStreaming.value) return
        viewModelScope.launch(Dispatchers.IO) {
            ensureConversation(content)
            val userMsg = MessageEntity(
                id = newId(),
                conversationId = conversationId!!,
                role = MessageEntity.ROLE_USER,
                content = content,
                createdAt = now()
            )
            db.messageDao().insert(userMsg)
            appendMessage(userMsg)
            startStreaming()
        }
    }

    fun respondToApproval(messageId: String, option: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = _messages.value.find { it.id == messageId }
            if (msg != null && msg.approvalStatus == MessageEntity.STATUS_PENDING) {
                val resolved = msg.copy(approvalStatus = MessageEntity.STATUS_RESOLVED)
                db.messageDao().update(resolved)
                updateMessage(resolved)
            }
        }
        // 把选中的指令作为用户消息回传，继续对话
        sendUserMessage(option)
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

    private fun startStreaming() {
        val base = settings.baseUrl
        val key = settings.apiKey
        // 模型固定使用 Hermes 默认模型，不需要用户填写
        val model = settings.model

        if (!settings.isConfigured()) {
            val warnMsg = MessageEntity(
                id = newId(),
                conversationId = conversationId!!,
                role = MessageEntity.ROLE_SYSTEM,
                content = "请先在「设置」中填写 API 地址和 Key。",
                createdAt = now()
            )
            viewModelScope.launch(Dispatchers.IO) { db.messageDao().insert(warnMsg) }
            appendMessage(warnMsg)
            return
        }

        _isStreaming.value = true
        streamingBuffer.clear()
        approvalHandledThisTurn = false

        val assistantId = newId()
        streamingAssistantId = assistantId
        val assistantMsg = MessageEntity(
            id = assistantId,
            conversationId = conversationId!!,
            role = MessageEntity.ROLE_ASSISTANT,
            content = "",
            createdAt = now()
        )
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().insert(assistantMsg) }
        appendMessage(assistantMsg)

        val history = buildHistoryForApi()
        val myTurn = ++streamingTurnId

        viewModelScope.launch(Dispatchers.IO) {
            api.streamChat(
                baseUrl = base,
                apiKey = key,
                model = model,
                messages = history,
                onDelta = { delta ->
                    if (streamingAssistantId == null) return@streamChat
                    streamingBuffer.append(delta)
                    val cur = _messages.value.find { it.id == streamingAssistantId } ?: return@streamChat
                    updateMessage(cur.copy(content = streamingBuffer.toString()))
                },
                onApproval = { req -> handleApproval(req) },
                onDone = { finalizeTurn(myTurn) },
                onError = { e -> handleError(e, myTurn) }
            )
        }
    }

    private fun handleApproval(req: ApprovalRequest) {
        approvalHandledThisTurn = true
        finalizeAssistant(removeIfBlank = true)
        streamingAssistantId = null
        _isStreaming.value = false

        val approvalMsg = MessageEntity(
            id = newId(),
            conversationId = conversationId!!,
            role = MessageEntity.ROLE_APPROVAL,
            content = req.detail,
            createdAt = now(),
            type = MessageEntity.TYPE_APPROVAL,
            optionsJson = req.options.joinToString("\n"),
            approvalTitle = req.title,
            approvalDetail = req.detail,
            approvalStatus = MessageEntity.STATUS_PENDING
        )
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().insert(approvalMsg) }
        appendMessage(approvalMsg)
    }

    private fun finalizeAssistant(removeIfBlank: Boolean) {
        val id = streamingAssistantId ?: return
        val cur = _messages.value.find { it.id == id } ?: return
        val finalContent = streamingBuffer.toString()
        if (removeIfBlank && finalContent.isBlank()) {
            viewModelScope.launch(Dispatchers.IO) { db.messageDao().deleteById(id) }
            removeMessage(id)
            return
        }
        val finalized = cur.copy(content = finalContent)
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().update(finalized) }
        updateMessage(finalized)
    }

    private fun finalizeTurn(myTurn: Int) {
        if (streamingAssistantId != null) {
            finalizeAssistant(removeIfBlank = true)
            streamingAssistantId = null
        }

        // 文本兜底：本回合没有结构化审批时，检测助手正文里的指令
        if (!approvalHandledThisTurn) {
            val last = _messages.value.lastOrNull { it.role == MessageEntity.ROLE_ASSISTANT }
            if (last != null) {
                val opts = ApprovalDetector.detect(last.content)
                if (opts.isNotEmpty()) {
                    val approvalMsg = MessageEntity(
                        id = newId(),
                        conversationId = conversationId!!,
                        role = MessageEntity.ROLE_APPROVAL,
                        content = last.content,
                        createdAt = now(),
                        type = MessageEntity.TYPE_APPROVAL,
                        optionsJson = opts.joinToString("\n"),
                        approvalTitle = "Hermes 请求你的确认",
                        approvalDetail = last.content,
                        approvalStatus = MessageEntity.STATUS_PENDING
                    )
                    viewModelScope.launch(Dispatchers.IO) { db.messageDao().insert(approvalMsg) }
                    appendMessage(approvalMsg)
                }
            }
        }

        // 只有最新回合结束时才复位「流式」状态，避免旧回合的收尾误复位新回合
        if (myTurn == streamingTurnId) {
            _isStreaming.value = false
        }
        conversationId?.let { cid ->
            viewModelScope.launch(Dispatchers.IO) {
                db.conversationDao().getById(cid)?.let { conv ->
                    db.conversationDao().update(conv.copy(updatedAt = now()))
                }
            }
        }
    }

    private fun handleError(e: Throwable, myTurn: Int) {
        streamingAssistantId?.let { id ->
            val cur = _messages.value.find { it.id == id }
            if (cur != null) {
                if (cur.content.isBlank()) {
                    viewModelScope.launch(Dispatchers.IO) { db.messageDao().deleteById(id) }
                    removeMessage(id)
                } else {
                    // 保存已接收的部分内容到数据库
                    viewModelScope.launch(Dispatchers.IO) { db.messageDao().update(cur) }
                }
            }
        }
        streamingAssistantId = null
        if (myTurn == streamingTurnId) {
            _isStreaming.value = false
        }
        val cid = conversationId ?: return
        val errorMsg = MessageEntity(
            id = newId(),
            conversationId = cid,
            role = MessageEntity.ROLE_SYSTEM,
            content = "请求出错：${e.message ?: e.javaClass.simpleName}",
            createdAt = now()
        )
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().insert(errorMsg) }
        appendMessage(errorMsg)
    }

    private fun buildHistoryForApi(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val sys = settings.systemPrompt
        if (sys.isNotBlank()) list.add(ChatMessage("system", sys))
        for (m in _messages.value) {
            when (m.role) {
                MessageEntity.ROLE_USER ->
                    list.add(ChatMessage("user", m.content))
                MessageEntity.ROLE_ASSISTANT ->
                    if (m.content.isNotBlank()) list.add(ChatMessage("assistant", m.content))
                // 审批卡片 / 系统消息不进入 API 历史
            }
        }
        return list
    }
}
