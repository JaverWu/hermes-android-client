package com.hermes.chat.ui.common

import com.hermes.chat.R

/**
 * 头像预设集：每个预设 = 一个圆形底色 + 一个字形（emoji 或字母）。
 * 用户头像与 Hermes 头像各自有独立预设，索引持久化在 [com.hermes.chat.data.preferences.SettingsRepository]。
 */
object AvatarPresets {

    data class Preset(val colorRes: Int, val glyph: String)

    val USER: List<Preset> = listOf(
        Preset(R.color.avatar_1, "我"),
        Preset(R.color.avatar_2, "🦊"),
        Preset(R.color.avatar_3, "⚡"),
        Preset(R.color.avatar_4, "🌟"),
        Preset(R.color.avatar_5, "🐱"),
        Preset(R.color.avatar_6, "🍎"),
    )

    val AI: List<Preset> = listOf(
        Preset(R.color.avatar_1, "H"),
        Preset(R.color.avatar_5, "🤖"),
        Preset(R.color.avatar_3, "✨"),
        Preset(R.color.avatar_2, "🌿"),
        Preset(R.color.avatar_4, "💡"),
        Preset(R.color.avatar_6, "🔷"),
    )

    fun user(index: Int): Preset =
        USER.getOrElse(index.coerceIn(0, USER.lastIndex)) { USER[0] }

    fun ai(index: Int): Preset =
        AI.getOrElse(index.coerceIn(0, AI.lastIndex)) { AI[0] }
}
