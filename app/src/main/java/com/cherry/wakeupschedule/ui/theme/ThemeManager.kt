package com.cherry.wakeupschedule.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.annotation.ColorInt
import com.cherry.wakeupschedule.R
import kotlin.math.*

object ThemeManager {

    private const val PREFS_NAME = "m3_theme"
    private const val KEY_PALETTE_INDEX = "palette_index"
    private const val COURSE_COLOR_COUNT = 16

    @Volatile
    private var currentLight: M3ColorPalette = M3ColorPalette.LIGHT_PALETTES[0]

    @Volatile
    private var currentDark: M3ColorPalette = M3ColorPalette.DARK_PALETTES[0]

    @Volatile
    private var _courseColors: IntArray = sampleCourseColors(currentLight)

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
        _courseColors = sampleCourseColors(currentLight)
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

    fun getCourseColors(): IntArray = _courseColors

    private fun sampleCourseColors(palette: M3ColorPalette): IntArray {
        val baseColors = intArrayOf(
            palette.primaryContainer, palette.tertiaryContainer,
            palette.secondaryContainer, palette.primary,
            palette.tertiary, palette.secondary,
            palette.surfaceContainerHigh
        )
        val result = IntArray(COURSE_COLOR_COUNT)
        for (i in 0 until COURSE_COLOR_COUNT) {
            val base = baseColors[i % baseColors.size]
            val hsl = FloatArray(3)
            Color.colorToHSV(base, hsl)
            hsl[0] = (hsl[0] + (i * 22.5f)) % 360f
            hsl[1] = hsl[1].coerceIn(0.15f, 0.5f)
            hsl[2] = hsl[2].coerceIn(0.80f, 0.95f)
            result[i] = Color.HSVToColor(hsl)
        }
        return result
    }
}
