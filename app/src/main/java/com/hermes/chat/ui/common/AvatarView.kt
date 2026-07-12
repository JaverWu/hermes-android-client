package com.hermes.chat.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.RoundedCornerTreatment
import com.google.android.material.shape.ShapeAppearanceModel
import com.hermes.chat.R
import java.io.File

/**
 * 可复用圆形头像组合控件。
 *
 * 内部组合 [ShapeableImageView]（圆形裁剪图片层）与 [TextView]（文字层），
 * 支持 TEXT / IMAGE / LOGO 三种模式，统一替换原有 FrameLayout + ImageView + TextView 三层结构。
 *
 * ## 核心修复
 * 通过 [ViewOutlineProvider] 设置圆形 outline 并启用 [clipToOutline]，
 * 彻底裁剪所有子 View 到圆形区域，解决文字/emoji 溢出圆形导致的重叠问题。
 *
 * ## 使用方式
 * - [bindText]：文字/emoji 模式，圆形底色 + 居中文字（预设头像 / 会话列表）
 * - [bindImage]：自定义图片模式，从绝对路径采样加载，返回是否成功
 * - [bindLogo]：固定 drawable 模式（如 R.drawable.logo，助手头像）
 *
 * @constructor 通过 XML 或代码创建
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 头像渲染模式 */
    private enum class Mode { TEXT, IMAGE, LOGO }

    /** 圆形裁剪的图片层（图片模式 / logo 模式 / 文字模式下的彩色底圆） */
    private val imageAvatar: ShapeableImageView

    /** 文字层（居中覆盖于图片层之上；图片/logo 模式下隐藏） */
    private val textAvatarLetter: TextView

    /** 当前渲染模式 */
    private var currentMode: Mode = Mode.TEXT

    init {
        // ── ShapeableImageView：圆形裁剪图片层 ──
        imageAvatar = ShapeableImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(RoundedCornerTreatment())
                .setAllCornerSizes(RelativeCornerSize(0.5f))
                .build()
            strokeWidth = 0f
        }
        addView(imageAvatar)

        // ── TextView：文字层（默认隐藏，仅在 bindText 模式下可见） ──
        textAvatarLetter = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            includeFontPadding = false
            text = ""
            visibility = GONE
        }
        addView(textAvatarLetter)

        // ── 解析自定义属性：avatarTextSize ──
        val typedArray = context.obtainStyledAttributes(
            attrs, R.styleable.AvatarView, defStyleAttr, 0
        )
        val defaultTextSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
        )
        val textSize = typedArray.getDimension(
            R.styleable.AvatarView_avatarTextSize, defaultTextSizePx
        )
        textAvatarLetter.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        typedArray.recycle()

        // ── 圆形裁剪：裁剪所有子 View 到圆形区域 ──
        // 这是修复文字/emoji 溢出圆形的核心：FrameLayout 的 clipToOutline + 圆形 outline
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }

    /**
     * 文字/emoji 模式：设置圆形底色 + 居中文字。
     *
     * @param glyph    显示的字形（字母或 emoji）
     * @param colorRes 圆形底色资源 ID（如 R.color.avatar_1）
     */
    fun bindText(glyph: String, colorRes: Int) {
        currentMode = Mode.TEXT
        val color = ContextCompat.getColor(context, colorRes)
        imageAvatar.setImageDrawable(null)
        imageAvatar.background = ContextCompat.getDrawable(context, R.drawable.bg_circle_blue)
        imageAvatar.backgroundTintList = ColorStateList.valueOf(color)
        textAvatarLetter.text = glyph
        textAvatarLetter.visibility = VISIBLE
    }

    /**
     * 自定义图片模式：从绝对路径加载（带 [BitmapFactory] 采样防 OOM）。
     *
     * @param absolutePath 图片文件的绝对路径
     * @return `true` 加载成功；`false` 加载失败（文件不存在或解码异常），
     *         调用方可回退到 [bindText]
     */
    fun bindImage(absolutePath: String): Boolean {
        val targetSize = if (width > 0 && height > 0) {
            maxOf(width, height)
        } else {
            (AVATAR_DEFAULT_DP * resources.displayMetrics.density).toInt()
        }
        val bitmap = loadSampledBitmap(absolutePath, targetSize)
        return if (bitmap != null) {
            currentMode = Mode.IMAGE
            imageAvatar.background = null
            imageAvatar.backgroundTintList = null
            imageAvatar.setImageBitmap(bitmap)
            // 彻底隐藏文字层：清空文本 + GONE，防止 clipToOutline 在部分 OEM ROM 上不可靠
            // 导致残留字形溢出到外部布局造成文字重叠
            textAvatarLetter.text = ""
            textAvatarLetter.visibility = GONE
            true
        } else {
            false
        }
    }

    /**
     * 固定 logo 模式：设置 drawable 资源（如 R.drawable.logo）。
     *
     * @param drawableRes drawable 资源 ID
     */
    fun bindLogo(drawableRes: Int) {
        currentMode = Mode.LOGO
        imageAvatar.background = null
        imageAvatar.backgroundTintList = null
        imageAvatar.setImageResource(drawableRes)
        // 彻底隐藏文字层：清空文本 + GONE（同 bindImage 防护逻辑）
        textAvatarLetter.text = ""
        textAvatarLetter.visibility = GONE
    }

    /**
     * 采样加载本地图片文件，防止 OOM。
     *
     * 先以 [BitmapFactory.Options.inJustDecodeBounds] 模式获取图片原始尺寸，
     * 计算 [BitmapFactory.Options.inSampleSize] 采样率，再以目标尺寸解码。
     *
     * @param path     图片文件绝对路径
     * @param reqWidth 期望的解码宽度（像素），同时用作高度（头像为正方形）
     * @return 解码后的 [Bitmap]，文件不存在或解码异常时返回 null
     */
    private fun loadSampledBitmap(path: String, reqWidth: Int): Bitmap? {
        if (!File(path).exists()) return null
        return try {
            // 第一遍：仅获取尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sampleSize = calculateInSampleSize(bounds, reqWidth, reqWidth)
            // 第二遍：按采样率解码
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算 BitmapFactory 采样率：取最接近且不小于目标尺寸的 2 的幂。
     *
     * @param options  包含 outWidth/outHeight 的 Options
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 采样率（≥ 1）
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    companion object {
        /** bindImage 时 view 尚未 layout 的回退目标尺寸（dp） */
        private const val AVATAR_DEFAULT_DP = 56
    }
}
