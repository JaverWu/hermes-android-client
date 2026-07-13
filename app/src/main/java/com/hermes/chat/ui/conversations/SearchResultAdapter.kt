package com.hermes.chat.ui.conversations

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hermes.chat.R
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.local.MessageSearchRow
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ItemSearchResultBinding
import com.hermes.chat.ui.common.AvatarLoader
import com.hermes.chat.ui.common.AvatarPresets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SearchResultAdapter(
    private val settings: SettingsRepository,
    private val onResultClick: (MessageSearchRow) -> Unit
) : ListAdapter<MessageSearchRow, SearchResultAdapter.VH>(DIFF) {

    /** 当前搜索关键词，用于片段高亮。每次 submitList 前由 Activity 更新。 */
    var query: String = ""

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.textTitle.text = item.conversationTitle.ifBlank { "对话" }
        holder.binding.textTime.text = formatTime(item.createdAt)
        val isUser = item.role == MessageEntity.ROLE_USER
        bindAvatar(holder, isUser)
        val snippet = buildSnippet(
            ctx = holder.binding.root.context,
            content = item.content,
            query = query,
            textView = holder.binding.textSnippet
        )
        holder.binding.textSnippet.text = snippet
        startHighlight(holder.binding.textSnippet, snippet)
        holder.binding.layoutRoot.setOnClickListener { onResultClick(item) }
    }

    override fun onViewRecycled(holder: VH) {
        // 回收时取消高亮动画，避免对复用后的 TextView 继续 invalidate
        (holder.binding.textSnippet.getTag() as? ValueAnimator)?.cancel()
        holder.binding.textSnippet.setTag(null)
        super.onViewRecycled(holder)
    }

    /** 头像：用户用自定义/预设头像，助手固定用 Hermes logo，均为圆形 40dp。 */
    private fun bindAvatar(holder: VH, isUser: Boolean) {
        val image = holder.binding.imageAvatar
        val letter = holder.binding.textAvatarLetter
        val frame = holder.binding.layoutAvatar

        if (isUser) {
            val path = settings.userAvatarPath
            if (path.isNotBlank() && AvatarLoader.loadCircularFromFile(image, path)) {
                frame.background = null
                letter.visibility = View.GONE
                return
            }
            val user = AvatarPresets.user(settings.userAvatarIndex)
            frame.setBackgroundResource(user.gradientRes)
            frame.backgroundTintList = null
            image.setImageDrawable(null)
            letter.visibility = View.VISIBLE
            letter.text = user.glyph
        } else {
            AvatarLoader.loadCircular(image, R.drawable.ic_logo_large)
            frame.background = null
            letter.visibility = View.GONE
        }
    }

    /**
     * 取关键词附近的片段，命中词用 [KeywordHighlightSpan] 包裹（蓝字加粗 + 背景高亮）。
     * 动画（闪烁两次 → 过渡为模糊阴影）由 [startHighlight] 驱动。
     */
    private fun buildSnippet(
        ctx: android.content.Context,
        content: String,
        query: String,
        textView: TextView
    ): SpannableString {
        val flat = content.replace("\n", " ").replace("\r", " ").trim()
        if (flat.isEmpty()) return SpannableString("")

        val accent = ContextCompat.getColor(ctx, R.color.tg_blue)
        val pill = ContextCompat.getColor(ctx, R.color.search_highlight_bg)

        val ql = query.trim().lowercase(Locale.ROOT)
        val idx = if (ql.isNotEmpty()) flat.lowercase(Locale.ROOT).indexOf(ql) else -1

        val from = maxOf(0, (if (idx >= 0) idx else 0) - 30)
        val to = minOf(flat.length, (if (idx >= 0) idx + ql.length else 0) + 90)
        val snippet = flat.substring(from, to)

        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < flat.length) "…" else ""
        val display = prefix + snippet + suffix

        val spannable = SpannableString(display)
        if (idx >= 0 && ql.isNotEmpty()) {
            var hs = idx - from
            if (from > 0) hs += prefix.length
            val he = hs + ql.length
            if (hs >= 0 && he <= display.length) {
                val span = KeywordHighlightSpan(accent, pill, textView)
                spannable.setSpan(span, hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    /**
     * 驱动命中词高亮动画：先闪烁两次（背景 on/off ×2），再过渡为柔和模糊阴影。
     * 动画绑定到 TextView 默认 tag，便于回收时取消。
     */
    private fun startHighlight(tv: TextView, spannable: SpannableString) {
        val spans = spannable.getSpans(0, spannable.length, KeywordHighlightSpan::class.java)
        if (spans.isEmpty()) return

        (tv.getTag() as? ValueAnimator)?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                spans.forEach { span ->
                    if (t < 0.5f) {
                        // 0~0.5 内分 4 段 → 闪两次：on/off/on/off
                        span.blinkOn = ((t / 0.125f).toInt() % 2 == 0)
                        span.blur = 0f
                    } else {
                        span.blinkOn = true
                        span.blur = (t - 0.5f) / 0.5f
                    }
                }
                tv.invalidate()
            }
        }
        tv.setTag(anim)
        anim.start()
    }

    class VH(val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MessageSearchRow>() {
            override fun areItemsTheSame(a: MessageSearchRow, b: MessageSearchRow) = a.id == b.id
            override fun areContentsTheSame(a: MessageSearchRow, b: MessageSearchRow) = a == b
        }

        private val FMT_TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val FMT_DATE = SimpleDateFormat("MM-dd", Locale.getDefault())

        private fun formatTime(ts: Long): String {
            val now = Calendar.getInstance()
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            return if (now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            ) FMT_TIME.format(Date(ts)) else FMT_DATE.format(Date(ts))
        }
    }
}
