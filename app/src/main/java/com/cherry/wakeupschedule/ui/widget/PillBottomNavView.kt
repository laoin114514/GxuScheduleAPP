package com.cherry.wakeupschedule.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import com.cherry.wakeupschedule.R
import com.google.android.material.color.MaterialColors
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes

/**
 * 设计稿风格的底部导航：激活页签为「圆形色块 + 白色图标 + 主色文字」，
 * 未激活为灰图标 + 灰色文字。
 *
 * 动效升级（v2）：不再让三个页签各自缩放自己的圆色块，而是**一个共享的胶囊指示器**
 * 在页签之间弹性滑动——切换时能明确看到"选中态从 A 移到 B"，而不是"旧的消失、新的冒出"。
 * 指示器的落点按每个页签图标容器的窗口坐标计算，字体缩放 / 屏宽变化后会自动重算。
 *
 * 三个页签固定（课表 / 工具 / 我的），id 与 nav_graph 一致，
 * 颜色全部走主题属性，自动适配配色切换与深色模式。
 */
class PillBottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs, 0) {

    private data class Tab(val id: Int, val title: String, val iconRes: Int)

    private val tabs = listOf(
        Tab(R.id.nav_schedule, context.getString(R.string.nav_schedule), R.drawable.ic_mtrl_calendar_month),
        Tab(R.id.nav_tools, context.getString(R.string.nav_tools), R.drawable.ic_mtrl_grid_view),
        Tab(R.id.nav_profile, context.getString(R.string.nav_profile), R.drawable.ic_mtrl_person),
    )

