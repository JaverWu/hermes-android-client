package com.hermes.chat.ui.conversations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chat.HermesApplication
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageSearchRow
import com.hermes.chat.data.local.escapeLikePattern
import com.hermes.chat.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ConversationsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HermesApplication).database

    val conversations: Flow<List<ConversationEntity>> = db.conversationDao().getAll()

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().deleteByConversation(id)
            db.conversationDao().deleteById(id)
            // 如果删除的是最近活跃会话，清除记录，避免通知栏回复指向已删会话
            val settings = SettingsRepository(getApplication())
            if (settings.lastConversationId == id) {
                settings.lastConversationId = ""
            }
        }
    }

    /** 跨会话搜索消息正文，返回命中行（按时间倒序）。 */
    suspend fun searchMessages(query: String): List<MessageSearchRow> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val pattern = "%" + escapeLikePattern(q) + "%"
        return db.messageDao().search(pattern)
    }
}
