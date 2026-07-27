package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import java.util.Calendar

/**
 * 最小化小组件提供者
 * 显示下课倒计时
 * - API 24+ 使用系统 Chronometer 实现硬件级倒计时，进程被杀也不影响
 * - API < 24 使用短周期精确闹钟保底刷新
 */
class MinimalWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.minimal.ACTION_REFRESH"
        private const val WIDGET_MINIMAL_PERIODIC_REQUEST_CODE = 10004
        private const val WIDGET_MINIMAL_COURSE_END_REQUEST_CODE = 10006
        private const val WIDGET_MINIMAL_TICK_REQUEST_CODE = 10007
        private const val WIDGET_MINIMAL_SAFETY_REQUEST_CODE = 10008
        private const val MINIMAL_PERIODIC_UPDATE_INTERVAL = 15 * 60 * 1000L
        private const val MINIMAL_TICK_INTERVAL = 30 * 1000L

        /**
         * 触发小组件更新
         */
        fun triggerUpdate(context: Context) {
            val intent = Intent(context, MinimalWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        schedulePeriodicUpdate(context)
        scheduleNextCourseEndUpdate(context)
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
            cancelMinimalCourseEndUpdate(context)
            cancelMinimalTick(context)
            cancelCountdownSafetyUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE",
            "com.cherry.wakeupschedule.widget.minimal.ACTION_TICK" -> updateAllWidgets(context)
        }
    }

    /**
     * 安排周期性更新
     */
    fun schedulePeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, MinimalWidgetPeriodicReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_PERIODIC_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 先取消现有的闹钟，避免重复调度
            alarmManager.cancel(pendingIntent)
            // 再设置新的闹钟
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + MINIMAL_PERIODIC_UPDATE_INTERVAL,
                    MINIMAL_PERIODIC_UPDATE_INTERVAL,
                    pendingIntent
                )
            } else {
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + MINIMAL_PERIODIC_UPDATE_INTERVAL,
                    MINIMAL_PERIODIC_UPDATE_INTERVAL,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消周期性更新
     */
    private fun cancelPeriodicUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, MinimalWidgetPeriodicReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_PERIODIC_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 安排下一个课程结束时的更新
     * 使用绝对时间调度（基于课程实际下课时刻），确保无论何时调用都能准确触发
     */
    private fun scheduleNextCourseEndUpdate(context: Context) {
        try {
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val currentTimeMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val currentWeek = calculateCurrentWeek(SettingsManager(context))

            val todayEndCourses = CourseDataManager.getInstance(context).getAllCourses()
                .filter { it.dayOfWeek == dayOfWeek && currentWeek in it.startWeek..it.endWeek && isCourseInCurrentWeekType(it, currentWeek) }
                .mapNotNull { val end = getCourseEndMinutes(context, it); if (end > currentTimeMinutes) end to it else null }
                .sortedBy { it.first }

            if (todayEndCourses.isEmpty()) {
                cancelMinimalCourseEndUpdate(context)
                return
            }

            // 使用绝对时间：计算出今天课程结束的精确时刻，而非相对延迟
            // 这样即使上课中途刷新了小组件，闹钟触发时间也不会偏移
            val endMinutes = todayEndCourses[0].first
            val calendarEnd = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, endMinutes)
            }
            val targetTriggerMillis = calendarEnd.timeInMillis + 2000L // 下课后2秒触发，给系统一点处理时间

            if (targetTriggerMillis <= System.currentTimeMillis() + 3000L) {
                // 距离触发时间已不足3秒，直接立即刷新，避免闹钟延迟导致不更新
                cancelMinimalCourseEndUpdate(context)
                triggerWidgetUpdate(context)
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_COURSE_END_REQUEST_CODE,
                Intent(context, MinimalWidgetCourseEndReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTriggerMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetTriggerMillis, pendingIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消课程结束时的更新
     */
    private fun cancelMinimalCourseEndUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_COURSE_END_REQUEST_CODE,
                Intent(context, MinimalWidgetCourseEndReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 触发小组件更新
     */
    private fun triggerWidgetUpdate(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MinimalWidgetProvider::class.java))
        if (appWidgetIds.isNotEmpty()) {
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    /**
     * 更新所有小组件
     */
    private fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, MinimalWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (appWidgetIds.isNotEmpty()) {
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    /**
     * 更新单个小组件
     */
    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_minimal)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

        updateWidgetContent(context, views)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /**
     * 更新小组件内容
     * API 24+ 使用系统 Chronometer 实现硬件级倒计时，即使进程被杀也能继续走
     */
    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val adjustedDayOfWeek = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)
            val currentSecond = calendar.get(Calendar.SECOND)
            val currentTime = currentHour * 60 + currentMinute
            val currentTimeSeconds = currentHour * 3600 + currentMinute * 60 + currentSecond

            val currentWeek = calculateCurrentWeek(settingsManager)

            val courseDataManager = CourseDataManager.getInstance(context)
            val allCourses = courseDataManager.getAllCourses()
            val todayCourses = allCourses.filter { course ->
                course.dayOfWeek == adjustedDayOfWeek &&
                currentWeek >= course.startWeek &&
                currentWeek <= course.endWeek &&
                isCourseInCurrentWeekType(course, currentWeek)
            }.sortedBy { course -> getCourseStartMinutes(context, course) }

            val currentCourse = todayCourses.find { course ->
                val startMinutes = getCourseStartMinutes(context, course)
                val endMinutes = getCourseEndMinutes(context, course)
                currentTime >= startMinutes && currentTime < endMinutes
            }

            when {
                currentCourse != null -> {
                    views.setTextViewText(R.id.tv_widget_title, "下课倒计时")
                    views.setTextViewText(R.id.tv_course_name, currentCourse.name)
                    views.setTextViewText(R.id.tv_course_time, "后下课")

                    val endSeconds = getCourseEndMinutes(context, currentCourse) * 60
                    val remainingSeconds = (endSeconds - currentTimeSeconds).coerceAtLeast(0)
                    val remainingMillis = remainingSeconds * 1000L

                    if (remainingSeconds < 60) {
                        views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
                        views.setViewVisibility(R.id.tv_countdown, android.view.View.VISIBLE)
                        val mins = remainingMillis / 60000
                        val secs = (remainingMillis % 60000) / 1000
                        views.setTextViewText(R.id.tv_countdown, "%02d:%02d".format(mins, secs))
                        cancelMinimalTick(context)
                        // 安全更新在倒计时归零时触发，避免显示负数
                        scheduleCountdownSafetyUpdate(context, remainingMillis.coerceAtLeast(1000L))
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        views.setViewVisibility(R.id.tv_countdown, android.view.View.GONE)
                        views.setViewVisibility(R.id.chronometer_countdown, android.view.View.VISIBLE)
                        views.setChronometerCountDown(R.id.chronometer_countdown, true)
                        views.setChronometer(
                            R.id.chronometer_countdown,
                            SystemClock.elapsedRealtime() + remainingMillis,
                            "%s",
                            true
                        )
                        cancelMinimalTick(context)
                        // Chronometer 归零后会继续走成负数，安排安全更新在倒计时归零时刷新小组件
                        scheduleCountdownSafetyUpdate(context, remainingMillis)
                    } else {
                        views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
                        views.setViewVisibility(R.id.tv_countdown, android.view.View.VISIBLE)
                        val mins = remainingMillis / 60000
                        val secs = (remainingMillis % 60000) / 1000
                        views.setTextViewText(R.id.tv_countdown, "%02d:%02d".format(mins, secs))
                        scheduleMinimalTick(context)
                        // 安全更新在倒计时归零时触发，确保课程结束时立即刷新
                        scheduleCountdownSafetyUpdate(context, remainingMillis)
                    }
                }
                else -> {
                    views.setTextViewText(R.id.tv_widget_title, "下课倒计时")
                    views.setTextViewText(R.id.tv_course_name, "当前没课")
                    views.setTextViewText(R.id.tv_course_time, "")
                    views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
                    views.setViewVisibility(R.id.tv_countdown, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.tv_countdown, "--")
                    cancelMinimalTick(context)
                    cancelCountdownSafetyUpdate(context)
                }
            }
        } catch (e: Exception) {
            views.setTextViewText(R.id.tv_widget_title, "下课倒计时")
            views.setTextViewText(R.id.tv_course_name, "加载失败")
            views.setTextViewText(R.id.tv_course_time, "")
            views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
            views.setViewVisibility(R.id.tv_countdown, android.view.View.VISIBLE)
            views.setTextViewText(R.id.tv_countdown, "--")
        }
    }

    /**
     * 为低版本 Android 安排短周期刷新（每30秒），弥补无 Chronometer countdown 的缺陷
     */
    private fun scheduleMinimalTick(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_TICK_REQUEST_CODE,
                Intent(context, MinimalWidgetTickReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + MINIMAL_TICK_INTERVAL,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + MINIMAL_TICK_INTERVAL,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消短周期刷新
     */
    private fun cancelMinimalTick(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_TICK_REQUEST_CODE,
                Intent(context, MinimalWidgetTickReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 安排倒计时结束后的安全更新
     * 当剩余时间不足60秒时使用文本显示，并在倒计时结束后触发一次更新以避免负数时间
     */
    private fun scheduleCountdownSafetyUpdate(context: Context, delayMillis: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_SAFETY_REQUEST_CODE,
                Intent(context, MinimalWidgetSafetyReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            if (delayMillis > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delayMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delayMillis,
                        pendingIntent
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消倒计时安全更新
     */
    private fun cancelCountdownSafetyUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_SAFETY_REQUEST_CODE,
                Intent(context, MinimalWidgetSafetyReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取课程开始时间（分钟）
     */
    private fun getCourseStartMinutes(context: Context, course: com.cherry.wakeupschedule.model.Course): Int {
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

    /**
     * 获取课程结束时间（分钟）
     */
    private fun getCourseEndMinutes(context: Context, course: com.cherry.wakeupschedule.model.Course): Int {
        return try {
            val timeTableManager = TimeTableManager.getInstance(context)
            val timeSlots = timeTableManager.getTimeSlots()
            val endSlot = timeSlots.find { it.node == course.endTime }
            if (endSlot != null) {
                val parts = endSlot.endTime.split(":")
                if (parts.size == 2) {
                    parts[0].toInt() * 60 + parts[1].toInt()
                } else {
                    (8 + course.endTime) * 60 + 45
                }
            } else {
                (8 + course.endTime) * 60 + 45
            }
        } catch (e: Exception) {
            (8 + course.endTime) * 60 + 45
        }
    }

    /**
     * 计算当前周
     */
    private fun calculateCurrentWeek(settingsManager: SettingsManager): Int {
        val semesterStartDate = settingsManager.getSemesterStartDate()
        if (semesterStartDate == 0L) {
            return settingsManager.getDefaultWeek()
        }

        val now = System.currentTimeMillis()
        val diffMillis = now - semesterStartDate
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        val week = (diffDays / 7) + 1

        return week.coerceIn(1, settingsManager.getTotalWeeks())
    }

    /**
     * 检查课程是否符合当前周类型
     */
    private fun isCourseInCurrentWeekType(course: com.cherry.wakeupschedule.model.Course, currentWeek: Int): Boolean {
        return when (course.weekType) {
            0 -> true
            1 -> currentWeek % 2 == 1
            2 -> currentWeek % 2 == 0
            else -> true
        }
    }
}
