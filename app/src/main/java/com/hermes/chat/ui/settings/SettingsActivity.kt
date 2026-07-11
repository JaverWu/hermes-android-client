package com.hermes.chat.ui.settings

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.hermes.chat.R
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivitySettingsBinding
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

        // 头像选择
        binding.layoutUserAvatar.setOnClickListener {
            showAvatarPicker(
                this,
                AvatarPresets.USER,
                settings.userAvatarIndex
            ) { settings.userAvatarIndex = it; refreshAvatars() }
        }
        binding.layoutAiAvatar.setOnClickListener {
            showAvatarPicker(
                this,
                AvatarPresets.AI,
                settings.aiAvatarIndex
            ) { settings.aiAvatarIndex = it; refreshAvatars() }
        }
        refreshAvatars()

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
        val user = AvatarPresets.user(settings.userAvatarIndex)
        binding.imageUserAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, user.colorRes)
        )
        binding.textUserAvatarLetter.text = user.glyph

        val ai = AvatarPresets.ai(settings.aiAvatarIndex)
        binding.imageAiAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, ai.colorRes)
        )
        binding.textAiAvatarLetter.text = ai.glyph
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
}
