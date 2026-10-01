package com.cherry.wakeupschedule

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.databinding.ActivityWidgetCenterBinding
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.cherry.wakeupschedule.widget.MinimalWidgetProvider
import com.cherry.wakeupschedule.widget.NextCourseWidgetProvider
import com.cherry.wakeupschedule.widget.ScheduleWidgetProvider
import com.cherry.wakeupschedule.widget.WeekViewWidgetProvider

/**
 * 小组件中心（我的 → 通用 → 小组件）。
 *
 * 部分手机在系统小组件列表找不到本应用（冻结 / SD 卡安装 / 桌面缓存等），
 * 这里通过 requestPinAppWidget 提供应用内一键添加；桌面不支持时回退手动添加引导。
 * 后续新增小组件时在页内加同构卡片即可。
 */
class WidgetCenterActivity : BaseActivity() {

    private lateinit var binding: ActivityWidgetCenterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWidgetCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupPageHeader(binding.toolbar, "小组件")

        binding.btnAddCountdown.setOnClickListener {
            pinWidget(MinimalWidgetProvider::class.java, R.layout.widget_minimal_preview)
        }
        binding.btnAddToday.setOnClickListener {
            pinWidget(ScheduleWidgetProvider::class.java, R.layout.widget_today_preview)
        }
        binding.btnAddWeek.setOnClickListener {
            pinWidget(WeekViewWidgetProvider::class.java, R.layout.widget_week_view_preview)
        }
        binding.btnAddNext.setOnClickListener {
            pinWidget(NextCourseWidgetProvider::class.java, R.layout.widget_next_course_preview)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAddedStatus(
            binding.tvCountdownStatus,
            ComponentName(this, MinimalWidgetProvider::class.java)
        )
        refreshAddedStatus(
            binding.tvTodayStatus,
            ComponentName(this, ScheduleWidgetProvider::class.java)
        )
        refreshAddedStatus(
            binding.tvWeekStatus,
            ComponentName(this, WeekViewWidgetProvider::class.java)
        )
        refreshAddedStatus(
            binding.tvNextStatus,
            ComponentName(this, NextCourseWidgetProvider::class.java)
        )
    }

    private fun refreshAddedStatus(statusView: TextView, component: ComponentName) {
        val count = AppWidgetManager.getInstance(this).getAppWidgetIds(component).size
        statusView.text = if (count > 0) "已添加 ${count} 个" else "未添加"
    }

    private fun pinWidget(provider: Class<*>, previewLayoutRes: Int) {
        val manager = AppWidgetManager.getInstance(this)
        val component = ComponentName(this, provider)
        if (!manager.isRequestPinAppWidgetSupported) {
            showManualGuide()
            return
        }

        val extras = Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 让系统"添加小组件"确认框直接展示真实预览，而不是桌面默认渲染
            extras.putParcelable(
                AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                RemoteViews(packageName, previewLayoutRes)
            )
        }

        val requested = try {
            manager.requestPinAppWidget(component, extras, null)
        } catch (e: Exception) {
            false
        }
        if (!requested) showManualGuide()
    }

    private fun showManualGuide() {
        StyledDialog.Builder(this)
            .title("当前桌面不支持一键添加")
            .message("请手动添加：长按桌面空白处 → 小组件（窗口小工具）→ 找到「西大课栈」→ 长按「下课倒计时」拖到桌面。")
            .positiveButton("知道了")
            .show()
    }
}
