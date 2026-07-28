package com.cherry.wakeupschedule.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.cherry.wakeupschedule.R

object ThemeManager {

    private const val PREFS_NAME = "m3_theme"
    private const val KEY_PALETTE_INDEX = "palette_index"
    const val COURSE_COLOR_COUNT = 10

    /** 课程卡片固定10色，不再从调色板采样 */
    val COURSE_COLORS: IntArray
        get() = _courseColors

    // ── 固定课程颜色集（白色文字） ──
    private val FIXED_COURSE_COLORS = intArrayOf(
        0xFF4A90E2.toInt(),  // 蓝色
        0xFF26C6DA.toInt(),  // 青色
        0xFF66BB6A.toInt(),  // 绿色
        0xFF9CCC65.toInt(),  // 黄绿
        0xFFFFD54F.toInt(),  // 黄色
        0xFFFFA726.toInt(),  // 橙色
        0xFFEF5350.toInt(),  // 红色
        0xFFEC407A.toInt(),  // 粉色
        0xFFAB47BC.toInt(),  // 紫色
        0xFF7E57C2.toInt(),  // 深紫
    )

    @Volatile
    private var _courseColors: IntArray = FIXED_COURSE_COLORS

    @Volatile
    private var currentLight: M3ColorPalette = M3ColorPalette.LIGHT_PALETTES[0]

    @Volatile
    private var currentDark: M3ColorPalette = M3ColorPalette.DARK_PALETTES[0]

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
        val index = prefs.getInt(KEY_PALETTE_INDEX, 0).coerceIn(0, 4)
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

    fun getPaletteIndex(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PALETTE_INDEX, 0)
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
