package com.cherry.wakeupschedule

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.databinding.ActivityAppearanceBinding
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.ThemeModeManager
import com.cherry.wakeupschedule.ui.theme.M3ColorPalette
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.cherry.wakeupschedule.ui.widget.GridBackgroundView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

/**
 * 外观设置页
 *
 * - 顶部并排两张浅色/深色主题预览卡（程序化迷你课表预览），点击即应用主题
 * - 自动切换开关：开启后显示「自动切换方式」入口，底部弹窗选择 跟随系统/自定时间
 * - 自定时间：分别设置深色/浅色切换时间，两时间冲突时自动计算规避（间隔≥60分钟）
 * - 卡片外观分区：课程卡片透明度 / 显示非本周课程 / 非本周课程透明度
 */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceBinding
    private lateinit var settingsManager: SettingsManager

    private var currentSheetDialog: Dialog? = null
    private var isUpdatingSwitchState = false

    /** 预览课表：周一~周日 每天 0~2 门课，颜色为真实课程卡片配色 */
    private val previewCourses: Array<Array<String?>> = arrayOf(
        arrayOf("高数", "英语"),   // 周一
        arrayOf("大物", "毛概"),   // 周二
        arrayOf("数构", "操作"),   // 周三
        arrayOf("马原", null),     // 周四
        arrayOf("计网", "体育"),   // 周五
        arrayOf("活动", null),     // 周六
        arrayOf(null, null)        // 周日
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        settingsManager = SettingsManager(this)
        setupPageHeader(binding.toolbar, "外观")
        setupClickListeners()
        buildThemePreviewCards()
        updateUi()
    }

    // ==================== 事件绑定 ====================

    private fun setupClickListeners() {
        // 浅色预览卡：手动选择浅色，并关闭自动切换
        binding.cardLight.root.setOnClickListener {
            settingsManager.setAutoSwitchTheme(false)
            settingsManager.setThemeMode("light")
            ThemeModeManager.apply(this)
            updateUi()
        }

        // 深色预览卡：手动选择深色，并关闭自动切换
        binding.cardDark.root.setOnClickListener {
            settingsManager.setAutoSwitchTheme(false)
            settingsManager.setThemeMode("dark")
            ThemeModeManager.apply(this)
            updateUi()
        }

        // 自动切换开关
        binding.switchAutoSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchState) return@setOnCheckedChangeListener
            settingsManager.setAutoSwitchTheme(isChecked)
            ThemeModeManager.apply(this)
            updateUi()
        }

        // 自动切换方式入口：底部弹窗
        binding.llAutoSwitchMode.setOnClickListener {
            showAutoSwitchSheet()
        }

        // 深色时间
        binding.rowDarkTime.setOnClickListener {
            showTimePicker(
                title = "深色时间",
                current = settingsManager.getDarkTime()
            ) { hour, minute ->
                val time = String.format("%02d:%02d", hour, minute)
                // 与浅色时间冲突时自动计算规避
                ThemeModeManager.setDarkTimeWithAvoidance(this, time)
                ThemeModeManager.apply(this)
                updateUi()
            }
        }

        // 浅色时间
        binding.rowLightTime.setOnClickListener {
            showTimePicker(
                title = "浅色时间",
                current = settingsManager.getLightTime()
            ) { hour, minute ->
                val time = String.format("%02d:%02d", hour, minute)
                // 与深色时间冲突时自动计算规避
                ThemeModeManager.setLightTimeWithAvoidance(this, time)
                ThemeModeManager.apply(this)
                updateUi()
            }
        }
    }

    // ==================== UI 刷新 ====================

    private fun updateUi() {
        // 自动切换开关
        isUpdatingSwitchState = true
        binding.switchAutoSwitch.isChecked = settingsManager.isAutoSwitchTheme()
        isUpdatingSwitchState = false

        val autoSwitchOn = settingsManager.isAutoSwitchTheme()
        val isCustom = settingsManager.getAutoSwitchMode() == "custom"

        // 自动切换方式入口显隐
        binding.llAutoSwitchMode.visibility = if (autoSwitchOn) View.VISIBLE else View.GONE
        binding.tvAutoSwitchValue.text = ThemeModeManager.effectiveLabel(this)

        // 自定时间选项显隐
        binding.llCustomTimes.visibility =
            if (autoSwitchOn && isCustom) View.VISIBLE else View.GONE
        binding.tvDarkTime.text = settingsManager.getDarkTime()
        binding.tvLightTime.text = settingsManager.getLightTime()
        binding.tvDarkPeriodHint.text = ThemeModeManager.darkPeriodLabel(this)

        // 预览卡选中态：仅手动选择 light/dark 且自动切换关闭时高亮
        val manualMode = settingsManager.getThemeMode()
        updateCardSelection(
            selected = !autoSwitchOn && manualMode == "light",
            card = binding.cardLight
        )
        updateCardSelection(
            selected = !autoSwitchOn && manualMode == "dark",
            card = binding.cardDark
        )
    }

    private fun updateCardSelection(selected: Boolean, card: com.cherry.wakeupschedule.databinding.ItemAppearancePreviewCardBinding) {
        val density = resources.displayMetrics.density
        card.root.strokeWidth = if (selected) (2 * density).toInt() else 1
        card.root.setStrokeColor(
            if (selected) resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
            else resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
        )
        card.ivCheck.visibility = if (selected) View.VISIBLE else View.GONE
    }

    /** 从当前主题解析颜色属性 */
    private fun resolveThemeColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(attrRes, tv, true)) tv.data else Color.TRANSPARENT
    }

    // ==================== 主题预览卡绘制 ====================

    private fun buildThemePreviewCards() {
        val paletteIndex = ThemeManager.getPaletteIndex(this)
        val lightPalette = M3ColorPalette.LIGHT_PALETTES[paletteIndex]
        val darkPalette = M3ColorPalette.DARK_PALETTES[paletteIndex]
        binding.cardLight.tvLabel.text = "浅色"
        binding.cardDark.tvLabel.text = "深色"
        buildPreview(binding.cardLight.previewContainer, lightPalette)
        buildPreview(binding.cardDark.previewContainer, darkPalette)
    }

    /**
     * 在容器内程序化绘制一张迷你课表预览。
     * 背景为 surface→surfaceContainer 渐变，叠加虚线网格、周几表头与课程色块。
     */
    private fun buildPreview(container: FrameLayout, palette: M3ColorPalette) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // 背景：surface → surfaceContainer 渐变，带圆角（与卡片圆角呼应，配合 clipToOutline 裁剪子 View）
        container.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.surface, palette.surfaceContainer)
        ).apply {
            cornerRadius = dp(10).toFloat()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(4))
        }

        // 顶部标题行
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "05月20日"
            textSize = 10f
            setTextColor(palette.onSurface)
            typeface = Typeface.DEFAULT_BOLD
        })
        headerRow.addView(TextView(this).apply {
            text = " 第12周"
            textSize = 8f
            setTextColor(palette.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        })
        root.addView(headerRow)

        // 网格区：7 列（周一~周日）
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { topMargin = dp(5) }
        }

        val weekLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val courseColors = ThemeManager.getCourseColors()
        val blockAlpha = if (palette.isDark) 191 else 128

        for (day in 0 until 7) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .apply { marginStart = if (day == 0) 0 else dp(2) }
            }

            // 周几表头
            column.addView(TextView(this).apply {
                text = weekLabels[day]
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(palette.onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(13)
                )
            })

            // 课程色块（最多 2 块，均分剩余高度）
            val dayCourses = previewCourses[day]
            val visibleCourses = dayCourses.filterNotNull()
            val divider = if (visibleCourses.size > 1) dp(2) else 0
            for (i in visibleCourses.indices) {
                val name = visibleCourses[i]
                val color = courseColors[(day * 2 + i) % courseColors.size]
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(color, blockAlpha)
                    )
                }
                column.addView(TextView(this).apply {
                    text = name
                    textSize = 8f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    background = bg
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ).apply {
                        topMargin = if (i == 0) 0 else divider
                    }
                })
            }

            // 无课的天：放置一个空块占位，保持与其他天一致
            if (visibleCourses.isEmpty()) {
                column.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
            }
            grid.addView(column)
        }
        root.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 虚线网格叠加层
        val gridView = GridBackgroundView(this).apply {
            rowCount = 2
            columnCount = 7
            gridColor = if (palette.isDark) 0x33FFFFFF else 0x22000000
        }
        container.addView(gridView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        container.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    // ==================== 自动切换方式底部弹窗 ====================

    private fun showAutoSwitchSheet() {
        currentSheetDialog?.dismiss()
        val dialog = Dialog(this, R.style.BottomSheetDialog)
        currentSheetDialog = dialog
        dialog.setOnDismissListener { currentSheetDialog = null }

        val sheetView = layoutInflater.inflate(R.layout.dialog_auto_switch_sheet, null)
        val density = resources.displayMetrics.density

        // 顶部圆角 + 跟随主题的表面色
        val topRadius = 20 * density
        val sheetBg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

        val isCustom = settingsManager.getAutoSwitchMode() == "custom"
        sheetView.findViewById<View>(R.id.iv_check_system).visibility =
            if (isCustom) View.INVISIBLE else View.VISIBLE
        sheetView.findViewById<View>(R.id.iv_check_custom).visibility =
            if (isCustom) View.VISIBLE else View.INVISIBLE

        // 跟随系统
        sheetView.findViewById<View>(R.id.row_system).setOnClickListener {
            settingsManager.setAutoSwitchMode("system")
            ThemeModeManager.apply(this)
            updateUi()
            dialog.dismiss()
        }
        // 自定时间
        sheetView.findViewById<View>(R.id.row_custom).setOnClickListener {
            settingsManager.setAutoSwitchMode("custom")
            ThemeModeManager.apply(this)
            updateUi()
            dialog.dismiss()
        }

        // 窗口配置：底部上滑 + 顶部点击空白关闭（复用底部弹窗样式）
        dialog.setContentView(sheetView)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        container.addView(View(this).apply {
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

    // ==================== 时间选择 ====================

    private fun showTimePicker(title: String, current: String, onPicked: (hour: Int, minute: Int) -> Unit) {
        var hour = 18
        var minute = 0
        val parts = current.split(":")
        if (parts.size == 2) {
            hour = parts[0].toIntOrNull() ?: 18
            minute = parts[1].toIntOrNull() ?: 0
        }

        val isSystem24Hour = DateFormat.is24HourFormat(this)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (isSystem24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .build()

        picker.addOnPositiveButtonClickListener {
            onPicked(picker.hour, picker.minute)
        }
        picker.show(supportFragmentManager, "time_picker_$title")
    }
}
