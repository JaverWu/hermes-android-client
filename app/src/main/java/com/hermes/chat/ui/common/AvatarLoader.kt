package com.hermes.chat.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.File

/**
 * 圆形头像加载工具。
 * - [loadCircular]：从 drawable 资源（如 logo）加载为圆形头像
 * - [loadCircularFromFile]：从内部存储图片文件加载为圆形头像（用户自定义上传）
 * - [copyPickedImageToInternal]：把用户从相册选取的图片复制到应用内部存储，返回路径
 */
object AvatarLoader {

    /** 从 drawable 资源加载为圆形头像（如 logo）。 */
    fun loadCircular(imageView: ImageView, drawableRes: Int) {
        val bmp = BitmapFactory.decodeResource(imageView.resources, drawableRes) ?: return
        setCircular(imageView, bmp)
    }

    /** 从内部文件加载为圆形头像；文件不存在或解码失败返回 false。 */
    fun loadCircularFromFile(imageView: ImageView, path: String): Boolean {
        val bmp = BitmapFactory.decodeFile(path) ?: return false
        setCircular(imageView, bmp)
        return true
    }

    /** 把圆形 Bitmap 设到 ImageView，并清除原本的纯色圆形背景。
     *  Bitmap 会缩放至 ImageView 当前像素尺寸（width × height），确保不同来源图片大小一致。 */
    private fun setCircular(imageView: ImageView, bmp: Bitmap) {
        val w = imageView.width
        val h = imageView.height
        val scaled = if (w > 0 && h > 0 && (bmp.width != w || bmp.height != h)) {
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else {
            bmp
        }
        val dr = RoundedBitmapDrawableFactory.create(imageView.resources, scaled).apply {
            isCircular = true
            // 缩放后如果生成了新 Bitmap，原图不再需要引用，允许回收
        }
        imageView.setImageDrawable(dr)
        imageView.background = null
        if (scaled !== bmp) bmp.recycle()
    }

    /** 将用户从相册选取的图片复制到应用内部存储，返回目标文件路径；失败返回 null。 */
    fun copyPickedImageToInternal(context: Context, uri: Uri): String? {
        return try {
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            val file = File(context.filesDir, "user_avatar.png")
            file.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
