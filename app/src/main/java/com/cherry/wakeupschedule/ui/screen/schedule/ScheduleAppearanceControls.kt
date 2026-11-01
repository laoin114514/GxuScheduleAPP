package com.cherry.wakeupschedule.ui.screen.schedule

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课表外观控件的装配逻辑（**只有这一份**）。
 *
 * 同一组 id 被两处 layout 共用：
 * - `view_schedule_appearance_controls.xml`：内联在课表菜单里（点开菜单就能调，不用再进二级面板）
 * - `sheet_schedule_appearance.xml`：「我的 → 课表外观」打开的半屏悬浮面板
 *
 * 改动都实时生效在面板/菜单后面的课表上：写入 SettingsManager 后调
 * [ScheduleFragment.applyAppearanceChanges]。
 *
 * 控件语义：
 * - 格子高度：滑块粗调 + 数值胶囊精确输入（40–120dp，越界夹取）
 * - 格子内容：课程名恒显示，教室 / 教师各一个开关
 * - 边框：自定义色块（点开自绘取色器）+「跟随课程颜色」开关；跟随开启时色块置灰禁点
 * - 课表整体：底部留白、高亮当日
 */
object ScheduleAppearanceControls {

    /**
     * 滑块「可用行程」两端到内容边的距离（dp）= 默认值刻度与默认提示的定位基准。
     *
     * 和轨道可见端点不是一回事：Slider 会把轨道再往里收一点（约 15dp，见 layout 里
     * 40 / 120 标注的内边距）。刻度要落在滑块圆圈的实际行程上，所以这里按 24dp 算。
     */
    private const val TRACK_INSET_DP = 24f

    /** 40 / 120 / 默认值小字那一行的水平内边距（dp）：与轨道可见端点对齐（和 layout 里的 15dp 一致） */
    private const val HINT_ROW_INSET_DP = 15f

    /**
     * 把 [root] 里的外观控件接上设置。
     *
     * 两个 layout 的 id 必须保持一致，否则这里会 `findViewById` 拿到 null 直接崩——
     * 这是有意的：宁可启动就崩，也不要静默少挂一个开关。
     */
    fun bind(root: View, context: Context, fragment: ScheduleFragment) {
        val settingsManager = SettingsManager(context)
        val density = context.resources.displayMetrics.density

        /** 回填开关状态时抑制监听，避免把读出来的值再写回去 */
        var isUpdatingSwitchState = false

        val outlineValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOutline, outlineValue, true
        )

        // ── 格子高度：滑块粗调，松手落库并实时生效 ──
        val slider = root.findViewById<Slider>(R.id.slider_cell_height)
        val btnValue = root.findViewById<MaterialButton>(R.id.btn_cell_height_value)
        // 范围以代码里的常量为准（XML 里的值只是保证 inflate 时 value 在界内）
        slider.valueFrom = SettingsManager.COURSE_CELL_HEIGHT_MIN_DP.toFloat()
        slider.valueTo = SettingsManager.COURSE_CELL_HEIGHT_MAX_DP.toFloat()
        // 粗轨道会埋住刻度，关掉刻度（与原独立页一致）
        slider.isTickVisible = false

