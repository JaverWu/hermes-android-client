package com.hermes.chat.ui.conversations

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hermes.chat.R
import com.hermes.chat.HermesApplication
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageDao
import com.hermes.chat.databinding.ActivityConversationsBinding
import com.hermes.chat.ui.chat.ChatActivity
import com.hermes.chat.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ConversationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationsBinding
    private val viewModel: ConversationsViewModel by viewModels()
    private lateinit var adapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        // 左上角常驻设置齿轮（P0 需求：首页设置入口常驻可见）
        binding.toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val messageDao: MessageDao = (application as HermesApplication).database.messageDao()
        adapter = ConversationAdapter(
            onClick = { openChat(it.id) },
            onDelete = { confirmDelete(it) },
            messageDao = messageDao
        )
        binding.recyclerConversations.layoutManager = LinearLayoutManager(this)
        binding.recyclerConversations.adapter = adapter

        binding.fabNewChat.setOnClickListener { openChat(null) }

        lifecycleScope.launch {
            viewModel.conversations.collect { list ->
                adapter.submitList(list)
                binding.textEmpty.visibility =
                    if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun openChat(conversationId: String?) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        startActivity(intent)
    }

    private fun confirmDelete(conv: ConversationEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.delete(conv.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
