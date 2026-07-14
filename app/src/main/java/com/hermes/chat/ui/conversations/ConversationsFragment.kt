package com.hermes.chat.ui.conversations

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hermes.chat.HermesApplication
import com.hermes.chat.R
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageDao
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.FragmentConversationsBinding
import com.hermes.chat.ui.chat.ChatActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ConversationsFragment : Fragment() {

    private var _binding: FragmentConversationsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConversationsViewModel by viewModels()
    private lateinit var adapter: ConversationAdapter
    private lateinit var searchAdapter: SearchResultAdapter

    private var searchJob: Job? = null
    private var suppressSearchText = false
    private lateinit var searchBackCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Toolbar 菜单：搜索图标
        binding.toolbar.inflateMenu(R.menu.conversations_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> { openSearch(); true }
                else -> false
            }
        }

        val messageDao: MessageDao = (requireActivity().application as HermesApplication).database.messageDao()
        adapter = ConversationAdapter(
            onClick = { openChat(it.id, null) },
            messageDao = messageDao
        )
        binding.recyclerConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerConversations.adapter = adapter

        searchAdapter = SearchResultAdapter(SettingsRepository(requireContext())) { result ->
            closeSearch()
            openChat(result.conversationId, result.id)
        }
        binding.recyclerSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSearchResults.adapter = searchAdapter

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

        // 搜索展开时，系统返回键先收起搜索栏（通过 enable/disable 控制）
        searchBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.conversations.collect { list ->
                adapter.submitList(list)
                binding.textEmpty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        resetSearchUi()
    }

    fun resetSearchUi() {
        _binding ?: return
        binding.layoutSearchBar.visibility = View.GONE
        binding.recyclerSearchResults.visibility = View.GONE
        binding.textSearchEmpty.visibility = View.GONE
        binding.recyclerConversations.visibility = View.VISIBLE
        if (::searchBackCallback.isInitialized) searchBackCallback.isEnabled = false
    }

    fun closeSearchIfOpen() {
        _binding ?: return
        if (binding.layoutSearchBar.visibility == View.VISIBLE) {
            closeSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        resetSearchUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ───────────── 搜索 ─────────────

    private fun openSearch() {
        if (prefersReducedMotion()) {
            binding.layoutSearchBar.visibility = View.VISIBLE
        } else {
            popSearchBar(show = true)
        }
        binding.recyclerConversations.visibility = View.GONE
        binding.textEmpty.visibility = View.GONE
        searchAdapter.query = ""
        searchAdapter.submitList(emptyList())
        binding.recyclerSearchResults.visibility = View.GONE
        binding.textSearchEmpty.visibility = View.VISIBLE
        binding.textSearchEmpty.setText(R.string.search_empty_hint)
        binding.editSearch.setText("")
        binding.editSearch.requestFocus()
        showKeyboard()
        searchBackCallback.isEnabled = true
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
        searchBackCallback.isEnabled = false
    }

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

    private fun prefersReducedMotion(): Boolean =
        Settings.Global.getFloat(requireContext().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
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
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
    }

    // ───────────── 导航 ─────────────

    private fun openChat(conversationId: String?, messageId: String?) {
        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatActivity.EXTRA_MESSAGE_ID, messageId)
        }
        startActivity(intent)
    }

    private fun confirmDelete(conv: ConversationEntity) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.delete(conv.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
