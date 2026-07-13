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

    /** 取某会话最近 N 条消息（按 createdAt DESC，最新在前）。用于通知栏 MessagingStyle 构建。纯查询，不改变表结构。 */
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getLastNMessages(cid: String, limit: Int): List<MessageEntity>

    /**
     * 跨全部会话搜索消息正文（LIKE，不区分大小写由 SQLite 默认 NOCASE 处理）。
     * [pattern] 形如 "%关键字%"，特殊字符 % _ \ 已在调用方转义。
     * 联表取会话标题，按时间倒序（最新命中在前）。
     */
    @Query("""
        SELECT m.id AS id, m.conversationId AS conversationId, m.role AS role,
               m.content AS content, m.createdAt AS createdAt,
               COALESCE(c.title, '对话') AS conversationTitle
        FROM messages m
        LEFT JOIN conversations c ON m.conversationId = c.id
        WHERE m.content LIKE :pattern ESCAPE '\'
        ORDER BY m.createdAt DESC
    """)
    suspend fun search(pattern: String): List<MessageSearchRow>
}

/** 转义 LIKE 的特殊字符（% _ \），避免用户输入被当作通配符。 */
fun escapeLikePattern(query: String): String {
    return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

