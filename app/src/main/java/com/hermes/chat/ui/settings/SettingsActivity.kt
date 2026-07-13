package com.hermes.chat.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.hermes.chat.R
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivitySettingsBinding
import com.hermes.chat.ui.chat.ChatNotificationManager
import com.hermes.chat.ui.common.AvatarLoader
import com.hermes.chat.ui.common.AvatarPresets
import com.hermes.chat.ui.common.showAvatarPicker

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

        // 头像选择
        binding.layoutUserAvatar.setOnClickListener { showUserAvatarChooser() }
        refreshAvatars()

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

        // 保持屏幕常亮
        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            settings.keepScreenOn = checked
        }

        // 通知栏快捷对话开关
        binding.switchPersistentNotification.isChecked = settings.persistentNotification
        binding.switchPersistentNotification.setOnCheckedChangeListener { _, checked ->
            settings.persistentNotification = checked
            if (checked && settings.isConfigured()) {
                ChatNotificationManager.showStandby(this, settings.lastConversationId.ifBlank { null })
            } else {
                ChatNotificationManager.cancel(this)
            }
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

    /** 根据当前设置刷新两个头像预览 */
    private fun refreshAvatars() {
        // 我的头像
        val path = settings.userAvatarPath
        if (path.isNotBlank() && AvatarLoader.loadCircularFromFile(binding.imageUserAvatar, path)) {
            // 有自定义图片：隐藏字母 fallback，显示图片路径提示
            binding.textUserAvatarLetter.visibility = android.view.View.GONE
            binding.textUserAvatarSubtitle.setText(R.string.avatar_change_image)
        } else {
            // 无自定义图片：显示预设渐变+字母
            val user = AvatarPresets.user(settings.userAvatarIndex)
            binding.imageUserAvatar.setBackgroundResource(user.gradientRes)
            binding.imageUserAvatar.backgroundTintList = null
            binding.textUserAvatarLetter.visibility = android.view.View.VISIBLE
            binding.textUserAvatarLetter.text = user.glyph
            binding.textUserAvatarSubtitle.setText(R.string.avatar_change)
        }

        // 接收方（Hermes）头像固定为 Logo，不可更改
        AvatarLoader.loadCircular(binding.imageAiAvatar, R.drawable.ic_logo_large)
        binding.textAiAvatarLetter.visibility = android.view.View.GONE
    }

    /** 我的头像：弹出选择菜单（从相册上传 / 使用预设 / 恢复默认） */
    private fun showUserAvatarChooser() {
        val items = arrayOf(
            getString(R.string.avatar_upload),
            getString(R.string.avatar_use_preset),
            getString(R.string.avatar_reset_default)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.avatar_user)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickImage.launch("image/*")
                    1 -> showAvatarPicker(
                        this,
                        AvatarPresets.USER,
                        settings.userAvatarIndex
                    ) { settings.userAvatarIndex = it; settings.userAvatarPath = ""; refreshAvatars() }
                    2 -> { settings.userAvatarPath = ""; refreshAvatars() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 从相册选取图片并复制到应用内部存储，作为用户自定义头像 */
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val path = AvatarLoader.copyPickedImageToInternal(this, uri)
            if (path != null) {
                settings.userAvatarPath = path
                refreshAvatars()
            } else {
                Toast.makeText(this, R.string.avatar_upload_fail, Toast.LENGTH_SHORT).show()
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
