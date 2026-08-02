package com.cherry.wakeupschedule.service

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Calendar
import java.util.Locale

/**
 * 主题模式中心逻辑
 *
 * 统一负责「深浅色 + 自动切换」的最终夜间模式解析与生效，
 * 替代原先散落在 App / SettingsActivity / ProfileFragment 三处的重复映射逻辑。
 *
 * 模式结构：
 * - 自动切换关闭：直接使用 theme_mode（light/dark/system 兼容旧值）
 * - 自动切换开启：
 *   - 跟随系统：MODE_NIGHT_FOLLOW_SYSTEM
 *   - 自定时间：深色时段为环形区间 [深色时间, 浅色时间)，跨午夜自然处理
 *
 * 两个时间点之间保持至少 [MIN_GAP_MINUTES] 分钟间隔，冲突时自动计算规避。
 */
object ThemeModeManager {

    /** 深浅色时间点之间的最小间隔（分钟） */
    const val MIN_GAP_MINUTES = 60

    private const val DAY_MINUTES = 24 * 60

    /** 解析 "HH:mm" 为当天分钟数 */
    private fun parseMinutes(time: String): Int {
        val parts = time.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return (h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59)) % DAY_MINUTES
    }

    /** 将当天分钟数格式化为 "HH:mm" */
    private fun formatMinutes(minutes: Int): String {
        val m = ((minutes % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** 环形时间线上两个时间点的最小距离（分钟） */
    private fun circularDistance(a: Int, b: Int): Int {
        val diff = Math.abs(a - b)
        return Math.min(diff, DAY_MINUTES - diff)
    }

    /**
     * 解析当前设置对应的最终夜间模式
     */
    fun resolveNightMode(context: Context): Int {
        val sm = SettingsManager(context)
        if (sm.isAutoSwitchTheme()) {
            return when (sm.getAutoSwitchMode()) {
                "custom" ->
                    if (isInDarkPeriod(sm)) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }
        // 手动模式（兼容旧值 system）
        return when (sm.getThemeMode()) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    /**
     * 判断当前时刻是否处于深色时段。
     * 深色时段 = 环形区间 [深色时间, 浅色时间)，跨午夜自然处理。
     */
    fun isInDarkPeriod(context: Context): Boolean {
        return isInDarkPeriod(SettingsManager(context))
    }

    private fun isInDarkPeriod(sm: SettingsManager): Boolean {
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val dark = parseMinutes(sm.getDarkTime())
        val light = parseMinutes(sm.getLightTime())
        return if (dark <= light) {
            nowMin >= dark && nowMin < light
        } else {
            nowMin >= dark || nowMin < light
        }
    }

    /** 将当前设置即时生效到 AppCompatDelegate */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(resolveNightMode(context))
    }

    /** 当前生效设置的展示文案（浅色/深色/跟随系统/自定时间） */
    fun effectiveLabel(context: Context): String {
        val sm = SettingsManager(context)
        if (sm.isAutoSwitchTheme()) {
            return if (sm.getAutoSwitchMode() == "custom") "自定时间" else "跟随系统"
        }
        return when (sm.getThemeMode()) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }
    }

    /** 深色时段文案，如「深色时段：18:00 — 次日07:00」 */
    fun darkPeriodLabel(context: Context): String {
        val sm = SettingsManager(context)
        val dark = parseMinutes(sm.getDarkTime())
        val light = parseMinutes(sm.getLightTime())
        val darkStr = sm.getDarkTime()
        val lightStr = sm.getLightTime()
        return if (dark > light) "深色时段：$darkStr — 次日$lightStr"
        else "深色时段：$darkStr — $lightStr"
    }

    /**
     * 用户修改深色时间，若与浅色时间冲突（间隔不足 [MIN_GAP_MINUTES] 分钟），
     * 自动将浅色时间顺延到深色时间之后 MIN_GAP_MINUTES 分钟。
     *
     * @return 是否发生了自动调整
     */
    fun setDarkTimeWithAvoidance(context: Context, time: String): Boolean {
        val sm = SettingsManager(context)
        val dark = parseMinutes(time)
        var light = parseMinutes(sm.getLightTime())
        var adjusted = false
        if (circularDistance(light, dark) < MIN_GAP_MINUTES) {
            light = (dark + MIN_GAP_MINUTES) % DAY_MINUTES
            adjusted = true
        }
        sm.setDarkTime(formatMinutes(dark))
        sm.setLightTime(formatMinutes(light))
        return adjusted
    }

    /**
     * 用户修改浅色时间，若与深色时间冲突（间隔不足 [MIN_GAP_MINUTES] 分钟），
     * 自动将深色时间前移到浅色时间之前 MIN_GAP_MINUTES 分钟。
     *
     * @return 是否发生了自动调整
     */
    fun setLightTimeWithAvoidance(context: Context, time: String): Boolean {
        val sm = SettingsManager(context)
        val light = parseMinutes(time)
        var dark = parseMinutes(sm.getDarkTime())
        var adjusted = false
        if (circularDistance(light, dark) < MIN_GAP_MINUTES) {
            dark = (light - MIN_GAP_MINUTES + DAY_MINUTES) % DAY_MINUTES
            adjusted = true
        }
        sm.setLightTime(formatMinutes(light))
        sm.setDarkTime(formatMinutes(dark))
        return adjusted
    }
}
