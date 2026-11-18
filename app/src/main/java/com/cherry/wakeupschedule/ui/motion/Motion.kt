package com.cherry.wakeupschedule.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 全 App 统一的动效令牌与常用动效封装。
 *
 * 目标：让"动效"从一个页面一个手感，变成一套可复用的语言——
 * - 时长只有四档（120 / 180 / 240 / 320ms），入场统一 260ms；
 * - 缓动只有三条曲线（标准 / 强调减速 / 回弹）；
 * - 所有动效都尊重系统"关闭动画"设置（开发者选项动画缩放 0、无障碍减少动态效果）；
 * - 交互反馈只做 transform（translation / scale / alpha），不触发 requestLayout，
 *   避免列表里的动效造成掉帧。
 *
 * 使用约定：
 * - 按压反馈统一用 [press]；
 * - 入场统一用 [enter] / [stagger] / [staggerRecycler]，同一屏只播一次；
 * - 数字变化用 [countUp]，避免统计卡从 0 直接跳到结果；
 * - 互斥内容切换用 [crossFade]，不要瞬间切 visibility。
 */
object Motion {

    // ── 时长（ms）──────────────────────────────────────────────
    /** 按压反馈：按下时长（要短，按下即响应） */
    const val PRESS_DOWN = 90L
    /** 按压反馈：抬手回弹时长（带一点回弹余韵） */
    const val PRESS_UP = 160L
    /** 小状态切换：图标旋转、选中态、透明度 */
    const val FAST = 120L
    /** 短切换：chip 脉冲、错误提示 */
    const val SHORT = 180L
    /** 标准切换：页面内互斥内容、弹层遮罩 */
    const val MEDIUM = 240L
    /** 大表面：底部弹层、弹窗入场 */
    const val LONG = 320L
    /** 列表 / 页面内容入场基准时长 */
    const val ENTRANCE = 260L
    /** 错峰步进：列表第 i 项延迟 = min(i, STAGGER_MAX_ITEMS) * STAGGER_STEP */
    const val STAGGER_STEP = 34L
    /** 错峰最多叠加到第几项，避免长列表最后一项等 1s 才出现 */
    const val STAGGER_MAX_ITEMS = 10
    /** 数字滚动时长 */
    const val COUNT_UP = 560L

    // ── 缓动（全部可安全复用，Interpolator 无状态）────────────────
    /** M3 标准曲线：快起慢收，绝大多数状态切换用它 */
    val standard: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** M3 强调减速：大表面入场（弹层 / 对话框），收尾更稳 */
    val emphasizedDecelerate: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** 回弹：用于"确认感"强的动作（页签胶囊、回到本周、选中脉冲），幅度克制 */
    val spring: Interpolator = OvershootInterpolator(1.15f)

    /** 退出：加速离开，不做回弹 */
    val accelerate: Interpolator = PathInterpolator(0.3f, 0f, 1f, 1f)

    private val countUpAnimators = WeakHashMap<TextView, ValueAnimator>()

    /** 系统是否允许播放动画（开发者选项动画缩放 = 0 / 无障碍减少动态效果时返回 false） */
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    // ── 按压反馈 ────────────────────────────────────────────────

