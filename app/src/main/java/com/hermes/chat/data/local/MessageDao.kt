package com.hermes.chat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC")
    fun getByConversation(cid: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MessageEntity)

    @Update
    suspend fun update(m: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :cid")
    suspend fun deleteByConversation(cid: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    /** 取某会话最后一条消息正文（用于会话列表预览，仅读取不改动结构）。 */
    @Query("SELECT content FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastContent(conversationId: String): String?

    /** 取所有"仍在流式输出中"的助手占位消息（App 被杀后用于断线续传 / 清理假卡死）。 */
    @Query("SELECT * FROM messages WHERE role = 'assistant' AND isStreaming = 1")
    suspend fun getStreamingAssistants(): List<MessageEntity>
}
