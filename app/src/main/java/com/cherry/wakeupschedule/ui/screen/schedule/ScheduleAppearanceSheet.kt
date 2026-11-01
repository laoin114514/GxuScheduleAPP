package com.cherry.wakeupschedule.ui.screen.schedule

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import com.cherry.wakeupschedule.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课表外观的**半屏悬浮面板**：只有容器、把手与高度手势，控件本身与课表菜单里内联的那一份
 * 是同一组 id + 同一份装配代码（[ScheduleAppearanceControls]）。
 *
 * 现在课表菜单已经内联了全部外观控件（点开菜单即可调），这个面板只保留给
 * 「我的 → 课表外观」入口 —— 从「我的」页点进来时不跳去课表页也能直接调参。
 *
 * - 面板高度可调：拖顶部把手在「25% ↔ 75% 屏高」间连续跟手（越界有果冻阻尼），松手保持；
 *   轻点把手在「半屏 ↔ 25%」间切换。拖拽只响应把手，与滑块/内容滚动互不干扰；
 *   内容塞不下时在面板内的 NestedScrollView 上下滑动。
 */
object ScheduleAppearanceSheet {

    /** 「我的」页入口设置的待打开标志：跳到课表 tab 后由 onResume 消费（见 ProfileFragment） */
    var pendingAutoOpen = false

    /** 读取并清掉待打开标志 */
    fun consumePendingAutoOpen(): Boolean {
        val pending = pendingAutoOpen
        pendingAutoOpen = false
        return pending
    }

    private var currentDialog: Dialog? = null

    /** 越界拉伸的渐近上限（dp）：系统 stretch 同款特征——初始跟手，越拉阻力越大 */
    private const val OVERSCROLL_MAX_DP = 56

    /** 松手越界回落动画时长（ms）；轻点切换沿用原来的 200ms */
    private const val OVERSCROLL_SETTLE_MS = 300L

    /** 面板高度可拖范围（屏高占比）：下限 25%、上限 75%；内容塞不下时由内部滚动承担 */
    private const val MIN_HEIGHT_RATIO = 0.25f
    private const val MAX_HEIGHT_RATIO = 0.75f

    /** 打开时的屏占比（半屏），内容更高也由内部滚动承担 */
    private const val DEFAULT_HEIGHT_RATIO = 0.5f

