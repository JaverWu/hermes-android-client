package com.hermes.chat.ui.chat

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hermes.chat.R
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ItemMessageApprovalBinding
import com.hermes.chat.databinding.ItemMessageAssistantBinding
import com.hermes.chat.databinding.ItemMessageSystemBinding
import com.hermes.chat.databinding.ItemMessageUserBinding
import com.hermes.chat.ui.common.AvatarPresets
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val settings: SettingsRepository,
    private val onApprovalClick: (MessageEntity, String) -> Unit
) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(DIFF) {

    private var markwon: Markwon? = null
    private var streamingAssistantId: String? = null
    private var thinkingContent: String = ""
    private var statusText: String = ""

    fun setStreamingAssistantId(id: String?) {
        streamingAssistantId = id
    }

    fun setStreamingState(thinking: String, status: String) {
        thinkingContent = thinking
        statusText = status
    }

    private fun getMarkwon(context: Context): Markwon {
        if (markwon == null) {
            markwon = Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .build()
        }
        return markwon!!
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position).role) {
        MessageEntity.ROLE_USER -> VIEW_USER
        MessageEntity.ROLE_ASSISTANT -> VIEW_ASSISTANT
        MessageEntity.ROLE_APPROVAL -> VIEW_APPROVAL
        else -> VIEW_SYSTEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_USER -> UserVH(ItemMessageUserBinding.inflate(inflater, parent, false))
            VIEW_ASSISTANT -> AssistantVH(ItemMessageAssistantBinding.inflate(inflater, parent, false))
            VIEW_APPROVAL -> ApprovalVH(ItemMessageApprovalBinding.inflate(inflater, parent, false))
            else -> SystemVH(ItemMessageSystemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        when (holder) {
            is UserVH -> {
                val user = AvatarPresets.user(settings.userAvatarIndex)
                holder.binding.imageAvatar.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, user.colorRes))
                holder.binding.textAvatarLetter.text = user.glyph
                holder.binding.textMessage.text = item.content
                holder.binding.textTime.text = formatTime(item.createdAt)
            }
            is AssistantVH -> {
                // 头像（按设置注入颜色 + 字形）
                val ai = AvatarPresets.ai(settings.aiAvatarIndex)
                holder.binding.imageAvatar.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, ai.colorRes))
                holder.binding.textAvatarLetter.text = ai.glyph

                val isStreamingThis = item.id == streamingAssistantId
                val hasContent = item.content.isNotBlank()

                if (isStreamingThis && !hasContent) {
                    // 流式且尚未有正文：优先展示思考流，否则展示打字指示器
                    if (thinkingContent.isNotBlank()) {
                        holder.binding.layoutThinking.visibility = View.VISIBLE
                        holder.binding.textThinking.text = thinkingContent
                        holder.binding.layoutTyping.visibility = View.GONE
                    } else {
                        holder.binding.layoutThinking.visibility = View.GONE
                        holder.binding.layoutTyping.visibility = View.VISIBLE
                    }
                    holder.binding.textMessage.visibility = View.GONE
                } else {
                    holder.binding.layoutThinking.visibility = View.GONE
                    holder.binding.layoutTyping.visibility = View.GONE
                    holder.binding.textMessage.visibility = View.VISIBLE
                    getMarkwon(ctx).setMarkdown(
                        holder.binding.textMessage,
                        if (hasContent) item.content else "…"
                    )
                }

                // 实时状态文字（Hermes 正在思考 / 生成 / 调用工具…）
                if (isStreamingThis && statusText.isNotBlank()) {
                    holder.binding.textStatus.visibility = View.VISIBLE
                    holder.binding.textStatus.text = statusText
                } else {
                    holder.binding.textStatus.visibility = View.GONE
                }

                holder.binding.textTime.text = formatTime(item.createdAt)
            }
            is SystemVH -> holder.binding.textMessage.text = item.content
            is ApprovalVH -> holder.bind(item, onApprovalClick)
        }
    }

    class UserVH(val binding: ItemMessageUserBinding) : RecyclerView.ViewHolder(binding.root)
    class AssistantVH(val binding: ItemMessageAssistantBinding) : RecyclerView.ViewHolder(binding.root)
    class SystemVH(val binding: ItemMessageSystemBinding) : RecyclerView.ViewHolder(binding.root)
    class ApprovalVH(val binding: ItemMessageApprovalBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MessageEntity, onClick: (MessageEntity, String) -> Unit) {
            val ctx = binding.root.context
            binding.textTitle.text = item.approvalTitle.ifBlank { ctx.getString(R.string.approval_hint) }
            if (item.approvalDetail.isNotBlank()) {
                binding.textDetail.visibility = View.VISIBLE
                binding.textDetail.text = item.approvalDetail
            } else {
                binding.textDetail.visibility = View.GONE
            }

            binding.layoutOptions.removeAllViews()
            val resolved = item.approvalStatus == MessageEntity.STATUS_RESOLVED
            val blue = ContextCompat.getColor(ctx, R.color.approval_btn_bg)
            val white = ContextCompat.getColor(ctx, R.color.approval_btn_text)
            val gray = ContextCompat.getColor(ctx, R.color.text_secondary)
            val radius = ctx.resources.getDimensionPixelSize(R.dimen.approval_btn_radius)

            item.options().forEach { option ->
                val btn = MaterialButton(ctx).apply {
                    text = option
                    isAllCaps = false
                    isEnabled = !resolved
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = radius
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (resolved) gray else blue
                    )
                    setTextColor(white)
                    setOnClickListener { onClick(item, option) }
                }
                val params = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                binding.layoutOptions.addView(btn, params)
            }
            if (resolved) {
                val tag = android.widget.TextView(ctx).apply {
                    text = "已处理"
                    setTextColor(gray)
                }
                binding.layoutOptions.addView(tag)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MessageEntity>() {
            override fun areItemsTheSame(a: MessageEntity, b: MessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: MessageEntity, b: MessageEntity) = a == b
        }

        private const val VIEW_USER = 1
        private const val VIEW_ASSISTANT = 2
        private const val VIEW_APPROVAL = 3
        private const val VIEW_SYSTEM = 4

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
