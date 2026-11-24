package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar

/**
 * 下一门课提醒 2×2 变体（2026-11）。
 *
 * 内布局对齐 WakeUp 课表 2×2：不铺整行底色，每门课 = 通高圆角色棒 + 文字块；
 * 主课与次课同构三行（课名 / 教室 + 徽标 / 起止时间），次课只是字号略小。
 * 徽标挂在教室行右端：窄屏下课名、教室与起止时间都保持完整；2×2 宽度放不下教师名，
 * 与 4×2 口径一致只显示教室。
 *
 * 主课 = 进行中 → 今天下一门 → 明天第一节，次课 = 主课之后最近的一门；
 * 这样任何时刻都能像 WakeUp 那样同时看到两门课，而不是把半屏留给占位文案。
 * 与 4×2 共用 [NextCourseTimeline.selectPair] 的同一套显示策略。
 *
 * 刷新链与 4×2 完全共用（15 分钟兜底、0 点跨天、分钟 tick、上下课状态切换），
 * 状态切换精确闹钟用独立 requestCode（10018），移除本组件不会影响 4×2 的闹钟。
 */
class NextCourseCompactWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.next.compact.ACTION_REFRESH"
        private const val TRANSITION_REQUEST_CODE = 10018

        @Volatile
        private var clickPendingIntent: PendingIntent? = null

        @Volatile
        private var transitionPendingIntent: PendingIntent? = null

        /** 已挂的状态切换精确闹钟目标时刻（0 = 当前没挂），目标不变就不再重复 setExact */
        @Volatile
        private var transitionAlarmTarget = 0L

        fun triggerUpdate(context: Context) {
            if (!WidgetPresence.isPresent(context, NextCourseCompactWidgetProvider::class.java)) return
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

        private fun alarmPendingIntent(context: Context, cached: PendingIntent?): PendingIntent =
            cached ?: PendingIntent.getBroadcast(
                context,
                TRANSITION_REQUEST_CODE,
                Intent(context, NextCourseTransitionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun updateAllWidgets(context: Context) {
            val appWidgetIds = WidgetPresence.widgetIds(context, NextCourseCompactWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            NextCourseCompactWidgetProvider().onUpdate(
                context, AppWidgetManager.getInstance(context), appWidgetIds
            )
        }

        /** 圆角课程色短棒：下标与 Course.color 的 1 起始色号 - 1 对齐（同 4×2） */
        private val COURSE_BAR_DRAWABLES = intArrayOf(
            R.drawable.widget_bar_1, R.drawable.widget_bar_2, R.drawable.widget_bar_3,
            R.drawable.widget_bar_4, R.drawable.widget_bar_5, R.drawable.widget_bar_6,
            R.drawable.widget_bar_7, R.drawable.widget_bar_8, R.drawable.widget_bar_9
        )

        /** 主课行 / 次课行同构，只是字号与位置不同 */
        private val PRIMARY_IDS = RowIds(
            R.id.bar_primary, R.id.tv_primary_name, R.id.tv_primary_detail,
            R.id.tv_primary_time, R.id.tv_primary_badge
        )
        private val SECONDARY_IDS = RowIds(
            R.id.bar_secondary, R.id.tv_secondary_name, R.id.tv_secondary_detail,
            R.id.tv_secondary_time, R.id.tv_secondary_badge
        )
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 系统回调带来当前完整的实例列表：顺手校准进程内缓存
        WidgetPresence.markUpdated(NextCourseCompactWidgetProvider::class.java, appWidgetIds)
        if (appWidgetIds.isNotEmpty()) {
            // 各实例内容完全一致：只构建一份 RemoteViews，一次 IPC 批量下发
            val views = RemoteViews(context.packageName, R.layout.widget_next_course_compact)
            views.setOnClickPendingIntent(R.id.widget_container, clickPendingIntent(context))
            updateWidgetContent(context, views)
            appWidgetManager.updateAppWidget(appWidgetIds, views)
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
        WidgetPresence.markAbsent(NextCourseCompactWidgetProvider::class.java)
        try {
            cancelTransitionUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ScheduleWidgetUpdateService.onProviderDisabled(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPresence.markDeleted(NextCourseCompactWidgetProvider::class.java, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE" -> updateAllWidgets(context)
        }
    }

    /**
     * 课程行（主课/次课同构）：色棒 + 课名 / 教室 + 徽标 / 起止时间。
     * [timeText] 为 null 时隐藏时间行（占位/失败态）。
     */
    private data class CourseRow(
        val barRes: Int,
        val name: String,
        val detail: String,
        val timeText: String?,
        val badge: String?
    )

    /** 一行课程对应的视图 id 组 */
    private data class RowIds(
        val bar: Int,
        val name: Int,
        val detail: Int,
        val time: Int,
        val badge: Int
    )

    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val timeline = NextCourseTimeline.resolve(context)
            val selection = timeline.selectPair(context)

            // 主课：进行中 → 今天下一门 → 明天第一节；都没课给「今明没有课程」占位
            val primary = selection.primary?.let { rowFor(context, it, timeline) }
                ?: placeholderRow(settingsManager, timeline)

            // 次课：主课之后最近的一门，同样三行（课名 / 教室+徽标 / 起止时间）
            val secondary = selection.secondary?.let { rowFor(context, it, timeline) }

            render(context, views, primary, secondary, timeline.todayDayOfWeek)
            scheduleTransitions(context, timeline)
        } catch (e: Exception) {
            renderLoadFailure(views)
        }
    }

    /** 一门课 → 三行渲染状态；主课/次课只在字号上不同 */
    private fun rowFor(context: Context, course: Course, timeline: NextCourseTimeline): CourseRow {
        val start = CourseTimeUtils.getStartMinutes(context, course)
        val end = CourseTimeUtils.getEndMinutes(context, course)
        return CourseRow(
            barRes = barRes(course),
            name = course.name,
            // 2×2 宽度有限：只显示教室，教师名交给课程详情页（与 4×2 口径一致）
            detail = course.classroom,
            timeText = "${formatWidgetMinutes(start)} - ${formatWidgetMinutes(end)}",
            badge = timeline.badgeFor(context, course)
        )
    }

    /** 今明都没有课：灰棒 +「今明没有课程」+ 学期剩余周数 */
    private fun placeholderRow(
        settingsManager: SettingsManager,
        timeline: NextCourseTimeline
    ): CourseRow {
        val remainingWeeks = settingsManager.getTotalWeeks() - timeline.currentWeek
        return CourseRow(
            R.drawable.widget_bar_neutral,
            "今明没有课程",
            if (remainingWeeks > 0) "本学期还剩 $remainingWeeks 周" else "本学期课程已结束",
            null, null
        )
    }

    /** 状态切换点：当前课下课 / 下一门上课 / 0 点跨天，取最早（与 4×2 同口径） */
    private fun scheduleTransitions(context: Context, timeline: NextCourseTimeline) {
        val transitions = mutableListOf<Long>()
        timeline.current?.let {
            transitions.add(transitionMillis(CourseTimeUtils.getEndMinutes(context, it)))
        }
        timeline.nextToday?.let {
            transitions.add(transitionMillis(CourseTimeUtils.getStartMinutes(context, it)))
        }
        transitions.add(timeline.tomorrowMillis)
        scheduleNextTransitionUpdate(context, transitions.minOrNull())
    }

    private fun barRes(course: Course): Int {
        val index = if (course.color > 0) (course.color - 1) % COURSE_BAR_DRAWABLES.size else 0
        return COURSE_BAR_DRAWABLES[index]
    }

    private fun render(
        context: Context,
        views: RemoteViews,
        primary: CourseRow,
        secondary: CourseRow?,
        todayDayOfWeek: Int
    ) {
        views.setTextViewText(R.id.tv_compact_label, "下一门课提醒")
        views.setTextViewText(R.id.tv_compact_day, "周${"一二三四五六日"[todayDayOfWeek - 1]}")

        renderRow(context, views, PRIMARY_IDS, primary)
        if (secondary == null) {
            views.setViewVisibility(R.id.row_secondary, View.GONE)
        } else {
            views.setViewVisibility(R.id.row_secondary, View.VISIBLE)
            renderRow(context, views, SECONDARY_IDS, secondary)
        }
    }

    private fun renderRow(context: Context, views: RemoteViews, ids: RowIds, row: CourseRow) {
        views.setViewVisibility(ids.bar, View.VISIBLE)
        views.setInt(ids.bar, "setBackgroundResource", row.barRes)
        views.setTextViewText(ids.name, row.name)
        views.setTextViewText(ids.detail, row.detail)
        if (row.timeText == null) {
            views.setViewVisibility(ids.time, View.GONE)
        } else {
            views.setViewVisibility(ids.time, View.VISIBLE)
            views.setTextViewText(ids.time, row.timeText)
        }
        renderBadge(context, views, ids.badge, row.badge)
    }

    private fun renderBadge(context: Context, views: RemoteViews, viewId: Int, badge: String?) {
        if (badge == null) {
            views.setViewVisibility(viewId, View.GONE)
        } else {
            views.setViewVisibility(viewId, View.VISIBLE)
            views.setTextViewText(viewId, badge)
            views.setTextColor(viewId, context.getColor(R.color.widget_accent))
        }
    }

    /** 数据读取失败：主课行报错，次课行整体收起 */
    private fun renderLoadFailure(views: RemoteViews) {
        views.setTextViewText(R.id.tv_compact_label, "下一门课提醒")
        views.setTextViewText(R.id.tv_compact_day, "")
        views.setInt(R.id.bar_primary, "setBackgroundResource", R.drawable.widget_bar_neutral)
        views.setTextViewText(R.id.tv_primary_name, "课程数据读取失败")
        views.setTextViewText(R.id.tv_primary_detail, "请打开 App 检查课表导入")
        views.setViewVisibility(R.id.tv_primary_time, View.GONE)
        views.setViewVisibility(R.id.tv_primary_badge, View.GONE)
        views.setViewVisibility(R.id.row_secondary, View.GONE)
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
     * 目标时刻没变就不再重复 setExact（分钟 tick 每分钟都会调到这里）。
     */
    private fun scheduleNextTransitionUpdate(context: Context, targetMillis: Long?) {
        try {
            if (targetMillis == null || targetMillis <= System.currentTimeMillis() + 5000L) {
                cancelTransitionUpdate(context)
                return
            }
            if (targetMillis == transitionAlarmTarget) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(context, transitionPendingIntent)
            transitionPendingIntent = pendingIntent
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
            // 只有 set 成功才记目标：尊重 Android 14+ 精确闹钟被拒时的原有失败回退语义
            transitionAlarmTarget = targetMillis
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelTransitionUpdate(context: Context) {
        if (transitionAlarmTarget == 0L) return
        transitionAlarmTarget = 0L
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(alarmPendingIntent(context, transitionPendingIntent))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 周期刷新：并入「所有小组件共用的一条 15 分钟闹钟」
     * （见 [ScheduleWidgetUpdateService.ensurePeriodicUpdate]），不再单独挂一条。
     */
    fun schedulePeriodicUpdate(context: Context) {
        ScheduleWidgetUpdateService.ensurePeriodicUpdate(context)
    }
}
