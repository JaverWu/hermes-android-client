package com.hermes.chat.data.local

import androidx.room.ColumnInfo

/**
 * 消息全文搜索结果行（messages LEFT JOIN conversations）。
 * 仅携带跳转与展示所需的字段：消息 id、所属会话 id、会话标题、消息正文、角色、时间。
 */
data class MessageSearchRow(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    @ColumnInfo(name = "conversationTitle")
    val conversationTitle: String
)
