package com.cherry.wakeupschedule

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.RemoteViews
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.cherry.wakeupschedule.databinding.ActivityWidgetCenterBinding
import com.cherry.wakeupschedule.databinding.DialogWidgetHelpBinding
import com.cherry.wakeupschedule.ui.adapter.WidgetCenterPagerAdapter
import com.cherry.wakeupschedule.ui.adapter.WidgetPage
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.component.createAppChip
import com.cherry.wakeupschedule.ui.component.themeColor
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.cherry.wakeupschedule.widget.WidgetPinPolicy
import com.cherry.wakeupschedule.widget.WidgetPinSupport
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 小组件中心（我的 → 小组件）。
 *
 * 4 个小组件各占一页，进入即落在「下课倒计时」；每页内可独立上下滚动。
 * 顶部 chip 切页，右下角「?」弹窗给出使用说明与「找不到小组件」的排查。
 * 部分手机在系统小组件列表找不到本应用（冻结 / SD 卡安装 / 桌面缓存等），
 * 这里通过 requestPinAppWidget 提供应用内一键添加；桌面不支持时回退手动添加引导。
 *
 * ### 为什么「一键添加」必须自己核验结果
 * `requestPinAppWidget` 的返回值只表示**桌面声称支持主动添加**，与「有没有真的加上」无关。
 * 国内 ROM 上它经常返回 true 却什么都不发生：vivo/iQOO 的桌面直接吞掉请求（既不弹框也不添加），
 * 小米/红米在缺少「桌面快捷方式」权限时静默不添加，华为/荣耀则无论成败都不回调 PendingIntent。
 * 老实现只按返回值判断，于是这些机型上「返回值 true → 不走兜底引导」+「没有系统确认框」+
 * 「没有成功回调」三者叠加，页面就变成了「点了完全没反应」，而桌面却能正常从系统选择器添加。
 *
 * 现在统一以**桌面上该组件的个数有没有变多**作为唯一判据：
 * 发起请求 → 记下基线 → 定时 / 回前台时核验 → 变多则刷状态并出卡，没变多则按机型给出可操作的手动添加引导。
 */
class WidgetCenterActivity : BaseActivity() {

    private lateinit var binding: ActivityWidgetCenterBinding
    private lateinit var pagerAdapter: WidgetCenterPagerAdapter
    private val dots = mutableListOf<View>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 上次进入本页时各 provider 的添加数；null = 尚未建立基线（首次进入只记录不比较） */
    private var lastAddedCounts: Map<Class<*>, Int>? = null

    /** 正在等待落地结果的一键添加请求；null = 当前没有待核验的请求 */
    private var pendingPin: PendingPin? = null

    /** 本页是否在前台，用于把「用户还在系统确认框上」和「桌面根本没响应」分开 */
    private var isForeground = false

    /**
     * 一次「一键添加」请求的核验上下文。
     *
     * [baseline] 是发起请求时桌面上该组件的个数；[leftForeground] 记录请求后本页是否被顶到后台
     * —— 有系统确认框出现过的证据，用来把「用户自己取消了」和「桌面静默吞掉了请求」区分开。
     */
    private class PendingPin(
        val page: WidgetPage,
        val baseline: Int,
        var leftForeground: Boolean = false,
        var attempts: Int = 0,
    )

    /** 延迟核验：把「桌面到底加上没有」从同步返回值里解耦出来 */
    private val pinCheck = Runnable { verifyPendingPin() }

    /** chip 高亮与页指示圆点共用主题主色 */
    private val accentColor: Int by lazy {
        themeColor(com.google.android.material.R.attr.colorPrimary)
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            syncChipRow(position)
            updateDots(position.toFloat())
        }

        override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
            updateDots(position + offset)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWidgetCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupPageHeader(binding.toolbar, "小组件")

