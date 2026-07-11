package com.hermes.chat.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hermes 工具调用进度（对应 SSE 事件 `hermes.tool.progress`）。
 *
 * @param id       工具调用唯一 id（同一调用 started/completed 时一致）
 * @param emoji    图标（如 💻 terminal、🐍 execute_code、🔍 search）
 * @param title    工具名称 / 描述
 * @param status   started / completed / error
 * @param preview  工具调用的前几行内容（折叠卡片中展示）
 * @param expanded 是否默认展开
 */
data class ToolCall(
    val id: String,
    val emoji: String,
    val title: String,
    val status: String,
    val preview: String,
    val expanded: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("emoji", emoji)
        put("title", title)
        put("status", status)
        put("preview", preview)
        put("expanded", expanded)
    }

    companion object {
        fun fromJson(o: JSONObject): ToolCall = ToolCall(
            id = o.optString("id", ""),
            emoji = o.optString("emoji", "\uD83D\uDD27"),
            title = o.optString("title", "工具调用"),
            status = o.optString("status", "started"),
            preview = o.optString("preview", ""),
            expanded = o.optBoolean("expanded", true)
        )
    }
}

fun List<ToolCall>.toJsonString(): String {
    val arr = JSONArray()
    for (t in this) arr.put(t.toJson())
    return arr.toString()
}

fun toolCallsFromJson(json: String): List<ToolCall> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { ToolCall.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }
}
