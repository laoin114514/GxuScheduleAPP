package com.cherry.wakeupschedule.ui.component

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.theme.ThemeManager

/**
 * 通用风格化弹窗组件 — 统一 App 内所有对话框的视觉风格。
 *
 * 支持三种模式：
 * 1. **确认框**：title + message + positive/negative/neutral 按钮
 * 2. **动作菜单**：title + 选项列表（点击立即执行）
 * 3. **自定义 View**：title + 自定义视图 + 按钮
 *
 * 用法：
 * ```kotlin
 * // 确认框
 * StyledDialog.Builder(context)
 *     .title("确认清除数据")
 *     .message("确定要清除所有课程数据吗？此操作不可恢复。")
 *     .positiveButton("确定清除") { ... }
 *     .negativeButton("取消")
 *     .show()
 *
 * // 动作菜单
 * StyledDialog.Builder(context)
 *     .title("选择反馈方式")
 *     .items(listOf("GitHub Issue", "发送邮件")) { index -> ... }
 *     .negativeButton("取消")
 *     .show()
 *
 * // 自定义 View
 * StyledDialog.Builder(context)
 *     .title("编辑时间段")
 *     .view(customView)
 *     .positiveButton("保存") { ... }
 *     .negativeButton("删除") { ... }
 *     .neutralButton("取消")
 *     .show()
 * ```
 */
