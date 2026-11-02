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
    // 课程卡片配色：浓色块 + 白字（原版观感）
    //
    // 原版做法是「课程色 50% 半透明 + 纯白字」，色块够艳但字读不出来
    // （浅色实测 1.48–2.77:1）。这里保留"浓色块 + 白字"的观感，只做一件事：
    // 对每个课程色求出"白字仍满足 4.5:1 的最亮程度"，够亮的颜色原样保留，
    // 亮色（绿/橙/浅蓝等）按需压暗。9 色实算后最差也有 4.6:1。
    // ──────────────────────────────────────────────────────────────────────

    /** 白字可读的最低对比度（AA 正文 4.5，留一点余量避免取整掉到线下） */
    private const val MIN_TEXT_CONTRAST = 4.6

    /** 每种课程色解出来的色块色（同一颜色只算一次） */
    private val blockColorCache = HashMap<Int, Int>()

    /** 课程卡片底色：白字可读前提下的"最接近原色"的浓色块 */
    fun courseContainerColor(courseColor: Int, dark: Boolean): Int =
        blockColorCache.getOrPut(courseColor) { solveBlockColor(courseColor) }

    /** 课程卡片上的主文字色（课名）：浓色块上恒为白色 */
    fun courseOnColor(courseColor: Int, dark: Boolean): Int = Color.WHITE

    /** 课程卡片上的次要文字色（教室 / 教师）：白字降一档不透明度 */
    fun courseOnColorSecondary(courseColor: Int, dark: Boolean): Int =
        ColorUtils.setAlphaComponent(Color.WHITE, if (dark) 216 else 224)

    /**
     * 求「白字对比度 ≥ [MIN_TEXT_CONTRAST] 时最亮的那个色」。
     *
     * 从原色开始按 4% 步长压暗，第一个达标的就返回 —— 这样红/蓝/深橙这类
     * 本来够深的颜色几乎保持原样，只有绿/橙/浅蓝这种高明度色会明显压深。
     */
    private fun solveBlockColor(color: Int): Int {
        var factor = 1.0f
        while (factor > 0.30f) {
            val candidate = scaleColor(color, factor)
            if (contrastWithWhite(candidate) >= MIN_TEXT_CONTRAST) return candidate
            factor -= 0.04f
        }
        return scaleColor(color, 0.30f)
    }

    /** WCAG 相对亮度 */
    private fun relativeLuminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
                0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
    }

    private fun contrastWithWhite(color: Int): Double {
        val lum = relativeLuminance(color)
        return 1.05 / (lum + 0.05)
    }

    /** 课程卡片描边：跟随课程色时用半透明白（浓色块与原版观感一致） */
    fun courseStrokeColor(courseColor: Int, dark: Boolean): Int =
        ColorUtils.setAlphaComponent(Color.WHITE, if (dark) 72 else 56)

    private fun scaleColor(color: Int, factor: Float): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).roundToInt().coerceIn(0, 255),
            (Color.green(color) * factor).roundToInt().coerceIn(0, 255),
            (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
        )
    }
}
