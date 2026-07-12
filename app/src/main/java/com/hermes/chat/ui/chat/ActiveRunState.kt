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

    private var statusRotateIndex = 0

    private val thinkingMsgs = listOf(
        "(˘ω˘) 思考中…",
        "(；￣Д￣) 嗯…让我想想…",
        "(´・ω・`) 脑细胞运转中…",
        "(◍•ᴗ•◍) 正在组织语言…",
        "(ง •̀_•́)ง 努力思考中…",
        "(°▽°) 正在分析你的问题…"
    )

    private val generatingMsgs = listOf(
        "✧(≖ ◡ ≖✿) 正在生成回复…",
        "(ﾉ◕ヮ◕)ﾉ*:・ﾟ✧ 打字中…",
        "( ˘ ³˘)❤ 文字流淌中…",
        "ヽ(✿ﾟ▽ﾟ)ノ 奋力码字中…"
    )

    private val toolMsgs = listOf(
        "(ง •_•)ง 正在调用 {tool}…",
        "🔧(・ω・) 使用 {tool} 中…",
        "⚙️(◕ᗜ◕) {tool} 运行中…",
        "(≧◡≦) {tool} 工作中…"
    )

    private val searchMsgs = listOf(
        "🔍(◕ᗜ◕) 正在检索…",
        "(￣ー￣) 搜索中…",
        "(；•̀ω•́) 查找资料中…"
    )

    private val runMsgs = listOf(
        "(づ￣ 3￣)づ 执行任务中…",
        "🏃(ง •̀_•́)ง 奔跑中…",
        "(•̀ᴗ•́)و 正在执行…"
    )

    private val defaultMsgs = listOf(
        "(´・ω・`) 处理中…",
        "(◡‿◡) 请稍等…",
        "( ˘ω˘) 正在处理…"
    )

    fun begin(assistantId: String) {
        statusRotateIndex = 0
        _isStreaming.value = true
        _thinking.value = ""
        _status.value = thinkingMsgs[0]
        _content.value = ""
        _toolCalls.value = emptyMap()
        _streamingAssistantId.value = assistantId
    }

    fun appendContent(delta: String) {
        statusRotateIndex++
        _status.value = generatingMsgs[statusRotateIndex % generatingMsgs.size]
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
        statusRotateIndex++
        _status.value = mapStatus(eventType)
    }

    /** 工具调用时设置状态，在文案中嵌入工具名 */
    fun setToolStatus(toolTitle: String) {
        statusRotateIndex++
        val template = toolMsgs[statusRotateIndex % toolMsgs.size]
        _status.value = template.replace("{tool}", toolTitle)
    }

    fun mapStatus(eventType: String): String {
        statusRotateIndex++
        val msgs = when {
            eventType.contains("tool", ignoreCase = true) -> toolMsgs.map { it.replace("{tool}", "工具") }
            eventType.contains("run", ignoreCase = true) -> runMsgs
            eventType.contains("search", ignoreCase = true) -> searchMsgs
            eventType.contains("reasoning", ignoreCase = true) -> thinkingMsgs
            else -> defaultMsgs
        }
        return msgs[statusRotateIndex % msgs.size]
    }
}
