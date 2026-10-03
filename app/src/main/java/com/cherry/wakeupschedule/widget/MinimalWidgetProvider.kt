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
 * 下课倒计时小组件提供者（2×2）。
 *
 * 布局：顶部状态（上课中 / 当前无课）贴顶，其余 5 行竖向居中 ——
 * 时间+说明 / 倒计时 / 课名 / 教室 / 教师。
 * 有进行中的课 → 距下课倒计时；今天还有下一节 → 距上课倒计时。
 * 倒计时走系统 Chronometer（进程被杀也能继续走秒），格式由 App 指定以保证恒为两位时分秒；
 * 归零时由安全闹钟刷新翻状态，跨整点时由格式闹钟回来重设格式。
 */
class MinimalWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.minimal.ACTION_REFRESH"
        private const val WIDGET_MINIMAL_PERIODIC_REQUEST_CODE = 10004
        private const val WIDGET_MINIMAL_COURSE_END_REQUEST_CODE = 10006
        private const val WIDGET_MINIMAL_TICK_REQUEST_CODE = 10007
        private const val WIDGET_MINIMAL_SAFETY_REQUEST_CODE = 10008

        /** 跨整点重设 Chronometer 格式（与到点翻状态的安全闹钟并存，互不覆盖） */
        private const val WIDGET_MINIMAL_FORMAT_REQUEST_CODE = 10011
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
            cancelCountdownFormatUpdate(context)
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
     * 更新小组件内容。
     * 顶部状态贴顶（上课中 / 当前无课），其余 5 行竖向居中：
     * 时间+说明（"16:05 下课，距下课还有"）/ 倒计时 / 课名 / 教室 / 教师。
     * 进行中 → 距下课倒计时；今天还有下一节 → 距上课倒计时；都没课 → 今天没课 / 今日已结束。
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
                showClassStatus(context, views, inClass = true)
                views.setTextViewText(R.id.tv_course_label, currentCourse.name)
                showCourseTime(context, views, currentCourse, isEnd = true)
                showClassroom(views, currentCourse.classroom)
                showTeacher(views, currentCourse.teacher)
                showCountdownTo(context, views, getCourseEndMinutes(context, currentCourse), currentTimeSeconds)
            } else {
                val nextCourse = todayCourses.firstOrNull { getCourseStartMinutes(context, it) > currentTime }
                if (nextCourse != null) {
                    showClassStatus(context, views, inClass = false)
                    views.setTextViewText(R.id.tv_course_label, nextCourse.name)
                    showCourseTime(context, views, nextCourse, isEnd = false)
                    showClassroom(views, nextCourse.classroom)
                    showTeacher(views, nextCourse.teacher)
                    showCountdownTo(context, views, getCourseStartMinutes(context, nextCourse), currentTimeSeconds)
                } else {
                    showClassStatus(context, views, inClass = false)
                    views.setTextViewText(
                        R.id.tv_course_label,
                        if (todayCourses.isEmpty()) "今天没课" else "今日已结束"
                    )
                    hideCourseDetail(views)
                    cancelMinimalTick(context)
                    cancelCountdownSafetyUpdate(context)
                    cancelCountdownFormatUpdate(context)
                }
            }
        } catch (e: Exception) {
            views.setViewVisibility(R.id.tv_class_status, android.view.View.GONE)
            views.setTextViewText(R.id.tv_course_label, "加载失败")
            hideCourseDetail(views)
            showTextCountdown(views, "--")
        }
    }

    /** 顶部状态：上课中（主色）/ 当前无课（弱色），两个有课状态一眼区分 */
    private fun showClassStatus(context: Context, views: RemoteViews, inClass: Boolean) {
        views.setViewVisibility(R.id.tv_class_status, android.view.View.VISIBLE)
        views.setTextViewText(R.id.tv_class_status, if (inClass) "上课中" else "当前无课")
        views.setTextColor(
            R.id.tv_class_status,
            context.getColor(if (inClass) R.color.widget_accent else R.color.widget_text_weak)
        )
    }

    /** 无课 / 异常时收起课程明细行（时间、教室、教师、倒计时） */
    private fun hideCourseDetail(views: RemoteViews) {
        views.setViewVisibility(R.id.tv_course_time, android.view.View.GONE)
        views.setViewVisibility(R.id.ll_location, android.view.View.GONE)
        views.setViewVisibility(R.id.tv_course_teacher, android.view.View.GONE)
        views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
        views.setViewVisibility(R.id.tv_countdown, android.view.View.GONE)
    }

    /**
     * 时间行（倒计时上方）："{时间} 下课，距下课还有" / "{时间} 上课，距上课还有"。
     * 时间优先取时间表的 HH:mm，缺失时退回「第 N 节」（与今日课程列表同口径）。
     */
    private fun showCourseTime(
        context: Context,
        views: RemoteViews,
        course: com.cherry.wakeupschedule.model.Course,
        isEnd: Boolean
    ) {
        val node = if (isEnd) course.endTime else course.startTime
        val slot = try {
            TimeTableManager.getInstance(context).getTimeSlots().find { it.node == node }
        } catch (e: Exception) {
            null
        }
        val clock = if (isEnd) slot?.endTime else slot?.startTime
        val timeText = if (!clock.isNullOrBlank()) clock else "第${node}节"
        val action = if (isEnd) "下课" else "上课"
        val countdownHint = if (isEnd) "距下课还有" else "距上课还有"
        views.setTextViewText(R.id.tv_course_time, "$timeText $action，$countdownHint")
        views.setViewVisibility(R.id.tv_course_time, android.view.View.VISIBLE)
    }

    /** 教室行：为空时整行隐藏，不留孤零零的定位图标 */
    private fun showClassroom(views: RemoteViews, classroom: String) {
        if (classroom.isBlank()) {
            views.setViewVisibility(R.id.ll_location, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.ll_location, android.view.View.VISIBLE)
            views.setTextViewText(R.id.tv_course_location, classroom)
        }
    }

    /** 教师行：为空时整行隐藏 */
    private fun showTeacher(views: RemoteViews, teacher: String) {
        if (teacher.isBlank()) {
            views.setViewVisibility(R.id.tv_course_teacher, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.tv_course_teacher, android.view.View.VISIBLE)
            views.setTextViewText(R.id.tv_course_teacher, teacher)
        }
    }

    /**
     * 距目标时刻（当天分钟数）的秒级倒计时，走系统 Chronometer（进程被杀也能继续走秒）。
     *
     * Chronometer 不补前导零（<1h 显示 "51:15"、≥1h 显示 "1:05:30"），所以按剩余时长指定格式，
     * 让渲染恒为 8 个字符：<1h "00:%s"、1–9h "0%s"、≥10h "%s"。
     * 它自己不会换格式，因此除"到点翻状态"的安全闹钟外，还要在跨 1 小时 / 10 小时整点时回来重设。
     */
    private fun showCountdownTo(
        context: Context,
        views: RemoteViews,
        targetMinutes: Int,
        currentTimeSeconds: Int
    ) {
        val remainingSeconds = (targetMinutes * 60 - currentTimeSeconds).coerceAtLeast(0)
        val remainingMillis = remainingSeconds * 1000L
        val hours = remainingSeconds / 3600
        val format = when {
            hours == 0 -> "00:%s"
            hours < 10 -> "0%s"
            else -> "%s"
        }

        views.setViewVisibility(R.id.tv_countdown, android.view.View.GONE)
        views.setViewVisibility(R.id.chronometer_countdown, android.view.View.VISIBLE)
        views.setChronometerCountDown(R.id.chronometer_countdown, true)
        views.setChronometer(
            R.id.chronometer_countdown,
            SystemClock.elapsedRealtime() + remainingMillis,
            format,
            true
        )

        // 归零时刷新并按新状态重渲染（Chronometer 归零后会继续走成负数）
        scheduleCountdownSafetyUpdate(context, remainingMillis.coerceAtLeast(1000L))

        // 跨过整点（1 小时 / 10 小时）时回来重设格式，否则会残留旧格式的数字
        val boundarySeconds = when {
            hours in 1..9 -> remainingSeconds - 3600
            hours >= 10 -> remainingSeconds - 10 * 3600
            else -> -1
        }
        if (boundarySeconds >= 0) {
            scheduleCountdownFormatUpdate(context, (boundarySeconds + 1) * 1000L)
        } else {
            cancelCountdownFormatUpdate(context)
        }
        cancelMinimalTick(context)
    }

    /**
     * 以静态文本显示倒计时位（无课 / 异常态）
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
     * 跨整点重设 Chronometer 格式的闹钟（复用安全刷新接收器，靠 request code 与到点闹钟区分）
     */
    private fun scheduleCountdownFormatUpdate(context: Context, delayMillis: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_FORMAT_REQUEST_CODE,
                Intent(context, MinimalWidgetSafetyReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            if (delayMillis > 0) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消跨整点的格式更新
     */
    private fun cancelCountdownFormatUpdate(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_MINIMAL_FORMAT_REQUEST_CODE,
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
