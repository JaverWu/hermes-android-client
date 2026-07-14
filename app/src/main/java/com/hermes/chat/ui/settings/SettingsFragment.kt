package com.hermes.chat.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.hermes.chat.R
import com.hermes.chat.data.preferences.SettingsRepository
import com.hermes.chat.databinding.FragmentSettingsBinding
import com.hermes.chat.ui.chat.ChatNotificationManager
import com.hermes.chat.ui.common.AvatarLoader
import com.hermes.chat.ui.common.AvatarPresets
import com.hermes.chat.ui.common.showAvatarPicker

class SettingsFragment : Fragment() {

    interface HostCallback {
        fun switchToConversations()
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val path = AvatarLoader.copyPickedImageToInternal(requireContext(), uri)
            if (path != null) {
                settings.userAvatarPath = path
                refreshAvatars()
            } else {
                Toast.makeText(requireContext(), R.string.avatar_upload_fail, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = SettingsRepository(requireContext())

        // 返回按钮 → 切回对话列表（非 finish，因为现在是 Fragment）
        binding.toolbar.setNavigationOnClickListener {
            (requireActivity() as? HostCallback)?.switchToConversations()
        }

        binding.editBaseUrl.setText(settings.baseUrl)
        binding.editApiKey.setText(settings.apiKey)
        binding.editSystemPrompt.setText(settings.systemPrompt)

        // 头像选择
        binding.layoutUserAvatar.setOnClickListener { showUserAvatarChooser() }
        // 头像涉及 Bitmap 解码+缩放（主线程耗时），延后到首帧绘制后再加载
        binding.root.post { refreshAvatars() }

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
                ChatNotificationManager.showStandby(requireContext(), settings.lastConversationId.ifBlank { null })
            } else {
                ChatNotificationManager.cancel(requireContext())
            }
        }

        // 电池优化白名单
        refreshBatteryOptimizationStatus()
        binding.layoutBatteryOptimization.setOnClickListener {
            val pm = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                try {
                    val intent = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "无法跳转电池优化设置，请手动在系统设置中操作", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(requireContext(), "已允许后台运行", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonSave.setOnClickListener {
            settings.baseUrl = binding.editBaseUrl.text.toString()
            settings.apiKey = binding.editApiKey.text.toString()
            settings.systemPrompt = binding.editSystemPrompt.text.toString()
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            (requireActivity() as? HostCallback)?.switchToConversations()
        }
    }

    private fun refreshAvatars() {
        _binding ?: return
        // 我的头像
        val path = settings.userAvatarPath
        if (path.isNotBlank() && AvatarLoader.loadCircularFromFile(binding.imageUserAvatar, path)) {
            binding.textUserAvatarLetter.visibility = View.GONE
            binding.textUserAvatarSubtitle.setText(R.string.avatar_change_image)
        } else {
            val user = AvatarPresets.user(settings.userAvatarIndex)
            binding.imageUserAvatar.setBackgroundResource(user.gradientRes)
            binding.imageUserAvatar.backgroundTintList = null
            binding.textUserAvatarLetter.visibility = View.VISIBLE
            binding.textUserAvatarLetter.text = user.glyph
            binding.textUserAvatarSubtitle.setText(R.string.avatar_change)
        }

        // 接收方（Hermes）头像固定为 Logo，不可更改
        AvatarLoader.loadCircular(binding.imageAiAvatar, R.drawable.ic_logo_large)
        binding.textAiAvatarLetter.visibility = View.GONE
    }

    private fun showUserAvatarChooser() {
        val items = arrayOf(
            getString(R.string.avatar_upload),
            getString(R.string.avatar_use_preset),
            getString(R.string.avatar_reset_default)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.avatar_user)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickImage.launch("image/*")
                    1 -> showAvatarPicker(
                        requireContext(),
                        AvatarPresets.USER,
                        settings.userAvatarIndex
                    ) { settings.userAvatarIndex = it; settings.userAvatarPath = ""; refreshAvatars() }
                    2 -> { settings.userAvatarPath = ""; refreshAvatars() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyNightMode(mode: String) {
        val nightMode = when (mode) {
            SettingsRepository.MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsRepository.MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun refreshBatteryOptimizationStatus() {
        _binding ?: return
        val pm = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        binding.textBatteryStatus.text = if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            getString(R.string.settings_battery_optimization_enabled)
        } else {
            getString(R.string.settings_battery_optimization_disabled)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshBatteryOptimizationStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
