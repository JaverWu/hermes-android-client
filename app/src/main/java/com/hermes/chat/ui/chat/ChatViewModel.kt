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
import com.hermes.chat.data.model.ToolCall
import com.hermes.chat.data.model.ToolProgressEvent
import com.hermes.chat.data.model.toJsonString
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

    /** 实时思考流（reasoning_content），仅流式期间有效，结束后持久化进消息 */
    private val _thinkingContent = MutableStateFlow("")
    val thinkingContent: StateFlow<String> = _thinkingContent

    /** 实时状态文案（正在思考 / 正在生成 / 调用工具…） */
    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText

    /** 实时工具调用进度卡片（key = 助手消息 id），流式期间有效，结束后持久化进消息 */
    private val _toolCalls = MutableStateFlow<Map<String, List<ToolCall>>>(emptyMap())
    val toolCalls: StateFlow<Map<String, List<ToolCall>>> = _toolCalls

    /** /v1/runs 长运行模式开关 */
    private val _runMode = MutableStateFlow(false)
    val runMode: StateFlow<Boolean> = _runMode

    private var conversationId: String? = null
    private val streamingBuffer = StringBuilder()
    private var approvalHandledThisTurn = false
    private var streamingTurnId = 0
    private var initialized = false
    private var lastPersistTs = 0L

    fun init(existingConversationId: String?) {
        if (initialized) return
        initialized = true
        if (existingConversationId != null) {
            conversationId = existingConversationId
            viewModelScope.launch(Dispatchers.IO) {
                val loaded = db.messageDao().getByConversation(existingConversationId).first()
                // 清理上次中断流式时遗留的"空助手消息"（无内容且非流式），避免重进看到空白气泡
                val cleaned = loaded.toMutableList()
                while (cleaned.isNotEmpty()) {
                    val last = cleaned.last()
                    if (last.role == MessageEntity.ROLE_ASSISTANT && last.content.isBlank()
                        && last.reasoning.isBlank() && last.toolCalls().isEmpty()
                    ) {
                        val removed = cleaned.removeAt(cleaned.lastIndex)
                        db.messageDao().deleteById(removed.id)
                    } else break
                }
                _messages.value = cleaned
            }
        }
    }

    fun toggleRunMode() { _runMode.value = !_runMode.value }

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
        if (content.isEmpty() || _isStreaming.value) return
        clearDraft()
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
        sendUserMessage(option)
    }

    /** 删除某条消息（长按菜单触发） */
    fun deleteMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().deleteById(id)
        }
        removeMessage(id)
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

    // ===================== 流式 / 长运行 =====================

    private fun startStreaming() {
        val base = settings.baseUrl
        val key = settings.apiKey
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
        lastPersistTs = 0L
        _thinkingContent.value = ""
        _statusText.value = "Hermes 正在思考…"

        val assistantId = newId()
        streamingAssistantId = assistantId
        val assistantMsg = MessageEntity(
            id = assistantId,
            conversationId = conversationId!!,
            role = MessageEntity.ROLE_ASSISTANT,
            content = "",
            createdAt = now()
        )
        // 关键：先把空助手消息落库，流式过程中再节流增量更新，保证页面切换可恢复
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().insert(assistantMsg) }
        appendMessage(assistantMsg)

        val history = buildHistoryForApi()
        val myTurn = ++streamingTurnId

        viewModelScope.launch(Dispatchers.IO) {
            val onDelta: (String) -> Unit = { delta -> appendContent(delta) }
            val onThinking: (String) -> Unit = { t -> appendThinking(t) }
            val onStatus: (String, String) -> Unit = { eventType, _ -> setStatus(eventType) }
            val onToolProgress: (ToolProgressEvent) -> Unit = { ev -> upsertTool(ev) }
            val onApproval: (ApprovalRequest) -> Unit = { req -> handleApproval(req) }
            val onDone: () -> Unit = { finalizeTurn(myTurn) }
            val onError: (Throwable) -> Unit = { e -> handleError(e, myTurn) }

            if (_runMode.value) {
                api.createRun(
                    baseUrl = base, apiKey = key, model = model, messages = history,
                    onRunId = { runId ->
                        viewModelScope.launch(Dispatchers.IO) {
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
        }
    }

    /** 追加文本增量，并更新内存态 + 节流落库 */
    private fun appendContent(delta: String) {
        if (streamingAssistantId == null) return
        if (_statusText.value != "Hermes 正在生成…") _statusText.value = "Hermes 正在生成…"
        streamingBuffer.append(delta)
        val cur = _messages.value.find { it.id == streamingAssistantId } ?: return
        updateMessage(cur.copy(content = streamingBuffer.toString()))
        flushStreamingToDb()
    }

    private fun appendThinking(t: String) {
        if (streamingAssistantId == null) return
        if (_thinkingContent.value.isEmpty()) _statusText.value = "Hermes 正在思考…"
        _thinkingContent.value = _thinkingContent.value + t
    }

    /** 工具调用进度：按 id 更新（started/completed 用同一 id 覆盖） */
    private fun upsertTool(event: ToolProgressEvent) {
        if (streamingAssistantId == null) return
        val id = streamingAssistantId ?: return
        val map = _toolCalls.value.toMutableMap()
        val list = map[id]?.toMutableList() ?: mutableListOf()
        val idx = list.indexOfFirst { it.id == event.id }
        val tc = ToolCall(event.id, event.emoji, event.title, event.status, event.preview, true)
        if (idx >= 0) list[idx] = tc else list.add(tc)
        map[id] = list
        _toolCalls.value = map
    }

    private fun setStatus(eventType: String) {
        if (streamingAssistantId == null) return
        _statusText.value = mapStatus(eventType)
    }

    /** 把当前流式内容节流落库（约每 250ms 一次），确保页面切换后可恢复 */
    private fun flushStreamingToDb() {
        val id = streamingAssistantId ?: return
        val ts = System.currentTimeMillis()
        if (ts - lastPersistTs < 250) return
        lastPersistTs = ts
        val cur = _messages.value.find { it.id == id } ?: return
        val snapshot = cur.copy(content = streamingBuffer.toString())
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().update(snapshot) }
    }

    private fun mapStatus(eventType: String): String = when {
        eventType.contains("tool", ignoreCase = true) -> "Hermes 正在调用工具…"
        eventType.contains("run", ignoreCase = true) -> "Hermes 正在执行任务…"
        eventType.contains("search", ignoreCase = true) -> "Hermes 正在检索…"
        eventType.contains("reasoning", ignoreCase = true) -> "Hermes 正在思考…"
        else -> "Hermes 正在处理…"
    }

    private fun handleApproval(req: ApprovalRequest) {
        approvalHandledThisTurn = true
        finalizeAssistant(removeIfBlank = true)
        streamingAssistantId = null
        _thinkingContent.value = ""
        _statusText.value = ""
        _toolCalls.value = _toolCalls.value.toMutableMap().also { it.remove(streamingAssistantId) }
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
        val reasoning = _thinkingContent.value
        val toolCallsJson = _toolCalls.value[id]?.toJsonString() ?: ""
        if (removeIfBlank && finalContent.isBlank() && reasoning.isBlank() && toolCallsJson.isBlank()) {
            viewModelScope.launch(Dispatchers.IO) { db.messageDao().deleteById(id) }
            removeMessage(id)
            return
        }
        val finalized = cur.copy(
            content = finalContent,
            reasoning = reasoning,
            toolCallsJson = toolCallsJson
        )
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().update(finalized) }
        updateMessage(finalized)
    }

    private fun finalizeTurn(myTurn: Int) {
        if (streamingAssistantId != null) {
            finalizeAssistant(removeIfBlank = true)
            streamingAssistantId = null
        }
        _thinkingContent.value = ""
        _statusText.value = ""
        _toolCalls.value = emptyMap()

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

        if (myTurn == streamingTurnId) _isStreaming.value = false
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
                val reasoning = _thinkingContent.value
                val toolCallsJson = _toolCalls.value[id]?.toJsonString() ?: ""
                if (cur.content.isBlank() && reasoning.isBlank() && toolCallsJson.isBlank()) {
                    viewModelScope.launch(Dispatchers.IO) { db.messageDao().deleteById(id) }
                    removeMessage(id)
                } else {
                    viewModelScope.launch(Dispatchers.IO) {
                        db.messageDao().update(
                            cur.copy(content = cur.content, reasoning = reasoning, toolCallsJson = toolCallsJson)
                        )
                    }
                }
            }
        }
        streamingAssistantId = null
        _thinkingContent.value = ""
        _statusText.value = ""
        _toolCalls.value = emptyMap()
        if (myTurn == streamingTurnId) _isStreaming.value = false
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
            }
        }
        return list
    }
}
