package com.cherry.wakeupschedule.ui.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * 页面底色渐变：由当前调色板派生，而不是写死一份蓝灰。
 *
 * 旧做法是 `drawable/bg_page_gradient.xml` 里写死 `#C4D4E4 → #DDE4ED`（深色
 * `#1A2433 → #0D1117`），也就是"无论用户选哪套配色，页面底永远是海洋蓝的灰蓝"。
 * 现在改成从 `primary` 与 `surface` 混合：
 *
 * - 浅色：top = 26% primary + surface，bottom = 14% primary + surface（略带主色的浅底，卡片仍浮在上面）
 * - 深色：top = 10% primary + surface，bottom = surface 压暗 20%
 *
 * 海洋蓝下与旧色几乎一致（#C1D5EF→#DEE7F6 vs #C4D4E4→#DDE4ED），
 * 换成青绿 / 鎏金 / 樱粉时页面底会跟着变色，不再是"蓝灰底 + 主题色标题"的割裂感。
 */
object PageBackground {

    /** 渐变顶端色（也用作底部导航栏、加载遮罩的底色，保证接缝一致） */
    fun topColor(context: Context): Int {
        val palette = ThemeManager.currentPalette(context)
        return if (palette.isDark) {
            ColorUtils.blendARGB(palette.surface, palette.primary, 0.10f)
        } else {
            ColorUtils.blendARGB(palette.surface, palette.primary, 0.26f)
        }
    }

    /** 渐变底端色 */
    fun bottomColor(context: Context): Int {
        val palette = ThemeManager.currentPalette(context)
        return if (palette.isDark) {
            ColorUtils.blendARGB(palette.surface, 0xFF000000.toInt(), 0.20f)
        } else {
            ColorUtils.blendARGB(palette.surface, palette.primary, 0.14f)
        }
    }

    /** 给页面根布局铺上渐变底（替换布局里的 @drawable/bg_page_gradient） */
    fun apply(root: View) {
        root.background = gradient(root.context)
    }

    private fun gradient(context: Context) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(topColor(context), bottomColor(context))
    )
}
