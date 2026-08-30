package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import com.cherry.wakeupschedule.R
import com.google.android.material.color.MaterialColors

/**
 * 设计稿风格的底部导航：激活页签为「圆形色块 + 白色图标 + 主色文字」，
 * 未激活为灰图标 + 灰文字。切换时圆形色块以回弹动画缩放出现。
 *
 * 三个页签固定（课表 / 工具 / 我的），id 与 nav_graph 一致，
 * 颜色全部走主题属性，自动适配配色切换与深色模式。
 */
class PillBottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs, 0) {

    private data class Tab(val id: Int, val title: String, val iconRes: Int)

    private val tabs = listOf(
        Tab(R.id.nav_schedule, "课表", R.drawable.ic_mtrl_calendar_month),
        Tab(R.id.nav_tools, "工具", R.drawable.ic_mtrl_grid_view),
        Tab(R.id.nav_profile, "我的", R.drawable.ic_mtrl_person),
    )

    private inner class Item(val tab: Tab) {
        val cell: FrameLayout
        val circle: View
        val icon: ImageView
        val label: TextView

        init {
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            circle = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        MaterialColors.getColor(
                            this@PillBottomNavView,
                            com.google.android.material.R.attr.colorPrimary
                        )
                    )
                }
                alpha = 0f
                scaleX = 0.4f
                scaleY = 0.4f
            }

            icon = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                setImageResource(tab.iconRes)
            }

            val holder = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                addView(circle)
                addView(icon)
            }

            label = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
                text = tab.title
                textSize = 10f
                includeFontPadding = false
            }

            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                addView(holder)
                addView(label)
            }

            cell = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(column, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ))
                contentDescription = tab.title
                setOnClickListener { this@PillBottomNavView.onItemSelected?.invoke(tab.id) }
                // 按压缩放反馈，与课程卡片一致
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }
                    false
                }
            }
        }
    }

    private val items = tabs.map { Item(it) }
    private var selectedId = 0

    /** 页签被点击时回调（含重复点击当前页签） */
    var onItemSelected: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        val density = resources.displayMetrics.density
        val padTop = (8 * density).toInt()
        val padBottom = (6 * density).toInt()
        setPadding(0, padTop, 0, padBottom)
        minimumHeight = (64 * density).toInt()
        items.forEach { addView(it.cell) }
        // 默认选中第一个页签（不播动画，MainActivity 随后会按目的地同步）
        selectInternal(tabs[0].id, animate = false)
    }

    /** 静默同步选中态（导航目的地变化时由外部调用） */
    fun select(id: Int) = selectInternal(id, animate = selectedId != id)

    private fun selectInternal(id: Int, animate: Boolean) {
        if (id != 0 && id == selectedId) return
        selectedId = id
        items.forEach { bindItem(it, it.tab.id == id, animate) }
    }

    private fun bindItem(item: Item, active: Boolean, animate: Boolean) {
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
        val onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

        item.icon.setColorFilter(if (active) onPrimary else onSurfaceVariant)
        item.label.setTextColor(if (active) primary else onSurfaceVariant)
        item.label.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val anim = item.circle.animate()
        if (active) {
            if (animate) {
                anim.scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            } else {
                item.circle.animate().cancel()
                item.circle.scaleX = 1f
                item.circle.scaleY = 1f
                item.circle.alpha = 1f
            }
        } else {
            if (animate) {
                anim.alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(160).start()
            } else {
                item.circle.animate().cancel()
                item.circle.scaleX = 0.4f
                item.circle.scaleY = 0.4f
                item.circle.alpha = 0f
            }
        }
    }

    /** 吸底避让手势条；键盘弹出时整条隐藏，避免导航板被顶到键盘上方遮住内容 */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        val bars = compat.getInsets(WindowInsetsCompat.Type.navigationBars())
        val density = resources.displayMetrics.density
        val bottom = bars.bottom.coerceAtLeast((4 * density).toInt())
        setPadding(paddingLeft, paddingTop, paddingRight, bottom)
        // 不消费，保持既有页面的 inset 派发行为不变
        return super.onApplyWindowInsets(insets)
    }

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        val target = if (compat.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
        if (visibility != target) visibility = target
        return super.dispatchApplyWindowInsets(insets)
    }
}
