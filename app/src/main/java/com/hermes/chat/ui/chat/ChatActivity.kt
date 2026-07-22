package com.hermes.chat.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hermes.chat.R
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.model.Attachment
import com.hermes.chat.data.model.ToolCall
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivityChatBinding
import com.hermes.chat.databinding.ItemToolChipBinding
import com.hermes.chat.databinding.ItemSlashCommandBinding
import com.hermes.chat.ui.common.AvatarPresets
import com.hermes.chat.ui.common.AvatarRole
import com.hermes.chat.ui.common.showAvatarPicker
import com.hermes.chat.ui.common.showAvatarRoleChooser
import com.hermes.chat.ui.conversations.ConversationsActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private val settings: SettingsRepository by lazy { SettingsRepository(this) }
    private lateinit var adapter: MessageAdapter
    private lateinit var slashAdapter: SlashInlineAdapter
    /** 插入斜杠命令时抑制 TextWatcher 的面板检测，避免插入后面板闪现。 */
    private var suppressSlash = false
    /** 发送消息后强制滚动到底部（绕过 wasAtBottom 检查）。 */
    private var forceScrollToBottom = false
    /** 上一个处于流式状态的助手消息 id，用于"完成态"重绑时仍能定位并切回 markdown。 */
    private var lastStreamingId: String? = null
    /** 搜索跳转：待定位的消息 id（定位后清空）。 */
    private var pendingTargetId: String? = null

    /** 待发送附件（点击曲别针后累积，点发送时一并发出并清空）。 */
    private val pendingAttachments = mutableListOf<Attachment>()

    /** 文件选择器（点击曲别针按钮触发）：拷贝到私有目录并加入待发送列表。 */
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val att = Attachment.copyToInternal(this, uri)
            if (att == null) {
                Toast.makeText(this, R.string.attachment_copy_fail, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            // 大文件提醒（base64 后会显著膨胀，避免后端拒绝）
            if (att.size > 20 * 1024 * 1024) {
                Toast.makeText(this, R.string.attachment_too_large, Toast.LENGTH_SHORT).show()
            }
            pendingAttachments.add(att)
            renderAttachments()
            Toast.makeText(this, getString(R.string.attachment_added, att.name), Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 保持屏幕常亮
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

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
        pendingTargetId = intent.getStringExtra(EXTRA_MESSAGE_ID)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_chat -> { startNewChat(); true }
                R.id.action_settings -> {
                    val intent = Intent(this, ConversationsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(ConversationsActivity.EXTRA_SHOW_SETTINGS, true)
                    }
                    startActivity(intent); true
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
        // 禁用变更动画：notifyItemChanged 默认触发闪烁动画，流式时每帧都闪 → 持续高刷新率
        (binding.recyclerMessages.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.supportsChangeAnimations = false

        // ── 内联斜杠命令弹板 ──
        slashAdapter = SlashInlineAdapter(SlashCommands.all) { cmd ->
            insertCommand(cmd)
            hideSlashInline()
        }
        binding.listSlashInline.layoutManager = LinearLayoutManager(this)
        binding.listSlashInline.adapter = slashAdapter

        // 草稿回填 + 斜杠命令检测（合并到一个 TextWatcher）
        binding.editInput.setText(viewModel.loadDraft())
        binding.editInput.setSelection(binding.editInput.text?.length ?: 0)
        binding.editInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.saveDraft(s?.toString().orEmpty())
                if (suppressSlash) return

                // 斜杠命令面板仅在输入框以 "/" 开头时激活，
                // 避免会话正文中任意位置的 "/"（如 "https://..." / "请使用 /help"）误触发
                val text = s?.toString().orEmpty()
                if (text.startsWith("/")) {
                    // 取首段（第一个空白符前）作为过滤词，命令后的参数不破坏实时筛选
                    val query = text.substring(1).substringBefore(' ').trim()
                    showSlashInline(query)
                } else {
                    hideSlashInline()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.buttonSend.setOnClickListener { send() }
        binding.buttonExpandInput.setOnClickListener {
            // 弹出瞬间检测小输入框当前键盘是否可见（放大前状态）
            val visRect = Rect()
            binding.root.getWindowVisibleDisplayFrame(visRect)
            val screenH = resources.displayMetrics.heightPixels
            val keypadH = screenH - visRect.bottom
            val keyboardOpen = keypadH > resources.displayMetrics.density * 120f
            val dlg = ExpandedInputDialog.newInstance(
                binding.editInput.text?.toString().orEmpty(), keyboardOpen
            )
            dlg.show(supportFragmentManager, "ExpandedInputDialog")
        }
        binding.editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        // 左侧曲别针：打开文件选择器
        binding.imageAttach.setOnClickListener { filePickerLauncher.launch("*/*") }

        lifecycleScope.launch {
            viewModel.messages.collect { list ->
                adapter.setStreamingAssistantId(viewModel.streamingAssistantId.value)
                adapter.setStreamingState(viewModel.thinkingContent.value, viewModel.statusText.value)
                adapter.liveContent = viewModel.liveContent.value

                // 搜索跳转：定位到目标消息并高亮（数据到达后即处理，未到则保留 pending）
                val target = pendingTargetId
                if (target != null) {
                    val pos = list.indexOfFirst { it.id == target }
                    if (pos >= 0) {
                        pendingTargetId = null
                        adapter.submitList(list) {
                            binding.recyclerMessages.post {
                                (binding.recyclerMessages.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(pos, 200)
                                adapter.setHighlight(target)
                            }
                        }
                        binding.textEmptyChat.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                        return@collect
                    }
                }

                val wasAtBottom = isAtBottom()
                adapter.submitList(list) {
                    if (list.isNotEmpty() && (wasAtBottom || forceScrollToBottom)) {
                        // post 确保在 RecyclerView 完成布局后再滚动，
                        // 避免 submitList 回调时新 item 尚未布局导致滚动无效
                        binding.recyclerMessages.post {
                            binding.recyclerMessages.scrollToPosition(list.lastIndex)
                        }
                    }
                    forceScrollToBottom = false
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
        // 合并 3 个高频流式 Flow + sample(100ms) 节流，避免每个 SSE delta 都触发
        // notifyItemChanged() → RecyclerView 重绘 → 屏幕面板保持 120Hz 降不下来 → 耗电
        // 节流后 UI 更新从每秒几十次降到最多 10 次，LTPO 可正常降刷新率
        lifecycleScope.launch {
            combine(viewModel.liveContent, viewModel.thinkingContent, viewModel.statusText) { content, thinking, status ->
                Triple(content, thinking, status)
            }.sample(100).collect { (content, thinking, status) ->
                adapter.liveContent = content
                adapter.setStreamingState(thinking, status)
                adapter.setStreamingAssistantId(viewModel.streamingAssistantId.value)
                notifyStreamingItemChanged()
                scrollToBottomIfAtBottom()
            }
        }
        lifecycleScope.launch {
            viewModel.streamingAssistantId.collect { id ->
                adapter.setStreamingAssistantId(id)
                notifyStreamingItemChanged()
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

    override fun onResume() {
        super.onResume()
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // 用户打开 App，取消回复完成提醒通知
        ChatNotificationManager.cancelAlert(this)
        // 从设置页返回后刷新气泡头像（用户可能刚改了自定义头像）
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (binding.layoutSlashInline.visibility == View.VISIBLE) {
                    hideSlashInline()
                    return true
                }
                binding.editInput.clearFocus()
                finish()
                return true
            }
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 检查用户是否在列表底部附近（用于判断是否需要自动滚动） */
    private fun isAtBottom(): Boolean {
        val lm = binding.recyclerMessages.layoutManager as? LinearLayoutManager ?: return false
        val lastVisible = lm.findLastCompletelyVisibleItemPosition()
        return lastVisible >= adapter.itemCount - 2
    }

    /** 滚动到最后一条（仅在用户已在底部时才滚） */
    private fun scrollToBottomIfAtBottom() {
        if (isAtBottom()) {
            binding.recyclerMessages.post {
                binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    /**
     * 只刷新（与流式状态相关的）助手消息 item，避免全量刷新导致跳动。
     * 关键：streamingAssistantId 被 reset 清空为 null 时不能 early-return——
     * 必须重绑「上一个流式 item」，否则完成态无法切回 markdown（一直卡在"正在回复"）。
     */
    private fun notifyStreamingItemChanged() {
        val sid = viewModel.streamingAssistantId.value
        // id 变 null（完成）时回退到上一个流式 id 进行重绑
        val target = sid ?: lastStreamingId
        lastStreamingId = sid
        if (target != null) {
            val pos = adapter.currentList.indexOfFirst { it.id == target }
            if (pos >= 0) adapter.notifyItemChanged(pos)
        }
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
                val detail = buildString {
                    append(tc.emoji)
                    append(" ")
                    append(tc.title.ifBlank { "未知工具" })
                    append("\n状态: ")
                    append(tc.status)
                    if (tc.preview.isNotBlank()) {
                        append("\n\n")
                        append(tc.preview)
                    }
                }
                binding.textToolPreview.text = detail
                binding.textToolPreview.visibility = View.VISIBLE
            }
            chips.addView(cb.root)
        }

        // 自动显示最新的工具调用 preview
        val latest = flat.lastOrNull()
        if (latest != null && latest.preview.isNotBlank()) {
            binding.textToolPreview.text = latest.preview.take(200)
            binding.textToolPreview.visibility = View.VISIBLE
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
        val atts = pendingAttachments.toList()
        if (text.isBlank() && atts.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        binding.editInput.text?.clear()
        forceScrollToBottom = true
        pendingAttachments.clear()
        renderAttachments()
        viewModel.sendUserMessage(text, atts)
    }

    /** 刷新输入条上方的附件芯片行。 */
    private fun renderAttachments() {
        val container = binding.layoutAttachments
        container.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val ctx = this
        pendingAttachments.forEachIndexed { idx, att ->
            val chip = android.widget.TextView(ctx).apply {
                text = "📎 ${att.name}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_attachment_chip)
                setPadding(
                    (8 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt()
                )
                // 点击右侧"×"移除单个附件
                setOnLongClickListener { removeAttachment(idx); true }
                contentDescription = getString(R.string.attachment_chip, att.name)
            }
            val params = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            container.addView(chip, params)
        }
        // 末尾加一个"× 清除全部"按钮
        val clear = android.widget.TextView(ctx).apply {
            text = "✕"
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.unread_red))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_attachment_chip)
            setPadding(
                (10 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt()
            )
            setOnClickListener { pendingAttachments.clear(); renderAttachments() }
            contentDescription = getString(R.string.attachment_clear_all)
        }
        container.addView(clear)
    }

    /** 移除第 [idx] 个待发送附件。 */
    private fun removeAttachment(idx: Int) {
        if (idx in pendingAttachments.indices) {
            pendingAttachments.removeAt(idx)
            renderAttachments()
        }
    }

    /** 放大输入框关闭（未发送）：把大框文本回写小输入框并保存草稿，避免丢失。 */
    fun onExpandedClose(text: String) {
        binding.editInput.setText(text)
        binding.editInput.setSelection(text.length)
        viewModel.saveDraft(text)
    }

    /** 放大输入框直接发送：回写后复用既有发送逻辑（清空 + 落库 + 滚动）。 */
    fun onExpandedSend(text: String) {
        binding.editInput.setText(text)
        send()
    }

    /** 显示内联斜杠命令半屏弹板，按 [query] 实时过滤。 */
    private fun showSlashInline(query: String) {
        slashAdapter.filter(query)
        binding.layoutSlashInline.visibility = View.VISIBLE
    }

    /** 隐藏内联斜杠命令弹板。 */
    private fun hideSlashInline() {
        binding.layoutSlashInline.visibility = View.GONE
    }

    /**
     * 把选中的斜杠命令替换进输入框：找到光标前最后一个 "/"，
     * 用命令整体替换「该 "/" 到光标」之间的文本，避免出现 //new / /us/usage。
     */
    private fun insertCommand(cmd: String) {
        val et = binding.editInput
        val editable = et.text ?: return
        val cursor = et.selectionStart.coerceAtLeast(0)
        val lastSlash = editable.lastIndexOf('/', cursor - 1)
        val replaceStart = if (lastSlash >= 0) lastSlash else cursor
        suppressSlash = true
        editable.replace(replaceStart, cursor, cmd)
        suppressSlash = false
        et.setSelection(replaceStart + cmd.length)
        et.requestFocus()
    }

    private fun startNewChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_MESSAGE_ID = "message_id"
    }
}

/**
 * 内联斜杠命令列表适配器（复用 [item_slash_command.xml] 布局，
 * 在输入框上方半屏显示，支持按名称/描述/分组过滤）。
 */
class SlashInlineAdapter(
    private val source: List<SlashCommand>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<SlashInlineAdapter.VH>() {

    private var items = source.toList()

    fun filter(q: String) {
        val ql = q.lowercase().trim()
        items = if (ql.isBlank()) source else source.filter {
            it.command.lowercase().contains(ql) ||
                it.description.lowercase().contains(ql) ||
                it.group.lowercase().contains(ql)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSlashCommandBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.b.textCommand.text = c.command
        holder.b.textDesc.text = c.description
        holder.b.textGroup.text = c.group
        holder.b.root.setOnClickListener { onClick(c.command) }
    }

    class VH(val b: ItemSlashCommandBinding) : RecyclerView.ViewHolder(b.root)
}
