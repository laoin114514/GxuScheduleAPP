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
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import java.text.SimpleDateFormat
import java.util.Calendar

class WeekViewWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.weekview.ACTION_REFRESH"
        private const val PERIODIC_UPDATE_INTERVAL = 15 * 60 * 1000L
        private val DAY_NAMES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, WeekViewWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
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

    @Suppress("UNUSED_PARAMETER")
    fun schedulePeriodicUpdate(context: Context) {
        // 一周课程组件已禁用，不进行更新调度
        /*
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WeekViewPeriodicReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                10012,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + PERIODIC_UPDATE_INTERVAL,
                PERIODIC_UPDATE_INTERVAL,
                pendingIntent
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        */
    }

    @Suppress("UNUSED_PARAMETER")
    private fun cancelPeriodicUpdate(context: Context) {
        // 一周课程组件已禁用，不进行更新取消
        /*
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WeekViewPeriodicReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                10012,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        */
    }

    private fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, WeekViewWidgetProvider::class.java))
        if (appWidgetIds.isNotEmpty()) {
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_week_view)
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

    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("MM月dd日", java.util.Locale.getDefault())
            val dayFormat = SimpleDateFormat("dd", java.util.Locale.getDefault())
            val currentWeek = calculateCurrentWeek(settingsManager)
            val todayDayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1

            views.setTextViewText(R.id.tv_widget_date, dateFormat.format(calendar.time))
            views.setTextViewText(R.id.tv_widget_info, "${settingsManager.getCurrentSemester()} · 第${currentWeek}周")

            val allCourses = CourseDataManager.getInstance(context).getAllCourses()

            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val weeksCalendar = calendar.clone() as Calendar

            for (i in 0..6) {
                val dayName = DAY_NAMES[i]
                val dayNumber = i + 1
                val isToday = dayNumber == todayDayOfWeek

                views.setTextViewText(
                    context.resources.getIdentifier("tv_${dayName}_date", "id", context.packageName),
                    dayFormat.format(weeksCalendar.time)
                )

                if (isToday) {
                    views.setTextColor(
                        context.resources.getIdentifier("tv_${dayName}_name", "id", context.packageName),
                        android.graphics.Color.parseColor("#4A90E2")
                    )
                    views.setTextColor(
                        context.resources.getIdentifier("tv_${dayName}_date", "id", context.packageName),
                        android.graphics.Color.parseColor("#4A90E2")
                    )
                } else {
                    views.setTextColor(
                        context.resources.getIdentifier("tv_${dayName}_name", "id", context.packageName),
                        android.graphics.Color.parseColor("#888888")
                    )
                    views.setTextColor(
                        context.resources.getIdentifier("tv_${dayName}_date", "id", context.packageName),
                        android.graphics.Color.parseColor("#888888")
                    )
                }

                val weekForDay = calculateWeekForDay(settingsManager, weeksCalendar)
                val dayCourses = allCourses.filter {
                    it.dayOfWeek == dayNumber &&
                    weekForDay in it.startWeek..it.endWeek &&
                    isCourseInCurrentWeekType(it, weekForDay)
                }.sortedBy { it.startTime }

                val courseViewId = context.resources.getIdentifier("tv_${dayName}_course_name", "id", context.packageName)

                if (dayCourses.isNotEmpty()) {
                    val firstCourse = dayCourses.first()
                    val colors = ThemeManager.getCourseColors()
                    val color = colors[(firstCourse.id % colors.size).toInt()]

                    views.setTextViewText(courseViewId, firstCourse.name)
                    views.setInt(courseViewId, "setBackgroundColor", color)
                    views.setTextColor(courseViewId, android.graphics.Color.WHITE)
                } else {
                    views.setTextViewText(courseViewId, "")
                    views.setInt(courseViewId, "setBackgroundColor", android.graphics.Color.parseColor("#E0E0E0"))
                }

                weeksCalendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateCurrentWeek(settingsManager: SettingsManager): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) {
            return settingsManager.getDefaultWeek()
        }
        return (((System.currentTimeMillis() - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1).coerceIn(1, 20)
    }

    private fun calculateWeekForDay(settingsManager: SettingsManager, calendar: Calendar): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) {
            return settingsManager.getDefaultWeek()
        }
        return (((calendar.timeInMillis - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1).coerceIn(1, 20)
    }

    private fun isCourseInCurrentWeekType(course: Course, week: Int): Boolean {
        return when (course.weekType) {
            0 -> true
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }
}