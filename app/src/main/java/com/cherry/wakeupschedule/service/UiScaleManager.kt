package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * 全局 UI 缩放管理。
 *
 * 实现方式是改写 [Configuration.densityDpi]：dp 与 sp 都按 density 换算，
 * 因此字体、间距、图标、卡片高度会随同一个系数同步等比变化，版面比例保持不变。
 *
 * 档位持久化复用 [SettingsManager] 既有的 `font_size` 键（app_settings），
 * 但 [wrap] 处于 attachBaseContext 热路径，直接读 SharedPreferences，
 * 避免每次创建页面都构造一个 SettingsManager（其内部会 new Gson）。
 */
object UiScaleManager {

    const val KEY_TINY = "tiny"
    const val KEY_SMALL = "small"
    const val KEY_NORMAL = "normal"
    const val KEY_LARGE = "large"
    const val KEY_XLARGE = "xlarge"

    private const val PREFS_NAME = "app_settings"
    private const val PREF_KEY = "font_size"
    private const val DEFAULT_KEY = KEY_NORMAL

    /** 一档预设：[key] 落盘，[label] 界面展示，[scale] 相对原始密度的整体缩放系数 */
    data class Preset(val key: String, val label: String, val scale: Float)

    /**
     * 五档预设，以「标准」为中心等距排布（每档相差 0.05），
     * 使大字侧与小字侧的变化幅度一致。
     */
    val PRESETS = listOf(
        Preset(KEY_TINY, "极小", 0.86f),
        Preset(KEY_SMALL, "小", 0.91f),
        Preset(KEY_NORMAL, "标准", 0.96f),
        Preset(KEY_LARGE, "大", 1.01f),
        Preset(KEY_XLARGE, "超大", 1.06f),
    )

    /** 当前档位 key；存量非法值一律回落到「标准」 */
    fun currentKey(context: Context): String {
        val saved = readKey(context)
        return PRESETS.firstOrNull { it.key == saved }?.key ?: DEFAULT_KEY
    }

    fun currentPreset(context: Context): Preset =
        PRESETS.first { it.key == currentKey(context) }

    fun currentScale(context: Context): Float = currentPreset(context).scale

    fun labelOf(context: Context): String = currentPreset(context).label

    fun setPreset(context: Context, key: String) {
        val safe = PRESETS.firstOrNull { it.key == key }?.key ?: DEFAULT_KEY
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, safe).apply()
    }

    /**
     * 按当前档位包装 base Context。标准档直接返回原 Context，省掉一次无谓的包装。
     */
    fun wrap(base: Context): Context {
        val scale = currentScale(base)
        if (scale == 1f) return base
        val config = Configuration(base.resources.configuration)
        config.densityDpi = (config.densityDpi * scale).roundToInt()
        return base.createConfigurationContext(config)
    }

    private fun readKey(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY, DEFAULT_KEY)
}
