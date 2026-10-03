package com.cherry.wakeupschedule

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
 */
class WidgetCenterActivity : BaseActivity() {

    private lateinit var binding: ActivityWidgetCenterBinding
    private lateinit var pagerAdapter: WidgetCenterPagerAdapter
    private val dots = mutableListOf<View>()

    /** 上次进入本页时各 provider 的添加数；null = 尚未建立基线（首次进入只记录不比较） */
    private var lastAddedCounts: Map<Class<*>, Int>? = null

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
        foregroundRef = WeakReference(this)
        val counts = readAddedCounts()
        pagerAdapter.submitAddedCounts(counts)
        reconcileAddedCounts(counts)
    }

    override fun onPause() {
        foregroundRef = null
        super.onPause()
    }

    override fun onDestroy() {
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

    /**
     * 兜底的「已添加」感知：与上次进入本页的计数比对，多出来的部分出卡。
     * 覆盖成功回调丢失的桌面、从系统小组件选择器手动添加、回调到达时人已离开超 8s 的情况；
     * 回调路径已提示过的 provider 本次跳过（顺手清掉标记，防陈旧压制后续提示）。
     */
    private fun reconcileAddedCounts(counts: Map<Class<*>, Int>) {
        val baseline = lastAddedCounts
        lastAddedCounts = counts
        baseline ?: return
        counts.forEach { (provider, count) ->
            if (pinToastShown.remove(provider.name)) return@forEach
            val delta = count - (baseline[provider] ?: 0)
            if (delta > 0) showAddedToast(provider, delta)
        }
    }

    private fun showAddedToast(provider: Class<*>, count: Int) {
        val page = pagerAdapter.widgetPages
            .flatMap { listOfNotNull(it, it.smallVariant) }
            .firstOrNull { it.providerClass == provider } ?: return
        val suffix = if (count > 1) " ×$count" else ""
        AppToast.success(this, "已添加到桌面：${page.displayName}$suffix")
    }

    private fun pinWidget(page: WidgetPage) {
        val manager = AppWidgetManager.getInstance(this)
        val component = ComponentName(this, page.providerClass)
        if (!manager.isRequestPinAppWidgetSupported) {
            showManualGuide(page.pickerLabel)
            return
        }

        val extras = Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 让系统"添加小组件"确认框直接展示真实预览，而不是桌面默认渲染
            extras.putParcelable(
                AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                RemoteViews(packageName, page.previewLayoutRes)
            )
        }

        val requested = try {
            manager.requestPinAppWidget(component, extras, pinSuccessPendingIntent(page))
        } catch (e: Exception) {
            false
        }
        if (!requested) showManualGuide(page.pickerLabel)
    }

    /**
     * 一键添加的成功回调：桌面放置完成后由系统发出，[WidgetPinResultReceiver] 出「已添加」卡。
     * requestCode 按 provider 类名派生 —— 同变体重发覆盖旧 PendingIntent，不同变体互不覆盖。
     */
    private fun pinSuccessPendingIntent(page: WidgetPage): PendingIntent {
        val intent = Intent(this, WidgetPinResultReceiver::class.java).apply {
            putExtra(WidgetPinResultReceiver.EXTRA_PROVIDER, page.providerClass.name)
            putExtra(WidgetPinResultReceiver.EXTRA_MESSAGE, "已添加到桌面：${page.displayName}")
        }
        // FLAG_MUTABLE：系统要往 intent 里回填 EXTRA_APPWIDGET_ID，immutable 收不到；
        // API 31 以下默认就是 mutable，没有该标志位
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, page.providerClass.name.hashCode(), intent, flags)
    }

    private fun showManualGuide(widgetName: String) {
        StyledDialog.Builder(this)
            .title("当前桌面不支持一键添加")
            .message("请手动添加：长按桌面空白处 → 小组件（窗口小工具）→ 找到「西大课栈」→ 长按「$widgetName」拖到桌面。")
            .positiveButton("知道了")
            .show()
    }

    companion object {
        private const val DOT_SIZE_DP = 8
        private const val DOT_MARGIN_DP = 4
        private const val DOT_MIN_ALPHA = 0.3f
        private const val DOT_MIN_SCALE = 0.75f

        /** 前台中的小组件中心：成功回调到达时直接在页上出卡（onResume/onPause 维护） */
        private var foregroundRef: WeakReference<WidgetCenterActivity>? = null

        /** 回调路径已出过「已添加」卡的 provider（类名），兜底路径据此去重 */
        private val pinToastShown = mutableSetOf<String>()

        /**
         * 添加成功回调入口（[WidgetPinResultReceiver] 调用，主线程）。
         * 页面在前台 → 立即出卡；已退后台 → 传 applicationContext，
         * AppToast 暂存 8s，回到应用任意页面补发，超时丢弃后由兜底路径接住。
         */
        internal fun notifyPinSuccess(context: Context, message: String, providerName: String) {
            pinToastShown.add(providerName)
            val activity = foregroundRef?.get()
            if (activity != null) {
                AppToast.success(activity, message)
            } else {
                AppToast.success(context.applicationContext, message)
            }
        }
    }
}
