package com.hermes.chat.ui.common

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hermes.chat.R

/**
 * 通用头像选择器：以网格形式展示预设头像，点击即选中并回调索引。
 *
 * @param context   上下文
 * @param presets   预设列表
 * @param currentIndex 当前选中的索引
 * @param onPick    选中预设后的回调（索引）
 * @param onUpload  点击「从相册上传」的回调，为 null 时不显示上传按钮
 */
fun showAvatarPicker(
    context: Context,
    presets: List<AvatarPresets.Preset>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onUpload: (() -> Unit)? = null
) {
    val ctx = context
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
    }

    val perRow = 3
    val rows = kotlin.math.ceil(presets.size.toFloat() / perRow).toInt()
    var dialog: AlertDialog? = null
    for (r in 0 until rows) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (c in 0 until perRow) {
            val idx = r * perRow + c
            if (idx >= presets.size) break
            val preset = presets[idx]
            val cell = TextView(ctx).apply {
                text = preset.glyph
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                val size = dp(ctx, 56)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
                }
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_circle_blue)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, preset.colorRes)
                )
                val selected = idx == currentIndex
                scaleX = if (selected) 1.12f else 1f
                scaleY = if (selected) 1.12f else 1f
                setOnClickListener {
                    onPick(idx)
                    dialog?.dismiss()
                }
            }
            row.addView(cell)
        }
        container.addView(row)
    }

    val scroll = ScrollView(ctx).apply { addView(container) }

    // 底部「从相册上传」按钮
    if (onUpload != null) {
        val divider = View(ctx).apply {
            setBackgroundColor(0xFFE5E5EA.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = dp(ctx, 8) }
        }
        container.addView(divider)

        val uploadBtn = TextView(ctx).apply {
            text = context.getString(R.string.avatar_upload)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.tg_blue))
            // 解析主题属性 ?android:attr/selectableItemBackground 作为点击反馈
            val outValue = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true
            )
            background = ContextCompat.getDrawable(ctx, outValue.resourceId)
            val h = dp(ctx, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            ).apply { topMargin = dp(ctx, 4) }
            setOnClickListener {
                onUpload()
                dialog?.dismiss()
            }
        }
        container.addView(uploadBtn)
    }

    dialog = AlertDialog.Builder(ctx)
        .setTitle(R.string.avatar_picker_title)
        .setView(scroll)
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** 在对话界面中先选择要修改「我的头像」还是「Hermes 头像」，再弹出对应选择器。 */
fun showAvatarRoleChooser(
    context: Context,
    onChoose: (AvatarRole) -> Unit
) {
    val items = arrayOf(
        context.getString(R.string.avatar_user),
        context.getString(R.string.avatar_ai)
    )
    AlertDialog.Builder(context)
        .setTitle(R.string.avatar_picker_title)
        .setItems(items) { _, which ->
            onChoose(if (which == 0) AvatarRole.USER else AvatarRole.AI)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

enum class AvatarRole { USER, AI }

private fun dp(context: Context, v: Int): Int =
    (v * context.resources.displayMetrics.density).toInt()
