package com.hermes.chat

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.ui.chat.ChatNotificationManager

class HermesApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)

        val settings = SettingsRepository(this)

        // 启动时应用夜间模式偏好
        val mode = when (settings.nightMode) {
            SettingsRepository.MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsRepository.MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)

        // 注册通知渠道（确保在任何通知发出前就绪）
        ChatNotificationManager.createChannels(this)

        // 已配置且开启常驻通知时，显示待机通知
        if (settings.isConfigured() && settings.persistentNotification) {
            val cid = settings.lastConversationId.ifBlank { null }
            ChatNotificationManager.showStandby(this, cid)
        }
    }
}