    /**
     * 给 View 挂上统一的按压缩放反馈（按下 [pressedScale]，抬手回弹到 1）。
     *
     * 返回 OnTouchListener 以便链式写法；内部返回 false，不影响 OnClickListener。
     * 必须保证 View 没有被其它逻辑同时操作 scaleX/scaleY。
     */
    fun press(view: View, pressedScale: Float = 0.96f): View.OnTouchListener {
        val listener = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (enabled()) {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(pressedScale)
                            .scaleY(pressedScale)
                            .setDuration(PRESS_DOWN)
                            .setInterpolator(standard)
                            .start()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (enabled()) {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(PRESS_UP)
                            .setInterpolator(spring)
                            .start()
                    } else {
                        v.scaleX = 1f
                        v.scaleY = 1f
                    }
                }
            }
            false
        }
        view.setOnTouchListener(listener)
        return listener
    }

    /** 把 View 的 scale 立即复位（列表复用 / 动画中断时兜底） */
    fun resetScale(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }

    // ── 入场 ────────────────────────────────────────────────────

    /**
     * 单 View 入场：淡入 + 从 [fromY]（dp）平移归位 + 可选从 [fromScale] 缩放归位。
     *
     * @param alphaOnly 只做透明度（大块内容用它，避免缩放造成文字发虚）
     */
    fun enter(
        view: View,
        delay: Long = 0L,
        duration: Long = ENTRANCE,
        fromY: Float = 10f,
        fromScale: Float = 1f,
        alphaOnly: Boolean = false,
        onEnd: (() -> Unit)? = null
    ) {
        if (!enabled()) {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            onEnd?.invoke()
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        if (!alphaOnly) {
            view.translationY = dp(view, fromY)
            view.scaleX = fromScale
            view.scaleY = fromScale
        } else {
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(standard)
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            .start()
    }

    /**
     * 批量错峰入场：第 i 个元素延迟 `min(i, STAGGER_MAX_ITEMS) * step` 毫秒。
     * 超过上限的元素共用同一延迟，避免长列表尾巴等太久。
     */
    fun stagger(
        views: Iterable<View?>,
        step: Long = STAGGER_STEP,
        duration: Long = ENTRANCE,
        fromY: Float = 10f,
        fromScale: Float = 1f,
        alphaOnly: Boolean = false
    ) {
        views.filterNotNull().forEachIndexed { index, view ->
            val delay = index.coerceAtMost(STAGGER_MAX_ITEMS) * step
            enter(
                view,
                delay = delay,
                duration = duration,
                fromY = fromY,
                fromScale = fromScale,
                alphaOnly = alphaOnly
            )
        }
    }

    /**
     * RecyclerView 首屏可见项错峰入场。
     *
     * 只应在一屏数据首次出现时调用（例如数据加载完成的回调里），
     * 不要挂在 onBindViewHolder 上——滚动复用会反复播放入场动画。
     * 节流到下一帧执行，等 RecyclerView 完成本次布局。
     */
    fun staggerRecycler(
        recyclerView: RecyclerView,
        step: Long = STAGGER_STEP,
        duration: Long = ENTRANCE,
        fromY: Float = 10f,
        fromScale: Float = 1f
    ) {
        if (!enabled()) return
        recyclerView.post {
            if (!recyclerView.isAttachedToWindow) return@post
            val visible = recyclerView.childCount
            for (index in 0 until visible) {
                val child = recyclerView.getChildAt(index) ?: continue
                val delay = index.coerceAtMost(STAGGER_MAX_ITEMS) * step
                // fromY / fromScale 同时生效：传 0 且 scale=1 时才退化为纯淡入（避免无意义位移）
                enter(
                    child,
                    delay = delay,
                    duration = duration,
                    fromY = fromY,
                    fromScale = fromScale,
                    alphaOnly = fromY == 0f && fromScale == 1f
                )
            }
        }
    }

    // ── 互斥内容切换 ────────────────────────────────────────────

    /**
     * 交叉淡入：from 淡出（轻微缩小）后隐藏，to 同步淡入（从 [fromScale] 放大归位）。
     * 用于「周课表 ⇄ 总课表」、筛选结果替换这类同位置内容切换。
     */
    fun crossFade(
        from: View?,
        to: View,
        duration: Long = MEDIUM,
        outFromScale: Float = 1f,
        inFromScale: Float = 0.98f
    ) {
        if (!enabled()) {
            from?.apply {
                animate().cancel()
                alpha = 0f
                visibility = View.GONE
            }
            to.animate().cancel()
            to.alpha = 1f
            to.scaleX = 1f
            to.scaleY = 1f
            to.visibility = View.VISIBLE
            return
        }
        from?.let {
            it.animate().cancel()
            it.animate()
                .alpha(0f)
                .scaleX(outFromScale)
                .scaleY(outFromScale)
                .setDuration(duration / 2)
                .setInterpolator(accelerate)
                .withEndAction {
                    it.visibility = View.GONE
                    it.scaleX = 1f
                    it.scaleY = 1f
                }
                .start()
        }
        to.animate().cancel()
        to.alpha = 0f
        to.scaleX = inFromScale
        to.scaleY = inFromScale
        to.visibility = View.VISIBLE
        to.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(duration / 4)
            .setDuration(duration)
            .setInterpolator(standard)
            .start()
    }

    // ── 出现 / 消失（±缩放，用于小控件）──────────────────────────

    /** 小控件出现/消失：出现时缩放 + 淡入，消失时缩放 + 淡出（不占布局位置，用 INVISIBLE） */
    fun showPop(view: View, show: Boolean, duration: Long = SHORT) {
        if (!enabled()) {
            view.visibility = if (show) View.VISIBLE else View.INVISIBLE
            view.alpha = if (show) 1f else 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(duration)
                .setInterpolator(spring)
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(duration / 2)
                .setInterpolator(accelerate)
                .withEndAction {
                    view.visibility = View.INVISIBLE
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
                .start()
        }
    }

    // ── 数字滚动 ────────────────────────────────────────────────

    /**
     * 数字从 [from] 滚动到 [target]，每帧调用 [format] 写文本。
     *
     * 对同一个 TextView 重复调用会取消上一次滚动；系统关动画时直接落到终值。
     */
    fun countUp(
        textView: TextView,
        target: Float,
        duration: Long = COUNT_UP,
        from: Float = 0f,
        format: (Float) -> String
    ) {
        countUpAnimators.remove(textView)?.cancel()
        if (!enabled() || duration <= 0L) {
            textView.text = format(target)
            return
        }
        val animator = ValueAnimator.ofFloat(from, target).apply {
            this.duration = duration
            interpolator = standard
            addUpdateListener { anim ->
                textView.text = format(anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    textView.text = format(target)
                }
            })
            start()
        }
        countUpAnimators[textView] = animator
    }

    // ── 小工具 ──────────────────────────────────────────────────

    /** 选中脉冲：先放大到 [scale] 再回弹到 1，给"点到了"的确认感 */
    fun pulse(view: View, scale: Float = 1.05f, duration: Long = SHORT) {
        if (!enabled()) return
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration / 2)
            .setInterpolator(emphasizedDecelerate)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration / 2)
                    .setInterpolator(spring)
                    .start()
            }
            .start()
    }

    /** 图标转动反馈（刷新按钮等）：转一圈 */
    fun spinOnce(view: View, duration: Long = MEDIUM) {
        if (!enabled()) return
        view.animate().cancel()
        view.rotation = 0f
        view.animate()
            .rotation(360f)
            .setDuration(duration)
            .setInterpolator(standard)
            .withEndAction { view.rotation = 0f }
            .start()
    }

    /** 高度展开/收起动画（避免 visibility 瞬切） */
    fun resizeHeight(
        view: View,
        from: Int,
        to: Int,
        duration: Long = MEDIUM,
        onEnd: (() -> Unit)? = null
    ) {
        heightAnimators[view]?.cancel()
        if (!enabled() || from == to) {
            applyHeight(view, to)
            onEnd?.invoke()
            return
        }
        val animator = ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            interpolator = standard
            addUpdateListener { anim -> applyHeight(view, anim.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd?.invoke() ?: Unit
            })
            start()
        }
        heightAnimators[view] = animator
    }

    private fun applyHeight(view: View, height: Int) {
        val lp = view.layoutParams ?: return
        if (lp.height == height) return
        lp.height = height
        view.layoutParams = lp
    }

    private val heightAnimators = WeakHashMap<View, ValueAnimator>()

    /** 取 dp 整数值（用于 layout 参数） */
    fun dpInt(context: Context, value: Float): Int = dp(context, value).roundToInt()
}
