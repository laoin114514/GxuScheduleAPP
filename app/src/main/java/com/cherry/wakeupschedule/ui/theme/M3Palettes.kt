package com.cherry.wakeupschedule.ui.theme

import android.content.Context
import androidx.annotation.ColorInt
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
    val isDark: Boolean = false
) {
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
                name = "暖棕", nameResId = 0,
                primary = "#8D5000".toColorInt(), onPrimary = "#FFFFFF".toColorInt(),
                primaryContainer = "#FFDCC1".toColorInt(), onPrimaryContainer = "#2D1600".toColorInt(),
                secondary = "#725A42".toColorInt(), onSecondary = "#FFFFFF".toColorInt(),
                secondaryContainer = "#FFDCC1".toColorInt(), onSecondaryContainer = "#281805".toColorInt(),
                tertiary = "#56633C".toColorInt(), onTertiary = "#FFFFFF".toColorInt(),
                tertiaryContainer = "#D9E9B7".toColorInt(), onTertiaryContainer = "#141F02".toColorInt(),
                surface = "#FFFBFF".toColorInt(), onSurface = "#1F1B16".toColorInt(),
                surfaceVariant = "#F2DFD1".toColorInt(), onSurfaceVariant = "#51443A".toColorInt(),
                surfaceContainer = "#FDF3EC".toColorInt(), surfaceContainerHigh = "#F7EDE6".toColorInt(),
                error = "#BA1A1A".toColorInt(), onError = "#FFFFFF".toColorInt(),
                outline = "#847468".toColorInt(), outlineVariant = "#D7C3B5".toColorInt()
            ),
            M3ColorPalette(
                name = "玫红", nameResId = 0,
                primary = "#B0005C".toColorInt(), onPrimary = "#FFFFFF".toColorInt(),
                primaryContainer = "#FFD9E4".toColorInt(), onPrimaryContainer = "#40001D".toColorInt(),
                secondary = "#74565F".toColorInt(), onSecondary = "#FFFFFF".toColorInt(),
                secondaryContainer = "#FFD9E3".toColorInt(), onSecondaryContainer = "#2B151D".toColorInt(),
                tertiary = "#7D5633".toColorInt(), onTertiary = "#FFFFFF".toColorInt(),
                tertiaryContainer = "#FFDCC5".toColorInt(), onTertiaryContainer = "#2E1500".toColorInt(),
                surface = "#FFFBFF".toColorInt(), onSurface = "#201A1B".toColorInt(),
                surfaceVariant = "#F2DDE2".toColorInt(), onSurfaceVariant = "#514348".toColorInt(),
                surfaceContainer = "#FFF0F3".toColorInt(), surfaceContainerHigh = "#FCEAEE".toColorInt(),
                error = "#BA1A1A".toColorInt(), onError = "#FFFFFF".toColorInt(),
                outline = "#847378".toColorInt(), outlineVariant = "#D7C1C7".toColorInt()
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
                name = "暖棕", nameResId = 0, isDark = true,
                primary = "#FFB870".toColorInt(), onPrimary = "#4B2800".toColorInt(),
                primaryContainer = "#6B3C00".toColorInt(), onPrimaryContainer = "#FFDCC1".toColorInt(),
                secondary = "#E1C1A7".toColorInt(), onSecondary = "#402D19".toColorInt(),
                secondaryContainer = "#59432E".toColorInt(), onSecondaryContainer = "#FFDCC1".toColorInt(),
                tertiary = "#BDCD9D".toColorInt(), onTertiary = "#293412".toColorInt(),
                tertiaryContainer = "#3F4B26".toColorInt(), onTertiaryContainer = "#D9E9B7".toColorInt(),
                surface = "#1F1B16".toColorInt(), onSurface = "#EBE0D9".toColorInt(),
                surfaceVariant = "#51443A".toColorInt(), onSurfaceVariant = "#D7C3B5".toColorInt(),
                surfaceContainer = "#26221D".toColorInt(), surfaceContainerHigh = "#312D27".toColorInt(),
                error = "#FFB4AB".toColorInt(), onError = "#690005".toColorInt(),
                outline = "#A08D7E".toColorInt(), outlineVariant = "#51443A".toColorInt()
            ),
            M3ColorPalette(
                name = "玫红", nameResId = 0, isDark = true,
                primary = "#FFB0CB".toColorInt(), onPrimary = "#650033".toColorInt(),
                primaryContainer = "#8E0049".toColorInt(), onPrimaryContainer = "#FFD9E4".toColorInt(),
                secondary = "#E3BDC7".toColorInt(), onSecondary = "#432A33".toColorInt(),
                secondaryContainer = "#5B4049".toColorInt(), onSecondaryContainer = "#FFD9E3".toColorInt(),
                tertiary = "#F5C096".toColorInt(), onTertiary = "#452900".toColorInt(),
                tertiaryContainer = "#613F1D".toColorInt(), onTertiaryContainer = "#FFDCC5".toColorInt(),
                surface = "#201A1B".toColorInt(), onSurface = "#ECE0E1".toColorInt(),
                surfaceVariant = "#514348".toColorInt(), onSurfaceVariant = "#D7C1C7".toColorInt(),
                surfaceContainer = "#272122".toColorInt(), surfaceContainerHigh = "#322B2D".toColorInt(),
                error = "#FFB4AB".toColorInt(), onError = "#690005".toColorInt(),
                outline = "#A08C91".toColorInt(), outlineVariant = "#514348".toColorInt()
            )
        )
    }
}
