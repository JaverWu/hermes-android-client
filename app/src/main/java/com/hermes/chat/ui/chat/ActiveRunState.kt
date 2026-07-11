package com.hermes.chat.ui.chat

import com.hermes.chat.data.model.ToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 活跃对话运行的共享状态。由 [RunWatcherService]（前台 Service）写入，
 * [ChatActivity] 在界面打开时读取以驱动"思考中/正在生成/工具进度条"等实时 UI。
 *
 * 放在独立的单例里，是因为流式接收已经脱离 Activity/ViewModel 的生命周期——
 * Service 进程级存活，Activity 可随时销毁重建，二者通过这份内存态 + 数据库协同。
 */
object ActiveRunState {

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _thinking = MutableStateFlow("")
    val thinking: StateFlow<String> = _thinking.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _toolCalls = MutableStateFlow<Map<String, List<ToolCall>>>(emptyMap())
    val toolCalls: StateFlow<Map<String, List<ToolCall>>> = _toolCalls.asStateFlow()

    private val _streamingAssistantId = MutableStateFlow<String?>(null)
    val streamingAssistantId: StateFlow<String?> = _streamingAssistantId.asStateFlow()

    fun reset() {
        _isStreaming.value = false
        _thinking.value = ""
        _status.value = ""
        _content.value = ""
        _toolCalls.value = emptyMap()
        _streamingAssistantId.value = null
    }

    fun begin(assistantId: String) {
        _isStreaming.value = true
        _thinking.value = ""
        _status.value = "Hermes 正在思考…"
        _content.value = ""
        _toolCalls.value = emptyMap()
        _streamingAssistantId.value = assistantId
    }

    fun appendContent(delta: String) {
        _status.value = "Hermes 正在生成…"
        _content.value += delta
    }

    fun appendThinking(t: String) {
        _thinking.value += t
    }

    fun upsertTool(tc: ToolCall) {
        val id = _streamingAssistantId.value ?: return
        val map = _toolCalls.value.toMutableMap()
        val list = map[id]?.toMutableList() ?: mutableListOf()
        val idx = list.indexOfFirst { it.id == tc.id }
        if (idx >= 0) list[idx] = tc else list.add(tc)
        map[id] = list
        _toolCalls.value = map
    }

    fun setStatus(eventType: String) {
        _status.value = mapStatus(eventType)
    }

    fun mapStatus(eventType: String): String = when {
        eventType.contains("tool", ignoreCase = true) -> "Hermes 正在调用工具…"
        eventType.contains("run", ignoreCase = true) -> "Hermes 正在执行任务…"
        eventType.contains("search", ignoreCase = true) -> "Hermes 正在检索…"
        eventType.contains("reasoning", ignoreCase = true) -> "Hermes 正在思考…"
        else -> "Hermes 正在处理…"
    }
}
