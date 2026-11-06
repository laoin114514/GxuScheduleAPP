package com.cherry.wakeupschedule.ui.theme

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt

data class M3ColorPalette(
    val name: String,
    val nameResId: Int,
    @ColorInt val primary: Int,
    @ColorInt val onPrimary: Int,
    @ColorInt val primaryContainer: Int,
    @ColorInt val onPrimaryContainer: Int,
    @ColorInt val secondary: Int,
    @ColorInt val onSecondary: Int,
    @ColorInt val secondaryContainer: Int,
    @ColorInt val onSecondaryContainer: Int,
    @ColorInt val tertiary: Int,
    @ColorInt val onTertiary: Int,
    @ColorInt val tertiaryContainer: Int,
    @ColorInt val onTertiaryContainer: Int,
    @ColorInt val surface: Int,
    @ColorInt val onSurface: Int,
    @ColorInt val surfaceVariant: Int,
    @ColorInt val onSurfaceVariant: Int,
    @ColorInt val surfaceContainer: Int,
    @ColorInt val surfaceContainerHigh: Int,
    @ColorInt val error: Int,
    @ColorInt val onError: Int,
    @ColorInt val outline: Int,
    @ColorInt val outlineVariant: Int,
    val isDark: Boolean = false,
    /**
     * 页面底渐变的两个端点色（null = 由 [surface]/[primary] 自动派生，见 [PageBackground]）。
     *
     * 个别配色需要打破默认派生比例时（例如鎏金：默认 26% 混主色会把浅色底压成"土棕"，
     * 深色底又不够金），在这里显式给"香槟丝绸"或"金色夜底"。
     * 只影响页面底/底部导航/导出背景，不是 M3 角色色，因此 XML overlay 不需要镜像。
     */
    @ColorInt val pageTop: Int? = null,
    @ColorInt val pageBottom: Int? = null
) {
    /**
     * 5 档 surface 容器族的其余三档（Material 的 Low / Lowest / Highest）。
     *
     * 这组值在 values/themes.xml 与 values-night/themes.xml 里也各写了一份静态值：
     * XML 无法做颜色运算，代码侧必须保持同构，否则"布局里拿到的卡片底色"和
     * "代码里算出来的卡片底色"会分叉。改动时两边一起改。
     */
    val surfaceContainerLowest: Int
        get() = if (isDark) mix(surface, 0xFF000000.toInt(), 0.42f)
        else mix(surface, 0xFFFFFFFF.toInt(), 0.72f)

    val surfaceContainerLow: Int
        get() = if (isDark) mix(surface, 0xFFFFFFFF.toInt(), 0.04f)
        else mix(surface, outlineVariant, 0.06f)

    val surfaceContainerHighest: Int
        get() = if (isDark) mix(surface, 0xFFFFFFFF.toInt(), 0.12f)
        else mix(surface, outlineVariant, 0.16f)

    private fun mix(base: Int, tint: Int, amount: Float): Int =
        ColorUtils.blendARGB(base, tint, amount)

    companion object {
        val LIGHT_PALETTES: List<M3ColorPalette> = listOf(
            M3ColorPalette(
                name = "经典紫", nameResId = 0,
                primary = "#6750A4".toColorInt(), onPrimary = "#FFFFFF".toColorInt(),
                primaryContainer = "#EADDFF".toColorInt(), onPrimaryContainer = "#21005D".toColorInt(),
                secondary = "#625B71".toColorInt(), onSecondary = "#FFFFFF".toColorInt(),
                secondaryContainer = "#E8DEF8".toColorInt(), onSecondaryContainer = "#1D192B".toColorInt(),
                tertiary = "#7D5260".toColorInt(), onTertiary = "#FFFFFF".toColorInt(),
                tertiaryContainer = "#FFD8E4".toColorInt(), onTertiaryContainer = "#31111D".toColorInt(),
                surface = "#FFFBFE".toColorInt(), onSurface = "#1C1B1F".toColorInt(),
                surfaceVariant = "#E7E0EC".toColorInt(), onSurfaceVariant = "#49454F".toColorInt(),
                surfaceContainer = "#F3EDF7".toColorInt(), surfaceContainerHigh = "#ECE6F0".toColorInt(),
                error = "#B3261E".toColorInt(), onError = "#FFFFFF".toColorInt(),
                outline = "#79747E".toColorInt(), outlineVariant = "#CAC4D0".toColorInt()
            ),
            M3ColorPalette(
                name = "海洋蓝", nameResId = 0,
                primary = "#1565C0".toColorInt(), onPrimary = "#FFFFFF".toColorInt(),
                primaryContainer = "#D1E4FF".toColorInt(), onPrimaryContainer = "#001D36".toColorInt(),
                secondary = "#545F71".toColorInt(), onSecondary = "#FFFFFF".toColorInt(),
                secondaryContainer = "#D7E3F7".toColorInt(), onSecondaryContainer = "#101C2B".toColorInt(),
                tertiary = "#6E5676".toColorInt(), onTertiary = "#FFFFFF".toColorInt(),
                tertiaryContainer = "#F8D8FF".toColorInt(), onTertiaryContainer = "#271430".toColorInt(),
                surface = "#FDFCFF".toColorInt(), onSurface = "#1A1C1E".toColorInt(),
                surfaceVariant = "#DFE2EB".toColorInt(), onSurfaceVariant = "#43474E".toColorInt(),
                surfaceContainer = "#F0F4FA".toColorInt(), surfaceContainerHigh = "#EAEEF4".toColorInt(),
                error = "#BA1A1A".toColorInt(), onError = "#FFFFFF".toColorInt(),
                outline = "#73777F".toColorInt(), outlineVariant = "#C3C7CF".toColorInt()
            ),
            M3ColorPalette(
                name = "青绿", nameResId = 0,
                primary = "#006A60".toColorInt(), onPrimary = "#FFFFFF".toColorInt(),
                primaryContainer = "#7AF7E6".toColorInt(), onPrimaryContainer = "#00201C".toColorInt(),
                secondary = "#4A635F".toColorInt(), onSecondary = "#FFFFFF".toColorInt(),
                secondaryContainer = "#CCE8E2".toColorInt(), onSecondaryContainer = "#051F1C".toColorInt(),
                tertiary = "#426277".toColorInt(), onTertiary = "#FFFFFF".toColorInt(),
                tertiaryContainer = "#C6E7FF".toColorInt(), onTertiaryContainer = "#001E2D".toColorInt(),
                surface = "#FBFDF9".toColorInt(), onSurface = "#191C1B".toColorInt(),
                surfaceVariant = "#DAE5E1".toColorInt(), onSurfaceVariant = "#3F4946".toColorInt(),
                surfaceContainer = "#EFF5F1".toColorInt(), surfaceContainerHigh = "#E9EFEB".toColorInt(),
                error = "#BA1A1A".toColorInt(), onError = "#FFFFFF".toColorInt(),
                outline = "#6F7976".toColorInt(), outlineVariant = "#BEC9C5".toColorInt()
            ),
            M3ColorPalette(
                // 香槟金 + 亮金箔 + 酒红点缀：浅色底是"香槟丝绸"的暖象牙，
                // 主色是比"深棕"更亮、更偏金的老金（#936A00，卡片上小字仍达 AA），
                // 高光容器用足量金箔色 #FFD35C——让金色是"亮出来的"，而不是把界面压成土棕；
                // 页面底显式给香槟渐变（默认 26% 混主色会偏土棕），见 pageTop/pageBottom。
                name = "鎏金", nameResId = 0,
                primary = "#936A00".toColorInt(), onPrimary = "#FFFFFF".toColorInt(),
                primaryContainer = "#FFD35C".toColorInt(), onPrimaryContainer = "#2A1C00".toColorInt(),
                secondary = "#7A5C32".toColorInt(), onSecondary = "#FFFFFF".toColorInt(),
                secondaryContainer = "#F3DCA6".toColorInt(), onSecondaryContainer = "#281B06".toColorInt(),
                tertiary = "#8C3345".toColorInt(), onTertiary = "#FFFFFF".toColorInt(),
                tertiaryContainer = "#FFD9DA".toColorInt(), onTertiaryContainer = "#3B0710".toColorInt(),
                surface = "#FFFCF4".toColorInt(), onSurface = "#211A10".toColorInt(),
                surfaceVariant = "#F0E2C6".toColorInt(), onSurfaceVariant = "#52452E".toColorInt(),
                surfaceContainer = "#FCF3E0".toColorInt(), surfaceContainerHigh = "#F7EBD3".toColorInt(),
                error = "#BA1A1A".toColorInt(), onError = "#FFFFFF".toColorInt(),
                outline = "#8A7652".toColorInt(), outlineVariant = "#D9C7A4".toColorInt(),
                pageTop = "#EAD69C".toColorInt(), pageBottom = "#FAF2D9".toColorInt()
            ),
            M3ColorPalette(
                // 花瓣粉 + 樱枝灰粉 + 新叶绿：浅色底是晨光下的花瓣白，主色取自樱花的深花蕊色，
                // tertiary 用叶芽绿补齐春天意象（很少出现，只在个别 M3 组件里露出）
                name = "樱粉", nameResId = 0,
                primary = "#9A3A57".toColorInt(), onPrimary = "#FFFFFF".toColorInt(),
                primaryContainer = "#FFD9E2".toColorInt(), onPrimaryContainer = "#3E0020".toColorInt(),
                secondary = "#74565D".toColorInt(), onSecondary = "#FFFFFF".toColorInt(),
                secondaryContainer = "#F6DFE2".toColorInt(), onSecondaryContainer = "#2B151A".toColorInt(),
                tertiary = "#5C6B3F".toColorInt(), onTertiary = "#FFFFFF".toColorInt(),
                tertiaryContainer = "#DFE8C2".toColorInt(), onTertiaryContainer = "#1A2108".toColorInt(),
                surface = "#FFF7F8".toColorInt(), onSurface = "#201A1B".toColorInt(),
                surfaceVariant = "#F2DDE0".toColorInt(), onSurfaceVariant = "#524345".toColorInt(),
                surfaceContainer = "#FFEFF1".toColorInt(), surfaceContainerHigh = "#FBE8EA".toColorInt(),
                error = "#BA1A1A".toColorInt(), onError = "#FFFFFF".toColorInt(),
                outline = "#837377".toColorInt(), outlineVariant = "#D7C1C5".toColorInt()
            )
        )

        val DARK_PALETTES: List<M3ColorPalette> = listOf(
            M3ColorPalette(
                name = "经典紫", nameResId = 0, isDark = true,
                primary = "#D0BCFF".toColorInt(), onPrimary = "#381E72".toColorInt(),
                primaryContainer = "#4F378B".toColorInt(), onPrimaryContainer = "#EADDFF".toColorInt(),
                secondary = "#CCC2DC".toColorInt(), onSecondary = "#332D41".toColorInt(),
                secondaryContainer = "#4A4458".toColorInt(), onSecondaryContainer = "#E8DEF8".toColorInt(),
                tertiary = "#EFB8C8".toColorInt(), onTertiary = "#492532".toColorInt(),
                tertiaryContainer = "#633B48".toColorInt(), onTertiaryContainer = "#FFD8E4".toColorInt(),
                surface = "#141218".toColorInt(), onSurface = "#E6E1E5".toColorInt(),
                surfaceVariant = "#49454F".toColorInt(), onSurfaceVariant = "#CAC4D0".toColorInt(),
                surfaceContainer = "#1C1B1F".toColorInt(), surfaceContainerHigh = "#262429".toColorInt(),
                error = "#F2B8B5".toColorInt(), onError = "#601410".toColorInt(),
                outline = "#938F99".toColorInt(), outlineVariant = "#49454F".toColorInt()
            ),
            M3ColorPalette(
                name = "海洋蓝", nameResId = 0, isDark = true,
                primary = "#AAC7FF".toColorInt(), onPrimary = "#002F5C".toColorInt(),
                primaryContainer = "#004682".toColorInt(), onPrimaryContainer = "#D1E4FF".toColorInt(),
                secondary = "#BBC7DB".toColorInt(), onSecondary = "#253140".toColorInt(),
                secondaryContainer = "#3B4858".toColorInt(), onSecondaryContainer = "#D7E3F7".toColorInt(),
                tertiary = "#DCBCE3".toColorInt(), onTertiary = "#3E2847".toColorInt(),
                tertiaryContainer = "#563E5F".toColorInt(), onTertiaryContainer = "#F8D8FF".toColorInt(),
                surface = "#111318".toColorInt(), onSurface = "#E2E2E9".toColorInt(),
                surfaceVariant = "#43474E".toColorInt(), onSurfaceVariant = "#C3C7CF".toColorInt(),
                surfaceContainer = "#1A1C1E".toColorInt(), surfaceContainerHigh = "#242629".toColorInt(),
                error = "#FFB4AB".toColorInt(), onError = "#690005".toColorInt(),
                outline = "#8D9199".toColorInt(), outlineVariant = "#43474E".toColorInt()
            ),
            M3ColorPalette(
                name = "青绿", nameResId = 0, isDark = true,
                primary = "#5CDBCB".toColorInt(), onPrimary = "#003730".toColorInt(),
                primaryContainer = "#005048".toColorInt(), onPrimaryContainer = "#7AF7E6".toColorInt(),
                secondary = "#B1CCC7".toColorInt(), onSecondary = "#1C3531".toColorInt(),
                secondaryContainer = "#324B47".toColorInt(), onSecondaryContainer = "#CCE8E2".toColorInt(),
                tertiary = "#AACBE3".toColorInt(), onTertiary = "#0F3447".toColorInt(),
                tertiaryContainer = "#294A5E".toColorInt(), onTertiaryContainer = "#C6E7FF".toColorInt(),
                surface = "#191C1B".toColorInt(), onSurface = "#E0E3E1".toColorInt(),
                surfaceVariant = "#3F4946".toColorInt(), onSurfaceVariant = "#BEC9C5".toColorInt(),
                surfaceContainer = "#1F2322".toColorInt(), surfaceContainerHigh = "#2A2E2C".toColorInt(),
                error = "#FFB4AB".toColorInt(), onError = "#690005".toColorInt(),
                outline = "#889390".toColorInt(), outlineVariant = "#3F4946".toColorInt()
            ),
            M3ColorPalette(
                // 黑金 VIP：近黑暖咖底 + 足色老金主色（比"浅香槟金"更沉、更接近金条），
                // 卡片是更低一档的黑，金色只出现在标题/开关/选中态上——像一张黑金卡，
                // 而不是把整屏刷成浅金。页面底给一档偏浓的金色夜底，托出顶部的金气。
                name = "鎏金", nameResId = 0, isDark = true,
                primary = "#E1B03E".toColorInt(), onPrimary = "#3A2A00".toColorInt(),
                primaryContainer = "#6A5000".toColorInt(), onPrimaryContainer = "#FFE3A0".toColorInt(),
                secondary = "#DFC28C".toColorInt(), onSecondary = "#3B2F14".toColorInt(),
                secondaryContainer = "#57462A".toColorInt(), onSecondaryContainer = "#F5E3BF".toColorInt(),
                tertiary = "#F2B0B4".toColorInt(), onTertiary = "#571D24".toColorInt(),
                tertiaryContainer = "#733039".toColorInt(), onTertiaryContainer = "#FFDADD".toColorInt(),
                surface = "#17130C".toColorInt(), onSurface = "#EDE3D1".toColorInt(),
                surfaceVariant = "#4E4433".toColorInt(), onSurfaceVariant = "#D5C6AE".toColorInt(),
                surfaceContainer = "#201B13".toColorInt(), surfaceContainerHigh = "#2A2419".toColorInt(),
                error = "#FFB4AB".toColorInt(), onError = "#690005".toColorInt(),
                outline = "#A0927A".toColorInt(), outlineVariant = "#4E4433".toColorInt(),
                pageTop = "#342914".toColorInt(), pageBottom = "#110E09".toColorInt()
            ),
            M3ColorPalette(
                // 夜樱：深李子黑的夜底 + 月光花瓣粉主色，容器色压到酒红一侧，
                // 保证深色下粉色不发灰、也不刺眼
                name = "樱粉", nameResId = 0, isDark = true,
                primary = "#FFB1C8".toColorInt(), onPrimary = "#5E1133".toColorInt(),
                primaryContainer = "#7C2C49".toColorInt(), onPrimaryContainer = "#FFD9E2".toColorInt(),
                secondary = "#E3BDC4".toColorInt(), onSecondary = "#422A30".toColorInt(),
                secondaryContainer = "#5A4046".toColorInt(), onSecondaryContainer = "#FFD9DF".toColorInt(),
                tertiary = "#C2CFA0".toColorInt(), onTertiary = "#2D3517".toColorInt(),
                tertiaryContainer = "#434D2A".toColorInt(), onTertiaryContainer = "#DEE9C2".toColorInt(),
                surface = "#1A1215".toColorInt(), onSurface = "#EEE0E3".toColorInt(),
                surfaceVariant = "#514246".toColorInt(), onSurfaceVariant = "#D6C1C6".toColorInt(),
                surfaceContainer = "#231A1D".toColorInt(), surfaceContainerHigh = "#2D2427".toColorInt(),
                error = "#FFB4AB".toColorInt(), onError = "#690005".toColorInt(),
                outline = "#9F8C90".toColorInt(), outlineVariant = "#514246".toColorInt()
            )
        )
    }
}