    private inner class Item(val tab: Tab) {
        val cell: FrameLayout
        val holder: FrameLayout
        val icon: ImageView
        val label: TextView

        /** 「课表」页签右上角的小角标（切换周课表 / 总课表），其余页签为 null */
        val badge: ImageView?

        /** 「我的」页签的未读更新小红点（有新版本时由外部点亮），其余页签为 null */
        val updateDot: ImageView?

        init {
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            icon = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                setImageResource(tab.iconRes)
            }

            // 「我的」页签右上角红点：更新提示与「关于应用」行保持同步（见 setUpdateHint）
            val updateDotView = if (tab.id == R.id.nav_profile) {
                ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(9), dp(9), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(1)
                        marginEnd = dp(1)
                    }
                    setImageResource(R.drawable.dot_update_hint)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    visibility = View.GONE
                }
            } else {
                null
            }
            updateDot = updateDotView

            holder = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                addView(icon)
                updateDotView?.let { addView(it) }
            }

            label = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = tab.title
                setTextSizeRes(R.dimen.text_label)
                includeFontPadding = false
            }

            // 「课表」页签：在「课表」字样右侧紧贴一个小的循环箭头，指示当前是周课表还是总课表。
            // 它只是指示器、不接收点击 —— 再次点击「课表」页签本身即完成切换（见 MainActivity）。
            val badgeView = if (tab.id == R.id.nav_schedule) {
                ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                        marginStart = dp(2)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(R.drawable.ic_mtrl_swap_horiz)
                    setColorFilter(
                        MaterialColors.getColor(
                            this@PillBottomNavView,
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    visibility = View.GONE
                }
            } else {
                null
            }
            badge = badgeView

            // 文字行：「课表」与循环箭头同行居中（箭头紧贴字样右侧）；其余页签只有文字
            val labelRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
                addView(label)
                if (badgeView != null) addView(badgeView)
            }

            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(holder)
                addView(labelRow)
            }

            cell = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(column, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ))
                contentDescription = tab.title
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    this@PillBottomNavView.onItemSelected?.invoke(tab.id)
                }
                // 按压缩放反馈，与课程卡片一致（复用全局令牌，避免各页手写参数不同）
                setOnTouchListener(Motion.press(this, pressedScale = 0.94f))
            }
        }
    }

    private val items = tabs.map { Item(it) }
    private var selectedId = 0

    private val row: LinearLayout
    private val indicator: View

    /** 指示器是否已按首帧布局落位（首帧直接放上去，不播滑动动画） */
    private var indicatorPlaced = false

    /** 「我的」页签红点当前是否点亮（避免重复播放入场动画） */
    private var updateHintShown = false

    /** 页签被点击时回调（含重复点击当前页签；再次点击「课表」即切换视图模式） */
    var onItemSelected: ((Int) -> Unit)? = null

    init {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 指示器先加（绘制在底层），导航内容后加（图标/文字盖在色块上）
        indicator = View(context).apply {
            layoutParams = LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
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
        addView(indicator)

        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        items.forEach { row.addView(it.cell) }
        addView(row)

        setPadding(0, dp(8), 0, dp(6))
        minimumHeight = dp(64)

        // 默认选中第一个页签（不播动画，MainActivity 随后会按目的地同步）
        selectInternal(tabs[0].id, animate = false)
        syncBadgeVisibility()
    }

    /** 静默同步选中态（导航目的地变化时由外部调用） */
    fun select(id: Int) = selectInternal(id, animate = selectedId != id)

    /**
     * 同步课表视图模式到角标外观：总课表激活时用主色，周课表时用普通图标色。
     * 角标本身是纯装饰（不接收点击、不参与无障碍），模式语义写进「课表」页签的描述里。
     */
    fun setScheduleOverview(overview: Boolean) {
        val item = items.firstOrNull { it.tab.id == R.id.nav_schedule } ?: return
        item.badge?.setColorFilter(
            MaterialColors.getColor(
                this,
                if (overview) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
        item.cell.contentDescription = context.getString(
            if (overview) R.string.nav_schedule_desc_overview else R.string.nav_schedule_desc_week
        )
    }

    /**
     * 底部导航「我的」页签的未读更新小红点：与「我的 → 关于应用」行右侧的红点同源
     * （`SettingsManager.hasNewUpdateHint()`），有新版本时在底部导航也能被看到。
     *
     * 红点是纯提示、不接收点击；只在状态变化时点亮/熄灭，入场用短促的淡入+放大，
     * 不做循环闪烁。无障碍描述同步带上「有新版本」。
     */
    fun setUpdateHint(show: Boolean) {
        val item = items.firstOrNull { it.tab.id == R.id.nav_profile } ?: return
        val dot = item.updateDot ?: return
        if (show == updateHintShown) return
        updateHintShown = show
        if (show) {
            dot.animate().cancel()
            dot.visibility = View.VISIBLE
            if (Motion.enabled()) {
                dot.alpha = 0f
                dot.scaleX = 0.6f
                dot.scaleY = 0.6f
                dot.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(Motion.SHORT)
                    .setInterpolator(Motion.standard)
                    .start()
            } else {
                dot.alpha = 1f
                dot.scaleX = 1f
                dot.scaleY = 1f
            }
        } else {
            dot.animate().cancel()
            dot.visibility = View.GONE
        }
        item.cell.contentDescription = context.getString(
            if (show) R.string.nav_profile_desc_update else R.string.nav_profile
        )
    }

    private fun selectInternal(id: Int, animate: Boolean) {
        // 重复选中同一页签时也要校准角标与指示器（冷启动首次同步走的就是这条分支）
        if (id != 0 && id == selectedId) {
            syncBadgeVisibility()
            if (indicatorPlaced) placeIndicator(selectId = id, animate = false)
            return
        }
        selectedId = id
        items.forEach { bindItem(it, it.tab.id == id, animate) }
        syncBadgeVisibility()
        if (indicatorPlaced) placeIndicator(selectId = id, animate = animate)
    }

    /** 转换角标只在「课表」页签激活时出现，避免在工具/我的页误触 */
    private fun syncBadgeVisibility() {
        val badge = items.firstOrNull { it.tab.id == R.id.nav_schedule }?.badge ?: return
        if (selectedId == R.id.nav_schedule) {
            if (badge.visibility != View.VISIBLE) {
                badge.alpha = 0f
                badge.visibility = View.VISIBLE
                if (Motion.enabled()) {
                    badge.animate().alpha(1f).setDuration(Motion.SHORT).start()
                } else {
                    badge.alpha = 1f
                }
            }
        } else if (badge.visibility == View.VISIBLE) {
            badge.animate().cancel()
            badge.visibility = View.GONE
        }
    }

    private fun bindItem(item: Item, active: Boolean, animate: Boolean) {
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
        val onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

        item.icon.setColorFilter(if (active) onPrimary else onSurfaceVariant)
        item.label.setTextColor(if (active) primary else onSurfaceVariant)
        item.label.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        if (!animate || !Motion.enabled()) {
            item.icon.animate().cancel()
            item.icon.scaleX = 1f
            item.icon.scaleY = 1f
            return
        }
        // 激活页签的图标轻微 pop：颜色变化是瞬时的，加一点缩放让"选中"有落点感
        if (active) {
            item.icon.animate().cancel()
            item.icon.scaleX = 0.86f
            item.icon.scaleY = 0.86f
            item.icon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(Motion.MEDIUM)
                .setInterpolator(OvershootInterpolator(1.30f))
                .start()
        }
    }

    // ── 共享胶囊指示器定位 ─────────────────────────────────────

    /**
     * 把指示器移动到 [selectId] 页签图标容器的正下方。
     *
     * 用窗口坐标做差而不是累加 left/top：文字缩放、内边距、inset 都会改变布局，
     * 每次布局后重算最稳；动画只改变 translation（走 View.x/y 属性）。
     */
    private fun placeIndicator(selectId: Int, animate: Boolean): Boolean {
        val item = items.firstOrNull { it.tab.id == selectId } ?: return false
        val holder = item.holder
        if (holder.width == 0) return false

        val holderLoc = IntArray(2)
        holder.getLocationInWindow(holderLoc)
        val selfLoc = IntArray(2)
        getLocationInWindow(selfLoc)
        // 尚未 attach 到窗口时窗口坐标不可用，等下一帧布局完成再放
        if (holderLoc[0] == 0 && holderLoc[1] == 0) return false

        val targetX = (holderLoc[0] - selfLoc[0]).toFloat()
        val targetY = (holderLoc[1] - selfLoc[1]).toFloat()

        val canAnimate = animate && indicatorPlaced && Motion.enabled()
        if (!canAnimate) {
            indicator.animate().cancel()
            indicator.x = targetX
            indicator.y = targetY
            indicator.alpha = 1f
            indicator.scaleX = 1f
            indicator.scaleY = 1f
            return true
        }

        // 滑动 + 轻微的横向拉伸（移动中的胶囊像被"带"过去，而不是闪现）
        indicator.animate().cancel()
        indicator.animate()
            .x(targetX)
            .y(targetY)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(Motion.MEDIUM)
            .setInterpolator(OvershootInterpolator(1.10f))
            .start()
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 首帧：等子 View 布局完成后把指示器直接放到当前页签（不播滑动）；
        // 之后的布局变化（字体缩放/横屏）只做无声重定位，避免"回来时指示器从别处飞过来"
        if (!indicatorPlaced) {
            // 首帧可能还没拿到窗口坐标：放不进去就保持未落位，下一帧布局再试
            indicatorPlaced = placeIndicator(selectedId, animate = false)
        } else if (changed) {
            placeIndicator(selectedId, animate = false)
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
