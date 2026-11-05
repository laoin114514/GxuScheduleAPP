package com.cherry.wakeupschedule.ui.feedback

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.animation.LayoutTransition
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.R
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * 应用内提示的宿主与调度中心。
 *
 * 设计要点：
 * - **单一容器**：整屏透明的 [ToastContainer] 挂到当前前台 Activity 的 `android.R.id.content`，
 *   因此提示卡是应用窗口的一部分 —— 退出/切后台后不会再像系统 Toast 一样悬浮在桌面上。
 * - **页面切换重画**：Activity 实例变化（导航、主题/字号重建）时按新主题重画在飞的卡片，
 *   所以"绑定成功！"后立刻 `finish()` 也能在新页面上看到提示。
 * - **无宿主不丢状态**：应用在后台时提示进 `pending` 队列（[ToastQueuePolicy.PENDING_TTL_MS] 内有效），
 *   等下一个页面 resume 时按原顺序补发并参与合并。
 */
internal object ToastManager {

    private const val TAG = "AppToast"

    /** 页面切换重画卡片时的最短剩余展示时长 */
    private const val MIN_REPLAY_MS = 600L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val policy = ToastQueuePolicy()

    private val pending = ArrayDeque<PendingRequest>()
    private val views = LinkedHashMap<Long, ToastView>()
    private val timers = HashMap<Long, Runnable>()
    private val dismissingIds = HashSet<Long>()

    private var hostRef: WeakReference<Activity>? = null
    private var container: ToastContainer? = null
    private var nextId = 1L

    /**
     * 提示卡可承载的窗口栈：栈底是 Activity 的 `android.R.id.content`，栈顶是当前最上层窗口。
     *
     * 本项目所有弹层（课表菜单、课程详情、外观面板、学期滚轮、StyledDialog、AlertDialog…）
     * 都是独立 Dialog 窗口 —— 挂在 Activity content 里的 View 永远位于它们之下，会被弹层
     * 遮罩压暗、或被弹层内容盖住（即 docs/UI-优化方案.md 里的「Toast 暗区」P0）。
     * 所以弹层打开时把同一个容器搬进弹层自己的 DecorView（成为窗口内最上层 View），
     * 提示卡就渲染在弹层之上；弹层关闭后再搬回下一个宿主。
     */
    private class WindowHost(
        val root: ViewGroup,
        val isDialog: Boolean,
    )

    private val hosts = ArrayList<WindowHost>()

    /** 弹层先于 Activity 宿主就绪时（如恢复弹窗在 onCreate 里弹出）暂存 DecorView，等宿主就绪补挂 */
    private val pendingDialogDecors = ArrayList<WeakReference<View>>()

    private class PendingRequest(
        val type: AppToast.Type,
        val message: String,
        val baseDurationMs: Long,
        val groupKey: String?,
        val createdAtMs: Long,
    )

    // ── 宿主生命周期（App 的 ActivityLifecycleCallbacks 与弹层注册回调）─────

