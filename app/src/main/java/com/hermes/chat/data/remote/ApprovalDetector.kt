package com.hermes.chat.data.remote

import java.util.LinkedHashSet
import java.util.regex.Pattern

/**
 * 文本兜底检测：当后端没有下发结构化审批事件时，
 * 扫描助手正文，若命中多个斜杠指令（如 /approve、/approve session、/deny），
 * 则把这些指令当作可点击的审批选项。
 *
 * 仅当找到 >=2 个不同选项时才判定为审批提示，以降低误判概率。
 */
object ApprovalDetector {

    private val PATTERN = Pattern.compile(
        "/(approve(?:\\s+session)?|deny|reject)\\b",
        Pattern.CASE_INSENSITIVE
    )

    fun detect(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        val matcher = PATTERN.matcher(text)
        while (matcher.find()) {
            found.add(matcher.group().trim().lowercase())
        }
        return if (found.size >= 2) found.toList() else emptyList()
    }
}