        // ── 默认值刻度：轨道上的凹槽标出默认高度位置，默认值小字在下方与凹槽同轴 ──
        val defaultDp = settingsManager.getDefaultCourseCellHeight()
        val marker = root.findViewById<View>(R.id.view_default_height_marker)
        val defaultHint = root.findViewById<TextView>(R.id.tv_default_height)
        val surfaceValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, surfaceValue, true
        )
        marker.background = GradientDrawable().apply {
            cornerRadius = density
            setColor(surfaceValue.data)
        }
        defaultHint.text = "默认 ${defaultDp}dp"

        // 提示文字和 40 / 120 同处一个带 15dp 内边距的 FrameLayout，
        // 而凹槽在无内边距的滑块容器里：两边的坐标系差这一截，必须减掉
        val hintRowInset = HINT_ROW_INSET_DP * density

        fun positionDefaultMark() {
            // 还没量出宽度就交给下面的布局回调，别在这里递归重排
            if (slider.width == 0 || defaultHint.width == 0) return
            val fraction = (defaultDp - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
            val center = TRACK_INSET_DP * density +
                fraction * (slider.width - 2 * TRACK_INSET_DP * density)
            marker.translationX = center - marker.width / 2f
            defaultHint.translationX = center - hintRowInset - defaultHint.width / 2f
        }

        /** 默认值小字只在「当前值 ≠ 默认值」时显形——两者相等时它只是重复胶囊里的数字 */
        fun updateDefaultAffordance(value: Float) {
            val distance = abs(value - defaultDp)
            defaultHint.alpha = if (distance <= 1f) 0f else 1f
            // 滑块距默认值 ≤1dp 时凹槽全透明、≥3dp 完全显形，之间线性渐变
            marker.alpha = ((distance - 1f) / 2f).coerceIn(0f, 1f)
        }

        // 布局完成 + 每次宽度变化（横竖屏、字号缩放）都要重算凹槽位置
        slider.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            slider.post { positionDefaultMark() }
        }
        defaultHint.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            slider.post { positionDefaultMark() }
        }
        slider.post { positionDefaultMark() }

        slider.addOnChangeListener { _, value, _ ->
            btnValue.text = "${value.roundToInt()}dp"
            updateDefaultAffordance(value)
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
        val switchClassroom = root.findViewById<Switch>(R.id.switch_show_classroom)
        val switchTeacher = root.findViewById<Switch>(R.id.switch_show_teacher)
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

        // ── 边框颜色：色块弹自绘取色器，跟随开关实时生效 ──
        val swatch = root.findViewById<View>(R.id.swatch_border_color)
        val switchFollow = root.findViewById<Switch>(R.id.switch_border_follow_course)

        /** 色块始终展示自定义色；跟随开关开启时置灰并禁点（描边已交给各课课程色） */
        fun refreshBorderSwatch() {
            val follow = settingsManager.isBorderFollowCourse()
            swatch.background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(settingsManager.getCellBorderColor())
                setStroke((1 * density).toInt(), outlineValue.data)
            }
            swatch.alpha = if (follow) 0.38f else 1f
            swatch.isEnabled = !follow
        }

        swatch.setOnClickListener {
            if (settingsManager.isBorderFollowCourse()) return@setOnClickListener
            CellBorderColorPicker.show(context, settingsManager.getCellBorderColor()) { color ->
                settingsManager.setCellBorderColor(color)
                fragment.applyAppearanceChanges()
                refreshBorderSwatch()
            }
        }
        switchFollow.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchState) return@setOnCheckedChangeListener
            settingsManager.setBorderFollowCourse(isChecked)
            fragment.applyAppearanceChanges()
            refreshBorderSwatch()
        }

        // ── 课表整体：底部留白 / 高亮当日 ──
        val switchBottomBlank = root.findViewById<Switch>(R.id.switch_bottom_blank)
        switchBottomBlank.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchState) return@setOnCheckedChangeListener
            settingsManager.setBottomBlank(isChecked)
            fragment.applyAppearanceChanges()
        }

        val switchHighlightToday = root.findViewById<Switch>(R.id.switch_highlight_today)
        switchHighlightToday.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchState) return@setOnCheckedChangeListener
            settingsManager.setHighlightToday(isChecked)
            fragment.applyAppearanceChanges()
        }

        // 回填当前设置（抑制开关监听，避免读出来又写一遍）
        val cellHeight = settingsManager.getCourseCellHeight()
        slider.value = cellHeight.toFloat()
        btnValue.text = "${cellHeight}dp"
        updateDefaultAffordance(cellHeight.toFloat())
        isUpdatingSwitchState = true
        switchClassroom.isChecked = settingsManager.isShowClassroom()
        switchTeacher.isChecked = settingsManager.isShowTeacher()
        switchFollow.isChecked = settingsManager.isBorderFollowCourse()
        switchBottomBlank.isChecked = settingsManager.isBottomBlank()
        switchHighlightToday.isChecked = settingsManager.isHighlightToday()
        isUpdatingSwitchState = false
        refreshBorderSwatch()
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
            .neutralButton("恢复默认") {
                // 一键回默认：落库并实时生效（弹窗随后自动关闭）
                applyCellHeight(
                    context, settingsManager, slider, btnValue,
                    settingsManager.getDefaultCourseCellHeight()
                )
                fragment.applyAppearanceChanges()
            }
            .show()

        // 「恢复默认」推到按钮行最左、其余按钮保持右侧（与取色器弹层同款布局）：
        // StyledDialog 按钮行是 [取消][中位][应用] 且 gravity=end，直接用会挤在取消旁边
        dialog.findViewById<View>(R.id.btn_neutral)?.let { btnNeutral ->
            (btnNeutral.parent as? LinearLayout)?.let { panel ->
                panel.removeView(btnNeutral)
                panel.addView(btnNeutral, 0)
                panel.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }, 1)
            }
        }

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
}
