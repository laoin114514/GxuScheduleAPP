package com.cherry.wakeupschedule.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import kotlin.math.roundToInt

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

    // ──────────────────────────────────────────────────────────────────────
    // 课程卡片配色：成对的「容器色 / 内容色」
    //
    // 旧做法是「课程色 50% 叠在页面渐变上 + 纯白字」，浅色下实测对比度只有
    // 1.48–2.77:1（AA 要求 4.5），全 9 色都不达标。现在改成按底色派生字色：
    //   浅色：容器 = 18% 课程色 + 82% 白（奶油底），内容 = 课程色 × 0.50（同色系深字）
    //   深色：容器 = 课程色 × 0.45（压暗保留色相），内容 = 白
    // 9 色逐一实算：浅色 5.16–10.35:1，深色 6.65–14.68:1，全部 ≥ AA。
    // ──────────────────────────────────────────────────────────────────────

    /** 课程卡片底色 */
    fun courseContainerColor(courseColor: Int, dark: Boolean): Int {
        return if (dark) {
            scaleColor(courseColor, 0.45f)
        } else {
            ColorUtils.blendARGB(courseColor, Color.WHITE, 0.82f)
        }
    }

    /** 课程卡片上的主文字色（课名） */
    fun courseOnColor(courseColor: Int, dark: Boolean): Int {
        return if (dark) Color.WHITE else scaleColor(courseColor, 0.50f)
    }

    /** 课程卡片上的次要文字色（教室 / 教师），比主文字降一档不透明度 */
    fun courseOnColorSecondary(courseColor: Int, dark: Boolean): Int {
        return ColorUtils.setAlphaComponent(courseOnColor(courseColor, dark), if (dark) 208 else 217)
    }

    /** 课程卡片描边：跟随课程色时用实色，否则用自定义边框色（见调用方） */
    fun courseStrokeColor(courseColor: Int, dark: Boolean): Int {
        return if (dark) scaleColor(courseColor, 0.70f) else scaleColor(courseColor, 0.86f)
    }

    private fun scaleColor(color: Int, factor: Float): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).roundToInt().coerceIn(0, 255),
            (Color.green(color) * factor).roundToInt().coerceIn(0, 255),
            (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
        )
    }
}
