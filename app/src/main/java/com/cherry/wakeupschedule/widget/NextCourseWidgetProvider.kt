package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar

/**
 * 下一门课提醒小组件（官网「桌面小组件专区」4×2 款；2026-10 双课展示 + WakeUp 式两列排版）。
 *
 * 三行结构：标题行「下一门课提醒 | 第N周 · 周X（accent 高亮）」+ 两门课行等分剩余高度；
 * 每行 = 圆角课程色短棒（只包文字高度）+ 左列「课名 / 教室」+ 右列「起 / 止时间」；
 * 徽标为课名行右侧的相对语义彩色小字：进行中 / N 分钟后（<60min）/ N 小时后 / 明天。
 * 第一行 = 今天进行中的课（无则「当前没有课程」占位，细节行按今天剩余情况三分支），
 * 第二行 = 今天下一门 → 今天清空后降级明天第一节 → 都无课给「明天无课 + 本学期还剩 N 周 · 好好休息」；
 * 取数范围只含今天与明天。
 *
 * 刷新：15 分钟周期闹钟 + 状态切换点（上课/下课/0 点跨天）精确闹钟 + App 分钟 tick + 0 点跨天。
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

        /** 一门课行对应的视图 id 组（当前课行 / 下一门课行同构） */
        private data class RowIds(
            val bar: Int,
            val name: Int,
            val badge: Int,
            val start: Int,
            val end: Int,
            val detail: Int
        )

        private val CURRENT_ROW_IDS = RowIds(
            R.id.bar_current, R.id.tv_current_name, R.id.tv_current_badge,
            R.id.tv_current_start, R.id.tv_current_end, R.id.tv_current_detail
        )
        private val NEXT_ROW_IDS = RowIds(
            R.id.bar_next, R.id.tv_next_name, R.id.tv_next_badge,
            R.id.tv_next_start, R.id.tv_next_end, R.id.tv_next_detail
        )

        /** 圆角课程色短棒：下标与 Course.color 的 1 起始色号 - 1 对齐（同 ThemeManager 9 色顺序） */
        private val COURSE_BAR_DRAWABLES = intArrayOf(
            R.drawable.widget_bar_1, R.drawable.widget_bar_2, R.drawable.widget_bar_3,
            R.drawable.widget_bar_4, R.drawable.widget_bar_5, R.drawable.widget_bar_6,
            R.drawable.widget_bar_7, R.drawable.widget_bar_8, R.drawable.widget_bar_9
        )
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

    /**
     * 单门课行的渲染状态：badge 为 null 时隐藏；startText/endText 为 null 时右列时间隐藏（占位行）。
     * detail 为左列第二行：课程行放教室，占位行放今天/学期视角文案。
     */
    private data class CourseRowState(
        val barRes: Int,
        val name: String,
        val badge: String?,
        val detail: String,
        val startText: String?,
        val endText: String?
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

            val todayMidnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val tomorrowMillis = todayMidnight + 24 * 60 * 60 * 1000L
            val tomorrowDow = todayDayOfWeek % 7 + 1
            val tomorrowWeek = CourseTimeUtils.getWeekForDate(settingsManager, tomorrowMillis)
            val tomorrowFirst = allCourses
                .filter { it.dayOfWeek == tomorrowDow && it.isActiveInWeek(tomorrowWeek) }
                .minByOrNull { CourseTimeUtils.getStartMinutes(context, it) }

            // 第一行（今天视角）：进行中的课；无课进行中时不得空置，给灰棒占位行
            val currentRow = if (current != null) {
                courseRow(context, current, "进行中")
            } else {
                val detail = when {
                    nextToday != null ->
                        "今天还剩 ${
                            todayCourses.count { CourseTimeUtils.getStartMinutes(context, it) > nowMinutes }
                        } 门课"
                    todayCourses.isNotEmpty() -> "今天的课程已全部结束"
                    else -> "今天没有安排"
                }
                CourseRowState(
                    R.drawable.widget_bar_neutral, "当前没有课程", null, detail, null, null
                )
            }

            // 第二行（未来视角）：今天下一门 → 今天清空后降级明天第一节 → 都无课给休息占位（取数只含今天与明天）
            val nextRow = when {
                nextToday != null -> {
                    val start = CourseTimeUtils.getStartMinutes(context, nextToday)
                    val gap = start - nowMinutes
                    val badge = if (gap < 60) "$gap 分钟后" else "${(gap + 30) / 60} 小时后"
                    courseRow(context, nextToday, badge)
                }
                tomorrowFirst != null -> courseRow(context, tomorrowFirst, "明天")
                else -> {
                    val remainingWeeks = settingsManager.getTotalWeeks() - currentWeek
                    val rest = if (remainingWeeks > 0) "本学期还剩 $remainingWeeks 周 · 好好休息"
                    else "本学期课程已结束 · 好好休息"
                    CourseRowState(
                        R.drawable.widget_bar_neutral, "明天无课", null, rest, null, null
                    )
                }
            }

            render(context, views, currentRow, nextRow, currentWeek, todayDayOfWeek)

            // 状态切换点：当前课下课 / 下一门上课 / 0 点跨天（今天明天角色互换），取最早
            val transitions = mutableListOf<Long>()
            current?.let { transitions.add(transitionMillis(CourseTimeUtils.getEndMinutes(context, it))) }
            nextToday?.let { transitions.add(transitionMillis(CourseTimeUtils.getStartMinutes(context, it))) }
            transitions.add(tomorrowMillis)
            scheduleNextTransitionUpdate(context, transitions.minOrNull())
        } catch (e: Exception) {
            renderLoadFailure(context, views)
        }
    }

    /**
     * 课程行状态：徽标为相对语义（进行中 / N 分钟后 / N 小时后 / 明天），绝对时间由右列承担；
     * 短棒色与课表页同口径（Course.color 为 1 起始色号，0 取首色）。
     */
    private fun courseRow(context: Context, course: Course, badge: String?): CourseRowState {
        val index = if (course.color > 0) (course.color - 1) % COURSE_BAR_DRAWABLES.size else 0
        val start = CourseTimeUtils.getStartMinutes(context, course)
        val end = CourseTimeUtils.getEndMinutes(context, course)
        return CourseRowState(
            COURSE_BAR_DRAWABLES[index],
            course.name,
            badge,
            detail = if (course.classroom.isBlank()) "" else course.classroom,
            startText = formatMinutes(start),
            endText = formatMinutes(end)
        )
    }

    private fun render(
        context: Context,
        views: RemoteViews,
        currentRow: CourseRowState,
        nextRow: CourseRowState,
        currentWeek: Int,
        todayDayOfWeek: Int
    ) {
        views.setTextViewText(R.id.tv_next_label, "下一门课提醒")
        val dayName = "一二三四五六日"[todayDayOfWeek - 1]
        views.setTextViewText(R.id.tv_next_week, "第${currentWeek}周 · ")
        views.setTextViewText(R.id.tv_next_day, "周$dayName")
        renderRow(context, views, CURRENT_ROW_IDS, currentRow)
        renderRow(context, views, NEXT_ROW_IDS, nextRow)
    }

    private fun renderRow(context: Context, views: RemoteViews, ids: RowIds, state: CourseRowState) {
        views.setViewVisibility(ids.bar, View.VISIBLE)
        views.setInt(ids.bar, "setBackgroundResource", state.barRes)
        views.setTextViewText(ids.name, state.name)
        if (state.badge == null) {
            views.setViewVisibility(ids.badge, View.GONE)
        } else {
            views.setViewVisibility(ids.badge, View.VISIBLE)
            views.setTextViewText(ids.badge, state.badge)
            views.setTextColor(ids.badge, context.getColor(R.color.widget_accent))
        }
        if (state.startText == null) {
            views.setViewVisibility(ids.start, View.GONE)
            views.setViewVisibility(ids.end, View.GONE)
        } else {
            views.setViewVisibility(ids.start, View.VISIBLE)
            views.setViewVisibility(ids.end, View.VISIBLE)
            views.setTextViewText(ids.start, state.startText)
            views.setTextViewText(ids.end, state.endText)
        }
        views.setTextViewText(ids.detail, state.detail)
    }

    /** 数据读取失败：第一行灰棒 + 报错文案，第二行整体留空 */
    private fun renderLoadFailure(context: Context, views: RemoteViews) {
        views.setTextViewText(R.id.tv_next_label, "下一门课提醒")
        views.setTextViewText(R.id.tv_next_week, "")
        views.setTextViewText(R.id.tv_next_day, "")
        renderRow(
            context, views, CURRENT_ROW_IDS,
            CourseRowState(
                R.drawable.widget_bar_neutral, "课程数据读取失败", null,
                "请打开 App 检查课表导入", null, null
            )
        )
        views.setViewVisibility(NEXT_ROW_IDS.bar, View.INVISIBLE)
        views.setTextViewText(NEXT_ROW_IDS.name, "")
        views.setViewVisibility(NEXT_ROW_IDS.badge, View.GONE)
        views.setViewVisibility(NEXT_ROW_IDS.start, View.GONE)
        views.setViewVisibility(NEXT_ROW_IDS.end, View.GONE)
        views.setTextViewText(NEXT_ROW_IDS.detail, "")
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
