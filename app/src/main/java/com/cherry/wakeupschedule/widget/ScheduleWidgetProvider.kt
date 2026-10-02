package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * 今日课程桌面小组件提供者
 * 显示当前日期和接下来要上的课程
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.ACTION_REFRESH"
        private const val WIDGET_COURSE_END_REQUEST_CODE = 10002

        /** 上次通知 ListView 重载时的列表内容签名（进程级缓存；null 表示未知，首次必通知） */
        @Volatile
        private var lastListSignature: String? = null
        private const val WIDGET_PERIODIC_UPDATE_REQUEST_CODE = 10003
        private const val PERIODIC_UPDATE_INTERVAL = 15 * 60 * 1000L // 15分钟

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, ScheduleWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) updateAppWidget(context, appWidgetManager, appWidgetId)
        // 仅当列表可见内容可能变化时才通知 ListView 重载（每次都通知会重置滚动条，导致右侧滑动条持续闪烁）
        val signature = computeListSignature(context)
        if (signature != lastListSignature) {
            lastListSignature = signature
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_today_courses)
        }
        scheduleNextCourseEndUpdate(context)
        schedulePeriodicUpdate(context)
    }

    /**
     * 列表可见内容的轻量签名：星期、周次、课程集合（id/名称/教室/节次），
     * 以及每门课相对当前时刻的阶段标记（未开始/进行中/已上完的组合决定每行徽标）。
     * 阶段标记只在某节课开始或结束的分钟边界变化，因此数据未变时不会触发重载。
     */
    private fun computeListSignature(context: Context): String {
        return try {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val todayDayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)
            val courses = CourseDataManager.getInstance(context).getAllCourses()
                .filter { it.dayOfWeek == todayDayOfWeek && it.isActiveInWeek(currentWeek) }
                .sortedBy { CourseTimeUtils.getStartMinutes(context, it) }
            courses.joinToString("|") { course ->
                val started = if (CourseTimeUtils.getStartMinutes(context, course) > nowMinutes) 0 else 1
                val ended = if (CourseTimeUtils.getEndMinutes(context, course) > nowMinutes) 0 else 1
                "${course.id}-${course.name}-${course.classroom}-${course.startTime}-${course.endTime}:$started$ended"
            } + "|$todayDayOfWeek|$currentWeek"
        } catch (e: Exception) {
            e.printStackTrace()
            // 取不到数据时放弃去重，保持原有的每次都通知行为
            UUID.randomUUID().toString()
        }
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
        try {
            cancelPeriodicUpdate(context)
            cancelCourseEndUpdate(context)
            ScheduleWidgetUpdateService.cancelScheduledUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE" -> updateAllWidgets(context)
        }
    }

    // 定期更新小组件
    fun schedulePeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WidgetPeriodicUpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_PERIODIC_UPDATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 先取消现有的闹钟，避免重复调度
            alarmManager.cancel(pendingIntent)
            // 再设置新的闹钟
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + PERIODIC_UPDATE_INTERVAL,
                PERIODIC_UPDATE_INTERVAL,
                pendingIntent
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cancelPeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WidgetPeriodicUpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_PERIODIC_UPDATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 更新所有小组件
    private fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
        if (appWidgetIds.isNotEmpty()) onUpdate(context, appWidgetManager, appWidgetIds)
    }

    // 更新单个小组件
    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_today_schedule)
        views.setOnClickPendingIntent(R.id.widget_container,
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
            views.setTextViewText(R.id.tv_widget_header, "$weekLabel · $todayCount 门课")

            // 绑定 ListView 到 RemoteViewsService，显示完整今日课程列表（含已结束，标注状态）
            // 使用 data Uri 让系统识别为唯一绑定
            val todayIntent = Intent(context, WidgetCourseListService::class.java).apply {
                putExtra(WidgetCourseListService.EXTRA_SOURCE, WidgetCourseListService.SOURCE_TODAY)
                data = android.net.Uri.parse("widget://course-list/today")
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.lv_today_courses, todayIntent)
            views.setEmptyView(R.id.lv_today_courses, android.R.id.empty)

            // 设置每行的点击 PendingIntent（跳转到主应用）
            val clickIntentTemplate = PendingIntent.getActivity(
                context, 20001,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.lv_today_courses, clickIntentTemplate)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 调度下次课程结束时更新小组件
    private fun scheduleNextCourseEndUpdate(context: Context) {
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
                cancelCourseEndUpdate(context)
                return
            }
            val endSeconds = todayEndCourses[0].first * 60
            val delayMillis = (endSeconds - currentTimeSeconds) * 1000L + 5000L
            if (delayMillis <= 5000L) { cancelCourseEndUpdate(context); triggerWidgetUpdate(context); return }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(context, WIDGET_COURSE_END_REQUEST_CODE, Intent(context, WidgetCourseEndReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pendingIntent)
            else alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pendingIntent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cancelCourseEndUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context, WIDGET_COURSE_END_REQUEST_CODE,
                Intent(context, WidgetCourseEndReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun triggerWidgetUpdate(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
        if (appWidgetIds.isNotEmpty()) onUpdate(context, appWidgetManager, appWidgetIds)
    }


}
