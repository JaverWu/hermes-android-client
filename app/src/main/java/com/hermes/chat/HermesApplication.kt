package com.hermes.chat

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.hermes.chat.data.local.AppDatabase
import com.hermes.chat.data.preferences.SettingsRepository

class HermesApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)

        // 启动时应用夜间模式偏好
        val settings = SettingsRepository(this)
        val mode = when (settings.nightMode) {
            SettingsRepository.MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsRepository.MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
