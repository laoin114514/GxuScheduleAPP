package com.cherry.wakeupschedule.ui.component

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.ui.motion.Motion
import com.google.android.material.color.MaterialColors

/**
 * 页面状态层控制器：加载中 / 空态 / 失败 / 内容 四态的统一实现。
 *
 * 成绩查询页与考试安排页原来各有一份逐行相同的 showStatus / hideStatus /
 * setStatusHeight / loadingScrim（约 50 行 × 2），任意一页改动都会造成视觉漂移。
 * 这里收成一份，两个页面只负责提供视图与「查询按钮置灰」的回调。
 *
 * 视图约定见 `res/layout/view_empty_state.xml`：
 * ll_status 容器 + pb_loading / iv_status_icon / tv_status / tv_status_action。
 *
 * ### 动效约定
 * - 出场：状态层整体淡入 + 0.96→1，空态/失败态图标 0.8→1 回弹、文案上浮；
 * - 退场：淡出后再隐藏（同样淡出的还有 loading 遮罩），页面据此在淡出后播列表入场；
 * - 加载中：查询按钮透明度呼吸（[loadingPulseView]），收起状态层即停；
 * - 动画只做 transform，高度 / isClickable / scrim / onLoadingChanged 语义一律不变；
 *   系统关闭动画时直接落终态，不会留下透明或缩小状态。
 */
