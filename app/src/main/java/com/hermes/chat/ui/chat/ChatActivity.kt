package com.hermes.chat.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hermes.chat.R
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.model.ToolCall
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivityChatBinding
import com.hermes.chat.databinding.ItemToolChipBinding
import com.hermes.chat.ui.common.AvatarPresets
import com.hermes.chat.ui.common.AvatarRole
import com.hermes.chat.ui.common.showAvatarPicker
import com.hermes.chat.ui.common.showAvatarRoleChooser
import com.hermes.chat.ui.settings.SettingsActivity
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

        // Android 13+ 需要运行时授权才能弹本地通知（"回复了你"提示依赖它）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            }
        }

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
                R.id.action_run_mode -> {
                    viewModel.toggleRunMode()
                    updateRunModeMenuItem()
                    true
                }
                else -> false
            }
        }
        updateRunModeMenuItem()

        adapter = MessageAdapter(
            settings,
            { msg, option -> viewModel.respondToApproval(msg.id, option) },
            { msg -> confirmDeleteMessage(msg) }
        )
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerMessages.adapter = adapter

        // 草稿回填：进入会话时恢复未发送的输入
        binding.editInput.setText(viewModel.loadDraft())
        binding.editInput.setSelection(binding.editInput.text?.length ?: 0)
        binding.editInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.saveDraft(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.buttonSend.setOnClickListener { send() }
        binding.editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        // 左侧按钮：打开斜杠命令面板（官方聊天命令 /new /status 等）
        binding.imageAttach.setOnClickListener { openSlashPanel() }

        lifecycleScope.launch {
            viewModel.messages.collect { list ->
                adapter.setStreamingAssistantId(viewModel.streamingAssistantId.value)
                adapter.setStreamingState(viewModel.thinkingContent.value, viewModel.statusText.value)
                adapter.liveContent = viewModel.liveContent.value
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
            }
        }
        lifecycleScope.launch {
            viewModel.thinkingContent.collect {
                adapter.setStreamingState(it, viewModel.statusText.value)
                adapter.setStreamingAssistantId(viewModel.streamingAssistantId.value)
                adapter.liveContent = viewModel.liveContent.value
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        lifecycleScope.launch {
            viewModel.statusText.collect {
                adapter.setStreamingState(viewModel.thinkingContent.value, it)
                adapter.setStreamingAssistantId(viewModel.streamingAssistantId.value)
                adapter.liveContent = viewModel.liveContent.value
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        lifecycleScope.launch {
            viewModel.liveContent.collect { c ->
                adapter.liveContent = c
                adapter.setStreamingAssistantId(viewModel.streamingAssistantId.value)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        lifecycleScope.launch {
            viewModel.streamingAssistantId.collect { id ->
                adapter.setStreamingAssistantId(id)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        lifecycleScope.launch {
            viewModel.toolCalls.collect { map ->
                renderToolStrip(map)
            }
        }
        lifecycleScope.launch {
            viewModel.runMode.collect { updateRunModeMenuItem() }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveDraft(binding.editInput.text?.toString().orEmpty())
    }

    private fun updateRunModeMenuItem() {
        val item = binding.toolbar.menu.findItem(R.id.action_run_mode) ?: return
        val on = viewModel.runMode.value
        item.isChecked = on
        item.title = if (on) getString(R.string.run_mode_on) else getString(R.string.run_mode_off)
    }

    /** 把工具调用 / 进度渲染到顶部 Hermes 状态条（不进入对话列表，不干扰滚动）。 */
    private fun renderToolStrip(map: Map<String, List<ToolCall>>) {
        val flat = map.values.flatten()
        val strip = binding.layoutToolStrip
        val chips = binding.toolChips
        if (flat.isEmpty()) {
            strip.visibility = View.GONE
            binding.textToolPreview.visibility = View.GONE
            return
        }
        strip.visibility = View.VISIBLE
        chips.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (tc in flat) {
            val cb = ItemToolChipBinding.inflate(inflater, chips, false)
            cb.textEmoji.text = tc.emoji.ifBlank { "🛠" }
            cb.textTitle.text = tc.title.ifBlank { getString(R.string.tool_calls_title) }
            val dotColor = when (tc.status.lowercase(java.util.Locale.ROOT)) {
                "completed" -> R.color.tool_chip_done
                "error" -> R.color.tool_chip_error
                else -> R.color.tool_chip_running
            }
            cb.statusDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))
            cb.root.setOnClickListener {
                binding.textToolPreview.text = tc.preview.ifBlank { getString(R.string.tool_preview_empty) }
                binding.textToolPreview.visibility = View.VISIBLE
            }
            chips.addView(cb.root)
        }
    }

    /** 在对话界面中修改用户 / Hermes 头像 */
    private fun openAvatarEditor() {
        showAvatarRoleChooser(this) { role ->
            val presets = if (role == AvatarRole.USER) AvatarPresets.USER else AvatarPresets.AI
            val current = if (role == AvatarRole.USER) settings.userAvatarIndex else settings.aiAvatarIndex
            showAvatarPicker(this, presets, current) { idx ->
                if (role == AvatarRole.USER) settings.userAvatarIndex = idx else settings.aiAvatarIndex = idx
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    private fun confirmDeleteMessage(msg: MessageEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_message_title)
            .setMessage(R.string.confirm_delete_message_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.deleteMessage(msg.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun openSlashPanel() {
        SlashCommandPanel(this) { cmd -> insertCommand(cmd) }.show()
    }

    /** 把选中的斜杠命令插入输入框光标处 */
    private fun insertCommand(cmd: String) {
        val et = binding.editInput
        val editable = et.text ?: return
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        editable.replace(start, end, cmd)
        et.setSelection(start + cmd.length)
        et.requestFocus()
    }

    private fun startNewChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
