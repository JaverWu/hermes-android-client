package com.hermes.chat.ui.common

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hermes.chat.R

/**
 * 底部玻璃悬浮标签栏控制器（对话 / ＋新对话 / 设置）。
 * 不再通过 startActivity 跳转，而是通过 [onSwitchTab] 回调通知宿主切换 Fragment。
 */
object TabBarController {

    enum class Tab { CHATS, SETTINGS }

    fun bind(
        root: View,
        current: Tab,
        onNewChat: (View) -> Unit,
        onSwitchTab: (Tab) -> Unit
    ) {
        val tabChats = root.findViewById<View>(R.id.tabChats) ?: return
        val tabNew = root.findViewById<View>(R.id.tabNew)
        val tabSettings = root.findViewById<View>(R.id.tabSettings)
        val tabChatsBtn = root.findViewById<View>(R.id.tabChatsBtn) ?: tabChats
        val tabSettingsBtn = root.findViewById<View>(R.id.tabSettingsBtn) ?: tabSettings

        tabChatsBtn.setOnClickListener { onSwitchTab(Tab.CHATS) }
        tabSettingsBtn.setOnClickListener { onSwitchTab(Tab.SETTINGS) }
        tabNew?.setOnClickListener { onNewChat(tabNew) }

        updateActive(root, current)
    }

    /** 更新标签高亮：激活=品牌蓝，未激活=次级灰。 */
    fun updateActive(root: View, tab: Tab) {
        val tabChats = root.findViewById<View>(R.id.tabChats) ?: return
        val tabSettings = root.findViewById<View>(R.id.tabSettings)
        setItemActive(tabChats, R.id.tabIconChats, R.id.tabLabelChats, tab == Tab.CHATS)
        setItemActive(tabSettings, R.id.tabIconSettings, R.id.tabLabelSettings, tab == Tab.SETTINGS)
    }

    private fun setItemActive(item: View, iconId: Int, labelId: Int, active: Boolean) {
        val icon = item.findViewById<ImageView>(iconId) ?: return
        val label = item.findViewById<TextView>(labelId) ?: return
        val color = ContextCompat.getColor(
            item.context,
            if (active) R.color.tg_blue else R.color.text_secondary
        )
        icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        label.setTextColor(color)
    }
}
