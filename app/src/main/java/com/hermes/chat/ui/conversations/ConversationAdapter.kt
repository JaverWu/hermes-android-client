package com.hermes.chat.ui.conversations

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hermes.chat.R
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageDao
import com.hermes.chat.databinding.ItemConversationBinding
import com.hermes.chat.ui.common.AvatarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onClick: (ConversationEntity) -> Unit,
    private val messageDao: MessageDao
) : ListAdapter<ConversationEntity, ConversationAdapter.VH>(DIFF) {

    // 仅用于异步加载预览，随 Adapter 生命周期结束而取消
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 头像调色板（按会话名 hash 选取）
    private val avatarColors = intArrayOf(
        R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
        R.color.avatar_4, R.color.avatar_5, R.color.avatar_6
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val itemId = item.id
        val pos = position
        val title = item.title.ifBlank { "新对话" }

        holder.binding.textTitle.text = title
        holder.binding.textTime.text = formatTime(item.updatedAt)

        // 头像：首字母 + 按名称 hash 选色（仅 TEXT 模式，不接自定义图片）
        val colorRes = avatarColors[Math.abs(title.hashCode()) % avatarColors.size]
        val letter = title.firstOrNull()?.uppercaseChar()?.toString() ?: "H"
        holder.binding.avatarView.bindText(letter, colorRes)

        // 预览：先清空避免复用残留，再异步读取最后一条消息（带复用防护）
        holder.binding.textPreview.text = ""
        scope.launch {
            val last = messageDao.getLastContent(itemId)
            withContext(Dispatchers.Main) {
                // 仅当该 ViewHolder 仍绑定同一位置、且同一条会话时才写回，防止滚动误写
                if (holder.bindingAdapterPosition == pos &&
                    currentList.getOrNull(pos)?.id == itemId
                ) {
                    holder.binding.textPreview.text = last ?: ""
                }
            }
        }

        holder.binding.cardConversation.setOnClickListener { onClick(item) }
    }

    class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConversationEntity>() {
            override fun areItemsTheSame(a: ConversationEntity, b: ConversationEntity) = a.id == b.id
            override fun areContentsTheSame(a: ConversationEntity, b: ConversationEntity) = a == b
        }

        private val FMT_TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val FMT_DATE = SimpleDateFormat("MM-dd", Locale.getDefault())

        /** 今天显示时间，其它日期显示月-日。 */
        private fun formatTime(ts: Long): String {
            val now = Calendar.getInstance()
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            return if (now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            ) FMT_TIME.format(Date(ts)) else FMT_DATE.format(Date(ts))
        }
    }
}
