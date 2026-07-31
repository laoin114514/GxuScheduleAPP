package com.cherry.wakeupschedule.ui.component

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.theme.ThemeManager

/**
 * 统一选择器弹窗组件 — 居中自定义对话框。
 *
 * 用法：
 * ```kotlin
 * SelectionDialog.show(
 *     context = this,
 *     title = "选择学期",
 *     options = listOf(SelectOption("2024-2025 第一学期"), ...),
 *     selectedIndex = currentIndex,
 *     onSelected = { index -> ... }
 * )
 * ```
 *
 * 特性：
 * - 暗色半透明卡片 + 20dp 圆角 + 微弱白色光晕边框
 * - 主题色装饰线 + 圆形 Radio 指示器
 * - 支持 leadingColor 色块预览（色板选择器）
 * - 支持 subtitle 辅助文字（学期 "当前" 标记）
 * - 列表超过 6 项自动可滚动
 * - 入场 OvershootInterpolator 弹性动画
 * - 确定按钮主题色填充圆角，取消按钮弱化文字
 */
class SelectionDialog private constructor(
    context: Context,
    private val title: String,
    private val options: List<SelectOption>,
    private val selectedIndex: Int,
    private val onSelected: ((Int) -> Unit)?,
    private val onCancel: (() -> Unit)?
) : Dialog(context, R.style.RoundedDialog) {

    private val primaryColor: Int by lazy {
        ThemeManager.currentPalette(context).primary
    }

    private fun onSurfaceColor(): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
        return tv.data
    }

    private fun onSurfaceVariantColor(): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true)
        return tv.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_selection)

        setupWindow()
        setupTitle()
        setupAccentLine()
        setupOptions()
        setupButtons()
        setupAnimations()
    }

    // ── Window ──────────────────────────────────────────

    private fun setupWindow() {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
            setLayout(
                minOf(width, dp2px(360)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ── Title & Accent ──────────────────────────────────

    private fun setupTitle() {
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        tvTitle.text = title
    }

    private fun setupAccentLine() {
        val vAccent = findViewById<View>(R.id.v_accent)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                primaryColor,
                primaryColor and 0x40FFFFFF.toInt(),
                primaryColor and 0x00FFFFFF.toInt()
            )
        )
        vAccent.background = gradient
    }

    // ── Option List ─────────────────────────────────────

    private fun setupOptions() {
        val llOptions = findViewById<LinearLayout>(R.id.ll_options)
        val maxVisibleItems = 6
        val itemHeight = dp2px(52)

        options.forEachIndexed { index, option ->
            val row = createOptionRow(option, index == selectedIndex)
            row.setOnClickListener {
                // 更新所有行的选中状态
                for (i in 0 until llOptions.childCount) {
                    updateOptionRowState(llOptions.getChildAt(i) as LinearLayout, i == index)
                }
                // 将选中项滚动到可见区域
                val sv = findViewById<ScrollView>(R.id.sv_options)
                val rowTop = index * itemHeight
                val rowBottom = rowTop + itemHeight
                val scrollY = sv.scrollY
                val visibleHeight = minOf(llOptions.childCount, maxVisibleItems) * itemHeight
                if (rowTop < scrollY) {
                    sv.smoothScrollTo(0, rowTop)
                } else if (rowBottom > scrollY + visibleHeight) {
                    sv.smoothScrollTo(0, rowBottom - visibleHeight)
                }
                pendingSelectedIndex = index
            }
            llOptions.addView(row)
        }

        // 选项超过 6 项时限制可见高度
        if (options.size > maxVisibleItems) {
            val sv = findViewById<ScrollView>(R.id.sv_options)
            sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply {
                height = maxVisibleItems * itemHeight
            }
        }
    }

    private var pendingSelectedIndex: Int = selectedIndex

    private fun createOptionRow(option: SelectOption, isSelected: Boolean): LinearLayout {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(52)
            )
            setPadding(dp2px(12), 0, dp2px(12), 0)
            background = createRowBackground(isSelected)
        }

        // 前置色块（用于色板选择器）
        if (option.leadingColor != null) {
            val colorDot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp2px(16), dp2px(16)).apply {
                    marginEnd = dp2px(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(option.leadingColor)
                    setStroke(dp2px(1.5f), Color.WHITE and 0x40FFFFFF.toInt())
                }
            }
            row.addView(colorDot)
        }

        // 前置图标（留作扩展）
        if (option.leadingIcon != null) {
            val icon = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp2px(20), dp2px(20)).apply {
                    marginEnd = dp2px(12)
                }
                setImageResource(option.leadingIcon)
            }
            row.addView(icon)
        }

        // 文字区域
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val tvLabel = TextView(ctx).apply {
            text = option.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(onSurfaceColor())
        }
        textCol.addView(tvLabel)

        if (option.subtitle != null) {
            val tvSub = TextView(ctx).apply {
                text = option.subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(onSurfaceVariantColor())
            }
            textCol.addView(tvSub)
        }

        row.addView(textCol)

        // Radio 圆
        val radio = createRadioView(isSelected)
        row.addView(radio)
        row.setTag(R.id.tag_radio_view, radio)
        row.setTag(R.id.tag_is_selected, isSelected)

        return row
    }

    private fun updateOptionRowState(row: LinearLayout, isSelected: Boolean) {
        row.background = createRowBackground(isSelected)
        val radio = row.getTag(R.id.tag_radio_view) as? View
        if (radio != null) {
            updateRadioView(radio, isSelected)
        }
        row.setTag(R.id.tag_is_selected, isSelected)
    }

    // ── Row Background ──────────────────────────────────

    private fun createRowBackground(isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(12).toFloat()
            if (isSelected) {
                setColor(primaryColor and 0x20FFFFFF.toInt())
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
    }

    // ── Radio ───────────────────────────────────────────

    private fun createRadioView(isSelected: Boolean): View {
        val size = dp2px(20)
        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp2px(8)
            }
            gravity = Gravity.CENTER
        }
        container.addView(buildRadioCircle(isSelected))
        return container
    }

    private fun updateRadioView(radio: View, isSelected: Boolean) {
        (radio as? LinearLayout)?.apply {
            removeAllViews()
            addView(buildRadioCircle(isSelected))
        }
    }

    private fun buildRadioCircle(isSelected: Boolean): View {
        val size = dp2px(20)
        val circle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = if (isSelected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(primaryColor)
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp2px(1.5f), onSurfaceVariantColor())
                }
            }
        }
        // 选中状态：实心主题色圆 + 白色对勾
        if (isSelected) {
            val frame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            frame.addView(circle)
            val check = TextView(context).apply {
                text = "✓"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(size, size)
            }
            frame.addView(check)
            return frame
        }
        return circle
    }

    // ── Buttons ─────────────────────────────────────────

    private fun setupButtons() {
        val btnCancel = findViewById<TextView>(R.id.btn_cancel)
        val btnConfirm = findViewById<TextView>(R.id.btn_confirm)

        // 确定按钮：主题色填充圆角 pill
        btnConfirm.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(24).toFloat()
            setColor(primaryColor)
        }

        btnCancel.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }

        btnConfirm.setOnClickListener {
            onSelected?.invoke(pendingSelectedIndex)
            dismiss()
        }
    }

    // ── Animations ──────────────────────────────────────

    private fun setupAnimations() {
        window?.decorView?.apply {
            scaleX = 0.9f
            scaleY = 0.9f
            alpha = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    override fun dismiss() {
        window?.decorView?.animate()
            ?.scaleX(0.95f)
            ?.scaleY(0.95f)
            ?.alpha(0f)
            ?.setDuration(100)
            ?.setInterpolator(AccelerateInterpolator())
            ?.withEndAction {
                try {
                    super.dismiss()
                } catch (_: IllegalArgumentException) {
                    // Activity already destroyed (e.g. via recreate()),
                    // dialog window already removed — safe to ignore.
                }
            }
            ?.start()
    }

    // ── Utility ─────────────────────────────────────────

    private fun dp2px(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private fun dp2px(dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            context.resources.displayMetrics
        ).toInt()

    // ── Companion (show API) ────────────────────────────

    companion object {
        /**
         * 显示选择器弹窗。
         *
         * @param context       Activity/Fragment context
         * @param title         弹窗标题
         * @param options       选项列表
         * @param selectedIndex 当前选中项索引（默认 0）
         * @param onSelected    选中回调（点确定后触发，返回索引）
         * @param onCancel      取消回调（点取消或点击遮罩外区域触发）
         */
        fun show(
            context: Context,
            title: String,
            options: List<SelectOption>,
            selectedIndex: Int = 0,
            onSelected: ((Int) -> Unit)? = null,
            onCancel: (() -> Unit)? = null
        ): SelectionDialog {
            val dialog = SelectionDialog(
                context = context,
                title = title,
                options = options,
                selectedIndex = selectedIndex,
                onSelected = onSelected,
                onCancel = onCancel
            )
            dialog.show()
            return dialog
        }
    }
}
