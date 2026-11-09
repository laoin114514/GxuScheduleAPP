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
 * 下一门课提醒小组件（官网「桌面小组件专区」4×2 款；2026-10 双课展示 + WakeUp 式两列排版）。
 *
 * 三行结构：标题行「下一门课提醒 | 第N周 · 周X（accent 高亮）」+ 两门课行等分剩余高度；
 * 每行 = 圆角课程色短棒（只包文字高度）+ 左列「课名 / 教室」+ 右列「起 / 止时间」；
 * 徽标为课名行右侧的相对语义彩色小字：进行中 / N 分钟后（<60min）/ N 小时后 / 明天。
 *
 * 显示策略与 2×2 共用 [NextCourseTimeline.selectPair]：
 * 主课 = 进行中 → 今天下一门 → 明天第一节（不再为「当前没有课程」单独占位）；
 * 次课 = 主课之后最近的一门（今天后续课优先，否则明天第一节/第二节），没有则隐藏第二行；
 * 今明都没有课时给「今明没有课程 + 本学期还剩 N 周」。取数范围只含今天与明天。
 *
 * 刷新：15 分钟周期闹钟 + 状态切换点（上课/下课/0 点跨天）精确闹钟 + App 分钟 tick + 0 点跨天。
 *
 * 性能口径（工作流 C）：无实例纯内存短路；有实例直接进程内刷新（不走广播）；
 * 一份 RemoteViews 批量下发；15 分钟周期与状态切换精确闹钟都按「已挂 / 目标时刻」去重。
 */
class NextCourseWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.next.ACTION_REFRESH"
        private const val NEXT_COURSE_TRANSITION_REQUEST_CODE = 10010

        @Volatile
        private var clickPendingIntent: PendingIntent? = null

        @Volatile
        private var transitionPendingIntent: PendingIntent? = null

        /** 已挂的状态切换精确闹钟目标时刻（0 = 当前没挂），目标不变就不再重复 setExact */
        @Volatile
        private var transitionAlarmTarget = 0L

        fun triggerUpdate(context: Context) {
            if (!WidgetPresence.isPresent(context, NextCourseWidgetProvider::class.java)) return
            updateAllWidgets(context)
        }

        private fun formatMinutes(minutes: Int): String =
            "%02d:%02d".format(minutes / 60, minutes % 60)

        /** 一门课行对应的视图 id 组（主课行 / 次课行同构三行） */
        private data class RowIds(
            val bar: Int,
            val name: Int,
            val badge: Int,
            val detail: Int,
            val time: Int
        )

        private val CURRENT_ROW_IDS = RowIds(
            R.id.bar_current, R.id.tv_current_name, R.id.tv_current_badge,
            R.id.tv_current_detail, R.id.tv_current_time
        )
        private val NEXT_ROW_IDS = RowIds(
            R.id.bar_next, R.id.tv_next_name, R.id.tv_next_badge,
            R.id.tv_next_detail, R.id.tv_next_time
        )

        /** 圆角课程色短棒：下标与 Course.color 的 1 起始色号 - 1 对齐（同 ThemeManager 9 色顺序） */
        private val COURSE_BAR_DRAWABLES = intArrayOf(
            R.drawable.widget_bar_1, R.drawable.widget_bar_2, R.drawable.widget_bar_3,
            R.drawable.widget_bar_4, R.drawable.widget_bar_5, R.drawable.widget_bar_6,
            R.drawable.widget_bar_7, R.drawable.widget_bar_8, R.drawable.widget_bar_9
        )

        private fun clickPendingIntent(context: Context): PendingIntent =
            clickPendingIntent ?: PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { clickPendingIntent = it }

        private fun alarmPendingIntent(
            context: Context,
            requestCode: Int,
            receiverClass: Class<*>,
            cached: PendingIntent?
        ): PendingIntent = cached ?: PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, receiverClass),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun updateAllWidgets(context: Context) {
            val appWidgetIds = WidgetPresence.widgetIds(context, NextCourseWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            NextCourseWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 系统回调带来当前完整的实例列表：顺手校准进程内缓存
        WidgetPresence.markUpdated(NextCourseWidgetProvider::class.java, appWidgetIds)
        if (appWidgetIds.isNotEmpty()) {
            // 各实例内容完全一致：只构建一份 RemoteViews，一次 IPC 批量下发
            val views = RemoteViews(context.packageName, R.layout.widget_next_course)
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
        // 借用全局更新链调度 30 分钟兜底刷新
        ScheduleWidgetUpdateService.triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.markAbsent(NextCourseWidgetProvider::class.java)
        try {
            cancelTransitionUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 如果这是桌面上最后一个小组件，顺手停掉 30 分钟兜底链
        ScheduleWidgetUpdateService.onProviderDisabled(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPresence.markDeleted(NextCourseWidgetProvider::class.java, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE" -> updateAllWidgets(context)
        }
    }

    /**
     * 单门课行的渲染状态：badge 为 null 时隐藏；timeText 为 null 时时间行隐藏（占位行）。
     * detail 为第二行：课程行放教室，占位行放学期视角文案。
     */
    private data class CourseRowState(
        val barRes: Int,
        val name: String,
        val badge: String?,
        val detail: String,
        val timeText: String?
    )

    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val timeline = NextCourseTimeline.resolve(context)
            val selection = timeline.selectPair(context)

            // 主课：进行中 → 今天下一门 → 明天第一节；都没课给「今明没有课程」占位
            val primaryRow = selection.primary?.let {
                courseRow(context, it, timeline.badgeFor(context, it))
            } ?: placeholderRow(settingsManager, timeline)

            // 次课：主课之后最近的一门；没有则隐藏第二行（主课独占高度）
            val secondaryRow = selection.secondary?.let {
                courseRow(context, it, timeline.badgeFor(context, it))
            }

            render(
                context, views, primaryRow, secondaryRow,
                timeline.currentWeek, timeline.todayDayOfWeek
            )

            // 状态切换点：当前课下课 / 下一门上课 / 0 点跨天（今天明天角色互换），取最早
            val transitions = mutableListOf<Long>()
            timeline.current?.let {
                transitions.add(transitionMillis(CourseTimeUtils.getEndMinutes(context, it)))
            }
            timeline.nextToday?.let {
                transitions.add(transitionMillis(CourseTimeUtils.getStartMinutes(context, it)))
            }
            transitions.add(timeline.tomorrowMillis)
            scheduleNextTransitionUpdate(context, transitions.minOrNull())
        } catch (e: Exception) {
            renderLoadFailure(context, views)
        }
    }

    /** 今明都没有课：灰棒 +「今明没有课程」+ 学期剩余周数 */
    private fun placeholderRow(
        settingsManager: SettingsManager,
        timeline: NextCourseTimeline
    ): CourseRowState {
        val remainingWeeks = settingsManager.getTotalWeeks() - timeline.currentWeek
        val rest = if (remainingWeeks > 0) "本学期还剩 $remainingWeeks 周"
        else "本学期课程已结束"
        return CourseRowState(
            R.drawable.widget_bar_neutral, "今明没有课程", null, rest, null
        )
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
            timeText = "${formatMinutes(start)} - ${formatMinutes(end)}"
        )
    }

    private fun render(
        context: Context,
        views: RemoteViews,
        primaryRow: CourseRowState,
        secondaryRow: CourseRowState?,
        currentWeek: Int,
        todayDayOfWeek: Int
    ) {
        views.setTextViewText(R.id.tv_next_label, "下一门课提醒")
        val dayName = "一二三四五六日"[todayDayOfWeek - 1]
        views.setTextViewText(R.id.tv_next_week, "第${currentWeek}周 · ")
        views.setTextViewText(R.id.tv_next_day, "周$dayName")
        renderRow(context, views, CURRENT_ROW_IDS, primaryRow)
        if (secondaryRow == null) {
            views.setViewVisibility(R.id.row_next, View.GONE)
        } else {
            views.setViewVisibility(R.id.row_next, View.VISIBLE)
            renderRow(context, views, NEXT_ROW_IDS, secondaryRow)
        }
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
        if (state.timeText == null) {
            views.setViewVisibility(ids.time, View.GONE)
        } else {
            views.setViewVisibility(ids.time, View.VISIBLE)
            views.setTextViewText(ids.time, state.timeText)
        }
        views.setTextViewText(ids.detail, state.detail)
    }

    /** 数据读取失败：第一行灰棒 + 报错文案，第二行整体收起 */
    private fun renderLoadFailure(context: Context, views: RemoteViews) {
        views.setTextViewText(R.id.tv_next_label, "下一门课提醒")
        views.setTextViewText(R.id.tv_next_week, "")
        views.setTextViewText(R.id.tv_next_day, "")
        renderRow(
            context, views, CURRENT_ROW_IDS,
            CourseRowState(
                R.drawable.widget_bar_neutral, "课程数据读取失败", null,
                "请打开 App 检查课表导入", null
            )
        )
        views.setViewVisibility(R.id.row_next, View.GONE)
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
            val pendingIntent = alarmPendingIntent(
                context,
                NEXT_COURSE_TRANSITION_REQUEST_CODE,
                NextCourseTransitionReceiver::class.java,
                transitionPendingIntent
            )
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
            alarmManager.cancel(
                alarmPendingIntent(
                    context,
                    NEXT_COURSE_TRANSITION_REQUEST_CODE,
                    NextCourseTransitionReceiver::class.java,
                    transitionPendingIntent
                )
            )
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
