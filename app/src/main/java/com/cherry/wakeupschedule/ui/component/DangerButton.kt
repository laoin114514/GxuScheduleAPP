package com.cherry.wakeupschedule.ui.component

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.cherry.wakeupschedule.R
import com.google.android.material.button.MaterialButton

/**
 * 危险操作按钮组件 — Material 3 Outlined 危险样式，与主按钮同高同排版。
 *
 * 尺寸与「保存」主按钮完全对齐（48dp 高、去上下 inset、文字水平垂直居中），
 * 仅将描边、文字、按压波纹替换为错误色（主题自动适配深浅），
 * 「透明描边红字」与「实心主按钮」主次分明，用红色系明确提示危险。
 *
 * 点击后自动弹出确认弹窗，确认才执行操作。
 *
 * 可复用：清除数据、解绑账号、删除记录等破坏性操作只需在 XML 使用本组件，
 * 配置弹窗文案，再设置 [setOnConfirmed] 回调即可。
 *
 * XML 用法：
 * ```xml
 * <com.cherry.wakeupschedule.ui.component.DangerButton
 *     android:id="@+id/btn_reset"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     android:text="重置默认"
 *     app:dangerTitle="重置时间表"
 *     app:dangerMessage="确定要恢复默认时间表吗？当前修改将丢失。"
 *     app:confirmText="重置" />
 * ```
 *
 * Kotlin 用法：
 * ```kotlin
 * btnReset.setOnConfirmed { doDangerousThing() }
 * ```
 */
class DangerButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialButton(context, attrs, defStyleAttr) {

    private var confirmTitle: String? = null
    private var confirmMessage: String? = null
    private var confirmText: String = "确定"
    private var confirmedListener: (() -> Unit)? = null

    /** 弹窗展示中标记，防止连点重复弹窗 */
    private var isDialogShowing = false

    init {
        parseAttrs(attrs)
        applyDangerStyle()
        setOnClickListener { showConfirmDialog() }
    }

    private fun parseAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        val a = context.obtainStyledAttributes(attrs, R.styleable.DangerButton)
        confirmTitle = a.getString(R.styleable.DangerButton_dangerTitle)
        confirmMessage = a.getString(R.styleable.DangerButton_dangerMessage)
        confirmText = a.getString(R.styleable.DangerButton_confirmText) ?: "确定"
        a.recycle()
    }

    /**
     * 应用描边红字样式：与「保存」主按钮同高（48dp）、去掉上下 inset 保证严格平行，
     * 文字水平垂直居中；描边、文字、按压波纹统一为危险色（[R.color.danger_color]，
     * 浅色经典红 / 深色饱和亮红，主题自动适配深浅）。
     */
    private fun applyDangerStyle() {
        val dangerColor = context.getColor(R.color.danger_color)
        val density = resources.displayMetrics.density

        // 与主按钮对齐的尺寸/排版
        minimumHeight = dp(48)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(24), 0, dp(24), 0)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        isAllCaps = false

        // 描边红字
        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        strokeColor = ColorStateList.valueOf(dangerColor)
        strokeWidth = (1.5f * density).toInt()
        setTextColor(dangerColor)
        cornerRadius = dp(14)

        // 红色半透明按压波纹，按压时保持危险语义
        rippleColor = ColorStateList.valueOf(dangerColor and 0x1F000000.toInt())
    }

    /**
     * 设置确认后的操作。
     * 点击按钮会先弹出确认弹窗，用户确认后才调用 [onConfirmed]。
     */
    fun setOnConfirmed(onConfirmed: () -> Unit) {
        confirmedListener = onConfirmed
    }

    private fun showConfirmDialog() {
        if (isDialogShowing) return
        isDialogShowing = true
        val dialog = StyledDialog.Builder(context)
            .title(confirmTitle ?: text.toString())
            .message(confirmMessage ?: "")
            .dangerButton(confirmText) {
                confirmedListener?.invoke()
            }
            .negativeButton("取消")
            .show()
        // 无论弹窗以何种方式关闭（确认/取消/点击外部），都解除锁定，避免下次点击失效
        dialog.setOnDismissListener { isDialogShowing = false }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
