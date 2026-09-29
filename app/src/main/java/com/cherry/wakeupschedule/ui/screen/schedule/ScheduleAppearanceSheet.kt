package com.cherry.wakeupschedule.ui.screen.schedule

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课表外观设置面板：悬浮在课表页下方的底部弹层，改动实时生效在面板上方的课表。
 * 原「我的 → 课表外观」独立页已删除，入口（课表菜单 + 我的页）都落到这里。
 *
 * - 格子高度：滑块粗调 + 数值胶囊精确输入，写入即通过 [ScheduleFragment.applyAppearanceChanges]
 *   实时刷进课表
 * - 格子内容：课程名恒显示，教室/教师各一个开关
 * - 面板高度可调：拖顶部把手在「贴合内容 ↔ 75% 屏高」间连续跟手，松手保持；
 *   轻点把手在「半屏 ↔ 贴合内容」间切换。拖拽只响应把手，与滑块/内容滚动互不干扰。
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

        val settingsManager = SettingsManager(context)

        /** 回填开关状态时抑制监听，避免把读出来的值再写回去 */
        var isUpdatingSwitchState = false

        // ── 格子高度：滑块粗调，松手落库并实时生效 ──
        val slider = sheetView.findViewById<Slider>(R.id.slider_cell_height)
        val btnValue = sheetView.findViewById<MaterialButton>(R.id.btn_cell_height_value)
        // 范围以代码里的常量为准（XML 里的值只是保证 inflate 时 value 在界内）
        slider.valueFrom = SettingsManager.COURSE_CELL_HEIGHT_MIN_DP.toFloat()
        slider.valueTo = SettingsManager.COURSE_CELL_HEIGHT_MAX_DP.toFloat()
        // 粗轨道会埋住刻度，关掉刻度（与原独立页一致）
        slider.isTickVisible = false
        slider.addOnChangeListener { _, value, _ ->
            btnValue.text = "${value.roundToInt()}dp"
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                settingsManager.setCourseCellHeight(slider.value.roundToInt())
                fragment.applyAppearanceChanges()
            }
        })
        btnValue.setOnClickListener {
            showCellHeightInput(context, fragment, settingsManager, slider, btnValue)
        }

        // ── 格子内容：教室/教师开关 ──
        val switchClassroom = sheetView.findViewById<Switch>(R.id.switch_show_classroom)
        val switchTeacher = sheetView.findViewById<Switch>(R.id.switch_show_teacher)
        switchClassroom.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchState) return@setOnCheckedChangeListener
            settingsManager.setShowClassroom(isChecked)
            fragment.applyAppearanceChanges()
        }
        switchTeacher.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchState) return@setOnCheckedChangeListener
            settingsManager.setShowTeacher(isChecked)
            fragment.applyAppearanceChanges()
        }

        // 回填当前设置（抑制开关监听，避免读出来又写一遍）
        val cellHeight = settingsManager.getCourseCellHeight()
        slider.value = cellHeight.toFloat()
        btnValue.text = "${cellHeight}dp"
        isUpdatingSwitchState = true
        switchClassroom.isChecked = settingsManager.isShowClassroom()
        switchTeacher.isChecked = settingsManager.isShowTeacher()
        isUpdatingSwitchState = false

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
        // 初始高度取半屏；内容比半屏还高时贴合内容
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels / 2).coerceAtLeast(contentHugHeight(sheetView, metrics))
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
     * 把手手势：上下拖连续调高（上拖增高、下拖收起，范围 [贴合内容, 75% 屏高]）；
     * 位移没超过 touchSlop 视为轻点，在「半屏 ↔ 贴合内容」间切换。
     * 拖拽只挂在把手上，滑块与内容滚动手势不受影响。
     */
    private fun setupHeightControl(context: Context, sheetView: View) {
        val handle = sheetView.findViewById<View>(R.id.handle_area)
        val metrics = context.resources.displayMetrics
        val maxH = (metrics.heightPixels * 0.75f).toInt()
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var dragStartRawY = 0f
        var dragStartHeight = 0
        var dragMoved = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = event.rawY
                    dragStartHeight = sheetView.layoutParams.height
                    dragMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (dragStartRawY - event.rawY).roundToInt()
                    if (abs(dy) > touchSlop) dragMoved = true
                    val target = (dragStartHeight + dy)
                        .coerceIn(contentHugHeight(sheetView, metrics), maxH)
                    setSheetHeight(sheetView, target)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragMoved) toggleSheetHeight(sheetView, metrics)
                    true
                }
                else -> false
            }
        }
    }

    /** 内容贴合高度：把手区 + 内容列的完整测量高度（含内边距），面板再矮就裁内容了 */
    private fun contentHugHeight(sheetView: View, metrics: DisplayMetrics): Int {
        val scroll = sheetView.findViewById<NestedScrollView>(R.id.scroll_appearance)
        val content = scroll.getChildAt(0)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val handle = sheetView.findViewById<View>(R.id.handle_area)
        return content.measuredHeight + handle.layoutParams.height
    }

    private fun setSheetHeight(sheetView: View, heightPx: Int) {
        if (sheetView.layoutParams.height == heightPx) return
        sheetView.layoutParams.height = heightPx
        sheetView.requestLayout()
    }

    /** 轻点把手：当前高度在「贴合↔半屏」区间中点以上则收到贴合，否则展开到半屏 */
    private fun toggleSheetHeight(sheetView: View, metrics: DisplayMetrics) {
        val hug = contentHugHeight(sheetView, metrics)
        val half = (metrics.heightPixels / 2).coerceAtLeast(hug)
        val current = sheetView.layoutParams.height
        val target = if (current > (hug + half) / 2) hug else half
        ValueAnimator.ofInt(current, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { setSheetHeight(sheetView, it.animatedValue as Int) }
            start()
        }
    }

    // ==================== 格子高度精确输入 ====================

    /** 数值输入弹窗：手动输入精确高度，越界自动夹取（与滑块共用同一设置） */
    private fun showCellHeightInput(
        context: Context,
        fragment: ScheduleFragment,
        settingsManager: SettingsManager,
        slider: Slider,
        btnValue: MaterialButton
    ) {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_cell_height_input, null)
        val etHeight = dialogView.findViewById<EditText>(R.id.et_cell_height)
        val tvRange = dialogView.findViewById<TextView>(R.id.tv_cell_height_range)

        // 越界不拦，应用时自动夹取到最近的边界，规则写在弹窗说明里
        tvRange.text = "范围 ${SettingsManager.COURSE_CELL_HEIGHT_MIN_DP}-" +
            "${SettingsManager.COURSE_CELL_HEIGHT_MAX_DP} dp"

        // 预填当前值并全选：直接输入即替换，不用先删
        etHeight.setText(settingsManager.getCourseCellHeight().toString())
        etHeight.setSelection(0, etHeight.text.length)

        val dialog = StyledDialog.Builder(context)
            .title("课程格子高度")
            .view(dialogView)
            .positiveButton("应用") {
                // 空输入视为不修改
                val typed = etHeight.text.toString().toIntOrNull()
                if (typed == null) {
                    AppToast.warn(context, "请输入高度")
                } else {
                    applyCellHeight(context, settingsManager, slider, btnValue, typed)
                    fragment.applyAppearanceChanges()
                }
            }
            .negativeButton("取消")
            .show()

        // 弹窗展示后拉起键盘并聚焦输入框，省掉一次点击
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        etHeight.requestFocus()
        etHeight.setOnEditorActionListener { _, _, _ ->
            // 键盘回车即应用
            etHeight.text.toString().toIntOrNull()?.let {
                applyCellHeight(context, settingsManager, slider, btnValue, it)
                fragment.applyAppearanceChanges()
            }
            dialog.dismiss()
            true
        }
    }

    /** 应用手动输入的高度：夹取到允许范围后落库，并让滑块跳到同一位置 */
    private fun applyCellHeight(
        context: Context,
        settingsManager: SettingsManager,
        slider: Slider,
        btnValue: MaterialButton,
        typedDp: Int
    ) {
        val applied = typedDp.coerceIn(
            SettingsManager.COURSE_CELL_HEIGHT_MIN_DP,
            SettingsManager.COURSE_CELL_HEIGHT_MAX_DP
        )
        settingsManager.setCourseCellHeight(applied)
        // 滑块位置变化会通过 onChange 同步数值文字，这里再显式设一次兜底
        slider.value = applied.toFloat()
        btnValue.text = "${applied}dp"
        if (applied != typedDp) AppToast.info(context, "已取范围边界 ${applied}dp")
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
