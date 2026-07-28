package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 竖直方向优先的 ScrollView，解决 ViewPager2 嵌套时的触摸冲突。
 *
 * 策略：
 * - 手势方向未确定时：先禁止父 View（ViewPager2）拦截
 * - 确定水平方向：放行父 View 拦截，让 ViewPager2 处理左右滑动
 * - 确定竖直方向：继续禁止父 View 拦截，由 ScrollView 处理上下滚动
 *
 * 解决：课表上下滑动容易被 ViewPager2 误判为左右滑动的问题。
 */
class VerticalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var axisLocked = false
    private var isVertical = false
    private var lastAction = MotionEvent.ACTION_DOWN

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                axisLocked = false
                isVertical = false
                lastAction = MotionEvent.ACTION_DOWN
                // Don't let ViewPager2 steal before we know the axis
                parent.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!axisLocked) {
                    val dx = abs(ev.x - startX)
                    val dy = abs(ev.y - startY)
                    val threshold = 12f // slightly above system touch slop
                    if (dx > threshold || dy > threshold) {
                        axisLocked = true
                        isVertical = dy > dx
                    }
                }
                if (axisLocked) {
                    // Vertical → block parent; horizontal → release to ViewPager2
                    parent.requestDisallowInterceptTouchEvent(isVertical)
                } else {
                    // Still ambiguous, keep blocking parent
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                axisLocked = false
                isVertical = false
                lastAction = ev.actionMasked
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    // Also disallow parent intercept during direct touch handling (when we are scrolling)
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!axisLocked) {
                    val dx = abs(ev.x - startX)
                    val dy = abs(ev.y - startY)
                    if (dx > 12f || dy > 12f) {
                        axisLocked = true
                        isVertical = dy > dx
                    }
                }
                parent.requestDisallowInterceptTouchEvent(axisLocked && isVertical)
            }
        }
        return super.onTouchEvent(ev)
    }
}
