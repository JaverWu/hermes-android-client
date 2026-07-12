package com.hermes.chat.ui.conversations

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
