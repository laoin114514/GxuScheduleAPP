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
                .filter { it.dayOfWeek == dayOfWeek && it.isActiveInWeek(currentWeek) }
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
     * 有进行中的课：课程名 + 距下课秒级倒计时（系统 Chronometer 硬件级，进程被杀也能走）+ 教室；
     * 无课：距下一节的分钟数，或"今日课程已结束"。
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
                course.isActiveInWeek(currentWeek)
            }.sortedBy { course -> getCourseStartMinutes(context, course) }

            val currentCourse = todayCourses.find { course ->
                val startMinutes = getCourseStartMinutes(context, course)
                val endMinutes = getCourseEndMinutes(context, course)
                currentTime >= startMinutes && currentTime < endMinutes
            }

            if (currentCourse != null) {
                views.setTextViewText(R.id.tv_course_label, "当前课程：${currentCourse.name}")
                views.setTextViewText(R.id.tv_countdown_label, "距下课还有")
                views.setViewVisibility(R.id.tv_countdown_label, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.ll_location, android.view.View.VISIBLE)
                views.setTextViewText(R.id.tv_course_location, currentCourse.classroom)
                showCountdownToEnd(context, views, currentCourse, currentTimeSeconds)
            } else {
                views.setTextViewText(R.id.tv_course_label, "当前没有课程")
                views.setViewVisibility(R.id.ll_location, android.view.View.GONE)
                val nextCourse = todayCourses.firstOrNull { getCourseStartMinutes(context, it) > currentTime }
                if (nextCourse != null) {
                    val startMinutes = getCourseStartMinutes(context, nextCourse)
                    views.setTextViewText(R.id.tv_countdown_label, "距下一节")
                    views.setViewVisibility(R.id.tv_countdown_label, android.view.View.VISIBLE)
                    showTextCountdown(views, "${startMinutes - currentTime}分钟")
                    cancelMinimalTick(context)
                    // 到点后翻转为进行中的下课倒计时
                    val millisUntilStart = (startMinutes * 60 - currentTimeSeconds).coerceAtLeast(1) * 1000L
                    scheduleCountdownSafetyUpdate(context, millisUntilStart)
                } else {
                    // 24sp 大字放不下长中文，无课态用短文案避免窄宽度被裁
                    views.setViewVisibility(R.id.tv_countdown_label, android.view.View.GONE)
                    showTextCountdown(views, if (todayCourses.isEmpty()) "今天没课" else "今日已结束")
                    cancelMinimalTick(context)
                    cancelCountdownSafetyUpdate(context)
                }
            }
        } catch (e: Exception) {
            views.setTextViewText(R.id.tv_course_label, "加载失败")
            views.setViewVisibility(R.id.tv_countdown_label, android.view.View.GONE)
            views.setViewVisibility(R.id.ll_location, android.view.View.GONE)
            showTextCountdown(views, "--")
        }
    }

    /**
     * 距下课秒级倒计时：剩余不足 60 秒用文本显示，其余走系统 Chronometer 倒计时
     */
    private fun showCountdownToEnd(
        context: Context,
        views: RemoteViews,
        course: com.cherry.wakeupschedule.model.Course,
        currentTimeSeconds: Int
    ) {
        val endSeconds = getCourseEndMinutes(context, course) * 60
        val remainingSeconds = (endSeconds - currentTimeSeconds).coerceAtLeast(0)
        val remainingMillis = remainingSeconds * 1000L

        if (remainingSeconds < 60) {
            showTextCountdown(
                views,
                "%02d:%02d".format(remainingMillis / 60000, (remainingMillis % 60000) / 1000)
            )
            // 安全更新在倒计时归零时触发，避免显示负数
            scheduleCountdownSafetyUpdate(context, remainingMillis.coerceAtLeast(1000L))
        } else {
            views.setViewVisibility(R.id.tv_countdown, android.view.View.GONE)
            views.setViewVisibility(R.id.chronometer_countdown, android.view.View.VISIBLE)
            views.setChronometerCountDown(R.id.chronometer_countdown, true)
            views.setChronometer(
                R.id.chronometer_countdown,
                SystemClock.elapsedRealtime() + remainingMillis,
                "%s",
                true
            )
            // Chronometer 归零后会继续走成负数，安排安全更新在倒计时归零时刷新小组件
            scheduleCountdownSafetyUpdate(context, remainingMillis)
        }
        cancelMinimalTick(context)
    }

    /**
     * 以静态文本显示倒计时位（无 Chronometer 的场景）
     */
    private fun showTextCountdown(views: RemoteViews, text: String) {
        views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
        views.setViewVisibility(R.id.tv_countdown, android.view.View.VISIBLE)
        views.setTextViewText(R.id.tv_countdown, text)
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
    private fun getCourseStartMinutes(context: Context, course: com.cherry.wakeupschedule.model.Course): Int =
        CourseTimeUtils.getStartMinutes(context, course)

    /**
     * 获取课程结束时间（分钟）
     */
    private fun getCourseEndMinutes(context: Context, course: com.cherry.wakeupschedule.model.Course): Int =
        CourseTimeUtils.getEndMinutes(context, course)

    /**
     * 计算当前周
     */
    private fun calculateCurrentWeek(settingsManager: SettingsManager): Int =
        CourseTimeUtils.getCurrentWeek(settingsManager)
}
