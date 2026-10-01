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
import java.util.Calendar

/**
 * 下一门课提醒小组件（官网「桌面小组件专区」4×1 款「近日课程轻量条」）。
 *
 * 单条展示"下一门"课：优先今天进行中的课，其次今天下一个未开始的课，
 * 都没有则看明天第一节。徽标（pill）表达时间状态：
 * 进行中 / {N} 分钟后（<60min）/ {HH:mm} 开始 / 明天 {HH:mm} / 今日已结束 / 暂无课程安排。
 *
 * 刷新：15 分钟周期闹钟 + 状态切换点（上课/下课/明天开课）精确闹钟 + App 分钟 tick + 0 点跨天。
 */
class NextCourseWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.next.ACTION_REFRESH"
        private const val NEXT_COURSE_PERIODIC_REQUEST_CODE = 10009
        private const val NEXT_COURSE_TRANSITION_REQUEST_CODE = 10010
        private const val PERIODIC_UPDATE_INTERVAL = 15 * 60 * 1000L

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, NextCourseWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }

        private fun formatMinutes(minutes: Int): String =
            "%02d:%02d".format(minutes / 60, minutes % 60)
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
        // 借用全局更新链调度 30 分钟兜底刷新
        ScheduleWidgetUpdateService.triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        try {
            cancelPeriodicUpdate(context)
            cancelTransitionUpdate(context)
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
        val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, NextCourseWidgetProvider::class.java))
        if (appWidgetIds.isNotEmpty()) {
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_next_course)
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

    /** 单条状态描述：徽标文案 + 徽标配色 + 状态切换的精确时刻（用于安排刷新） */
    private data class NextCourseState(
        val course: Course?,
        val pill: String,
        val isAmber: Boolean,
        val transitionAtMillis: Long?
    )

    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val todayDayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)

            val allCourses = CourseDataManager.getInstance(context).getAllCourses()
            val todayCourses = allCourses
                .filter { it.dayOfWeek == todayDayOfWeek && it.isActiveInWeek(currentWeek) }
                .sortedBy { CourseTimeUtils.getStartMinutes(context, it) }

            val current = todayCourses.firstOrNull {
                val start = CourseTimeUtils.getStartMinutes(context, it)
                val end = CourseTimeUtils.getEndMinutes(context, it)
                nowMinutes >= start && nowMinutes < end
            }
            val nextToday = todayCourses.firstOrNull { CourseTimeUtils.getStartMinutes(context, it) > nowMinutes }

            val state = when {
                current != null -> NextCourseState(
                    current, "进行中", true,
                    transitionMillis(CourseTimeUtils.getEndMinutes(context, current))
                )
                nextToday != null -> {
                    val start = CourseTimeUtils.getStartMinutes(context, nextToday)
                    val minutes = start - nowMinutes
                    val pill = if (minutes < 60) "$minutes 分钟后" else "${formatMinutes(start)} 开始"
                    NextCourseState(nextToday, pill, true, transitionMillis(start))
                }
                else -> {
                    // 今天没课或已全部结束 → 看明天第一节
                    val todayMidnight = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    val tomorrowMillis = todayMidnight + 24 * 60 * 60 * 1000L
                    val tomorrowDow = todayDayOfWeek % 7 + 1
                    val tomorrowWeek = CourseTimeUtils.getWeekForDate(settingsManager, tomorrowMillis)
                    val tomorrow = allCourses
                        .filter { it.dayOfWeek == tomorrowDow && it.isActiveInWeek(tomorrowWeek) }
                        .minByOrNull { CourseTimeUtils.getStartMinutes(context, it) }
                    if (tomorrow != null) {
                        val start = CourseTimeUtils.getStartMinutes(context, tomorrow)
                        NextCourseState(tomorrow, "明天 ${formatMinutes(start)}", true, tomorrowMillis + start * 60 * 1000L)
                    } else if (todayCourses.isNotEmpty()) {
                        NextCourseState(null, "今日已结束", false, null)
                    } else {
                        NextCourseState(null, "暂无课程安排", false, null)
                    }
                }
            }

            render(context, views, state)
            scheduleNextTransitionUpdate(context, state.transitionAtMillis)
        } catch (e: Exception) {
            views.setTextViewText(R.id.tv_next_label, "下一门课提醒")
            views.setTextViewText(R.id.tv_next_pill, "--")
            views.setTextViewText(R.id.tv_next_name, "加载失败")
            views.setTextViewText(R.id.tv_next_detail, "")
        }
    }

    private fun render(context: Context, views: RemoteViews, state: NextCourseState) {
        views.setTextViewText(R.id.tv_next_label, "下一门课提醒")
        views.setTextViewText(R.id.tv_next_pill, state.pill)
        views.setInt(
            R.id.tv_next_pill, "setBackgroundResource",
            if (state.isAmber) R.drawable.widget_pill_amber else R.drawable.widget_pill_gray
        )
        views.setTextColor(
            R.id.tv_next_pill,
            context.getColor(if (state.isAmber) R.color.widget_pill_text_amber else R.color.widget_pill_text_gray)
        )

        val course = state.course
        if (course == null) {
            // 无课可提醒：主行给安抚文案，细节行留空
            views.setTextViewText(
                R.id.tv_next_name,
                if (state.pill == "今日已结束") "今天没有更多课程了" else "假期愉快，好好休息"
            )
            views.setTextViewText(R.id.tv_next_detail, "")
        } else {
            views.setTextViewText(R.id.tv_next_name, course.name)
            val start = CourseTimeUtils.getStartMinutes(context, course)
            val end = CourseTimeUtils.getEndMinutes(context, course)
            views.setTextViewText(
                R.id.tv_next_detail,
                "${formatMinutes(start)} ~ ${formatMinutes(end)} · ${course.classroom}"
            )
        }
    }

    /** 今天第 minutes 分钟对应的绝对时刻 */
    private fun transitionMillis(minutes: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis + minutes * 60 * 1000L
    }

    /**
     * 在下一个状态切换点（上课/下课/明天开课）安排精确刷新；
     * 若切换点已过则不安排（15 分钟周期与分钟 tick 兜底）。
     */
    private fun scheduleNextTransitionUpdate(context: Context, targetMillis: Long?) {
        try {
            if (targetMillis == null || targetMillis <= System.currentTimeMillis() + 5000L) {
                cancelTransitionUpdate(context)
                return
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                NEXT_COURSE_TRANSITION_REQUEST_CODE,
                Intent(context, NextCourseTransitionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelTransitionUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                NEXT_COURSE_TRANSITION_REQUEST_CODE,
                Intent(context, NextCourseTransitionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun schedulePeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, NextCoursePeriodicReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                NEXT_COURSE_PERIODIC_REQUEST_CODE,
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

    private fun cancelPeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                NEXT_COURSE_PERIODIC_REQUEST_CODE,
                Intent(context, NextCoursePeriodicReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
