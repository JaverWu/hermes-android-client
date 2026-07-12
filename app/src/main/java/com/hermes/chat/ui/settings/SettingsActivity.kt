package com.hermes.chat.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.hermes.chat.R
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivitySettingsBinding
import com.hermes.chat.ui.common.AvatarPresets
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.editBaseUrl.setText(settings.baseUrl)
        binding.editApiKey.setText(settings.apiKey)
        binding.editSystemPrompt.setText(settings.systemPrompt)

        // 夜间模式选项
        when (settings.nightMode) {
            SettingsRepository.MODE_LIGHT -> binding.radioLight.isChecked = true
            SettingsRepository.MODE_DARK -> binding.radioDark.isChecked = true
            else -> binding.radioSystem.isChecked = true
        }
        binding.radioGroupNightMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioLight -> SettingsRepository.MODE_LIGHT
                R.id.radioDark -> SettingsRepository.MODE_DARK
                else -> SettingsRepository.MODE_SYSTEM
            }
            settings.nightMode = mode
            applyNightMode(mode)
        }

        refreshAvatars()

        // 保持屏幕常亮
        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            settings.keepScreenOn = checked
        }

        // 电池优化白名单
        refreshBatteryOptimizationStatus()
        binding.layoutBatteryOptimization.setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "无法跳转电池优化设置，请手动在系统设置中操作", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "已允许后台运行", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonSave.setOnClickListener {
            settings.baseUrl = binding.editBaseUrl.text.toString()
            settings.apiKey = binding.editApiKey.text.toString()
            settings.systemPrompt = binding.editSystemPrompt.text.toString()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 顶部资料区头像：有自定义图片则图片，否则预设 */
    private fun refreshAvatars() {
        val userUri = settings.userAvatarUri
        val userLoaded = userUri.isNotBlank() && File(userUri).exists()
        if (userLoaded) {
            val okProfile = binding.avatarProfile.bindImage(userUri)
            if (!okProfile) {
                val user = AvatarPresets.user(settings.userAvatarIndex)
                binding.avatarProfile.bindText(user.glyph, user.colorRes)
            }
        } else {
            val user = AvatarPresets.user(settings.userAvatarIndex)
            binding.avatarProfile.bindText(user.glyph, user.colorRes)
        }
    }

    private fun applyNightMode(mode: String) {
        val nightMode = when (mode) {
            SettingsRepository.MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsRepository.MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        // 系统会自动 recreate Activity，无需手动 finish
    }

    private fun refreshBatteryOptimizationStatus() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        binding.textBatteryStatus.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            getString(R.string.settings_battery_optimization_enabled)
        } else {
            getString(R.string.settings_battery_optimization_disabled)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshBatteryOptimizationStatus()
    }
}
