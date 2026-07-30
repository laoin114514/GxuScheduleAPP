package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import java.text.SimpleDateFormat
import java.util.Calendar

class UpcomingDaysWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.upcoming.ACTION_REFRESH"
        private const val PERIODIC_UPDATE_INTERVAL = 15 * 60 * 1000L

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, UpcomingDaysWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        // 通知两个 ListView 数据变化
        @Suppress("DEPRECATION")
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_today_courses)
        @Suppress("DEPRECATION")
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_tomorrow_courses)
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
        try {
            cancelPeriodicUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
        }
    }

    fun schedulePeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, UpcomingDaysPeriodicReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                10010,
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelPeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, UpcomingDaysPeriodicReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                10010,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, UpcomingDaysWidgetProvider::class.java))
        if (appWidgetIds.isNotEmpty()) {
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_upcoming_days)
            try {
                views.setOnClickPendingIntent(
                    R.id.widget_container,
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } catch (e: Exception) { e.printStackTrace() }
            updateWidgetContent(context, views)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_upcoming_days)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e2: Exception) { e2.printStackTrace() }
        }
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
            val clickIntentTemplate = PendingIntent.getActivity(
                context, 20002,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.lv_today_courses, clickIntentTemplate)
            views.setPendingIntentTemplate(R.id.lv_tomorrow_courses, clickIntentTemplate)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getIdByName(context: Context, name: String): Int {
        return context.resources.getIdentifier(name, "id", context.packageName)
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
