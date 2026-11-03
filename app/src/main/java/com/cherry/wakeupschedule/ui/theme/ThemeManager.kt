package com.cherry.wakeupschedule.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.cherry.wakeupschedule.R

object ThemeManager {

    private const val PREFS_NAME = "m3_theme"
    private const val KEY_PALETTE_INDEX = "palette_index"
    const val COURSE_COLOR_COUNT = 9

    /** 课程卡片颜色 (WakeUp Schedule) */
    val COURSE_COLORS: IntArray
        get() = _courseColors

    // ── WakeUp Schedule 9 色 ──
    private val FIXED_COURSE_COLORS = intArrayOf(
        0xFFFF1744.toInt(),  // red
        0xFFFA6278.toInt(),  // pink
        0xFF2979FF.toInt(),  // blue
        0xFF1DE9B6.toInt(),  // green
        0xFFA375FF.toInt(),  // purple
        0xFFFF9100.toInt(),  // orange
        0xFFFF3D00.toInt(),  // deepOrange
        0xFF2196F3.toInt(),  // lightBlue
        0xFF005CAF.toInt(),  // ruri
    )

    @Volatile
    private var _courseColors: IntArray = FIXED_COURSE_COLORS

    @Volatile
    private var currentLight: M3ColorPalette = M3ColorPalette.LIGHT_PALETTES[1]

    @Volatile
    private var currentDark: M3ColorPalette = M3ColorPalette.DARK_PALETTES[1]

    /** Map palette index to theme overlay style resource ID */
    private val OVERLAY_STYLES = intArrayOf(
        R.style.ThemeOverlay_WakeupSchedule_Palette0,
        R.style.ThemeOverlay_WakeupSchedule_Palette1,
        R.style.ThemeOverlay_WakeupSchedule_Palette2,
        R.style.ThemeOverlay_WakeupSchedule_Palette3,
        R.style.ThemeOverlay_WakeupSchedule_Palette4
    )

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val index = prefs.getInt(KEY_PALETTE_INDEX, 1).coerceIn(0, 4)
        setPaletteIndex(index)
    }

    /**
     * Apply the current palette overlay to the activity theme.
     * Must be called BEFORE setContentView().
     * The correct overlay (light/dark) is resolved automatically
     * via the values/values-night resource qualifier system.
     */
    fun applyToTheme(activity: Activity) {
        val index = getPaletteIndex(activity)
        val overlayResId = OVERLAY_STYLES[index.coerceIn(0, 4)]
        activity.theme.applyStyle(overlayResId, true)
    }

    /**
     * 把当前调色板 overlay 叠加到**弹窗自己的主题**上（构造完 Dialog、setContentView 之前调用）。
     *
     * 为什么弹窗需要单独补一次：居中弹窗以 [com.cherry.wakeupschedule.R.style.RoundedDialog] 为窗口主题，
     * Dialog 的 ContextThemeWrapper 会先复制 Activity 主题（其中已 applyStyle 了当前调色板 overlay），
     * 再叠加 RoundedDialog；而 RoundedDialog 继承了完整的 Theme.Material3.DayNight.Dialog，
     * 其父链重新声明了 M3 基线色板（colorSurfaceContainerHigh = #ECE6F0、colorPrimary = #6750A4 等），
     * 把调色板 overlay 覆盖掉。表现就是弹窗底色比页面偏粉/偏紫、按钮变回基线紫，
     * 与 5 套调色板 / 深浅色不联动。
     *
     * 在弹窗主题上再 apply 一次 overlay 后，弹窗布局里的 `?attr/colorXxx` 就重新跟随当前调色板；
     * 窗口行为（透明圆角底、点击外部关闭、软键盘 adjustResize 等）仍由 RoundedDialog 的 M3 Dialog
     * 父链提供，不受影响。
     *
     * 适用于所有 `Dialog(context, R.style.RoundedDialog)` 子类：
     * StyledDialog / SelectionDialog / SemesterWheelDialog / GradeDetailDialog。
     */
    fun applyPaletteOverlay(dialog: android.app.Dialog) {
        val index = getPaletteIndex(dialog.context).coerceIn(0, 4)
        dialog.context.theme.applyStyle(OVERLAY_STYLES[index], true)
    }

    fun getPaletteIndex(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PALETTE_INDEX, 1)
    }

    fun setPaletteIndex(index: Int, context: Context? = null) {
        val idx = index.coerceIn(0, 4)
        currentLight = M3ColorPalette.LIGHT_PALETTES[idx]
        currentDark = M3ColorPalette.DARK_PALETTES[idx]
        // 课程颜色固定，不受调色板切换影响
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_PALETTE_INDEX, idx)?.apply()
    }

    fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun currentPalette(context: Context): M3ColorPalette {
        return if (isDarkMode(context)) currentDark else currentLight
    }

    fun paletteNames(): List<String> =
        M3ColorPalette.LIGHT_PALETTES.map { it.name }

    fun getCourseColors(): IntArray = FIXED_COURSE_COLORS
}
