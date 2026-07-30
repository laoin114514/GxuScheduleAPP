package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.ColorUtils

class OverlapBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        val density = resources.displayMetrics.density
        val size = (20 * density).toInt()
        setWidth(size)
        setHeight(size)
        gravity = Gravity.CENTER
        textSize = 9f
        setTextColor(Color.WHITE)
        setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, 192))
        isClickable = true
        isFocusable = true
    }

    fun setCount(count: Int) {
        text = "+$count"
    }
}
