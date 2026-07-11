package com.hermes.chat.data.model

/** Hermes 工具调用进度事件（SSE `event: hermes.tool.progress` 的解析结果）。 */
data class ToolProgressEvent(
    val id: String,
    val emoji: String,
    val title: String,
    val status: String, // started | completed | error
    val preview: String
)
