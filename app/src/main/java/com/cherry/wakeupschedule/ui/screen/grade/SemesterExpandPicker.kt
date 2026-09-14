package com.cherry.wakeupschedule.ui.screen.grade

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.SemesterEntity
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.cherry.wakeupschedule.ui.widget.SemesterSceneryView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 成绩页的学期选择器：点击字段后以**浮层下拉**展开（不是滚轮，也不在页内撑开把下方顶走）。
 *
 * 视觉沿用「我的」里的学期选择（[com.cherry.wakeupschedule.ui.component.SemesterWheelDialog]）：
 * 左侧迷你色块（该学期已有成绩 → 风景块，否则素色块）+ 右侧「大二上  2024-2025学年 第一学期」，
 * 选中项用对勾标记。选项挂在 PopupWindow 上覆盖在下方内容之上，浮层内可滚动。
 *
 * 选择结果只回调给调用方用于本页筛选，不写回全局当前学期。
 */
class SemesterExpandPicker(
    private val context: Context,
    private val field: View,
    private val blockContainer: FrameLayout,
    private val labelView: TextView,
    private val arrow: ImageView,
    private val onSelected: (SemesterEntity) -> Unit
) {

    private val density = context.resources.displayMetrics.density

    /** 浮层最高不超过屏幕一半多一点，超出部分在浮层内滚动 */
    private val maxPopupHeight =
        (context.resources.displayMetrics.heightPixels * 0.55f).roundToInt()

    private fun resolveAttr(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private val primaryColor = resolveAttr(com.google.android.material.R.attr.colorPrimary)
    private val primaryContainer = resolveAttr(com.google.android.material.R.attr.colorPrimaryContainer)
    private val secondaryContainer = resolveAttr(com.google.android.material.R.attr.colorSecondaryContainer)
    private val tertiaryContainer = resolveAttr(com.google.android.material.R.attr.colorTertiaryContainer)
    private val onSurfaceColor = resolveAttr(com.google.android.material.R.attr.colorOnSurface)
    private val blockColor = resolveAttr(com.google.android.material.R.attr.colorSurfaceVariant)
    private val sunColor = 0xFFFFD54F.toInt()

    private val optionsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private val optionsScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(optionsContainer)
    }

    private var popup: PopupWindow? = null
    private var semesters: List<SemesterEntity> = emptyList()
    private var gradeCounts: Map<Long, Int> = emptyMap()
    private var selectedId: Long = 0L

    val isExpanded: Boolean
        get() = popup?.isShowing == true

    init {
        field.setOnClickListener { toggle() }
    }

    /** 更新数据并重绘（展开状态保持不变） */
    fun submit(semesters: List<SemesterEntity>, gradeCounts: Map<Long, Int>, selectedId: Long) {
        this.semesters = semesters
        this.gradeCounts = gradeCounts
        this.selectedId = selectedId
        renderField()
        rebuildOptions()
        resizePopupIfShowing()
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    fun expand() {
        if (semesters.isEmpty() || field.width <= 0) return
        rebuildOptions()
        val popup = ensurePopup()
        popup.width = field.width
        popup.height = min(measureOptionsHeight(), maxPopupHeight)
        popup.showAsDropDown(field, 0, dp(4))
        animateArrow(expanded = true)
    }

    fun collapse() {
        popup?.takeIf { it.isShowing }?.dismiss()
    }

    // ── 浮层 ──────────────────────────────────────────────

    private fun ensurePopup(): PopupWindow {
        popup?.let { return it }
        return PopupWindow(
            optionsScroll,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(popupBackground())
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            setOnDismissListener { animateArrow(expanded = false) }
            popup = this
        }
    }

    /** 按字段宽度量一次内容高度（行高固定，量的是行数与内边距） */
    private fun measureOptionsHeight(): Int {
        if (field.width <= 0) return 0
        optionsContainer.measure(
            View.MeasureSpec.makeMeasureSpec(field.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return optionsContainer.measuredHeight
    }

    private fun resizePopupIfShowing() {
        val popup = popup ?: return
        if (!popup.isShowing) return
        popup.height = min(measureOptionsHeight(), maxPopupHeight)
    }

    private fun popupBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerLowest))
        setStroke(dp(1), resolveAttr(com.google.android.material.R.attr.colorOutlineVariant))
    }

    private fun animateArrow(expanded: Boolean) {
        arrow.animate().rotation(if (expanded) 180f else 0f).setDuration(160).start()
    }

    // ── 字段区 ────────────────────────────────────────────

    private fun renderField() {
        val selected = semesters.firstOrNull { it.id == selectedId }
        labelView.text = if (selected != null) {
            "${selected.label}  ${selected.academicYear}学年 ${selected.termName}"
        } else {
            "请选择学期"
        }
        setBlock(blockContainer, 36, selected != null && (gradeCounts[selected.id] ?: 0) > 0, false)
    }

    // ── 浮层列表 ──────────────────────────────────────────

    private fun rebuildOptions() {
        optionsContainer.removeAllViews()
        semesters.forEach { semester ->
            optionsContainer.addView(buildOptionRow(semester))
        }
    }

    private fun buildOptionRow(semester: SemesterEntity): View {
        val isSelected = semester.id == selectedId
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            )
            setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (isSelected) ColorUtils.setAlphaComponent(primaryColor, 28) else 0)
            }
            setOnClickListener {
                selectedId = semester.id
                renderField()
                rebuildOptions()
                collapse()
                onSelected(semester)
            }
        }

        val blockHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        setBlock(blockHost, 40, (gradeCounts[semester.id] ?: 0) > 0, isSelected)
        row.addView(blockHost)

        row.addView(TextView(context).apply {
            text = "${semester.label}  ${semester.academicYear}学年 ${semester.termName}"
            setTextSizeRes(R.dimen.text_body)
            setTextColor(onSurfaceColor)
            if (isSelected) setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        })

        if (isSelected) {
            row.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                setImageResource(R.drawable.ic_check)
                setColorFilter(primaryColor)
            })
        }
        return row
    }

    /** 左侧迷你色块：已有成绩 → 风景块（选中再加描边），否则素色块 */
    private fun setBlock(host: FrameLayout, sizeDp: Int, hasGrades: Boolean, selected: Boolean) {
        val size = dp(sizeDp)
        host.removeAllViews()
        if (hasGrades) {
            host.addView(SemesterSceneryView(context).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                setPalette(
                    skyTop = primaryContainer,
                    skyBottom = ColorUtils.blendARGB(primaryContainer, primaryColor, 0.35f),
                    hillBack = tertiaryContainer,
                    hillFront = secondaryContainer,
                    sunColor = sunColor
                )
                if (selected) setSelectionStroke(primaryColor, 2.5f * density)
            })
        } else {
            host.addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    cornerRadius = (size * 0.28f)
                    setColor(blockColor)
                    if (selected) setStroke((2.5f * density).roundToInt(), primaryColor)
                }
            })
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).roundToInt()
}
