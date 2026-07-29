package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

/**
 * Canvas 绘制的课程表虚线网格背景。
 *
 * 替代 GridLayout 放置 84+ 个 cell View 的方案，
 * 仅用一个 View 在 onDraw 中画虚线，大幅降低渲染开销。
 */
class GridBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 行数（节数） */
    var rowCount: Int = 12
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** 列数（天数，默认周一到周日共 7 列） */
    var columnCount: Int = 7
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** 列间距 px */
    var horizontalGap: Int = 0
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** 行间距 px（一般为 0，行高由 cellHeight 控制） */
    var verticalGap: Int = 0
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** 网格线颜色 */
    @ColorInt
    var gridColor: Int = 0x33000000
        set(value) {
            paint.color = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
        color = gridColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rowCount <= 0 || columnCount <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0f || h <= 0f) return

        val cellWidth = (w - (columnCount - 1) * horizontalGap) / columnCount
        val cellHeight = h / rowCount

        // 竖线（列分隔）
        var x = cellWidth + horizontalGap / 2f
        for (i in 1 until columnCount) {
            canvas.drawLine(x, 0f, x, h, paint)
            x += cellWidth + horizontalGap
        }

        // 横线（行分隔）
        for (i in 1 until rowCount) {
            val y = i * cellHeight + verticalGap / 2f
            canvas.drawLine(0f, y, w, y, paint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }
}
