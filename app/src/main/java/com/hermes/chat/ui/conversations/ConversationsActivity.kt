package com.hermes.chat.ui.conversations

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.fragment.app.commit
import com.hermes.chat.R
import com.hermes.chat.databinding.ActivityConversationsBinding
import com.hermes.chat.ui.chat.ChatActivity
import com.hermes.chat.ui.common.TabBarController
import com.hermes.chat.ui.settings.SettingsFragment

/**
 * Fragment 宿主：管理 [ConversationsFragment]（会话列表）与 [SettingsFragment]（设置页）。
 * 两个 Fragment 通过 show/hide 切换，保持常驻内存，切换时不会重新加载。
 * 底部玻璃标签栏由本 Activity 持有，Fragment 不包含标签栏。
 */
class ConversationsActivity : AppCompatActivity(), SettingsFragment.HostCallback {

    private lateinit var binding: ActivityConversationsBinding
    private lateinit var conversationsFragment: ConversationsFragment
    private lateinit var settingsFragment: SettingsFragment

    private var currentTab = TabBarController.Tab.CHATS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            conversationsFragment = ConversationsFragment()
            settingsFragment = SettingsFragment()
            supportFragmentManager.commit {
                add(R.id.fragmentContainer, conversationsFragment, TAG_CONVERSATIONS)
                add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                hide(settingsFragment)
            }
        } else {
            conversationsFragment = supportFragmentManager.findFragmentByTag(TAG_CONVERSATIONS) as ConversationsFragment
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as SettingsFragment
            // 恢复标签高亮（夜间模式重建后 FragmentManager 已恢复 Fragment，但 tab bar 是新 inflate 的）
            currentTab = savedInstanceState.getInt(KEY_CURRENT_TAB, 0).let {
                if (it == 1) TabBarController.Tab.SETTINGS else TabBarController.Tab.CHATS
            }
            if (currentTab == TabBarController.Tab.SETTINGS) {
                supportFragmentManager.commit {
                    show(settingsFragment)
                    hide(conversationsFragment)
                }
            }
        }

        // 底部玻璃标签栏
        TabBarController.bind(
            root = binding.tabBar.root,
            current = currentTab,
            onNewChat = { fab -> startNewChatWithZoom(fab) },
            onSwitchTab = { tab -> switchTab(tab) }
        )

        // 返回键：设置页→对话页，对话页→默认（退出）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab == TabBarController.Tab.SETTINGS) {
                    switchTab(TabBarController.Tab.CHATS)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // 处理从 ChatActivity 跳转来设置页的请求
        handleSettingsIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSettingsIntent(intent)
    }

    private fun handleSettingsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_SETTINGS, false) == true) {
            intent.removeExtra(EXTRA_SHOW_SETTINGS)
            switchTab(TabBarController.Tab.SETTINGS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, if (currentTab == TabBarController.Tab.SETTINGS) 1 else 0)
    }

    /** 切换标签：show/hide Fragment，更新高亮。切换前关闭搜索避免残留。 */
    private fun switchTab(tab: TabBarController.Tab) {
        if (tab == currentTab) return
        // 离开对话页时关闭搜索
        if (currentTab == TabBarController.Tab.CHATS) {
            conversationsFragment.closeSearchIfOpen()
        }
        supportFragmentManager.commit {
            when (tab) {
                TabBarController.Tab.CHATS -> show(conversationsFragment).hide(settingsFragment)
                TabBarController.Tab.SETTINGS -> show(settingsFragment).hide(conversationsFragment)
            }
        }
        currentTab = tab
        TabBarController.updateActive(binding.tabBar.root, tab)
    }

    override fun switchToConversations() {
        switchTab(TabBarController.Tab.CHATS)
    }

    private fun startNewChatWithZoom(fab: View) {
        val intent = Intent(this, ChatActivity::class.java)
        if (prefersReducedMotion()) {
            startActivity(intent)
        } else {
            val opt = ActivityOptionsCompat.makeScaleUpAnimation(
                fab, fab.width / 2, fab.height / 2, 0, 0
            )
            startActivity(intent, opt.toBundle())
        }
    }

    private fun prefersReducedMotion(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    companion object {
        private const val TAG_CONVERSATIONS = "conversations"
        private const val TAG_SETTINGS = "settings"
        private const val KEY_CURRENT_TAB = "current_tab"
        const val EXTRA_SHOW_SETTINGS = "show_settings"
    }
}
