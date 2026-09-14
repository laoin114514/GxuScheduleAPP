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
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.SemesterEntity
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.cherry.wakeupschedule.ui.widget.SemesterSceneryView
import kotlin.math.roundToInt

/**
 * 成绩页的学期选择器：**页内下拉展开**，不是滚轮。
 *
 * 视觉沿用「我的」里的学期选择（[com.cherry.wakeupschedule.ui.component.SemesterWheelDialog]）：
 * 左侧迷你色块（该学期已有成绩 → 风景块，否则素色块）+ 右侧「大二上  2024-2025学年 第一学期」，
 * 区别只是改用就地展开的列表、选中项用对勾标记。
 *
 * 选择结果只回调给调用方用于本页筛选，不写回全局当前学期。
 */
class SemesterExpandPicker(
    private val context: Context,
    private val field: View,
    private val blockContainer: FrameLayout,
    private val labelView: TextView,
    private val arrow: ImageView,
    private val optionsContainer: LinearLayout,
    private val onSelected: (SemesterEntity) -> Unit
) {

    private val density = context.resources.displayMetrics.density

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

    private var semesters: List<SemesterEntity> = emptyList()
    private var gradeCounts: Map<Long, Int> = emptyMap()
    private var selectedId: Long = 0L

    val isExpanded: Boolean
        get() = optionsContainer.visibility == View.VISIBLE

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
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    fun expand() {
        optionsContainer.visibility = View.VISIBLE
        arrow.animate().rotation(180f).setDuration(160).start()
    }

    fun collapse() {
        optionsContainer.visibility = View.GONE
        arrow.animate().rotation(0f).setDuration(160).start()
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

    // ── 展开列表 ──────────────────────────────────────────

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
