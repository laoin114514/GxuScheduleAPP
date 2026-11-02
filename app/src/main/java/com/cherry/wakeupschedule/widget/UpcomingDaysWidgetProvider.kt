package com.cherry.wakeupschedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import java.text.SimpleDateFormat
import java.util.Calendar

/**
 * 近日课程小组件：今天 / 明天两个课程列表。
 *
 * 性能口径（工作流 C）：
 * - 无实例纯内存短路，不再每次 getAppWidgetIds / 实例化 provider；
 * - 一份 RemoteViews 批量下发；两个 ListView 只在「可见内容签名」变化时才通知重载
 *   （旧实现每次刷新都通知，两个 RemoteViewsFactory 就各重载一遍，还会重置滚动条）；
 * - 15 分钟周期闹钟挂上后不再重复 set。
 */
class UpcomingDaysWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.upcoming.ACTION_REFRESH"

        /** 上次通知 ListView 重载时的列表内容签名（进程级缓存；null 表示未知，首次必通知） */
        @Volatile
        private var lastListSignature: String? = null

        @Volatile
        private var clickPendingIntent: PendingIntent? = null

        /** 两个列表共用的行点击模板（requestCode 20002；进程内复用） */
        @Volatile
        private var rowClickPendingIntent: PendingIntent? = null

        fun triggerUpdate(context: Context) {
            if (!WidgetPresence.isPresent(context, UpcomingDaysWidgetProvider::class.java)) return
            updateAllWidgets(context)
        }

        private fun clickPendingIntent(context: Context): PendingIntent =
            clickPendingIntent ?: PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { clickPendingIntent = it }

        /** 列表行点击模板 PendingIntent（进程内复用，避免每次刷新都申请一遍） */
        private fun rowClickPendingIntent(context: Context): PendingIntent =
            rowClickPendingIntent ?: PendingIntent.getActivity(
                context, 20002,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_IMMUTABLE
            ).also { rowClickPendingIntent = it }

        private fun updateAllWidgets(context: Context) {
            val appWidgetIds = WidgetPresence.widgetIds(context, UpcomingDaysWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            UpcomingDaysWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 系统回调带来当前完整的实例列表：顺手校准进程内缓存
        WidgetPresence.markUpdated(UpcomingDaysWidgetProvider::class.java, appWidgetIds)
        if (appWidgetIds.isNotEmpty()) {
            // 各实例内容完全一致：一份 RemoteViews 一次 IPC 批量下发
            appWidgetManager.updateAppWidget(appWidgetIds, buildRemoteViews(context))

            // 通知两个 ListView 数据变化。列表内容只在跨天 / 课程数据 / 课程状态变化时才会变，
            // 用签名去重，避免每次刷新都让两个 RemoteViewsFactory 全量重载（还会重置滚动条）
            val signature = WidgetListSignature.upcomingSignature(context)
            if (signature != lastListSignature) {
                lastListSignature = signature
                @Suppress("DEPRECATION")
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_today_courses)
                @Suppress("DEPRECATION")
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_tomorrow_courses)
            }
        }
        schedulePeriodicUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
        schedulePeriodicUpdate(context)
        WidgetMidnightReceiver.scheduleMidnightUpdate(context)
        ScheduleWidgetUpdateService.triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.markAbsent(UpcomingDaysWidgetProvider::class.java)
        // 如果这是桌面上最后一个小组件，顺手停掉 30 分钟兜底链
        ScheduleWidgetUpdateService.onProviderDisabled(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPresence.markDeleted(UpcomingDaysWidgetProvider::class.java, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
        }
    }

    /**
     * 周期刷新：并入「所有小组件共用的一条 15 分钟闹钟」
     * （见 [ScheduleWidgetUpdateService.ensurePeriodicUpdate]），不再单独挂一条。
     */
    fun schedulePeriodicUpdate(context: Context) {
        ScheduleWidgetUpdateService.ensurePeriodicUpdate(context)
    }

    private fun buildRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_upcoming_days)
        try {
            views.setOnClickPendingIntent(R.id.widget_container, clickPendingIntent(context))
        } catch (e: Exception) { e.printStackTrace() }
        updateWidgetContent(context, views)
        return views
    }

    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("MM.dd", java.util.Locale.getDefault())

            views.setTextViewText(R.id.tv_widget_title, settingsManager.getCurrentSemester())
            views.setTextViewText(R.id.tv_widget_date, dateFormat.format(calendar.time))

            // 绑定 ListView 到 RemoteViewsService
            // 关键：必须用不同的 data Uri 让 RemoteViews 区分两个 Service 绑定
            // 否则系统会缓存第一个 Intent，导致两个 ListView 共享同一份数据
            val todayIntent = Intent(context, WidgetCourseListService::class.java).apply {
                putExtra(WidgetCourseListService.EXTRA_SOURCE, WidgetCourseListService.SOURCE_UPCOMING_TODAY)
                // 不同的 data Uri 让系统识别为不同的 Intent
                data = android.net.Uri.parse("widget://course-list/upcoming_today")
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.lv_today_courses, todayIntent)
            views.setEmptyView(R.id.lv_today_courses, android.R.id.empty)

            val tomorrowIntent = Intent(context, WidgetCourseListService::class.java).apply {
                putExtra(WidgetCourseListService.EXTRA_SOURCE, WidgetCourseListService.SOURCE_TOMORROW)
                data = android.net.Uri.parse("widget://course-list/tomorrow")
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.lv_tomorrow_courses, tomorrowIntent)
            views.setEmptyView(R.id.lv_tomorrow_courses, android.R.id.empty)

            // 点击模板
            val clickIntentTemplate = rowClickPendingIntent(context)
            views.setPendingIntentTemplate(R.id.lv_today_courses, clickIntentTemplate)
            views.setPendingIntentTemplate(R.id.lv_tomorrow_courses, clickIntentTemplate)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCourseTimeString(context: Context, course: Course): String {
        return try {
            val timeTableManager = TimeTableManager.getInstance(context)
            val timeSlots = timeTableManager.getTimeSlots()
            val startSlot = timeSlots.find { it.node == course.startTime }
            val endSlot = timeSlots.find { it.node == course.endTime }
            if (startSlot != null && endSlot != null) {
                "${startSlot.startTime} - ${endSlot.endTime}"
            } else {
                "第${course.startTime}-${course.endTime}节"
            }
        } catch (e: Exception) {
            "第${course.startTime}-${course.endTime}节"
        }
    }

    private fun getCourseStartMinutes(context: Context, course: Course): Int {
        return try {
            val timeTableManager = TimeTableManager.getInstance(context)
            val timeSlots = timeTableManager.getTimeSlots()
            val startSlot = timeSlots.find { it.node == course.startTime }
            if (startSlot != null) {
                val parts = startSlot.startTime.split(":")
                if (parts.size == 2) {
                    parts[0].toInt() * 60 + parts[1].toInt()
                } else {
                    (8 + course.startTime) * 60
                }
            } else {
                (8 + course.startTime) * 60
            }
        } catch (e: Exception) {
            (8 + course.startTime) * 60
        }
    }

    private fun calculateCurrentWeek(settingsManager: SettingsManager): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) {
            return settingsManager.getDefaultWeek()
        }
        return (((System.currentTimeMillis() - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks())
    }

    private fun calculateWeekForDay(settingsManager: SettingsManager, calendar: Calendar): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) {
            return settingsManager.getDefaultWeek()
        }
        return (((calendar.timeInMillis - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks())
    }
}
