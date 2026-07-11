package com.hermes.chat.data.model

/** 发送给 Hermes 后端的单条消息。 */
data class ChatMessage(val role: String, val content: String)