class PageStatusController(
    private val container: View,
    private val textView: TextView,
    private val loadingView: View,
    private val iconView: ImageView,
    private val actionView: TextView,
    private val listView: RecyclerView,
    /** 加载态变化回调（用于把「查询」按钮置灰 / 恢复） */
    private val onLoadingChanged: (Boolean) -> Unit = {},
    /** 加载中做透明度呼吸的按钮（如查询按钮）；null 表示不需要 */
    private val loadingPulseView: View? = null
) {

    /** 盖住列表的 loading 遮罩：与列表卡片同色的圆角块 */
    private val scrim: GradientDrawable by lazy {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * container.resources.displayMetrics.density
            setColor(
                ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(
                        container,
                        com.google.android.material.R.attr.colorSurfaceContainerLowest
                    ),
                    0xF5
                )
            )
        }
    }

    /** 空态图标在布局里是调淡的：入场回弹只动缩放，透明度回到这个基础值，不做满透明度闪烁 */
    private val iconRestAlpha: Float = iconView.alpha

    /** 本次展示内容签名：内容没变时重复 show 不重播入场（返回页面 / 重渲染不闪一下） */
    private var lastStateKey: String? = null

    /** show/hide 交替的世代号：延迟收尾的动画只在世代未变时生效，避免状态错位 */
    private var transitionGeneration = 0

    /** 加载中按钮的呼吸动画是否在跑 */
    private var pulseOn = false
    private var pulseAnimator: ValueAnimator? = null

    /** 状态层当前是否可见（页面据此在状态层淡出后再播列表入场） */
    val isShowing: Boolean get() = container.isVisible

    /**
     * 显示状态层。
     *
     * @param loading true = 转圈并盖住旧列表；false = 空态/失败态
     * @param iconRes 非加载态显示的状态图标（空态用语义图标，失败用 error 图标）
     * @param actionText / action 主动作（去绑定 / 重试），没有就不显示
     */
    fun show(
        text: String,
        loading: Boolean,
        @DrawableRes iconRes: Int? = null,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        transitionGeneration++
        val wasVisible = container.isVisible
        val stateKey = "$loading\u0000$text\u0000${iconRes ?: -1}\u0000${actionText ?: ""}"
        val replay = !wasVisible || stateKey != lastStateKey
        lastStateKey = stateKey

        // 取消上一次过渡：淡出中的状态层要立刻回到可见终态，否则会停在半透明/缩小
        container.animate().cancel()
        textView.animate().cancel()
        actionView.animate().cancel()
        iconView.animate().cancel()
        container.isVisible = true
        resetTransforms()

        loadingView.isVisible = loading
        iconView.isVisible = !loading && iconRes != null
        iconRes?.let { iconView.setImageResource(it) }
        actionView.isVisible = !loading && actionText != null && action != null
        actionView.text = actionText
        actionView.setOnClickListener { action?.invoke() }
        textView.text = text

        // 加载中铺满列表区：盖住旧数据，避免"查询中"被当成新结果；
        // 高度必须显式给——单个 match_parent 子项在 wrap_content 容器里量不出列表高度
        setHeight(if (loading && listView.height > 0) listView.height else null)
        container.isClickable = loading
        container.background = if (loading) scrim else null
        onLoadingChanged(loading)
        setLoadingPulse(loading)

        if (replay) playEntrance(loading, fromHidden = !wasVisible)
    }

    fun hide() {
        transitionGeneration++
        lastStateKey = null
        onLoadingChanged(false)
        setLoadingPulse(false)
        container.isClickable = false
        if (!container.isVisible || !Motion.enabled()) {
            // 已经隐藏 / 系统关闭动画：立即落终态，不重播淡出
            finishHide()
            return
        }
        val generation = transitionGeneration
        container.animate().cancel()
        container.animate()
            .alpha(0f)
            .scaleX(STATUS_FROM_SCALE)
            .scaleY(STATUS_FROM_SCALE)
            .setDuration(Motion.FAST)
            .setInterpolator(Motion.accelerate)
            .withEndAction {
                if (generation != transitionGeneration) return@withEndAction
                finishHide()
            }
            .start()
    }

    /** 状态层收尾：隐藏 + 清理遮罩/高度 + 复位 transform，保证下次 show 从完整可见态开始 */
    private fun finishHide() {
        container.isVisible = false
        container.background = null
        setHeight(null)
        resetTransforms()
    }

    /**
     * 状态层入场：整体淡入 + 0.96→1；空态/失败态图标 0.8→1 回弹、文案与操作按钮上浮。
     * 状态层原本可见（内容切换）时不做整层淡入，避免"闪一下"。
     */
    private fun playEntrance(loading: Boolean, fromHidden: Boolean) {
        if (!Motion.enabled()) return
        val baseDelay = if (fromHidden) {
            container.alpha = 0f
            container.scaleX = STATUS_FROM_SCALE
            container.scaleY = STATUS_FROM_SCALE
            container.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(Motion.ENTRANCE)
                .setInterpolator(Motion.standard)
                .start()
            40L
        } else {
            0L
        }

        textView.alpha = 0f
        textView.translationY = Motion.dp(textView, 6f)
        textView.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(baseDelay)
            .setDuration(Motion.ENTRANCE)
            .setInterpolator(Motion.standard)
            .start()

        // 空态 / 失败态：图标 0.8→1 回弹（每次内容切换都播，不做循环）
        if (!loading && iconView.isVisible) {
            iconView.alpha = 0f
            iconView.scaleX = ICON_FROM_SCALE
            iconView.scaleY = ICON_FROM_SCALE
            iconView.animate()
                .alpha(iconRestAlpha)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(baseDelay + 40L)
                .setDuration(Motion.ENTRANCE)
                .setInterpolator(Motion.spring)
                .start()
        }

        if (actionView.isVisible) {
            actionView.alpha = 0f
            actionView.translationY = Motion.dp(actionView, 6f)
            actionView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(baseDelay + 80L)
                .setDuration(Motion.ENTRANCE)
                .setInterpolator(Motion.standard)
                .start()
        }
    }

    /** 把状态层各部件复位到终态（系统关动画 / 动画被中断时保证可见，不留半透明） */
    private fun resetTransforms() {
        container.alpha = 1f
        container.scaleX = 1f
        container.scaleY = 1f
        textView.alpha = 1f
        textView.translationY = 0f
        actionView.alpha = 1f
        actionView.translationY = 0f
        iconView.alpha = iconRestAlpha
        iconView.scaleX = 1f
        iconView.scaleY = 1f
    }

    /**
     * 加载中查询按钮透明度呼吸；状态层收起 / 页面销毁后自动停。
     *
     * 用独立 ValueAnimator 只改 alpha：不能复用 ViewPropertyAnimator，
     * 否则会和 Motion.press 的按压缩放抢同一个 animator，把按钮卡在按下态。
     */
    private fun setLoadingPulse(active: Boolean) {
        val view = loadingPulseView ?: return
        if (active == pulseOn) return
        pulseOn = active
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (!active) {
            view.alpha = 1f
            return
        }
        if (!Motion.enabled()) {
            // 系统关动画时退回静态置灰表现
            view.alpha = PULSE_MIN_ALPHA
            return
        }
        pulseAnimator = ValueAnimator.ofFloat(1f, PULSE_MIN_ALPHA).apply {
            duration = Motion.SHORT
            interpolator = Motion.standard
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                // 页面销毁后视图脱离窗口：主动停掉，避免后台空转
                if (!view.isAttachedToWindow) {
                    anim.cancel()
                    return@addUpdateListener
                }
                view.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun setHeight(height: Int?) {
        val lp = container.layoutParams
        val target = height ?: android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height == target) return
        lp.height = target
        container.layoutParams = lp
    }

    private companion object {
        /** 状态层入场起始缩放（0.96→1，只做 transform） */
        const val STATUS_FROM_SCALE = 0.96f

        /** 空态 / 失败态图标回弹起始缩放 */
        const val ICON_FROM_SCALE = 0.8f

        /** 加载中按钮呼吸的最低透明度 */
        const val PULSE_MIN_ALPHA = 0.55f
    }
}
