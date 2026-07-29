package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 竖直方向优先的 ScrollView，解决 ViewPager2 嵌套时的触摸冲突。
 *
 * 策略：
 * - ACTION_DOWN：不做任何阻止——让 RecyclerView 正常收到 DOWN 事件初始化触摸追踪
 * - ACTION_MOVE：一旦确认是竖滑（dy > dx 且 dy > touchSlop），立即 block ViewPager2
 * - 横滑：不干预，让 ViewPager2 自然接管
 *
 * 注意：不能在下 ACTION_DOWN 时就 block，否则 RecyclerView 的 onInterceptTouchEvent
 * 永远收不到 DOWN，后续放行也失效，导致横滑完全不可用。
 */
class VerticalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var blocked = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                blocked = false
                // 不 block——让 RecyclerView 也能收到 DOWN，正常初始化触摸追踪
            }

            MotionEvent.ACTION_MOVE -> {
                if (blocked) return super.onInterceptTouchEvent(ev)

                val dx = abs(ev.x - startX)
                val dy = abs(ev.y - startY)

                // 确认竖滑（dy > dx）时立即 block ViewPager2
                if (dy > dx && dy > touchSlop) {
                    blocked = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                // 横滑：不干预，ViewPager2 自行接管
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                blocked = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
