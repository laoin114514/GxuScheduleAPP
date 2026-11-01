package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.drawable.InsetDrawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.cherry.wakeupschedule.R
import com.google.android.material.color.MaterialColors

/**
 * 小圆形图标按钮（统一组件）。
 *
 * 视觉：圆形色块底 + 居中图标，图标为主题标准图标色（浅色灰/深色白，
 * 即 colorOnSurfaceVariant），跟随主题与配色切换。
 * 手感：与底部导航一致——按压整颗缩放、松手回弹；不变色、无涟漪阴影。
 * 凡是这类小圆形按钮一律用它。
 *
 * 图标用 android:src 指定；XML 里的子 View 会叠在图标上方
 * （如刷新按钮的转圈指示器）。
 */
class CircleIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs, 0) {

    private val iconView: ImageView

    /** 当前圆形底色 drawable（setCircleBackground 可换） */
    private var circleRes: Int = R.drawable.bg_icon_circle

    /** 视觉圆直径：默认 40dp；控件本身做成 48dp 以拿到标准触控热区 */
    private var circleDiameterPx: Int = (40 * resources.displayMetrics.density).toInt()

    init {
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.bg_icon_circle)

        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
            setColorFilter(
                MaterialColors.getColor(
                    this@CircleIconButton,
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
        }
        addView(iconView)

        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.src))
        val src = a.getResourceId(0, 0)
        a.recycle()
        if (src != 0) iconView.setImageResource(src)

        // 按压整颗缩放，松手回弹（同底部导航手感，不变色）
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
            }
            false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 控件尺寸 ≥ 48dp（触控热区标准），但视觉圆保持 circleDiameterPx：
        // 用 InsetDrawable 把背景圆往里缩，多出来的部分就是"看不见但可点"的触控区。
        val size = minOf(w, h)
        val inset = ((size - circleDiameterPx) / 2).coerceAtLeast(0)
        applyCircleBackground(inset)
        // 图标约占视觉圆的 9/16（40dp 圆钮 → 22dp 图标）
        val visual = (size - inset * 2).coerceAtLeast(1)
        val icon = (visual * 9f / 16f).toInt()
        iconView.layoutParams = LayoutParams(icon, icon, Gravity.CENTER)
    }

    private fun applyCircleBackground(inset: Int) {
        val drawable = AppCompatResources.getDrawable(context, circleRes)?.mutate() ?: return
        background = if (inset > 0) InsetDrawable(drawable, inset) else drawable
    }

    /** 动态换图标 */
    fun setIcon(resId: Int) = iconView.setImageResource(resId)

    /**
     * 换圆形底色（默认 [R.drawable.bg_icon_circle]）。
     * 只在页面底色与默认色接近、圆钮会「糊」进背景时才用，例如小组件中心的「?」。
     */
    fun setCircleBackground(@DrawableRes resId: Int) {
        circleRes = resId
        val size = minOf(width, height)
        val inset = ((size - circleDiameterPx) / 2).coerceAtLeast(0)
        applyCircleBackground(inset)
    }

    /** 调整视觉圆直径（dp）：控件仍按 48dp 热区，只影响看起来多大 */
    fun setCircleDiameterDp(dp: Int) {
        circleDiameterPx = (dp * resources.displayMetrics.density).toInt()
        requestLayout()
    }
}
