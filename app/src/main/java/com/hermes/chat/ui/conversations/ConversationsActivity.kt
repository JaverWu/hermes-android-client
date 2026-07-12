package com.hermes.chat.ui.conversations

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hermes.chat.R
import com.hermes.chat.HermesApplication
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.local.MessageDao
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivityConversationsBinding
import com.hermes.chat.ui.chat.ChatActivity
import com.hermes.chat.ui.chat.ActiveRunState
import com.hermes.chat.ui.chat.RunWatcherService
import com.hermes.chat.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ConversationsActivity : AppCompatActivity() {

    companion object {
        /**
         * 每进程只允许自动恢复一次，避免重复触发续传。
         * 改为 internal 供 ChatActivity 共享：点通知/深链直接进聊天时，由 ChatActivity 复用同一守卫，
         * 确保进程内（首页或聊天页）只有一处发起恢复，不会重复重发被中断的流式。
         */
        internal val RECOVERY_DONE = AtomicBoolean(false)
    }

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
            messageDao = messageDao
        )
        binding.recyclerConversations.layoutManager = LinearLayoutManager(this)
        binding.recyclerConversations.adapter = adapter

        binding.fabNewChat.setOnClickListener { openChat(null) }

        // iOS 风格：左滑删除会话
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val conv = adapter.currentList.getOrNull(pos)
                // 先还原位置，再弹确认框，避免误删
                adapter.notifyItemChanged(pos)
                if (conv != null) confirmDelete(conv)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    val itemView = viewHolder.itemView
                    val top = itemView.top.toFloat()
                    val bottom = itemView.bottom.toFloat()
                    val right = itemView.right.toFloat()
                    val left = right + dX
                    val bg = Paint().apply { color = Color.parseColor("#FF3B30") }
                    c.drawRect(left, top, right, bottom, bg)
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 16f * resources.displayMetrics.density
                        textAlign = Paint.Align.RIGHT
                    }
                    val textY = top + (bottom - top) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                    c.drawText("删除", right - 24f, textY, textPaint)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })
        touchHelper.attachToRecyclerView(binding.recyclerConversations)

        lifecycleScope.launch {
            viewModel.conversations.collect { list ->
                adapter.submitList(list)
                binding.textEmpty.visibility =
                    if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        // App 被杀后重进：恢复被中断的流式（长运行模式可断线续传），并清理"假卡死"占位气泡
        recoverInterruptedStreams()
        // 申请"忽略电池优化"：根治 vivo/OriginOS 把后台 Service/网络杀掉导致切后台断流
        maybeRequestBatteryOptimization()
    }

    /**
     * 启动恢复：扫描数据库里仍标记 isStreaming=true 的助手占位消息。
     * - 长运行模式且有持久化 runId：重连续传（服务端 run 可能还在跑，把回复补全）。
     * - 其余情况（非 run 模式 / 无 runId）：不丢弃半截，而是删除该残留占位、用同一会话里
     *   最后一条 user 消息重新发起一轮对话，由 RunWatcherService 重新拉取完整回复（避免后半段永久缺失）。
     * 每进程最多自动跑一次，避免重复触发。
     */
    private fun recoverInterruptedStreams() {
        if (RECOVERY_DONE.getAndSet(true)) return
        if (isServiceRunning()) return // Service 仍在跑，无需续传
        val db = (application as HermesApplication).database
        val settings = SettingsRepository(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val stalled = db.messageDao().getStreamingAssistants()
            if (stalled.isEmpty()) return@launch
            for (m in stalled) {
                val runId = settings.getRunId(m.id)
                if (runId != null && settings.runMode) {
                    Log.i("Conversations", "resuming interrupted run msg=${m.id} runId=$runId")
                    val intent = Intent(this@ConversationsActivity, RunWatcherService::class.java).apply {
                        putExtra(RunWatcherService.EXTRA_CONVERSATION_ID, m.conversationId)
                        putExtra(RunWatcherService.EXTRA_ASSISTANT_ID, m.id)
                        putExtra(RunWatcherService.EXTRA_RESUME, true)
                        putExtra(RunWatcherService.EXTRA_RUN_ID, runId)
                    }
                    ContextCompat.startForegroundService(this@ConversationsActivity, intent)
                } else {
                    // 非 run 模式 / 无 runId：删除残留半截占位，用最后一条 user 消息重发以补齐完整回复
                    Log.i("Conversations", "re-triggering interrupted turn for msg=${m.id} (runMode=${settings.runMode}, runId=$runId)")
                    resendLastUserTurn(m)
                }
            }
        }
    }

    /**
     * 重新触发某条被中断（isStreaming 残留）的助手回复：
     * 删除残留的半截助手占位，找到同一会话里最后一条 user 消息作为上下文，
     * 新建一条"进行中"助手占位并拉起 RunWatcherService 重新拉取完整回复。
     * 不重复插入 user 消息——原 user 消息仍在库中，RunWatcherService.buildHistory 会复用它。
     */
    private suspend fun resendLastUserTurn(m: MessageEntity) {
        val db = (application as HermesApplication).database
        // 取该会话全部消息，定位 stalled 助手之前的最后一条 user 消息
        val msgs = db.messageDao().getByConversation(m.conversationId).first()
        val userMsg = msgs.lastOrNull { it.role == MessageEntity.ROLE_USER }
        if (userMsg == null) {
            // 找不到对应 user 消息，无法重发，仅把残留占位置为已结束，避免永久"处理中"
            Log.w("Conversations", "no user message before stalled msg=${m.id}, just clearing placeholder")
            db.messageDao().update(m.copy(isStreaming = false))
            return
        }
        // 删除残留在半截的助手占位（避免重复/错位），稍后由全新一轮重新生成完整回复
        db.messageDao().deleteById(m.id)
        // 新建"进行中"助手占位，复用原 user 消息（不重复插入），由 Service 重新拉取完整回复
        val newAssistantId = UUID.randomUUID().toString()
        db.messageDao().insert(
            MessageEntity(
                id = newAssistantId,
                conversationId = m.conversationId,
                role = MessageEntity.ROLE_ASSISTANT,
                content = "",
                createdAt = System.currentTimeMillis(),
                isStreaming = true
            )
        )
        ActiveRunState.begin(newAssistantId)
        val intent = Intent(this@ConversationsActivity, RunWatcherService::class.java).apply {
            putExtra(RunWatcherService.EXTRA_CONVERSATION_ID, m.conversationId)
            putExtra(RunWatcherService.EXTRA_ASSISTANT_ID, newAssistantId)
        }
        ContextCompat.startForegroundService(this@ConversationsActivity, intent)
    }

    /** 是否已有 RunWatcherService 在运行（避免重复续传）。 */
    private fun isServiceRunning(): Boolean {
        val mgr = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return mgr.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == RunWatcherService::class.java.name }
    }

    /**
     * 申请忽略电池优化（Doze / 后台限制）。这是根治"切后台后流式被系统杀掉"的标准手段：
     * 弹一次引导，用户点"去设置"后系统直接给"不受限制"。vivo 等 ROM 还可能需要额外在
     * 设置-电池-后台高耗电里手动允许，已通过弹窗文案提示。
     */
    private fun maybeRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        val pkg = packageName
        if (pm.isIgnoringBatteryOptimizations(pkg)) return
        val settings = SettingsRepository(this)
        if (settings.batteryPromptShown) return
        AlertDialog.Builder(this)
            .setTitle("允许后台运行")
            .setMessage("为保证切到后台后仍能持续接收回复，请在下个页面选择「不受限制 / 不允许」，允许 Hermes 后台运行。\n\n（vivo/OriginOS 还需在 设置→电池→后台高耗电 中把 Hermes 设为允许）")
            .setPositiveButton("去设置") { _, _ ->
                settings.batteryPromptShown = true
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$pkg"))
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) { /* 部分 ROM 无此页，忽略 */ }
                }
            }
            .setNegativeButton("稍后", null)
            .show()
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
