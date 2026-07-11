package com.hermes.chat.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** 取值见 [ROLE_USER] / [ROLE_ASSISTANT] / [ROLE_APPROVAL] / [ROLE_SYSTEM] */
    val role: String,
    val content: String,
    val createdAt: Long,
    /** [TYPE_NORMAL] 或 [TYPE_APPROVAL] */
    val type: String = TYPE_NORMAL,
    /** 审批选项，使用 \n 连接存储 */
    val optionsJson: String = "",
    val approvalTitle: String = "",
    val approvalDetail: String = "",
    /** [STATUS_PENDING] 或 [STATUS_RESOLVED]，仅审批消息有意义 */
    val approvalStatus: String = STATUS_PENDING
) {
    fun options(): List<String> =
        if (optionsJson.isBlank()) emptyList()
        else optionsJson.split("\n").filter { it.isNotBlank() }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_APPROVAL = "approval"
        const val ROLE_SYSTEM = "system"

        const val TYPE_NORMAL = "normal"
        const val TYPE_APPROVAL = "approval"

        const val STATUS_PENDING = "pending"
        const val STATUS_RESOLVED = "resolved"
    }
}
