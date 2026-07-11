package com.hermes.chat.ui.conversations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chat.HermesApplication
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.local.ConversationEntity
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
        }
    }
}
