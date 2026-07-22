package com.hermes.chat.ui.chat

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.DialogFragment
import com.hermes.chat.R
import com.hermes.chat.databinding.DialogExpandedInputBinding

/**
 * 放大输入框（参考微信「对话框放大」）：5/6 屏幕底部对话框，进入时从底部以
 * 「缩放 + 上滑」动画展开，关闭/发送时反向缩放收起。
 * 输入法显隐遵循「放大前」状态：若小输入框当时键盘可见则放大后保持可见，
 * 否则放大后也不弹（避免无谓弹出）。顶部操作栏含关闭 × 与发送，中间大号
 * 多行 EditText，右下角字数统计。关闭时文本回写小输入框（草稿不丢）；发送
 * 时直接走 ChatActivity 既有发送逻辑。
 */
class ExpandedInputDialog : DialogFragment() {

    private var _binding: DialogExpandedInputBinding? = null
    private val binding get() = _binding!!
    private var exiting = false
    /** 是否保持键盘：放大前小输入框键盘可见则为 true，反之为 false */
    private var keepKeyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_HermesChat_ExpandedInput)
        isCancelable = true
        keepKeyboard = arguments?.getBoolean(KEY_KEEP_KB, false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogExpandedInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val text = savedInstanceState?.getString(KEY_TEXT)
            ?: arguments?.getString(KEY_TEXT).orEmpty()
        binding.editExpanded.setText(text)
        binding.editExpanded.setSelection(text.length)
        updateCount(text.length)

        binding.editExpanded.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCount(s?.length ?: 0)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.buttonClose.setOnClickListener {
            (activity as? ChatActivity)?.onExpandedClose(binding.editExpanded.text.toString())
            dismiss()
        }
        binding.textSend.setOnClickListener {
            (activity as? ChatActivity)?.onExpandedSend(binding.editExpanded.text.toString())
            dismiss()
        }
        // 点击遮罩关闭（带动画）
        binding.scrim.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        // 透明全屏窗口 + 5/6 底部卡片（高度由布局权重决定，随输入法自适应）
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0f) // 遮罩由布局内 scrim 控制，避免双重变暗
            // 键盘模式跟随「放大前」状态：可见则保持(VISIBLE)，不可见则保持隐藏(HIDDEN)
            setSoftInputMode(
                if (keepKeyboard)
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                            or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                else
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                            or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        binding.card.post {
            playEnter()
            if (keepKeyboard) {
                // 放大前键盘存在 → 放大后恢复键盘
                binding.editExpanded.requestFocus()
                val imm = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.editExpanded, InputMethodManager.SHOW_IMPLICIT)
            } else {
                // 放大前键盘不存在 → 放大后也不弹，清除焦点防止系统自动弹出
                binding.editExpanded.clearFocus()
            }
        }
    }

    /** 进入动画：遮罩渐显 + 卡片从底部缩放上滑 */
    private fun playEnter() {
        binding.scrim.alpha = 0f
        binding.card.apply {
            pivotX = width / 2f
            pivotY = height.toFloat() // 以底部为锚点上滑放大
            scaleX = 0.92f
            scaleY = 0.92f
            alpha = 0f
            translationY = 48f
        }
        binding.scrim.animate().alpha(0.5f).setDuration(300).start()
        binding.card.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(340)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 退出动画：反向缩放收起后真正 dismiss */
    private fun playExit(then: () -> Unit) {
        if (exiting) { then(); return }
        exiting = true
        binding.scrim.animate().alpha(0f).setDuration(220).start()
        binding.card.animate()
            .scaleX(0.92f).scaleY(0.92f).alpha(0f).translationY(48f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(then)
            .start()
    }

    override fun dismiss() {
        if (!isAdded || isStateSaved) {
            super.dismiss()
            return
        }
        playExit { super.dismiss() }
    }

    private fun updateCount(n: Int) {
        binding.textCount.text = n.toString()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TEXT, binding.editExpanded.text.toString())
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_TEXT = "expanded_text"
        private const val KEY_KEEP_KB = "keep_keyboard"
        fun newInstance(text: String, keepKeyboard: Boolean): ExpandedInputDialog {
            return ExpandedInputDialog().apply {
                arguments = Bundle().apply {
                    putString(KEY_TEXT, text)
                    putBoolean(KEY_KEEP_KB, keepKeyboard)
                }
            }
        }
    }
}
