package com.hermes.chat.ui.settings

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.hermes.chat.R
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.ActivitySettingsBinding

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

        binding.buttonSave.setOnClickListener {
            settings.baseUrl = binding.editBaseUrl.text.toString()
            settings.apiKey = binding.editApiKey.text.toString()
            settings.systemPrompt = binding.editSystemPrompt.text.toString()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
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
}
