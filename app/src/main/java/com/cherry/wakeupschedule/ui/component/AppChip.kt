package com.cherry.wakeupschedule.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import kotlin.math.roundToInt

/**
 * 从当前主题解析颜色属性。
 *
 * 必须用页面自己的 Context（Activity）：调色板 overlay 是
 * [com.cherry.wakeupschedule.ui.theme.ThemeManager.applyToTheme] 在 setContentView 之前
 * applyStyle 到 activity.theme 上的，而 window.decorView.context.theme 是另一个没叠加
 * overlay 的 Theme 实例，从它解析会拿到 M3 基线色而不是当前调色板的主色。
 */
fun Context.themeColor(@AttrRes attribute: Int): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(attribute, tv, true)) tv.data else Color.TRANSPARENT
}

private fun Context.chipDp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

/**
 * 胶囊 chip：成绩查询页的筛选行与绩点计算页的范围行共用（与 AppSlider 同层）。
 *
 * @param selected 选中态：填充主题主色；[accent] 非空时改填 accent
 * @param accent 语义色（如不及格用的 error 色）；未选中时表现为同色描边 + 同色文字
 * @param leadingIcon 可选前置图标
 */
fun Context.createAppChip(
    label: String,
    selected: Boolean,
    accent: Int? = null,
    @DrawableRes leadingIcon: Int? = null,
    onClick: () -> Unit
): View {
    val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
    val onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary)
    val onError = themeColor(com.google.android.material.R.attr.colorOnError)
    val outline = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    val surface = themeColor(com.google.android.material.R.attr.colorSurfaceContainerLowest)
    val onSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    val chip = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(chipDp(12), chipDp(6), chipDp(12), chipDp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = chipDp(8) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = chipDp(999).toFloat()
            when {
                selected -> setColor(accent ?: primary)
                accent != null -> {
                    setColor(surface)
                    setStroke(chipDp(1), ColorUtils.setAlphaComponent(accent, 0x4D))
                }
                else -> {
                    setColor(surface)
                    setStroke(chipDp(1), outline)
                }
            }
        }
        setOnClickListener { onClick() }
    }

    val textColor = when {
        selected -> if (accent != null) onError else onPrimary
        accent != null -> accent
        else -> onSurfaceVariant
    }

    if (leadingIcon != null) {
        chip.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(chipDp(16), chipDp(16)).apply {
                marginEnd = chipDp(6)
            }
            setImageResource(leadingIcon)
            setColorFilter(textColor)
        })
    }

    chip.addView(TextView(this).apply {
        text = label
        setTextSizeRes(R.dimen.text_caption)
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
    })

    return chip
}

/** chip 行里的竖分隔线（成绩查询页在筛选 chip 与排序 chip 之间用） */
fun Context.createChipDivider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(chipDp(1), chipDp(16)).apply {
        marginStart = chipDp(2)
        marginEnd = chipDp(10)
    }
    setBackgroundColor(
        ColorUtils.setAlphaComponent(
            themeColor(com.google.android.material.R.attr.colorOutlineVariant), 0x66
        )
    )
}
