package com.hermes.chat.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hermes.chat.R
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivityChatBinding
import com.hermes.chat.ui.common.AvatarPresets
import com.hermes.chat.ui.common.AvatarRole
import com.hermes.chat.ui.common.showAvatarPicker
import com.hermes.chat.ui.common.showAvatarRoleChooser
import com.hermes.chat.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private val settings: SettingsRepository by lazy { SettingsRepository(this) }
    private lateinit var adapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.init(intent.getStringExtra(EXTRA_CONVERSATION_ID))

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_chat -> { startNewChat(); true }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                R.id.action_edit_avatar -> { openAvatarEditor(); true }
                else -> false
            }
        }

        adapter = MessageAdapter(settings) { msg, option -> viewModel.respondToApproval(msg.id, option) }
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerMessages.adapter = adapter

        binding.buttonSend.setOnClickListener { send() }
        binding.editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }

        lifecycleScope.launch {
            viewModel.messages.collect { list ->
                adapter.setStreamingAssistantId(viewModel.streamingAssistantId)
                adapter.setStreamingState(viewModel.thinkingContent.value, viewModel.statusText.value)
                adapter.submitList(list) {
                    if (list.isNotEmpty()) {
                        binding.recyclerMessages.scrollToPosition(list.lastIndex)
                    }
                }
                binding.textEmptyChat.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.isStreaming.collect { streaming ->
                binding.buttonSend.isEnabled = !streaming
                adapter.setStreamingState(viewModel.thinkingContent.value, viewModel.statusText.value)
            }
        }
        // 思考流 / 状态变化时需要重新绑定以刷新显示
        lifecycleScope.launch {
            viewModel.thinkingContent.collect {
                adapter.setStreamingState(it, viewModel.statusText.value)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        lifecycleScope.launch {
            viewModel.statusText.collect {
                adapter.setStreamingState(viewModel.thinkingContent.value, it)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    /** 在对话界面中修改用户 / Hermes 头像 */
    private fun openAvatarEditor() {
        showAvatarRoleChooser(this) { role ->
            val presets = if (role == AvatarRole.USER) AvatarPresets.USER else AvatarPresets.AI
            val current = if (role == AvatarRole.USER) settings.userAvatarIndex else settings.aiAvatarIndex
            showAvatarPicker(this, presets, current) { idx ->
                if (role == AvatarRole.USER) settings.userAvatarIndex = idx else settings.aiAvatarIndex = idx
                // 立即刷新当前对话中的头像
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    private fun send() {
        val text = binding.editInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.toast_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        binding.editInput.text?.clear()
        viewModel.sendUserMessage(text)
    }

    private fun startNewChat() {
        // 不带 conversationId 重新打开自己，得到一个干净的会话
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
