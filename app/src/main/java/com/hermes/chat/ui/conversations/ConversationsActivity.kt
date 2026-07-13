package com.hermes.chat.ui.conversations

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.core.app.ActivityOptionsCompat
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
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivityConversationsBinding
import com.hermes.chat.ui.chat.ChatActivity
import com.hermes.chat.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ConversationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationsBinding
    private val viewModel: ConversationsViewModel by viewModels()
    private lateinit var adapter: ConversationAdapter
    private lateinit var searchAdapter: SearchResultAdapter

    /** 搜索防抖任务 */
    private var searchJob: Job? = null

    /** 关闭搜索时清空输入框会触发 TextWatcher，用此标志抑制重复搜索导致空提示残留 */
    private var suppressSearchText = false

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
            onClick = { openChat(it.id, null) },
            messageDao = messageDao
        )
        binding.recyclerConversations.layoutManager = LinearLayoutManager(this)
        binding.recyclerConversations.adapter = adapter

        searchAdapter = SearchResultAdapter(SettingsRepository(this)) { result ->
            closeSearch() // 收起搜索栏再跳转，避免返回时状态错乱
            openChat(result.conversationId, result.id)
        }
        binding.recyclerSearchResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerSearchResults.adapter = searchAdapter

        // 右下角「新增对话」悬浮按钮：macOS 式神奇缩放进入新会话
        binding.fabNewChat.setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java)
            if (prefersReducedMotion()) {
                startActivity(intent)
            } else {
                val opt = ActivityOptionsCompat.makeScaleUpAnimation(
                    binding.fabNewChat,
                    binding.fabNewChat.width / 2,
                    binding.fabNewChat.height / 2,
                    0, 0
                )
                startActivity(intent, opt.toBundle())
            }
        }

        // 搜索栏关闭按钮
        binding.textSearchClose.setOnClickListener { closeSearch() }
        // 输入即搜索（防抖 200ms）
        binding.editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressSearchText) return
                scheduleSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 搜索展开时，系统返回键先收起搜索栏（回到启动页/会话列表），而不是关闭整个 App
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutSearchBar.visibility == View.VISIBLE) {
                    closeSearch()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }

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
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.conversations_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> { openSearch(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ───────────── 搜索 ─────────────

    private fun openSearch() {
        // 搜索栏贝塞尔弹出（ease-out 360ms），尊重"减少动效"
        if (prefersReducedMotion()) {
            binding.layoutSearchBar.visibility = View.VISIBLE
        } else {
            popSearchBar(show = true)
        }
        binding.recyclerConversations.visibility = View.GONE
        binding.textEmpty.visibility = View.GONE
        // 空查询状态：提示用户输入
        searchAdapter.query = ""
        searchAdapter.submitList(emptyList())
        binding.recyclerSearchResults.visibility = View.GONE
        binding.textSearchEmpty.visibility = View.VISIBLE
        binding.textSearchEmpty.setText(R.string.search_empty_hint)
        binding.editSearch.setText("")
        binding.editSearch.requestFocus()
        showKeyboard()
    }

    private fun closeSearch() {
        searchJob?.cancel()
        if (prefersReducedMotion()) {
            binding.layoutSearchBar.visibility = View.GONE
        } else {
            popSearchBar(show = false)
        }
        suppressSearchText = true
        binding.editSearch.setText("")
        suppressSearchText = false
        hideKeyboard()
        binding.recyclerSearchResults.visibility = View.GONE
        binding.textSearchEmpty.visibility = View.GONE
        binding.recyclerConversations.visibility = View.VISIBLE
    }

    /** 搜索栏平滑展开/收起：alpha + 轻微上移，FastOutSlowInInterpolator ≈ ease-out-quint。 */
    private fun popSearchBar(show: Boolean) {
        val bar = binding.layoutSearchBar
        if (show) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = -48f
            bar.animate().alpha(1f).translationY(0f)
                .setDuration(360).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            bar.animate().alpha(0f).translationY(-48f)
                .setDuration(300).setInterpolator(FastOutSlowInInterpolator())
                .withEndAction { bar.visibility = View.GONE }.start()
        }
    }

    /** 是否开启"减少动效"（系统动画时长缩放为 0）。 */
    private fun prefersReducedMotion(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(200)
            runSearch(query)
        }
    }

    private suspend fun runSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) {
            searchAdapter.submitList(emptyList())
            binding.recyclerSearchResults.visibility = View.GONE
            binding.textSearchEmpty.visibility = View.VISIBLE
            binding.textSearchEmpty.setText(R.string.search_empty_hint)
            return
        }
        val results = viewModel.searchMessages(q)
        searchAdapter.query = q
        searchAdapter.submitList(results)
        if (results.isEmpty()) {
            binding.recyclerSearchResults.visibility = View.GONE
            binding.textSearchEmpty.visibility = View.VISIBLE
            binding.textSearchEmpty.setText(R.string.search_result_empty)
        } else {
            binding.recyclerSearchResults.visibility = View.VISIBLE
            binding.textSearchEmpty.visibility = View.GONE
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
    }

    // ───────────── 导航 ─────────────

    private fun openChat(conversationId: String?, messageId: String?) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatActivity.EXTRA_MESSAGE_ID, messageId)
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
