package com.cherry.wakeupschedule.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 学期色块里的迷你风景：渐变天空 + 太阳 + 双层山丘。
 *
 * 已填充课表的学期在课表菜单里用这块风景标识：
 * 太阳带缓慢的呼吸光晕动画，让色块"活"起来，未填充的学期保持素色块。
 */
class SemesterSceneryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hillBackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hillFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var skyTop = 0
    private var skyBottom = 0
    private var hillBackColor = 0
    private var hillFrontColor = 0
    private var sunColor = 0
    private var strokeColor = 0
    private var strokeWidthPx = 0f

    private val clipPath = Path()
    private var cornerRadius = 0f

    private var sunAnimator: ValueAnimator? = null
    private var sunGlowAlpha = 1f

    private var viewWidth = 0
    private var viewHeight = 0

    /**
     * 设置风景配色。天空为自上而下的渐变，山丘两层，太阳固定暖色。
     */
    fun setPalette(skyTop: Int, skyBottom: Int, hillBack: Int, hillFront: Int, sunColor: Int) {
        this.skyTop = skyTop
        this.skyBottom = skyBottom
        hillBackColor = hillBack
        hillFrontColor = hillFront
        this.sunColor = sunColor
        rebuildGradient()
        invalidate()
    }

    /** 选中态描边（当前学期用主题色圈出） */
    fun setSelectionStroke(color: Int, widthPx: Float) {
        strokeColor = color
        strokeWidthPx = widthPx
        strokePaint.color = color
        strokePaint.strokeWidth = widthPx
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        // 圆角约 14dp（与整体色块圆角一致）
        cornerRadius = w * 0.24f
        clipPath.reset()
        clipPath.addRoundRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            cornerRadius, cornerRadius, Path.Direction.CW
        )
        rebuildGradient()
    }

    private fun rebuildGradient() {
        if (viewWidth == 0 || viewHeight == 0) return
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, viewHeight.toFloat(),
            skyTop, skyBottom, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (viewWidth == 0 || viewHeight == 0) return

        canvas.save()
        canvas.clipPath(clipPath)

        // 天空
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), skyPaint)

        // 太阳：光晕 + 核心（光晕带呼吸动画）
        val sunX = viewWidth * 0.72f
        val sunY = viewHeight * 0.30f
        val sunR = viewWidth * 0.13f
        sunGlowPaint.color = sunColor
        sunGlowPaint.alpha = (70 * sunGlowAlpha).toInt()
        canvas.drawCircle(sunX, sunY, sunR * 2.1f, sunGlowPaint)
        sunPaint.color = sunColor
        canvas.drawCircle(sunX, sunY, sunR, sunPaint)

        // 后山（圆心在画面下方，只露出顶部弧线）
        hillBackPaint.color = hillBackColor
        canvas.drawCircle(
            viewWidth * 0.30f, viewHeight * 1.05f,
            viewWidth * 0.55f, hillBackPaint
        )

        // 前山（更低、更大，制造层次）
        hillFrontPaint.color = hillFrontColor
        canvas.drawCircle(
            viewWidth * 0.85f, viewHeight * 1.18f,
            viewWidth * 0.62f, hillFrontPaint
        )

        canvas.restore()

        // 选中描边画在裁剪区之外，保证完整圆角描边
        if (strokeWidthPx > 0f && strokeColor != 0) {
            canvas.drawRoundRect(
                strokeWidthPx / 2f, strokeWidthPx / 2f,
                viewWidth - strokeWidthPx / 2f, viewHeight - strokeWidthPx / 2f,
                cornerRadius, cornerRadius, strokePaint
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSunPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sunAnimator?.cancel()
        sunAnimator = null
    }

    private fun startSunPulse() {
        if (sunAnimator != null) return
        sunAnimator = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                sunGlowAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
