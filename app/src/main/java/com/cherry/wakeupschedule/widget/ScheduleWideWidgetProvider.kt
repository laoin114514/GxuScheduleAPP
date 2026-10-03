package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar

/**
 * 今日课程概览 4×2 小组件（2026-10 新增变体，与 2×2 的 ScheduleWidgetProvider 并存）。
 *
 * 样式与 2×2 完全同款（彩底行卡 + 徽标三色 + 文字 token），只做布局扩展：
 * 标题行「今日课程 · N 门 | 第N周 · 周X」+ 双行行卡列表
 * （行 = 课名 + 徽标 + 起时间 / 教室·节次 + 止时间），110dp 可见约 2 门、可上下滑动。
 * 数据源复用 WidgetCourseListService 的 today_wide 渠道；刷新链与 2×2 一致
 * （15 分钟周期 + 课程结束精确闹钟 + 分钟 tick + TIME_SET + 0 点跨天 + 30 分钟全局链），
 * 闹钟 requestCode 独立（10015/10016），移除本组件不影响 2×2 的闹钟。
 */
class ScheduleWideWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.wide.ACTION_REFRESH"
        private const val WIDE_COURSE_END_REQUEST_CODE = 10016
        private const val WIDE_PERIODIC_REQUEST_CODE = 10015
        private const val PERIODIC_UPDATE_INTERVAL = 15 * 60 * 1000L

        /** 上次通知 ListView 重载时的列表内容签名（进程级缓存；null 表示未知，首次必通知） */
        @Volatile
        private var lastListSignature: String? = null

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, ScheduleWideWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) updateAppWidget(context, appWidgetManager, appWidgetId)
        // 仅当列表可见内容可能变化时才通知 ListView 重载（每次都通知会重置滚动条导致闪烁）
        val signature = WidgetListSignature.todaySignature(context)
        if (signature != lastListSignature) {
            lastListSignature = signature
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_wide_courses)
        }
        scheduleWideCourseEndUpdate(context)
        scheduleWidePeriodicUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
        scheduleWidePeriodicUpdate(context)
        WidgetMidnightReceiver.scheduleMidnightUpdate(context)
        // 借用全局更新链调度 30 分钟兜底刷新
        ScheduleWidgetUpdateService.triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        try {
            cancelWidePeriodicUpdate(context)
            cancelWideCourseEndUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE" -> updateAllWidgets(context)
        }
    }

    private fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, ScheduleWideWidgetProvider::class.java))
        if (appWidgetIds.isNotEmpty()) {
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_today_schedule_wide)
        views.setOnClickPendingIntent(
            R.id.widget_container,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        updateWidgetContent(context, views)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    // 更新小组件内容
    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)

            val todayCount = CourseDataManager.getInstance(context).getAllCourses().count {
                it.dayOfWeek == dayOfWeek && it.isActiveInWeek(currentWeek)
            }
            val weekLabel = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")[dayOfWeek]
            views.setTextViewText(R.id.tv_wide_header, "今日课程 · $todayCount 门")
            views.setTextViewText(R.id.tv_wide_day, "第${currentWeek}周 · $weekLabel")

            // 绑定 ListView 到 RemoteViewsService 的 today_wide 渠道（宽版行：右列起止时间）
            // 使用 data Uri 让系统识别为唯一绑定（RemoteViews 缓存只比 Uri 不比 extras）
            val todayIntent = Intent(context, WidgetCourseListService::class.java).apply {
                putExtra(WidgetCourseListService.EXTRA_SOURCE, WidgetCourseListService.SOURCE_TODAY_WIDE)
                data = android.net.Uri.parse("widget://course-list/today_wide")
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.lv_wide_courses, todayIntent)
            views.setEmptyView(R.id.lv_wide_courses, android.R.id.empty)

            // 设置每行的点击 PendingIntent（跳转到主应用）
            val clickIntentTemplate = PendingIntent.getActivity(
                context, 20002,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.lv_wide_courses, clickIntentTemplate)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 调度今天最近一个未结束课程的下课时刻精确刷新（与 2×2 同逻辑，独立 requestCode）
    private fun scheduleWideCourseEndUpdate(context: Context) {
        try {
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val currentTimeSeconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600 + calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)
            val currentTimeMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val currentWeek = CourseTimeUtils.getCurrentWeek(SettingsManager(context))

            val todayEndCourses = CourseDataManager.getInstance(context).getAllCourses()
                .filter { it.dayOfWeek == dayOfWeek && it.isActiveInWeek(currentWeek) }
                .mapNotNull { val end = CourseTimeUtils.getEndMinutes(context, it); if (end > currentTimeMinutes) end to it else null }
                .sortedBy { it.first }

            if (todayEndCourses.isEmpty()) {
                cancelWideCourseEndUpdate(context)
                return
            }
            val endSeconds = todayEndCourses[0].first * 60
            val delayMillis = (endSeconds - currentTimeSeconds) * 1000L + 5000L
            if (delayMillis <= 5000L) { cancelWideCourseEndUpdate(context); updateAllWidgets(context); return }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(context, WIDE_COURSE_END_REQUEST_CODE, Intent(context, WidgetCourseEndReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pendingIntent)
            else alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pendingIntent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cancelWideCourseEndUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context, WIDE_COURSE_END_REQUEST_CODE,
                Intent(context, WidgetCourseEndReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun scheduleWidePeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WidgetPeriodicUpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDE_PERIODIC_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + PERIODIC_UPDATE_INTERVAL,
                PERIODIC_UPDATE_INTERVAL,
                pendingIntent
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelWidePeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDE_PERIODIC_REQUEST_CODE,
                Intent(context, WidgetPeriodicUpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
