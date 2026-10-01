package com.cherry.wakeupschedule

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RemoteViews
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.cherry.wakeupschedule.databinding.ActivityWidgetCenterBinding
import com.cherry.wakeupschedule.ui.adapter.WidgetCenterPagerAdapter
import com.cherry.wakeupschedule.ui.adapter.WidgetPage
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.component.createAppChip
import com.cherry.wakeupschedule.ui.component.themeColor
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 小组件中心（我的 → 小组件）。
 *
 * 6 页横向分页：使用说明 → 4 个小组件 → 找不到小组件排查；每页内可独立上下滚动。
 * 部分手机在系统小组件列表找不到本应用（冻结 / SD 卡安装 / 桌面缓存等），
 * 这里通过 requestPinAppWidget 提供应用内一键添加；桌面不支持时回退手动添加引导。
 */
class WidgetCenterActivity : BaseActivity() {

    private lateinit var binding: ActivityWidgetCenterBinding
    private lateinit var pagerAdapter: WidgetCenterPagerAdapter
    private val dots = mutableListOf<View>()

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
        // 落地第 1 页：只有圆点，chip 行不显示
        syncChipRow(WidgetCenterPagerAdapter.PAGE_INTRO)
    }

    override fun onResume() {
        super.onResume()
        pagerAdapter.submitAddedCounts(readAddedCounts())
    }

    override fun onDestroy() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroy()
    }

    // ── 顶部 chip 行 ──

    /**
     * chip 行只在小组件页显示（使用说明页隐藏）。
     * 选中项填主题主色，并自动滚到行中间 —— 5 个 chip 一屏放不下。
     */
    private fun syncChipRow(position: Int) {
        val selected = position - WidgetCenterPagerAdapter.WIDGET_PAGE_START
        binding.hsvWidgetChips.isVisible = selected >= 0
        if (selected < 0) return

        val labels = pagerAdapter.widgetPages.map { it.title } + TROUBLESHOOT_TITLE
        binding.llWidgetChips.removeAllViews()
        labels.forEachIndexed { index, label ->
            val chip = createAppChip(label = label, selected = index == selected) {
                binding.viewPager.setCurrentItem(
                    index + WidgetCenterPagerAdapter.WIDGET_PAGE_START, true
                )
            }
            binding.llWidgetChips.addView(chip)
            if (index == selected) scrollChipToCenter(chip)
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

    // ── 添加到桌面 ──

    /** 4 个小组件在桌面上已添加的个数 */
    private fun readAddedCounts(): Map<Class<*>, Int> {
        val manager = AppWidgetManager.getInstance(this)
        return pagerAdapter.widgetPages.associate { page ->
            page.providerClass to
                    manager.getAppWidgetIds(ComponentName(this, page.providerClass)).size
        }
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
            manager.requestPinAppWidget(component, extras, null)
        } catch (e: Exception) {
            false
        }
        if (!requested) showManualGuide(page.pickerLabel)
    }

    private fun showManualGuide(widgetName: String) {
        StyledDialog.Builder(this)
            .title("当前桌面不支持一键添加")
            .message("请手动添加：长按桌面空白处 → 小组件（窗口小工具）→ 找到「西大课栈」→ 长按「$widgetName」拖到桌面。")
            .positiveButton("知道了")
            .show()
    }

    companion object {
        /** chip 行最后一项：找不到小组件排查页 */
        private const val TROUBLESHOOT_TITLE = "找不到小组件"

        private const val DOT_SIZE_DP = 8
        private const val DOT_MARGIN_DP = 4
        private const val DOT_MIN_ALPHA = 0.3f
        private const val DOT_MIN_SCALE = 0.75f
    }
}
