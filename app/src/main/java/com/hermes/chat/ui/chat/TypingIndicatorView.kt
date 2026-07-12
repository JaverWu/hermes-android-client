package com.hermes.chat.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.hermes.chat.R

/**
 * 三点跳动打字指示器，用于流式传输时显示 Hermes 正在输入。
 */
class TypingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val dotRadius = 6f.dpToPx()
    private val dotSpacing = 10f.dpToPx()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }

    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (dotRadius * 6 + dotSpacing * 2 + paddingStart + paddingEnd).toInt()
        val height = (dotRadius * 2 + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val startX = paddingStart + dotRadius
        for (i in 0 until 3) {
            val cx = startX + (dotRadius * 2 + dotSpacing) * i
            val offset = Math.sin((phase + i * 0.5f) * Math.PI).toFloat()
            val scale = 0.6f + 0.4f * (offset * 0.5f + 0.5f)
            val alpha = (120 + 135 * (offset * 0.5f + 0.5f)).toInt().coerceIn(0, 255)
            paint.alpha = alpha
            canvas.drawCircle(cx, cy, dotRadius * scale, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 2f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                phase = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun Float.dpToPx(): Float = this * resources.displayMetrics.density
}