    /** 打开面板。[fragment] 用于把设置改动实时刷进课表。 */
    fun show(fragment: ScheduleFragment) {
        val context = fragment.requireContext()
        // 防止连点打开多个（旧弹窗可能已随 Activity 销毁，安全关闭）
        dismissCurrent()
        val dialog = Dialog(context, R.style.BottomSheetDialog)
        currentDialog = dialog
        dialog.setOnDismissListener { currentDialog = null }

        val sheetView = LayoutInflater.from(context)
            .inflate(R.layout.sheet_schedule_appearance, null)
        val metrics = context.resources.displayMetrics
        val density = metrics.density

        // 顶部圆角 + colorSurfaceContainer：与菜单/课程详情弹层同一观感
        val topRadius = 24 * density
        val sheetBg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

        // 控件装配只有一份实现（菜单里内联的同一组控件也走它），这里只负责容器与高度手势
        ScheduleAppearanceControls.bind(sheetView, context, fragment)

        // ── 面板高度：把手拖动调节 ──
        setupHeightControl(context, sheetView)

        // ── 组装容器：上半屏空白点击关闭（照 SchedulePageDetailDialog 的做法） ──
        dialog.setContentView(sheetView)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        // 初始固定半屏；内容塞不下时由面板内的 NestedScrollView 上下滚动
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels * DEFAULT_HEIGHT_RATIO).toInt()
        )
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnClickListener { dialog.dismiss() }
        })
        container.addView(sheetView)
        dialog.setContentView(container)

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.BottomSheetAnimation)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setDimAmount(0.5f)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    // ==================== 面板高度调节 ====================

    /**
     * 把手手势：上下拖连续调高（上拖增高、下拖收起，范围 [25%, 75%] 屏高）；
     * 到边界不硬停，而是像系统 stretch overscroll（工具页同款手感）那样越界渐进阻尼
     * 地拉伸（初始跟手、越拉越难拉，渐近 [OVERSCROLL_MAX_DP]），松手平滑回落到边界。
     * 位移没超过 touchSlop 视为轻点，在「半屏 ↔ 25%」间切换。
     * 拖拽只挂在把手上，滑块与内容滚动手势不受影响。
     */
    private fun setupHeightControl(context: Context, sheetView: View) {
        val handle = sheetView.findViewById<View>(R.id.handle_area)
        val metrics = context.resources.displayMetrics
        val minH = (metrics.heightPixels * MIN_HEIGHT_RATIO).toInt()
        val maxH = (metrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        val maxOverPx = (OVERSCROLL_MAX_DP * metrics.density).toInt()
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var dragStartRawY = 0f
        var dragStartHeight = 0
        var dragMoved = false

        // 回弹 / 轻点切换共用的动画；再次抓手把时先取消，避免两套动画抢高度
        var heightAnimator: ValueAnimator? = null
        fun settleTo(target: Int, duration: Long = OVERSCROLL_SETTLE_MS) {
            heightAnimator?.cancel()
            heightAnimator = ValueAnimator.ofInt(sheetView.layoutParams.height, target).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener { setSheetHeight(sheetView, it.animatedValue as Int) }
                start()
            }
        }

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    heightAnimator?.cancel()
                    dragStartRawY = event.rawY
                    dragStartHeight = sheetView.layoutParams.height
                    dragMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (dragStartRawY - event.rawY).roundToInt()
                    if (abs(dy) > touchSlop) dragMoved = true
                    val raw = dragStartHeight + dy
                    // 越界走渐进阻尼：初始仍跟手，越拉越难拉，渐近封顶
                    val target = when {
                        raw > maxH -> maxH + overshoot(raw - maxH, maxOverPx)
                        raw < minH -> minH - overshoot(minH - raw, maxOverPx)
                        else -> raw
                    }
                    setSheetHeight(sheetView, target)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val current = sheetView.layoutParams.height
                    when {
                        current > maxH -> settleTo(maxH)
                        current < minH -> settleTo(minH)
                        !dragMoved -> {
                            // 轻点：当前高度在「25%↔半屏」区间中点以上则收到 25%，否则展开到半屏
                            val half = (metrics.heightPixels * DEFAULT_HEIGHT_RATIO).toInt()
                            settleTo(if (current > (minH + half) / 2) minH else half, duration = 200)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 渐进阻尼：越界量 over → 拉伸量。导数在 0 处为 1（边界处不跳变），渐近封顶 maxOverPx */
    private fun overshoot(over: Int, maxOverPx: Int): Int =
        (maxOverPx * (1f - 1f / (1f + over.toFloat() / maxOverPx))).roundToInt()

    private fun setSheetHeight(sheetView: View, heightPx: Int) {
        if (sheetView.layoutParams.height == heightPx) return
        sheetView.layoutParams.height = heightPx
        sheetView.requestLayout()
    }

    // ==================== 生命周期 ====================

    /**
     * 安全关闭当前弹窗。
     *
     * 承载弹窗的 Activity 销毁/重建后，旧弹窗的 DecorView 已脱离 WindowManager，
     * 直接 dismiss() 会抛 "not attached to window manager" 崩溃（本单例持有旧引用，
     * 属于 Activity 生命周期管理不到的对象）。这里先查 isShowing，再 try-catch 兜底。
     */
    private fun dismissCurrent() {
        val d = currentDialog ?: return
        if (!d.isShowing) {
            currentDialog = null
            return
        }
        try {
            d.dismiss()
        } catch (_: Exception) {
            // 窗口已解绑（Activity 已销毁/重建），忽略并清引用
            currentDialog = null
        }
    }
}
