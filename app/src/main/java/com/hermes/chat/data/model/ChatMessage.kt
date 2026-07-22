package com.hermes.chat.data.model

/** 发送给 Hermes 后端的单条消息。带附件时，[content] 会以多模态 content 数组发送。 */
data class ChatMessage(
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList()
)
