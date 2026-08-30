package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.cherry.wakeupschedule.R
import com.google.android.material.color.MaterialColors

/**
 * 小圆形图标按钮（统一组件）。
 *
 * 视觉：圆形色块底 + 居中主色图标（与工具页图标圆底同语言）。
 * 手感：与底部导航一致——按压整颗缩放并轻微变色，松手回弹，
 * 不使用涟漪/阴影反馈。凡是这类小圆形按钮一律用它。
 *
 * 图标用 android:src 指定；XML 里的子 View 会叠在图标上方
 * （如刷新按钮的转圈指示器）。
 */
class CircleIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs, 0) {

    private val iconView: ImageView

    /** 常态图标色（主色），按压时变浅灰表示按下 */
    private val normalTint: Int by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    }
    private val pressedTint: Int by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.bg_icon_circle)

        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        }
        addView(iconView)

        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.src))
        val src = a.getResourceId(0, 0)
        a.recycle()
        if (src != 0) iconView.setImageResource(src)

        // 按压缩放 + 变色，松手回弹（同底部导航手感）
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                    iconView.setColorFilter(pressedTint)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                    iconView.setColorFilter(normalTint)
                }
            }
            false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 图标约占圆钮的 9/16（32dp 圆钮 → 18dp 图标）
        val icon = (minOf(w, h) * 9f / 16f).toInt()
        iconView.layoutParams = LayoutParams(icon, icon, Gravity.CENTER)
    }

    /** 动态换图标 */
    fun setIcon(resId: Int) = iconView.setImageResource(resId)
}
