package com.hermes.chat.ui.conversations

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import android.widget.TextView

/**
 * 搜索关键词高亮 Span：
 * 1. 闪烁阶段（blinkOn 在 true/false 间切换）→ 关键词背景闪两下作为视觉提示
 * 2. 过渡阶段（blur 由 0→1）→ 背景从清晰实心圆角过渡到柔和扩散的模糊阴影晕开
 *
 * 通过外部 ValueAnimator 驱动 [blinkOn] / [blur] 并调用 textView.invalidate() 重绘。
 * 文本本身（蓝字 + 加粗）始终绘制，只有背景参与闪烁与模糊。
 */
class KeywordHighlightSpan(
    private val accent: Int,        // 关键词文字颜色
    private val pill: Int,          // 背景高亮色
    private val textView: TextView
) : ReplacementSpan() {

    /** 当前是否绘制背景（闪烁用） */
    var blinkOn: Boolean = true

    /** 模糊进度 0=清晰实心，1=完全柔和晕开 */
    var blur: Float = 0f

    private val density = textView.resources.displayMetrics.density
    private val padX = 4f * density
    private val padY = 3f * density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        // 预留左右内边距，避免文字贴边
        return (paint.measureText(text, start, end) + 2 * padX).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val word = text.subSequence(start, end).toString()
        val textW = paint.measureText(word)
        val baseline = y.toFloat()

        val pillTop = baseline + paint.ascent() - padY
        val pillBottom = baseline + paint.descent() + padY
        val radius = (pillBottom - pillTop) / 2f

        // 背景（参与闪烁 + 模糊）
        if (blinkOn) {
            bgPaint.color = pill
            // 越模糊越淡，呈现柔和阴影而非实心块
            bgPaint.alpha = (255 * (1f - 0.5f * blur)).toInt().coerceIn(0, 255)
            bgPaint.maskFilter = if (blur > 0.02f) {
                BlurMaskFilter(blur * 10f * density, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }
            canvas.drawRoundRect(
                x - padX, pillTop, x + textW + padX, pillBottom, radius, radius, bgPaint
            )
            bgPaint.maskFilter = null
        }

        // 文字（始终绘制：蓝字 + 加粗）
        textPaint.set(paint)
        textPaint.color = accent
        textPaint.isFakeBoldText = true
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(word, x, baseline, textPaint)
    }
}
