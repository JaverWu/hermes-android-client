package com.hermes.chat.ui.common

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 将选中的图片 URI 复制到应用私有目录 `filesDir/avatars/user_avatar.jpg`，
 * 返回绝对路径；失败返回 null。
 *
 * - 文件名固定 user_avatar.jpg，每次上传覆盖旧文件。
 * - 存储绝对路径而非 Content URI，避免重启后权限撤销导致头像失效。
 */
fun copyAvatarToInternal(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "avatars")
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, "user_avatar.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dest.absolutePath
    } catch (e: Exception) {
        null
    }
}
