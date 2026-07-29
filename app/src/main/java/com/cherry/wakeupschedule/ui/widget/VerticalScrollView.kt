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
 * 跟之前的区别：
 * - ~~ACTION_DOWN 立即 block 父 View~~ → 会造成水平滑动 12px 死区
 * - ACTION_MOVE 仅当确认是竖直滑动（dy > dx + 超过 touchSlop）才 block 父 View
 *
 * 这样水平滑动零延迟，斜向/竖直滑动也不会被 ViewPager2 误拦截。
 */
class VerticalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var locked = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                locked = false
                // 不再在 DOWN 时 block 父 View——让 ViewPager2 也能响应
            }

            MotionEvent.ACTION_MOVE -> {
                if (locked) return super.onInterceptTouchEvent(ev)

                val dx = abs(ev.x - startX)
                val dy = abs(ev.y - startY)

                if (dy > dx && dy > touchSlop) {
                    // 确认竖直滑动 → 阻止 ViewPager2 拦截
                    locked = true
                    parent.requestDisallowInterceptTouchEvent(true)
                } else if (dx > dy && dx > touchSlop) {
                    // 确认水平滑动 → 放行给 ViewPager2
                    locked = true
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                locked = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
