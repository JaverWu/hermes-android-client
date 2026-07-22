package com.hermes.chat.data.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条待发送 / 已发送附件。
 * - 选中文件后会被拷贝到 App 私有目录（[path]），以规避 content:// 权限在发送时失效。
 * - [dataUri] 在真正发往后端的那一刻才把文件读成 base64 data URI（图片→image_url，其它→file part）。
 */
data class Attachment(
    val name: String,
    val mime: String,
    val size: Long,
    val path: String
) {
    /** 读文件并编码为 `data:<mime>;base64,...`，供 OpenAI 多模态 content part 使用。 */
    fun dataUri(): String {
        val bytes = File(path).readBytes()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:$mime;base64,$b64"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("mime", mime)
        put("size", size)
        put("path", path)
    }

    companion object {
        fun fromJson(o: JSONObject): Attachment = Attachment(
            name = o.optString("name"),
            mime = o.optString("mime", "application/octet-stream"),
            size = o.optLong("size"),
            path = o.optString("path")
        )

        fun listFromJson(json: String): List<Attachment> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun listToJson(list: List<Attachment>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        /**
         * 把用户选中的 [uri] 拷贝到 App 私有目录，返回可持久化与重发的 [Attachment]。
         * 失败（无读取权限 / IO 异常）返回 null。
         */
        fun copyToInternal(context: Context, uri: Uri): Attachment? = runCatching {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: uri.lastPathSegment ?: "file"

            val mime = context.contentResolver.getType(uri)
                ?: "application/octet-stream"

            val dir = File(context.filesDir, "hermes_attachments").apply { mkdirs() }
            val safeName = name.replace(Regex("[^\\w.\\-]"), "_")
            val dest = File(dir, "${System.currentTimeMillis()}_$safeName")

            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: return null

            Attachment(
                name = name,
                mime = mime,
                size = dest.length(),
                path = dest.absolutePath
            )
        }.getOrNull()
    }
}
