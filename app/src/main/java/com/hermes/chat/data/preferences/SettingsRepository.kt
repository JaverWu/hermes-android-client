package com.hermes.chat.data.preferences

import android.content.Context
import android.content.SharedPreferences

/** 保存 Hermes 后端连接配置（本地 SharedPreferences）。 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BASE_URL, v.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_API_KEY, v.trim()).apply()

    // 模型固定使用 Hermes agent 自带的默认模型，App 不向用户询问。
    // 默认 "hermes-agent"：与 Hermes /v1/models 返回值一致（Hermes API server advertised 的模型名）。
    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString(KEY_MODEL, v.trim().ifBlank { DEFAULT_MODEL }).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SYSTEM_PROMPT, v).apply()

    /** 夜间模式："system"（跟随系统）/ "light" / "dark" */
    var nightMode: String
        get() = prefs.getString(KEY_NIGHT_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
        set(v) = prefs.edit().putString(KEY_NIGHT_MODE, v).apply()

    /** 用户头像预设索引（见 [com.hermes.chat.ui.common.AvatarPresets.USER]） */
    var userAvatarIndex: Int
        get() = prefs.getInt(KEY_USER_AVATAR, 0)
        set(v) = prefs.edit().putInt(KEY_USER_AVATAR, v).apply()

    /** Hermes 头像预设索引（见 [com.hermes.chat.ui.common.AvatarPresets.AI]） */
    var aiAvatarIndex: Int
        get() = prefs.getInt(KEY_AI_AVATAR, 0)
        set(v) = prefs.edit().putInt(KEY_AI_AVATAR, v).apply()

    /** 长运行模式（/v1/runs）开关：Service 读取以决定走普通流式还是 runs 订阅。 */
    var runMode: Boolean
        get() = prefs.getBoolean(KEY_RUN_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_RUN_MODE, v).apply()

    // ===== 草稿：按会话 id 缓存未发送的输入，退出再进不丢失 =====
    fun getDraft(conversationId: String): String =
        prefs.getString(KEY_DRAFT + conversationId, "") ?: ""

    fun setDraft(conversationId: String, text: String) =
        prefs.edit().putString(KEY_DRAFT + conversationId, text).apply()

    fun clearDraft(conversationId: String) =
        prefs.edit().remove(KEY_DRAFT + conversationId).apply()

    // 配置只需 API 地址 + Key；模型由 Hermes 默认提供，不再强制要求。
    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "hermes-agent"
        private const val PREFS_NAME = "hermes_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_USER_AVATAR = "user_avatar_index"
        private const val KEY_AI_AVATAR = "ai_avatar_index"
        private const val KEY_RUN_MODE = "run_mode"
        private const val KEY_DRAFT = "draft_"

        const val MODE_SYSTEM = "system"
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"
    }
}