        pagerAdapter = WidgetCenterPagerAdapter(::pinWidget)
        // 相邻页预加载，往返滑动不出现空白页
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)

        setupDots()
        // 落地第 1 页「下课倒计时」
        syncChipRow(0)

        // 页面渐变是蓝灰调，圆钮用纯白（浅色）才不糊进背景
        binding.btnWidgetHelp.setCircleBackground(R.drawable.bg_widget_help)
        binding.btnWidgetHelp.setOnClickListener { showHelpDialog() }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        foregroundRef = WeakReference(this)
        val counts = readAddedCounts()
        pagerAdapter.submitAddedCounts(counts, pendingPin?.page?.providerClass)
        reconcileAddedCounts(counts)
        // 从系统确认框 / 桌面回来：再核验一次刚才的请求有没有真的落地
        if (pendingPin != null) schedulePinCheck(PIN_RESUME_SETTLE_MS)
    }

    override fun onPause() {
        isForeground = false
        // 本页被顶到后台，多半是系统确认框或桌面接管了前台：记为「系统界面出现过」，
        // 之后若没加上就按「用户取消」温和提示，而不是甩一整页排查弹窗
        pendingPin?.let { it.leftForeground = true }
        foregroundRef = null
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pinCheck)
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroy()
    }

    // ── 顶部 chip 行 ──

    /**
     * chip 行与 4 个小组件页一一对应。
     * 选中项填主题主色，并自动滚到行中间 —— 4 个 chip 一屏未必放得下。
     */
    private fun syncChipRow(position: Int) {
        val labels = pagerAdapter.widgetPages.map { it.title }
        binding.llWidgetChips.removeAllViews()
        labels.forEachIndexed { index, label ->
            val chip = createAppChip(label = label, selected = index == position) {
                binding.viewPager.setCurrentItem(index, true)
            }
            binding.llWidgetChips.addView(chip)
            if (index == position) scrollChipToCenter(chip)
        }
    }

    private fun scrollChipToCenter(chip: View) {
        binding.hsvWidgetChips.post {
            val centered = chip.left + chip.width / 2 - binding.hsvWidgetChips.width / 2
            binding.hsvWidgetChips.smoothScrollTo(centered.coerceAtLeast(0), 0)
        }
    }

    // ── 底部页指示圆点：滑动过程中跟手放大 / 加深 ──

    private fun setupDots() {
        val size = dp(DOT_SIZE_DP)
        val margin = dp(DOT_MARGIN_DP)
        repeat(pagerAdapter.itemCount) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = margin
                    marginEnd = margin
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accentColor)
                }
            }
            binding.llPageDots.addView(dot)
            dots += dot
        }
        updateDots(0f)
    }

    /** [progress] 为「页序 + 滑动偏移」，距当前越近的点越大越实 */
    private fun updateDots(progress: Float) {
        dots.forEachIndexed { index, dot ->
            val closeness = (1f - abs(index - progress)).coerceIn(0f, 1f)
            dot.alpha = DOT_MIN_ALPHA + (1f - DOT_MIN_ALPHA) * closeness
            val scale = DOT_MIN_SCALE + (1f - DOT_MIN_SCALE) * closeness
            dot.scaleX = scale
            dot.scaleY = scale
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    // ── 右下角「?」：使用说明 + 找不到小组件的排查 ──

    private fun showHelpDialog() {
        StyledDialog.Builder(this)
            .title("使用说明与排查")
            .view(DialogWidgetHelpBinding.inflate(layoutInflater).root)
            .positiveButton("知道了")
            .show()
    }

    // ── 添加到桌面 ──

    /** 各小组件在桌面上已添加的个数（含同页次变体，如今日课程的 2×2） */
    private fun readAddedCounts(): Map<Class<*>, Int> {
        val manager = AppWidgetManager.getInstance(this)
        val classes = pagerAdapter.widgetPages
            .flatMap { listOfNotNull(it.providerClass, it.smallVariant?.providerClass) }
            .distinct()
        return classes.associateWith { clazz ->
            manager.getAppWidgetIds(ComponentName(this, clazz)).size
        }
    }

    private fun countOf(provider: Class<*>): Int =
        AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, provider)).size

    /**
     * 兜底的「已添加」感知：与上次进入本页的计数比对，多出来的部分出卡。
     * 覆盖成功回调丢失的桌面（华为/荣耀）、从系统小组件选择器手动添加、
     * 以及人已离开页面后才落地的情况；一键添加自己的核验路径会同步 [lastAddedCounts]，
     * 因此同一笔添加不会被两条路径重复出卡。
     */
    private fun reconcileAddedCounts(counts: Map<Class<*>, Int>) {
        val baseline = lastAddedCounts
        lastAddedCounts = counts
        baseline ?: return
        counts.forEach { (provider, count) ->
            val delta = count - (baseline[provider] ?: 0)
            if (delta > 0) showAddedToast(provider, delta)
        }
    }

    private fun showAddedToast(provider: Class<*>, count: Int) {
        val page = pagerAdapter.widgetPages
            .flatMap { listOfNotNull(it, it.smallVariant) }
            .firstOrNull { it.providerClass == provider } ?: return
        val suffix = if (count > 1) " ×$count" else ""
        AppToast.success(toastHost(), "已添加到桌面：${page.displayName}$suffix")
    }

    /**
     * 提示卡的宿主：本页在前台就出在本页上，已退后台则给 applicationContext ——
     * 否则 AppToast 会把卡片画进看不见的页面里，用户回来时早就自超时消失了。
     * 传 applicationContext 时它会暂存到下一个页面 resume 再补发。
     */
    private fun toastHost(): Context = if (isForeground) this else applicationContext

    private fun pinWidget(page: WidgetPage) {
        val manager = AppWidgetManager.getInstance(this)
        val component = ComponentName(this, page.providerClass)
        if (!manager.isRequestPinAppWidgetSupported) {
            showManualGuide(page, PinFailure.UNSUPPORTED)
            return
        }

        val requested = try {
            manager.requestPinAppWidget(component, pinExtras(page), pinSuccessPendingIntent(page))
        } catch (e: Exception) {
            false
        }
        if (!requested) {
            showManualGuide(page, PinFailure.REJECTED)
            return
        }

        // 返回值只说明「桌面声称支持」，不保证真的加上（见类注释），所以必须自己核验：
        // 期间状态行显示「正在添加到桌面…」，结果由 verifyPendingPin 落到成功卡或手动引导。
        pendingPin = PendingPin(page, countOf(page.providerClass))
        pagerAdapter.submitAddedCounts(readAddedCounts(), page.providerClass)
        schedulePinCheck(PIN_FIRST_CHECK_MS)
    }

    /**
     * requestPinAppWidget 的 extras。
     * API 31 起可以让系统确认框直接展示真实预览（而不是桌面默认渲染）。
     * 预览布局若混进 RemoteViews 白名单外的控件会整卡 inflate 失败，
     * 这里兜住异常 → 退回系统默认预览，不让整个一键添加跟着挂掉。
     */
    private fun pinExtras(page: WidgetPage): Bundle {
        val extras = Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preview = try {
                RemoteViews(packageName, page.previewLayoutRes)
            } catch (e: Exception) {
                null
            }
            if (preview != null) {
                extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview)
            }
        }
        return extras
    }

    /**
     * 一键添加的成功回调：requestPinAppWidget 的 successCallback，桌面放置完成后由系统发出。
     * requestCode 按 provider 类名派生 —— 同变体重发覆盖旧 PendingIntent，不同变体互不覆盖。
     *
     * 回调只当作「可以去核验了」的触发器，不直接出卡：华为/荣耀根本不发这个回调，
     * 而真正有没有加上要以桌面上的组件个数为准（见 [verifyPendingPin]）。
     */
    private fun pinSuccessPendingIntent(page: WidgetPage): PendingIntent {
        val intent = Intent(this, WidgetPinResultReceiver::class.java).apply {
            putExtra(WidgetPinResultReceiver.EXTRA_PROVIDER, page.providerClass.name)
        }
        // FLAG_MUTABLE：系统要往 intent 里回填 EXTRA_APPWIDGET_ID，immutable 收不到；
        // API 31 以下默认就是 mutable，没有该标志位
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, page.providerClass.name.hashCode(), intent, flags)
    }

    // ── 一键添加的结果核验 ──

    private fun schedulePinCheck(delayMs: Long) {
        mainHandler.removeCallbacks(pinCheck)
        mainHandler.postDelayed(pinCheck, delayMs)
    }

    /**
     * 核验一次待处理的一键添加，结论由 [WidgetPinPolicy] 给出：
     * - 组件数比请求前多 → 成功：刷新状态行，未被回调路径报过就补一张「已添加」卡；
     * - 还没多 → 继续等（桌面落地有延迟；系统确认框压在本页之上时本页拿不到焦点，也在这里等）；
     * - 等满了仍没多 → 判定失败：出现过系统界面就温和提示（多半是用户自己取消），
     *   压根没出现过就弹手动添加引导 —— 这正是「点了没反应」的那批机型。
     */
    private fun verifyPendingPin() {
        val pending = pendingPin ?: return
        val counts = readAddedCounts()
        val count = counts[pending.page.providerClass] ?: 0
        pending.attempts += 1

        val outcome = WidgetPinPolicy.evaluate(
            baselineCount = pending.baseline,
            currentCount = count,
            attempts = pending.attempts,
            leftForeground = pending.leftForeground,
            foregroundWithFocus = isForeground && hasWindowFocus(),
        )

        when (outcome) {
            WidgetPinPolicy.Outcome.RETRY -> schedulePinCheck(PIN_CHECK_INTERVAL_MS)

            WidgetPinPolicy.Outcome.ADDED -> {
                pendingPin = null
                // 回调路径 / onResume 的兜底路径已经报过这笔添加就不再重复出卡
                val alreadyReported =
                    (lastAddedCounts?.get(pending.page.providerClass) ?: 0) >= count
                lastAddedCounts = counts
                pagerAdapter.submitAddedCounts(counts)
                if (!alreadyReported) {
                    showAddedToast(pending.page.providerClass, count - pending.baseline)
                }
            }

            WidgetPinPolicy.Outcome.CANCELED -> {
                pendingPin = null
                pagerAdapter.submitAddedCounts(counts)
                AppToast.warn(toastHost(), "没有检测到新添加的小组件，可再试一次或手动添加")
            }

            WidgetPinPolicy.Outcome.NO_RESPONSE -> {
                pendingPin = null
                pagerAdapter.submitAddedCounts(counts)
                showManualGuide(pending.page, PinFailure.NO_RESPONSE)
            }
        }
    }

    /** 一键添加没能落地的三种情形，决定引导弹窗的标题与首句 */
    private enum class PinFailure { UNSUPPORTED, REJECTED, NO_RESPONSE }

    private fun showManualGuide(page: WidgetPage, failure: PinFailure) {
        if (isFinishing || isDestroyed) return
        val title = when (failure) {
            PinFailure.UNSUPPORTED -> "当前桌面不支持一键添加"
            PinFailure.REJECTED -> "桌面拒绝了本次添加请求"
            PinFailure.NO_RESPONSE -> "桌面没有响应，请手动添加"
        }
        val intro = when (failure) {
            PinFailure.NO_RESPONSE ->
                "已经向桌面发起了添加，但既没有弹出确认框，也没有检测到新增的小组件。" +
                        "部分手机的桌面不支持应用内一键添加，请按下面的方式手动添加："

            else -> "请按下面的方式手动添加："
        }
        val steps = "长按桌面空白处 → 小组件（窗口小工具）→ 找到「西大课栈」→ " +
                "长按「${page.displayName}」拖到桌面。"
        val message = listOfNotNull(intro, steps, WidgetPinSupport.manualHint())
            .joinToString("\n\n")
        StyledDialog.Builder(this)
            .title(title)
            .message(message)
            .positiveButton("知道了")
            .show()
    }

    companion object {
        private const val DOT_SIZE_DP = 8
        private const val DOT_MARGIN_DP = 4
        private const val DOT_MIN_ALPHA = 0.3f
        private const val DOT_MIN_SCALE = 0.75f

        /** 发起请求后首次核验的延迟：桌面静默添加（小米）也要来得及反映到 getAppWidgetIds */
        private const val PIN_FIRST_CHECK_MS = 900L

        /** 核验间隔 */
        private const val PIN_CHECK_INTERVAL_MS = 1200L

        /** 成功回调到达后多久核验（等桌面把 appWidgetId 提交完） */
        private const val PIN_CALLBACK_SETTLE_MS = 300L

        /** 回到本页后多久核验 */
        private const val PIN_RESUME_SETTLE_MS = 400L

        /** 前台中的小组件中心：成功回调到达时在这里续上核验（onResume/onPause 维护） */
        private var foregroundRef: WeakReference<WidgetCenterActivity>? = null

        /**
         * 添加成功回调入口（[WidgetPinResultReceiver] 调用，主线程）。
         *
         * 只做一件事：让还活着的本页去核验刚发起的那笔请求是否真的落地。
         * 页面已退后台时不做任何事 —— 回到页面时 onResume 会自己补一次核验，
         * 这样华为/荣耀不发回调的情况也走同一条路径。
         */
        internal fun notifyPinSuccess(providerName: String) {
            val activity = foregroundRef?.get() ?: return
            // 陈旧回调（上一笔请求的）不触发核验
            if (activity.pendingPin?.page?.providerClass?.name != providerName) return
            activity.schedulePinCheck(PIN_CALLBACK_SETTLE_MS)
        }
    }
}
