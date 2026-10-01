package com.cherry.wakeupschedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar

/**
 * 一周课程微缩矩阵小组件（官网「桌面小组件专区」4×4 款）。
 *
 * 每天一格显示「星期 + 课程数」，当日格实底高亮；默认只显示周一~周五，
 * 周六或周日有课时切换为 7 列。内容只在跨天/课程变更时变化，
 * 刷新依赖 30 分钟链式闹钟（ScheduleWidgetUpdateService）与 0 点跨天闹钟，自身不额外调度。
 */
class WeekViewWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.weekview.ACTION_REFRESH"

        private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

        private val CELL_IDS_5 = listOf(
            R.id.day5_1, R.id.day5_2, R.id.day5_3, R.id.day5_4, R.id.day5_5
        )
        private val LABEL_IDS_5 = listOf(
            R.id.day5_1_label, R.id.day5_2_label, R.id.day5_3_label, R.id.day5_4_label, R.id.day5_5_label
        )
        private val COUNT_IDS_5 = listOf(
            R.id.day5_1_count, R.id.day5_2_count, R.id.day5_3_count, R.id.day5_4_count, R.id.day5_5_count
        )
        private val CELL_IDS_7 = listOf(
            R.id.day7_1, R.id.day7_2, R.id.day7_3, R.id.day7_4,
            R.id.day7_5, R.id.day7_6, R.id.day7_7
        )
        private val LABEL_IDS_7 = listOf(
            R.id.day7_1_label, R.id.day7_2_label, R.id.day7_3_label, R.id.day7_4_label,
            R.id.day7_5_label, R.id.day7_6_label, R.id.day7_7_label
        )
        private val COUNT_IDS_7 = listOf(
            R.id.day7_1_count, R.id.day7_2_count, R.id.day7_3_count, R.id.day7_4_count,
            R.id.day7_5_count, R.id.day7_6_count, R.id.day7_7_count
        )

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
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
        WidgetMidnightReceiver.scheduleMidnightUpdate(context)
        // 借用全局更新链调度 30 分钟兜底刷新（即使本组件是唯一小组件也有兜底）
        ScheduleWidgetUpdateService.triggerUpdate(context)
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
            val todayDayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)

            views.setTextViewText(R.id.tv_week_number, "第${currentWeek}周")

            // 当日高亮仅在本组件展示的就是"今天所在的那一周"时生效（学期未设置/假期外不强亮）
            val semesterStart = settingsManager.getSemesterStartDate()
            val rawWeek = if (semesterStart == 0L) 0
            else (((System.currentTimeMillis() - semesterStart) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1)
            val highlightToday = rawWeek in 1..settingsManager.getTotalWeeks()

            // 统计本周每天的课程数（周一~周日）；库里可能存在星期为 0 的异常数据，需过滤
            val counts = IntArray(7)
            CourseDataManager.getInstance(context).getAllCourses().forEach { course ->
                if (course.dayOfWeek in 1..7 && course.isActiveInWeek(currentWeek)) {
                    counts[course.dayOfWeek - 1]++
                }
            }

            // 默认 5 列（一~五），周六或周日有课时扩为 7 列
            val showWeekend = counts[5] > 0 || counts[6] > 0
            views.setViewVisibility(
                R.id.ll_week_days_5,
                if (showWeekend) android.view.View.GONE else android.view.View.VISIBLE
            )
            views.setViewVisibility(
                R.id.ll_week_days_7,
                if (showWeekend) android.view.View.VISIBLE else android.view.View.GONE
            )

            val cellIds = if (showWeekend) CELL_IDS_7 else CELL_IDS_5
            val labelIds = if (showWeekend) LABEL_IDS_7 else LABEL_IDS_5
            val countIds = if (showWeekend) COUNT_IDS_7 else COUNT_IDS_5
            for (i in 0 until (if (showWeekend) 7 else 5)) {
                applyDayCell(
                    context, views, cellIds[i], labelIds[i], countIds[i],
                    DAY_LABELS[i], counts[i],
                    highlightToday && (i + 1) == todayDayOfWeek
                )
            }

            views.setTextViewText(R.id.tv_week_total, "本周共 ${counts.sum()} 节课")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 填充一个日期格：格子容器上背景，两个文字按状态着色 */
    private fun applyDayCell(
        context: Context,
        views: RemoteViews,
        cellId: Int,
        labelId: Int,
        countId: Int,
        label: String,
        count: Int,
        isToday: Boolean
    ) {
        views.setTextViewText(labelId, label)
        views.setTextViewText(countId, "${count}节")
        when {
            isToday -> {
                views.setInt(cellId, "setBackgroundResource", R.drawable.widget_day_today)
                val color = context.getColor(R.color.widget_day_text_today)
                views.setTextColor(labelId, color)
                views.setTextColor(countId, color)
            }
            count > 0 -> {
                views.setInt(cellId, "setBackgroundResource", R.drawable.widget_day_has)
                val color = context.getColor(R.color.widget_day_text_has)
                views.setTextColor(labelId, color)
                views.setTextColor(countId, color)
            }
            else -> {
                views.setInt(cellId, "setBackgroundResource", R.drawable.widget_day_empty)
                val color = context.getColor(R.color.widget_day_text_empty)
                views.setTextColor(labelId, color)
                views.setTextColor(countId, color)
            }
        }
    }
}