    fun onHostResumed(activity: Activity) {
        if (hostRef?.get() === activity && container != null) {
            // 同一页面从后台回来（如被系统弹窗/桌面短暂压住后焦点返还）：
            // 容器还挂着无需重建，但后台期间暂存的提示要在此时补发
            pruneDetachedDialogHosts()
            flushPending()
            return
        }
        detachContainer()
        hosts.clear()
        hostRef = WeakReference(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        hosts.add(WindowHost(content, isDialog = false))
        // 弹层先于页面就绪的极端场景（启动即弹恢复框）：补挂到栈顶
        adoptPendingDialogHosts(activity)

        val host = ToastContainer(activity)
        container = host
        // 栈顶若有已打开的弹层，容器直接挂到弹层窗口，避免先落页面再搬的闪动
        attachContainerToTopHost()

        // 按新主题重画在飞的卡片（不播入场动画，避免页面切换时闪一下）
        val now = SystemClock.uptimeMillis()
        policy.visible.toList()
            .filter { it.id !in dismissingIds }
            .forEach { renderCard(it, animate = false) }
        policy.drain(now).forEach { renderCard(it, animate = true) }
        flushPending()
    }

    fun onHostDestroyed(activity: Activity) {
        if (hostRef?.get() !== activity) return
        detachContainer()
        hostRef = null
        hosts.clear()
        pendingDialogDecors.clear()
    }

    /** 弹层窗口显示（DecorView attach）：压入宿主栈并把容器搬到该窗口 */
    fun onDialogAttached(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        val activity = hostRef?.get()
        val dialogActivity = dialog.context.findActivity()
        if (activity == null || dialogActivity !== activity) {
            // 页面宿主还没就绪（或弹层属于别的页面）：先记下，等 onHostResumed 补挂
            rememberPendingDialog(decor)
            return
        }
        if (hosts.any { it.root === decor }) return
        hosts.add(WindowHost(decor, isDialog = true))
        attachContainerToTopHost()
    }

    /** 弹层窗口关闭（DecorView detach）：弹出宿主栈并把容器搬回下一个宿主 */
    fun onDialogDetached(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        pendingDialogDecors.removeAll { it.get() === decor }
        if (hosts.removeAll { it.root === decor }) attachContainerToTopHost()
    }

    private fun rememberPendingDialog(decor: View) {
        prunePendingDialogDecors()
        if (pendingDialogDecors.none { it.get() === decor }) {
            pendingDialogDecors.add(WeakReference(decor))
        }
    }

    private fun adoptPendingDialogHosts(activity: Activity) {
        prunePendingDialogDecors()
        val alive = pendingDialogDecors.mapNotNull { it.get() }
            .filter { it.isAttachedToWindow && it.context.findActivity() === activity }
        pendingDialogDecors.clear()
        alive.forEach { decor ->
            if (decor is ViewGroup && hosts.none { it.root === decor }) {
                hosts.add(WindowHost(decor, isDialog = true))
            }
        }
    }

    private fun prunePendingDialogDecors() {
        pendingDialogDecors.removeAll { ref ->
            val decor = ref.get()
            decor == null || (!decor.isAttachedToWindow && decor.parent == null)
        }
    }

    /**
     * 兜底清理：弹层已关闭却没收到 detach（系统回收、异常路径）时，把失效宿主移出栈，
     * 避免后续提示一直被渲染进已经不存在的窗口。
     */
    private fun pruneDetachedDialogHosts() {
        if (hosts.size <= 1) return
        val removed = hosts.removeAll { it.isDialog && !it.root.isAttachedToWindow }
        if (removed) attachContainerToTopHost()
    }

    /** 把容器挂到当前栈顶宿主：Activity content 或最上层弹层的 DecorView */
    private fun attachContainerToTopHost() {
        val host = container ?: return
        val target = hosts.lastOrNull() ?: return
        if (host.parent === target.root) return
        (host.parent as? ViewGroup)?.removeView(host)
        target.root.addView(
            host,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun detachContainer() {
        val host = container ?: return
        (host.parent as? ViewGroup)?.removeView(host)
        container = null
        views.values.forEach { it.stopProgress() }
        views.clear()
    }

    // ── 提交 ─────────────────────────────────────────────

    fun submit(
        context: Context,
        type: AppToast.Type,
        message: String,
        duration: AppToast.Duration,
        groupKey: String?,
    ) {
        val now = SystemClock.uptimeMillis()
        val host = resolveHost(context)
        if (host == null) {
            if (pending.size >= ToastQueuePolicy.MAX_QUEUED) pending.removeFirst()
            pending.addLast(PendingRequest(type, message, duration.millis, groupKey, now))
            Log.d(TAG, "无宿主页面，暂存提示: $message")
            return
        }
        if (hostRef?.get() !== host || container == null) onHostResumed(host)
        dispatch(type, message, duration.millis, groupKey, now)
    }

    fun cancelAll() {
        timers.values.forEach { mainHandler.removeCallbacks(it) }
        timers.clear()
        policy.clear()
        container?.stack?.removeAllViews()
        views.clear()
        dismissingIds.clear()
        pending.clear()
    }

    private fun dispatch(
        type: AppToast.Type,
        message: String,
        baseDurationMs: Long,
        groupKey: String?,
        now: Long,
    ) {
        val request = ToastRequest(
            id = nextId++,
            type = type,
            message = message,
            groupKey = groupKey,
            durationMs = ToastQueuePolicy.effectiveDuration(baseDurationMs, message),
            createdAtMs = now,
            updatedAtMs = now,
        )
        apply(policy.submit(request, now))
    }

    private fun apply(decisions: List<ToastQueuePolicy.Decision>) {
        for (decision in decisions) {
            when (decision) {
                is ToastQueuePolicy.Decision.Show ->
                    renderCard(decision.request, animate = true)

                is ToastQueuePolicy.Decision.Refresh ->
                    refreshCard(decision.request)

                is ToastQueuePolicy.Decision.Preempt ->
                    // 立刻让位：不播退场动画，由新卡的入场动画接管视觉
                    removeCard(decision.request.id, animate = false)

                is ToastQueuePolicy.Decision.Drop ->
                    Log.w(TAG, "提示队列已满，丢弃: ${decision.request.message}")
            }
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val items = pending.toList()
        pending.clear()
        for (item in items) {
            if (now - item.createdAtMs <= ToastQueuePolicy.PENDING_TTL_MS) {
                dispatch(item.type, item.message, item.baseDurationMs, item.groupKey, now)
            } else {
                Log.w(TAG, "无宿主页面导致提示过期，丢弃: ${item.message}")
            }
        }
    }

    // ── 卡片渲染 ─────────────────────────────────────────

    private fun renderCard(request: ToastRequest, animate: Boolean) {
        val host = hostRef?.get()
        val hostContainer = container
        if (host == null || hostContainer == null) {
            policy.forget(request.id)
            Log.d(TAG, "无宿主页面，跳过渲染: ${request.message}")
            return
        }
        if (views.containsKey(request.id)) {
            refreshCard(request)
            return
        }

        val now = SystemClock.uptimeMillis()
        val toastView = ToastView(hostContainer.context)
        toastView.bind(request, resolveColors(host, request.type), maxTextWidthPx(host))
        toastView.onDismissRequested = { removeCard(request.id, animate = true) }
        views[request.id] = toastView
        hostContainer.stack.addView(toastView.view, cardParams(host))

        if (animate) toastView.playEnter()
        // 页面切换重画时按剩余时长续播（至少留 600ms），避免卡片一闪而过
        val elapsed = if (request.shownAtMs > 0L) now - request.shownAtMs else 0L
        val remaining = (request.durationMs - elapsed).coerceAtLeast(MIN_REPLAY_MS)
        toastView.startProgress(remaining)
        scheduleDismiss(request, remaining)
        hostContainer.announceForAccessibility("${request.type.label}：${request.message}")
    }

    private fun refreshCard(request: ToastRequest) {
        val toastView = views[request.id] ?: return
        toastView.updateCount(request.count)
        toastView.playPulse()
        toastView.startProgress(request.durationMs)
        scheduleDismiss(request)
    }

    private fun removeCard(id: Long, animate: Boolean) {
        timers.remove(id)?.let { mainHandler.removeCallbacks(it) }
        val toastView = views.remove(id)
        if (toastView == null) {
            // 视图已随宿主页面一起被摘掉，但策略状态必须同步，
            // 否则这条已到期的提示会在下个页面 resume 时"复活"
            onCardGone(id)
            return
        }

        if (animate) dismissingIds.add(id)

        val finish = {
            dismissingIds.remove(id)
            toastView.stopProgress()
            (toastView.view.parent as? ViewGroup)?.removeView(toastView.view)

            onCardGone(id)
        }

        if (animate) {
            toastView.playExit(finish)
        } else {
            finish()
        }
    }

    /** 卡片离场后的统一收尾：有宿主就顶上下一条，没有宿主就只清状态等 [drain] */
    private fun onCardGone(id: Long) {
        val now = SystemClock.uptimeMillis()
        if (hostRef?.get() != null && container != null) {
            policy.onDismissed(id, now)?.let { next -> renderCard(next, animate = true) }
        } else {
            policy.forget(id)
        }
    }

    private fun scheduleDismiss(request: ToastRequest, delayMs: Long = request.durationMs) {
        timers.remove(request.id)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { removeCard(request.id, animate = true) }
        timers[request.id] = runnable
        mainHandler.postDelayed(runnable, delayMs.coerceAtLeast(1L))
    }

    // ── 宿主解析与配色 ───────────────────────────────────

    private fun resolveHost(context: Context): Activity? {
        App.currentActivity()?.let { return it }
        return context.findActivity()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun resolveColors(activity: Activity, type: AppToast.Type): ToastColors {
        val accent = when (type) {
            AppToast.Type.INFO ->
                themeColor(activity, com.google.android.material.R.attr.colorPrimary)

            AppToast.Type.SUCCESS ->
                ContextCompat.getColor(activity, R.color.success_color)

            AppToast.Type.WARN ->
                ContextCompat.getColor(activity, R.color.warn_color)

            AppToast.Type.ERROR ->
                ContextCompat.getColor(activity, R.color.danger_color)
        }
        return ToastColors(
            accent = accent,
            surface = themeColor(
                activity,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
            ),
            onSurface = themeColor(activity, com.google.android.material.R.attr.colorOnSurface),
        )
    }

    private fun themeColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else Color.DKGRAY
    }

    private fun maxTextWidthPx(activity: Activity): Int {
        // 宿主若是居中小弹窗，卡片最大宽度要跟着弹窗窗口收窄，否则会被窗口边缘裁掉
        val hostWidth = hosts.lastOrNull()?.root?.width?.takeIf { it > 0 }
        val widthPixels = hostWidth ?: activity.resources.displayMetrics.widthPixels
        val maxCard = minOf(widthPixels - dp(activity, 32), dp(activity, 420))
        return (maxCard - dp(activity, 92)).coerceAtLeast(dp(activity, 120))
    }

    private fun cardParams(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(context, 10)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}

/**
 * 提示卡堆叠容器（整窗透明的覆盖层）。
 *
 * 平时挂在 Activity 的 `android.R.id.content` 上；弹层打开时被 [ToastManager] 搬进弹层
 * 自己的 DecorView，成为该窗口内最上层 View（见 [ToastManager.WindowHost]）。
 *
 * 自身不消费触摸（`isClickable = false`），空白区域的点击会穿透到底层页面；
 * 顶部内边距按状态栏 inset 让位，因此 edge-to-edge 的 MainActivity 与非 edge-to-edge
 * 的独立页面共用同一套定位逻辑（非 edge-to-edge 下 inset 已被 decor 消费，值为 0）。
 * 挂在居中小弹窗（[com.cherry.wakeupschedule.R.style.RoundedDialog]）时窗口本身很小，
 * 提示卡呈"弹窗顶部 banner"形态，宽度由 [ToastManager.maxTextWidthPx] 收敛到窗口内。
 */
internal class ToastContainer(context: Context) : FrameLayout(context) {

    val stack: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
        layoutTransition = LayoutTransition().apply {
            setDuration(LAYOUT_TRANSITION_MS)
            // 卡片自身的出现/消失由 ToastView 的进出场动画负责，
            // 这里只保留"兄弟卡片补位"的位置过渡，避免两套动画互相打架
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            for (type in TRANSITION_TYPES) setStartDelay(type, 0L)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        setPadding(dp(16), dp(16), dp(16), 0)
        addView(stack)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(dp(16), top + dp(10), dp(16), 0)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val LAYOUT_TRANSITION_MS = 180L
        val TRANSITION_TYPES = intArrayOf(
            LayoutTransition.APPEARING,
            LayoutTransition.DISAPPEARING,
            LayoutTransition.CHANGE_APPEARING,
            LayoutTransition.CHANGE_DISAPPEARING,
        )
    }
}