class StyledDialog private constructor(
    context: Context,
    private val config: DialogConfig
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
        setContentView(R.layout.dialog_styled)

        val hasTitle = !config.title.isNullOrBlank()

        setupWindow()
        if (hasTitle) {
            setupTitle()
            setupAccentLine()
        } else {
            hideHeader()
        }
        setupMessage()
        setupItems()
        setupCustomView()
        if (config.hasAnyButton) {
            setupButtons()
        } else {
            hideButtons()
        }
        setupAnimations()
    }

    private fun hideHeader() {
        findViewById<View>(R.id.tv_title).visibility = View.GONE
        findViewById<View>(R.id.v_accent).visibility = View.GONE
        // 减小顶部间距
        (findViewById<View>(R.id.tv_title).layoutParams as? LinearLayout.LayoutParams)?.apply {
            topMargin = dp2px(8)
        }
    }

    private fun hideButtons() {
        findViewById<View>(R.id.v_divider).visibility = View.GONE
        findViewById<View>(R.id.ll_buttons).visibility = View.GONE
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
        tvTitle.text = config.title
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

    // ── Message ─────────────────────────────────────────

    private fun setupMessage() {
        val tvMessage = findViewById<TextView>(R.id.tv_message)
        if (config.message != null) {
            tvMessage.text = config.message
            tvMessage.visibility = View.VISIBLE
        }
    }

    // ── Items (action menu) ─────────────────────────────

    private fun setupItems() {
        if (config.items.isNullOrEmpty()) return

        val svItems = findViewById<ScrollView>(R.id.sv_items)
        val llItems = findViewById<LinearLayout>(R.id.ll_items)
        svItems.visibility = View.VISIBLE

        val maxVisible = 6
        val itemHeight = dp2px(48)

        config.items.forEachIndexed { index, label ->
            val row = createItemRow(label)
            row.setOnClickListener {
                config.onItemClick?.invoke(index)
                dismiss()
            }
            llItems.addView(row)
        }

        if (config.items.size > maxVisible) {
            svItems.layoutParams = (svItems.layoutParams as LinearLayout.LayoutParams).apply {
                height = maxVisible * itemHeight
            }
        }
    }

    private fun createItemRow(label: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(48)
            )
            setPadding(dp2px(16), 0, dp2px(16), 0)

            val tv = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onSurfaceColor())
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            addView(tv)

            // 右箭头提示可点击
            val arrow = TextView(context).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(onSurfaceVariantColor())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp2px(8) }
            }
            addView(arrow)
        }
    }

    // ── Custom View ─────────────────────────────────────

    private fun setupCustomView() {
        if (config.customView == null) return

        val flCustom = findViewById<FrameLayout>(R.id.fl_custom)
        flCustom.visibility = View.VISIBLE
        flCustom.addView(config.customView)
    }

    // ── Buttons ─────────────────────────────────────────

    private fun setupButtons() {
        val btnNegative = findViewById<TextView>(R.id.btn_negative)
        val btnNeutral = findViewById<TextView>(R.id.btn_neutral)
        val btnPositive = findViewById<TextView>(R.id.btn_positive)

        // Positive → 主题色填充圆角 pill
        if (config.positiveText != null) {
            btnPositive.text = config.positiveText
            btnPositive.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(24).toFloat()
                setColor(primaryColor)
            }
            btnPositive.visibility = View.VISIBLE
            btnPositive.setOnClickListener {
                config.positiveAction?.invoke()
                dismiss()
            }
        }

        // Negative → 弱化文字按钮
        if (config.negativeText != null) {
            btnNegative.text = config.negativeText
            btnNegative.visibility = View.VISIBLE
            btnNegative.setOnClickListener {
                config.negativeAction?.invoke()
                dismiss()
            }
        }

        // Neutral → 弱化文字按钮
        if (config.neutralText != null) {
            btnNeutral.text = config.neutralText
            btnNeutral.visibility = View.VISIBLE
            btnNeutral.setOnClickListener {
                config.neutralAction?.invoke()
                dismiss()
            }
        }

        // 如果三个按钮都不可见，隐藏分割线
        if (config.positiveText == null && config.negativeText == null && config.neutralText == null) {
            findViewById<View>(R.id.v_divider).visibility = View.GONE
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

    // ── Builder ─────────────────────────────────────────

    class Builder(private val context: Context) {
        private var title: String? = null
        private var message: String? = null
        private var items: List<String>? = null
        private var onItemClick: ((Int) -> Unit)? = null
        private var customView: View? = null
        private var positiveText: String? = null
        private var positiveAction: (() -> Unit)? = null
        private var negativeText: String? = null
        private var negativeAction: (() -> Unit)? = null
        private var neutralText: String? = null
        private var neutralAction: (() -> Unit)? = null

        fun title(text: String) = apply { title = text }
        fun message(text: String) = apply { message = text }
        fun items(list: List<String>, onClick: (Int) -> Unit) = apply {
            items = list
            onItemClick = onClick
        }
        fun items(array: Array<String>, onClick: (Int) -> Unit) = apply {
            items = array.toList()
            onItemClick = onClick
        }
        fun view(v: View) = apply { customView = v }
        fun positiveButton(text: String, action: (() -> Unit)? = null) = apply {
            positiveText = text
            positiveAction = action
        }
        fun negativeButton(text: String, action: (() -> Unit)? = null) = apply {
            negativeText = text
            negativeAction = action
        }
        fun neutralButton(text: String, action: (() -> Unit)? = null) = apply {
            neutralText = text
            neutralAction = action
        }

        fun show(): StyledDialog {
            val config = DialogConfig(
                title = title,
                message = message,
                items = items,
                onItemClick = onItemClick,
                customView = customView,
                positiveText = positiveText,
                positiveAction = positiveAction,
                negativeText = negativeText,
                negativeAction = negativeAction,
                neutralText = neutralText,
                neutralAction = neutralAction,
                hasAnyButton = positiveText != null || negativeText != null || neutralText != null
            )
            val dialog = StyledDialog(context, config)
            dialog.show()
            return dialog
        }
    }
}

/**
 * 弹窗配置（内部使用）
 */
internal data class DialogConfig(
    val title: String?,
    val message: String?,
    val items: List<String>?,
    val onItemClick: ((Int) -> Unit)?,
    val customView: View?,
    val positiveText: String?,
    val positiveAction: (() -> Unit)?,
    val negativeText: String?,
    val negativeAction: (() -> Unit)?,
    val neutralText: String?,
    val neutralAction: (() -> Unit)?,
    val hasAnyButton: Boolean
)
