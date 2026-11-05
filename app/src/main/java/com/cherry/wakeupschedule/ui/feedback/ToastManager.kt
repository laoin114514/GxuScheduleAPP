package com.cherry.wakeupschedule.ui.feedback

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * - **单一容器**：整屏透明的 [ToastContainer] 平时挂到当前前台 Activity 的
 *   `android.R.id.content`；有弹层打开时挂到独立的**顶层透明浮层**上 ——
 *   位置始终固定在屏幕顶部，只是层级压在所有弹层之上。提示卡属于应用窗口，
 *   退出/切后台后不会再像系统 Toast 一样悬浮在桌面上。
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
     * 当前 Activity 上已显示的弹层窗口（DecorView 已 attach）。
     *
     * 本项目所有弹层（课表菜单、课程详情、外观面板、学期滚轮、StyledDialog、AlertDialog…）
     * 都是独立 Dialog 窗口：提示卡挂在 Activity content 里时会被弹层遮罩压暗、甚至被弹层
     * 内容盖住（docs/UI-优化方案.md 的「Toast 暗区」P0）。只要存在弹层，就把容器搬到
     * 一个**全屏透明的顶层提示浮层**里 —— 位置仍固定在屏幕顶部（与无弹层时完全一致），
     * 只是层级压在所有弹层之上。
     */
    private val dialogDecors = ArrayList<WeakReference<View>>()

    /** 弹层先于 Activity 宿主就绪时（如恢复弹窗在 onCreate 里弹出）暂存 DecorView，等宿主就绪补挂 */
    private val pendingDialogDecors = ArrayList<WeakReference<View>>()

    /** 弹层打开期间承载提示卡的全屏透明浮层（不抢焦点、不拦触摸，位置固定在屏幕顶部） */
    private var overlayDialog: Dialog? = null
    private var overlayRoot: FrameLayout? = null

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
            pruneDetachedDialogs()
            flushPending()
            return
        }
        dismissOverlay()
        detachContainer()
        dialogDecors.clear()
        hostRef = WeakReference(activity)
        // 弹层先于页面就绪的极端场景（启动即弹恢复框）：先把弹层补记进来
        adoptPendingDialogs(activity)

        val host = ToastContainer(activity)
        container = host
        // 已有弹层时直接挂到顶层提示浮层，避免先落页面再搬的闪动
        moveContainerToCurrentTarget()

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
        dismissOverlay()
        detachContainer()
        hostRef = null
        dialogDecors.clear()
        pendingDialogDecors.clear()
    }

    /** 弹层窗口显示（DecorView attach）：登记弹层，并按需把提示浮层重新压到它上面 */
    fun onDialogAttached(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        val activity = hostRef?.get()
        val dialogActivity = dialog.context.findActivity()
        if (activity == null || dialogActivity !== activity) {
            // 页面宿主还没就绪（或弹层属于别的页面）：先记下，等 onHostResumed 补挂
            rememberPendingDialog(decor)
            return
        }
        pruneDetachedDialogs()
        if (dialogDecors.none { it.get() === decor }) dialogDecors.add(WeakReference(decor))
        // 新弹层窗口按添加顺序会压在提示浮层下面，浮层要重新 show 一次才能回到最上层
        raiseOverlay()
        moveContainerToCurrentTarget()
    }

    /** 弹层窗口关闭（DecorView detach）：移出登记；没有弹层了就把容器搬回 Activity content */
    fun onDialogDetached(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        pendingDialogDecors.removeAll { it.get() === decor }
        val removed = dialogDecors.removeAll { it.get() === decor || it.get() == null }
        if (removed) {
            if (dialogDecors.isEmpty()) dismissOverlay()
            moveContainerToCurrentTarget()
        }
    }

    private fun rememberPendingDialog(decor: View) {
        prunePendingDialogDecors()
        if (pendingDialogDecors.none { it.get() === decor }) {
            pendingDialogDecors.add(WeakReference(decor))
        }
    }

    private fun adoptPendingDialogs(activity: Activity) {
        prunePendingDialogDecors()
        val alive = pendingDialogDecors.mapNotNull { it.get() }
            .filter { it.isAttachedToWindow && it.context.findActivity() === activity }
        pendingDialogDecors.clear()
        alive.forEach { decor ->
            if (dialogDecors.none { it.get() === decor }) dialogDecors.add(WeakReference(decor))
        }
    }

    private fun prunePendingDialogDecors() {
        pendingDialogDecors.removeAll { ref ->
            val decor = ref.get()
            decor == null || (!decor.isAttachedToWindow && decor.parent == null)
        }
    }

    /** 兜底清理：弹层已关闭却没收到 detach（异常路径）时移除登记，避免提示一直往浮层上走 */
    private fun pruneDetachedDialogs() {
        dialogDecors.removeAll { ref ->
            val decor = ref.get()
            decor == null || !decor.isAttachedToWindow
        }
    }

    /** 把容器搬回当前正确宿主：有弹层 → 顶层提示浮层；无弹层 → Activity content */
    private fun moveContainerToCurrentTarget() {
        val host = container ?: return
        val activity = hostRef?.get() ?: return
        val target: ViewGroup = if (dialogDecors.isNotEmpty()) {
            ensureOverlay(activity) ?: return
        } else {
            activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        }
        if (host.parent === target) return
        (host.parent as? ViewGroup)?.removeView(host)
        target.addView(
            host,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Activity content 与提示浮层的窗口 inset 行为不同（有的窗口消费状态栏、有的不消费，
        // 浮层自身顶边位置也随系统版本变化），这里统一按"卡片堆栈在屏幕上的实际 y"
        // 反推需要补的 padding，保证两种宿主下提示卡位置完全一致。
        alignContainerToScreenTop(activity, host, target)
    }

    /**
     * 把提示卡堆栈在屏幕上的顶部对齐到「状态栏高度 + 10dp」。
     *
     * 目标位置与宿主窗口无关：提示卡永远出现在同一个屏幕位置，弹层只是改变它的层级。
     * 做法：先按"窗口顶到屏幕 0 点"给一个 padding，等布局完成后量出堆栈实际屏幕 y，
     * 再把差值补进 padding（一次布局即可收敛，不依赖窗口是否消费状态栏 inset）。
     */
    private fun alignContainerToScreenTop(
        activity: Activity,
        container: ToastContainer,
        target: ViewGroup,
    ) {
        val density = activity.resources.displayMetrics.density
        val basePadding = (10 * density).roundToInt()
        val desiredTop = statusBarHeightPx(activity) + basePadding
        container.applyForcedTopInset(desiredTop)
        container.post {
            if (container.parent !== target) return@post
            val location = IntArray(2)
            container.stack.getLocationOnScreen(location)
            val delta = desiredTop - location[1]
            if (delta != 0) container.applyForcedTopInset(container.paddingTop + delta)
        }
    }

    /** 系统状态栏高度（px）：优先取当前窗口的 inset，取不到时回落到平台资源 */
    private fun statusBarHeightPx(activity: Activity): Int {
        val density = activity.resources.displayMetrics.density
        val insetTop = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        if (insetTop > 0) return insetTop
        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            activity.resources.getDimensionPixelSize(resId)
        } else {
            (24 * density).roundToInt()
        }
    }

    /** 已有提示在展示时把浮层重新 show 一次，保证它始终压在最新打开的弹层之上 */
    private fun raiseOverlay() {
        if (overlayDialog?.isShowing != true) return
        dismissOverlay()
        hostRef?.get()?.let { activity ->
            if (dialogDecors.isNotEmpty()) ensureOverlay(activity)
        }
    }

    /**
     * 创建/复用全屏透明的顶层提示浮层。
     *
     * 为什么需要它：居中弹窗（StyledDialog / AlertDialog…）的窗口只有弹窗那么大，
     * 把提示卡放进弹窗窗口会让提示位置跟着弹窗跑；这里用独立全屏浮层承载，
     * 提示位置与"无弹层"时完全一致（屏幕顶部居中），只是层级在弹层之上。
     *
     * 浮层不抢焦点、不接收触摸（[WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]），
     * 因此不会挡住弹层自身的交互 —— 代价是弹层打开期间提示卡不响应点按关闭
     * （自动消失与进度线仍在；无弹层时点按关闭不受影响）。
     */
    private fun ensureOverlay(activity: Activity): FrameLayout? {
        overlayRoot?.let { if (overlayDialog?.isShowing == true) return it }
        dismissOverlay()
        return try {
            // 用非浮动的全屏窗口样式（与底部弹层同款 windowIsFloating=false）：窗口从屏幕
            // 顶端 (0,0) 开始铺满整个屏幕，配合下面显式指定的状态栏留白，位置可精确确定。
            val dialog = Dialog(activity, R.style.BottomSheetDialog)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            val root = FrameLayout(activity)
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                setGravity(Gravity.TOP)
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
                attributes = attributes.apply { windowAnimations = 0 }
            }
            dialog.show()
            overlayDialog = dialog
            overlayRoot = root
            root
        } catch (e: Exception) {
            // Activity 正在销毁 / 窗口令牌失效：本次放弃，容器留在页面里即可
            Log.w(TAG, "创建提示浮层失败", e)
            null
        }
    }

    private fun dismissOverlay() {
        val dialog = overlayDialog ?: return
        overlayDialog = null
        overlayRoot = null
        try {
            dialog.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "关闭提示浮层失败", e)
        }
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
        // 有弹层时容器应在顶层提示浮层上；这里再校准一次，避免弹层/宿主切换的时序缝隙
        moveContainerToCurrentTarget()
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
        // 提示卡宿主始终是整屏宽度（Activity content 或全屏浮层），与屏幕宽度一致
        val widthPixels = activity.resources.displayMetrics.widthPixels
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
 * 平时挂在 Activity 的 `android.R.id.content` 上；有弹层打开时被 [ToastManager] 挂到
 * 顶层透明提示浮层（全屏窗口，位置固定在屏幕顶部），保证提示不被弹层遮罩压暗/遮挡。
 *
 * 自身不消费触摸（`isClickable = false`），空白区域的点击会穿透到底层页面；
 * 顶部内边距按状态栏 inset 让位，因此 edge-to-edge 的 MainActivity 与非 edge-to-edge
 * 的独立页面共用同一套定位逻辑（非 edge-to-edge 下 inset 已被 decor 消费，值为 0）。
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

    /** 非空时忽略窗口 inset，使用该值作为顶部留白（浮层宿主模式） */
    private var forcedTopInsetPx: Int? = null

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        setPadding(dp(16), dp(16), dp(16), 0)
        addView(stack)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val fixed = forcedTopInsetPx
            val top = fixed ?: (insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + dp(10))
            view.setPadding(dp(16), top, dp(16), 0)
            insets
        }
    }

    /**
     * 浮层宿主下的顶部留白（px，含状态栏高度）：浮层是独立窗口，其 inset 是否被窗口消费
     * 随系统版本/窗口样式而变，这里由 [ToastManager] 显式给定，保证提示位置与页面内一致。
     */
    fun applyForcedTopInset(insetPx: Int) {
        forcedTopInsetPx = insetPx
        setPadding(dp(16), insetPx, dp(16), 0)
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
