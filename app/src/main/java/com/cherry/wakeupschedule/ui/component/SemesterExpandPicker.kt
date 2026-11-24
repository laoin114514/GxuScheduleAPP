package com.cherry.wakeupschedule.ui.component

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
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.cherry.wakeupschedule.ui.widget.SemesterSceneryView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 学期选择器：点击字段后以**浮层下拉**展开（不是滚轮，也不在页内撑开把下方顶走）。
 * 成绩查询页与考试安排页共用。
 *
 * 视觉沿用「我的」里的学期选择（[com.cherry.wakeupschedule.ui.component.SemesterWheelDialog]）：
 * 左侧迷你色块（该学期已有缓存数据 → 风景块，否则素色块）+ 右侧「大二上  2024-2025学年 第一学期」，
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
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(optionsContainer)
    }

    /** 浮层内容根：展开/收起的高度动画作用在它上面，PopupWindow 窗口高度由 [ensurePopup] 逐帧同步 */
    private val optionsWrapper = FrameLayout(context)

    private var popup: PopupWindow? = null
    private var semesters: List<SemesterEntity> = emptyList()
    private var cachedCounts: Map<Long, Int> = emptyMap()
    private var selectedId: Long = 0L

    /** 高度动画进行中（OnLayoutChange 里据此同步 PopupWindow 窗口高度） */
    private var heightAnimating = false

    /** 高度动画的令牌：连续展开/收起时，被取消的旧动画不能触发收尾逻辑 */
    private var heightAnimToken = 0

    /** 用户期望的展开态（收起动画途中又被展开时，旧动画的 dismiss 必须作废） */
    private var expandedTarget = false

    val isExpanded: Boolean
        get() = popup?.isShowing == true

    init {
        optionsWrapper.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        optionsWrapper.addView(optionsScroll)
        // PopupWindow 不会跟随内容高度自动改窗口高度，动画期间逐帧同步
        optionsWrapper.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (heightAnimating && height != oldBottom - oldTop) {
                popup?.takeIf { it.isShowing }?.update(field.width, height)
            }
        }
        field.setOnClickListener { toggle() }
    }

    /** 更新数据并重绘（展开状态保持不变）。[cachedCounts] 为各学期已有的缓存条数（成绩 / 考试条数） */
    fun submit(semesters: List<SemesterEntity>, cachedCounts: Map<Long, Int>, selectedId: Long) {
        this.semesters = semesters
        this.cachedCounts = cachedCounts
        this.selectedId = selectedId
        renderField()
        rebuildOptions()
        resizePopupIfShowing()
    }

    fun toggle() {
        // 用期望态判断而不是 popup.isShowing：收起动画途中再点一下能立即重新展开
        if (expandedTarget) collapse() else expand()
    }

    fun expand() {
        if (semesters.isEmpty() || field.width <= 0) return
        expandedTarget = true
        rebuildOptions()
        val popup = ensurePopup()
        popup.width = field.width
        val targetHeight = min(measureOptionsHeight(), maxPopupHeight)
        // 未展开时从 0 高度长出；已在收起途中又被展开则从当前高度接着长
        val fromHeight = if (popup.isShowing) optionsWrapper.height else 0
        if (!popup.isShowing) {
            setWrapperHeight(0)
            popup.height = 0
            popup.showAsDropDown(field, 0, dp(4))
        }
        fadeInRows()
        animateArrow(expanded = true)
        animateHeight(fromHeight, targetHeight, Motion.MEDIUM) {
            if (!expandedTarget) return@animateHeight
            // 高度动画结束必须恢复 WRAP_CONTENT，不能把内容高度永久钉死成像素值
            setWrapperHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            // 窗口高度落到最终值（PopupWindow 只能用像素值维护已显示窗口的尺寸）
            popup.update(field.width, targetHeight)
        }
    }

    fun collapse() {
        if (!expandedTarget) return
        expandedTarget = false
        animateArrow(expanded = false)
        val popup = popup ?: return
        if (!popup.isShowing) {
            setWrapperHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }
        val fromHeight = optionsWrapper.height
        animateHeight(fromHeight, 0, Motion.SHORT) {
            if (expandedTarget) return@animateHeight
            popup.dismiss()
        }
    }

    /**
     * 高度展开/收起：统一走 [Motion.resizeHeight]，动画期间同步 PopupWindow 窗口高度。
     * 连续点击时用令牌作废旧动画的收尾回调，避免"收起动画还没完就又把浮层关掉"。
     */
    private fun animateHeight(
        from: Int,
        to: Int,
        duration: Long,
        onEnd: (() -> Unit)? = null
    ) {
        val token = ++heightAnimToken
        heightAnimating = true
        Motion.resizeHeight(optionsWrapper, from, to, duration) {
            if (token != heightAnimToken) return@resizeHeight
            heightAnimating = false
            onEnd?.invoke()
        }
    }

    /** 内容行错峰上浮淡入（行数多时 Motion.stagger 内部封顶 10 项） */
    private fun fadeInRows() {
        if (!Motion.enabled()) return
        val rows = (0 until optionsContainer.childCount)
            .mapNotNull { optionsContainer.getChildAt(it) }
        Motion.stagger(rows, fromY = 6f)
    }

    private fun setWrapperHeight(height: Int) {
        val lp = optionsWrapper.layoutParams ?: return
        if (lp.height == height) return
        lp.height = height
        optionsWrapper.layoutParams = lp
    }

    // ── 浮层 ──────────────────────────────────────────────

    private fun ensurePopup(): PopupWindow {
        popup?.let { return it }
        return PopupWindow(
            optionsWrapper,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(popupBackground())
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            setOnDismissListener {
                animateArrow(expanded = false)
                // 外部点击直接关闭时不会有收起动画：这里把状态复位，下次展开仍从 0 长出
                expandedTarget = false
                heightAnimToken++
                heightAnimating = false
                setWrapperHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            }
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
        // 展开/收起动画进行中由动画自己维护高度，别在这里插一脚造成抖动
        if (!popup.isShowing || heightAnimating) return
        // 数据刷新（如缓存条数变化）时直接落到新高度，不做展开动画
        val target = min(measureOptionsHeight(), maxPopupHeight)
        setWrapperHeight(target)
        popup.update(field.width, target)
        optionsWrapper.post {
            if (popup.isShowing && !heightAnimating) {
                setWrapperHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun popupBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerLowest))
        setStroke(dp(1), resolveAttr(com.google.android.material.R.attr.colorOutlineVariant))
    }

    /** 箭头旋转：与展开/收起统一用 FAST 时长 + 标准曲线，方向仍是展开 180° / 收起 0° */
    private fun animateArrow(expanded: Boolean) {
        val targetRotation = if (expanded) 180f else 0f
        if (!Motion.enabled()) {
            arrow.rotation = targetRotation
            return
        }
        arrow.animate()
            .rotation(targetRotation)
            .setDuration(Motion.FAST)
            .setInterpolator(Motion.standard)
            .start()
    }

    // ── 字段区 ────────────────────────────────────────────

    private fun renderField() {
        val selected = semesters.firstOrNull { it.id == selectedId }
        labelView.text = if (selected != null) {
            "${selected.label}  ${selected.academicYear}学年 ${selected.termName}"
        } else {
            "请选择学期"
        }
        setBlock(blockContainer, 36, selected != null && (cachedCounts[selected.id] ?: 0) > 0, false)
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
        setBlock(blockHost, 40, (cachedCounts[semester.id] ?: 0) > 0, isSelected)
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

    /** 左侧迷你色块：已有缓存数据 → 风景块（选中再加描边），否则素色块 */
    private fun setBlock(host: FrameLayout, sizeDp: Int, hasCache: Boolean, selected: Boolean) {
        val size = dp(sizeDp)
        host.removeAllViews()
        if (hasCache) {
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
