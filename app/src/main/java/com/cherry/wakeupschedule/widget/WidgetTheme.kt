package com.cherry.wakeupschedule.widget

import android.content.Context
import android.content.res.Configuration
import com.cherry.wakeupschedule.R

/**
 * 小组件动态取色统一入口。
 *
 * 布局里引用 @color/widget_* 时，桌面进程会按系统深色模式自动解析 night 资源；
 * 但代码里 setTextColor / setColorFilter 的颜色值无法走资源解析，
 * 部分定制桌面解析 night 资源又不可靠，所以统一从这里按 uiMode 显式取色兜底。
 */
object WidgetTheme {

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun title(context: Context): Int = context.getColor(R.color.widget_text_title)

    fun secondary(context: Context): Int = context.getColor(R.color.widget_text_secondary)

    fun weak(context: Context): Int = context.getColor(R.color.widget_text_weak)

    fun accent(context: Context): Int = context.getColor(R.color.widget_accent)
}
