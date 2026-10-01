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
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar

/**
 * 一周课程微缩地图小组件（官网「桌面小组件专区」4×4 款）。
 *
 * 周一~周日 7 列时段网格：课程只以 1-2 / 3-4 / 5-6 / 7-8 / 11-12 节的整段安排，
 * 每列即 5 个课程时段容器（各代表两节，随组件高度拉伸）+ 9-10 节薄空档。
 * 一门课 = 一个整块容器（不会在中缝被拆开），块内两行：课名（粗）+ 教室；
 * 底色与应用内 FIXED_COURSE_COLORS 配色一致（半透明填充+描边，与课表同画法）；
 * 当日列淡蓝高亮。同段重叠的脏数据只保留先到的一门，9-10 节的课程（理论不存在）跳过。
 * 内容只在跨天/课程变更时变化，刷新依赖 30 分钟链式闹钟（ScheduleWidgetUpdateService）
 * 与 0 点跨天闹钟，自身不额外调度。
 */
class WeekViewWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.weekview.ACTION_REFRESH"

        private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

        /** 课程块底色与应用内配色一致：ThemeManager.FIXED_COURSE_COLORS 共 9 色 */
        private const val COURSE_COLOR_COUNT = 9

        private val COL_IDS = listOf(
            R.id.week_col_1, R.id.week_col_2, R.id.week_col_3, R.id.week_col_4,
            R.id.week_col_5, R.id.week_col_6, R.id.week_col_7
        )
        private val LABEL_IDS = listOf(
            R.id.week_1_label, R.id.week_2_label, R.id.week_3_label, R.id.week_4_label,
            R.id.week_5_label, R.id.week_6_label, R.id.week_7_label
        )

        /** [day][slot] → 时段容器/课名/教室 id；slot 0-4 对应 1-2 / 3-4 / 5-6 / 7-8 / 11-12 节 */
        private val SLOT_IDS: List<List<Int>> = listOf(
            listOf(R.id.week_1_s1, R.id.week_1_s2, R.id.week_1_s3, R.id.week_1_s4, R.id.week_1_s5),
            listOf(R.id.week_2_s1, R.id.week_2_s2, R.id.week_2_s3, R.id.week_2_s4, R.id.week_2_s5),
            listOf(R.id.week_3_s1, R.id.week_3_s2, R.id.week_3_s3, R.id.week_3_s4, R.id.week_3_s5),
            listOf(R.id.week_4_s1, R.id.week_4_s2, R.id.week_4_s3, R.id.week_4_s4, R.id.week_4_s5),
            listOf(R.id.week_5_s1, R.id.week_5_s2, R.id.week_5_s3, R.id.week_5_s4, R.id.week_5_s5),
            listOf(R.id.week_6_s1, R.id.week_6_s2, R.id.week_6_s3, R.id.week_6_s4, R.id.week_6_s5),
            listOf(R.id.week_7_s1, R.id.week_7_s2, R.id.week_7_s3, R.id.week_7_s4, R.id.week_7_s5)
        )
        private val NAME_IDS: List<List<Int>> = listOf(
            listOf(
                R.id.week_1_s1_name, R.id.week_1_s2_name, R.id.week_1_s3_name,
                R.id.week_1_s4_name, R.id.week_1_s5_name
            ),
            listOf(
                R.id.week_2_s1_name, R.id.week_2_s2_name, R.id.week_2_s3_name,
                R.id.week_2_s4_name, R.id.week_2_s5_name
            ),
            listOf(
                R.id.week_3_s1_name, R.id.week_3_s2_name, R.id.week_3_s3_name,
                R.id.week_3_s4_name, R.id.week_3_s5_name
            ),
            listOf(
                R.id.week_4_s1_name, R.id.week_4_s2_name, R.id.week_4_s3_name,
                R.id.week_4_s4_name, R.id.week_4_s5_name
            ),
            listOf(
                R.id.week_5_s1_name, R.id.week_5_s2_name, R.id.week_5_s3_name,
                R.id.week_5_s4_name, R.id.week_5_s5_name
            ),
            listOf(
                R.id.week_6_s1_name, R.id.week_6_s2_name, R.id.week_6_s3_name,
                R.id.week_6_s4_name, R.id.week_6_s5_name
            ),
            listOf(
                R.id.week_7_s1_name, R.id.week_7_s2_name, R.id.week_7_s3_name,
                R.id.week_7_s4_name, R.id.week_7_s5_name
            )
        )
        private val ROOM_IDS: List<List<Int>> = listOf(
            listOf(
                R.id.week_1_s1_room, R.id.week_1_s2_room, R.id.week_1_s3_room,
                R.id.week_1_s4_room, R.id.week_1_s5_room
            ),
            listOf(
                R.id.week_2_s1_room, R.id.week_2_s2_room, R.id.week_2_s3_room,
                R.id.week_2_s4_room, R.id.week_2_s5_room
            ),
            listOf(
                R.id.week_3_s1_room, R.id.week_3_s2_room, R.id.week_3_s3_room,
                R.id.week_3_s4_room, R.id.week_3_s5_room
            ),
            listOf(
                R.id.week_4_s1_room, R.id.week_4_s2_room, R.id.week_4_s3_room,
                R.id.week_4_s4_room, R.id.week_4_s5_room
            ),
            listOf(
                R.id.week_5_s1_room, R.id.week_5_s2_room, R.id.week_5_s3_room,
                R.id.week_5_s4_room, R.id.week_5_s5_room
            ),
            listOf(
                R.id.week_6_s1_room, R.id.week_6_s2_room, R.id.week_6_s3_room,
                R.id.week_6_s4_room, R.id.week_6_s5_room
            ),
            listOf(
                R.id.week_7_s1_room, R.id.week_7_s2_room, R.id.week_7_s3_room,
                R.id.week_7_s4_room, R.id.week_7_s5_room
            )
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

            // 本周每天的课程，按开始节次排序；库里可能存在星期为 0 的异常数据（实践课），需过滤
            val dayCourses = Array(7) { mutableListOf<Course>() }
            CourseDataManager.getInstance(context).getAllCourses().forEach { course ->
                if (course.dayOfWeek in 1..7 && course.isActiveInWeek(currentWeek)) {
                    dayCourses[course.dayOfWeek - 1].add(course)
                }
            }
            dayCourses.forEach { it.sortBy { c -> c.startTime } }

            val weakColor = context.getColor(R.color.widget_day_text_empty)
            val hasColor = context.getColor(R.color.widget_text_secondary)
            val todayColor = context.getColor(R.color.widget_accent)

            for (day in 1..7) {
                val courses = dayCourses[day - 1]
                val isToday = highlightToday && day == todayDayOfWeek

                // 当日列淡蓝高亮，其余列清除背景
                views.setInt(
                    COL_IDS[day - 1], "setBackgroundResource",
                    if (isToday) R.drawable.widget_week_col_today else 0
                )
                views.setTextViewText(LABEL_IDS[day - 1], DAY_LABELS[day - 1])
                views.setTextColor(
                    LABEL_IDS[day - 1],
                    when {
                        isToday -> todayColor
                        courses.isNotEmpty() -> hasColor
                        else -> weakColor
                    }
                )

                renderDaySlots(views, day, courses)
            }

            views.setTextViewText(R.id.tv_week_total, "本周共 ${dayCourses.sumOf { it.size }} 节课")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 把一天的课程铺进 5 个时段：起节 (startTime-1)/2 即时段序号（1-2→0、3-4→1、…、11-12→5），
     * 序号 4（9-10 节薄空档）不排课直接跳过，其余时段一门课占一个整块。
     */
    private fun renderDaySlots(views: RemoteViews, day: Int, courses: List<Course>) {
        val slotIds = SLOT_IDS[day - 1]
        val nameIds = NAME_IDS[day - 1]
        val roomIds = ROOM_IDS[day - 1]
        val occupants = arrayOfNulls<Course>(slotIds.size)
        for (course in courses) {
            val slot = courseSlotIndex(course.startTime)
            if (slot == null) continue
            if (occupants[slot] != null) continue // 同段重叠的脏数据：保留先到的
            occupants[slot] = course
        }

        for (slot in slotIds.indices) {
            val containerId = slotIds[slot]
            val nameId = nameIds[slot]
            val roomId = roomIds[slot]
            val course = occupants[slot]
            if (course == null) {
                // INVISIBLE 而非 GONE：时段必须保留 weight 占位，列高才被课表形状撑满
                views.setViewVisibility(containerId, android.view.View.INVISIBLE)
                views.setInt(containerId, "setBackgroundResource", 0)
                views.setTextViewText(nameId, "")
                views.setTextViewText(roomId, "")
            } else {
                views.setViewVisibility(containerId, android.view.View.VISIBLE)
                views.setInt(containerId, "setBackgroundResource", chipBackground(course))
                views.setViewVisibility(nameId, android.view.View.VISIBLE)
                views.setTextViewText(nameId, course.name)
                if (course.classroom.isNotBlank()) {
                    views.setViewVisibility(roomId, android.view.View.VISIBLE)
                    views.setTextViewText(roomId, course.classroom)
                } else {
                    views.setViewVisibility(roomId, android.view.View.GONE)
                }
            }
        }
    }

    /** 起节 → 时段下标：1-2→0、3-4→1、5-6→2、7-8→3、9-10→null（薄空档不排课）、11-12→4，越界回落到首尾时段 */
    private fun courseSlotIndex(startTime: Int): Int? = when {
        startTime <= 0 -> 0
        startTime <= 7 -> (startTime - 1) / 2          // 1..7 → 0..3（含 2/4/6 等脏数据就近归段）
        startTime <= 10 -> null                        // 9-10 节：默认时间表无时间，不排课
        else -> 4                                      // 11-12（含 13+ 越界）
    }

    /** 课程块底色：与周课表格子同规则（color 为 1 起始的色号，0 取首色） */
    private fun chipBackground(course: Course): Int {
        val index = if (course.color > 0) (course.color - 1) % COURSE_COLOR_COUNT else 0
        return when (index) {
            0 -> R.drawable.widget_week_chip_1
            1 -> R.drawable.widget_week_chip_2
            2 -> R.drawable.widget_week_chip_3
            3 -> R.drawable.widget_week_chip_4
            4 -> R.drawable.widget_week_chip_5
            5 -> R.drawable.widget_week_chip_6
            6 -> R.drawable.widget_week_chip_7
            7 -> R.drawable.widget_week_chip_8
            else -> R.drawable.widget_week_chip_9
        }
    }
}
