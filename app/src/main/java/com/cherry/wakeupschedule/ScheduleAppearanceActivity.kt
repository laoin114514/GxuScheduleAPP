package com.cherry.wakeupschedule

import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.databinding.ActivityScheduleAppearanceBinding
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * 课表外观设置页
 *
 * - 课程格子高度：滑块粗调 + 点数值胶囊精确输入，两者共用同一设置、双向同步
 * - 取值范围 [SettingsManager.COURSE_CELL_HEIGHT_MIN_DP, COURSE_CELL_HEIGHT_MAX_DP] dp，
 *   默认值取自 dimens.xml 的 course_cell_height（68dp），不动设置时与旧版观感一致
 * - 高度实际生效在课表页 onResume（见 ScheduleFragment），因此写入后返回课表页即生效
 */
class ScheduleAppearanceActivity : BaseActivity() {

    private lateinit var binding: ActivityScheduleAppearanceBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        settingsManager = SettingsManager(this)
        setupPageHeader(binding.toolbar, "课表外观")
        setupCellHeightRow()
        updateUi()
    }

    // ==================== 课程格子高度 ====================

    /**
     * 滑块与可点击的数值胶囊共用同一设置。
     * 拖动过程中只同步数值文字，松手才写入；点数值弹输入框，应用后滑块同步跳转。
     */
    private fun setupCellHeightRow() {
        binding.sliderCellHeight.apply {
            // 范围以代码里的常量为准（XML 里的值只是保证 inflate 时 value 在界内）
            valueFrom = SettingsManager.COURSE_CELL_HEIGHT_MIN_DP.toFloat()
            valueTo = SettingsManager.COURSE_CELL_HEIGHT_MAX_DP.toFloat()
            // 胶囊轨道较粗会埋住刻度，关掉刻度（与课表页周数滑块一致）
            isTickVisible = false
        }

        binding.sliderCellHeight.addOnChangeListener { _, value, _ ->
            binding.btnCellHeightValue.text = "${value.roundToInt()}dp"
        }
        binding.sliderCellHeight.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                settingsManager.setCourseCellHeight(slider.value.roundToInt())
            }
        })

        binding.btnCellHeightValue.setOnClickListener {
            showCellHeightInput()
        }
    }

    /** 数值输入弹窗：手动输入精确高度，越界自动夹取（与滑块共用同一设置） */
    private fun showCellHeightInput() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cell_height_input, null)
        val etHeight = dialogView.findViewById<EditText>(R.id.et_cell_height)
        val tvRange = dialogView.findViewById<TextView>(R.id.tv_cell_height_range)

        // 越界不拦，应用时自动夹取到最近的边界，规则写在弹窗说明里
        tvRange.text = "范围 ${SettingsManager.COURSE_CELL_HEIGHT_MIN_DP}-" +
            "${SettingsManager.COURSE_CELL_HEIGHT_MAX_DP} dp"

        // 预填当前值并全选：直接输入即替换，不用先删
        etHeight.setText(settingsManager.getCourseCellHeight().toString())
        etHeight.setSelection(0, etHeight.text.length)

        val dialog = StyledDialog.Builder(this)
            .title("课程格子高度")
            .view(dialogView)
            .positiveButton("应用") {
                // 空输入视为不修改
                val typed = etHeight.text.toString().toIntOrNull()
                if (typed == null) AppToast.warn(this, "请输入高度") else applyCellHeight(typed)
            }
            .negativeButton("取消")
            .show()

        // 弹窗展示后拉起键盘并聚焦输入框，省掉一次点击
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        etHeight.requestFocus()
        etHeight.setOnEditorActionListener { _, _, _ ->
            // 键盘回车即应用
            etHeight.text.toString().toIntOrNull()?.let { applyCellHeight(it) }
            dialog.dismiss()
            true
        }
    }

    /**
     * 应用手动输入的高度：夹取到允许范围后落库，并让滑块跳到同一位置
     * （滑块位置变化会通过 onChange 同步数值文字）。
     */
    private fun applyCellHeight(typedDp: Int) {
        val applied = typedDp.coerceIn(
            SettingsManager.COURSE_CELL_HEIGHT_MIN_DP,
            SettingsManager.COURSE_CELL_HEIGHT_MAX_DP
        )
        settingsManager.setCourseCellHeight(applied)
        binding.sliderCellHeight.value = applied.toFloat()
        binding.btnCellHeightValue.text = "${applied}dp"
        if (applied != typedDp) AppToast.info(this, "已取范围边界 ${applied}dp")
    }

    // ==================== UI 刷新 ====================

    private fun updateUi() {
        // 课程格子高度：滑块位置与数值胶囊同步到当前设置
        val cellHeight = settingsManager.getCourseCellHeight()
        binding.sliderCellHeight.value = cellHeight.toFloat()
        binding.btnCellHeightValue.text = "${cellHeight}dp"
    }
}
