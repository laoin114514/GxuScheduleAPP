package com.cherry.wakeupschedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import java.util.Calendar

/**
 * 小组件课程列表的 RemoteViewsService
 *
 * 小组件原生不支持 RecyclerView，只能使用 ListView + RemoteViewsService 模式。
 * 每个"槽位"（今天 / 明天）对应一个独立 Service 实例，由 Provider 在 onUpdate 中绑定。
 *
 * 数据通过 Intent extras 传入（包含 source 标识: today / tomorrow / upcoming_today）
 */
class WidgetCourseListService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        // 优先从 Uri 路径解析 source（因为 RemoteViews 缓存时只比 Uri 不比 extras）
        val uriSource = intent.data?.lastPathSegment
        val source = when (uriSource) {
            "today" -> SOURCE_TODAY
            "upcoming_today" -> SOURCE_UPCOMING_TODAY
            "tomorrow" -> SOURCE_TOMORROW
            else -> intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_TODAY
        }
        return WidgetCourseListFactory(applicationContext, source)
    }

    companion object {
        const val EXTRA_SOURCE = "extra_source"
        const val SOURCE_TODAY = "today"
        const val SOURCE_TOMORROW = "tomorrow"
        const val SOURCE_UPCOMING_TODAY = "upcoming_today"
    }
}

/**
 * 课程列表工厂：根据 source 从 CourseDataManager 读取对应课程列表
 * 并为 ListView 的每一行生成 RemoteViews
 */
class WidgetCourseListFactory(
    private val context: Context,
    private val source: String
) : RemoteViewsService.RemoteViewsFactory {

    /** 内存中的课程数据快照 */
    private var items: List<WidgetCourseItem> = emptyList()

    override fun onCreate() {
        // 在 Service 创建时立即加载数据
        loadItems()
    }

    override fun onDataSetChanged() {
        // 每次 notifyAppWidgetViewDataChanged 时重新加载
        loadItems()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position < 0 || position >= items.size) {
            return loadingView()
        }
        val item = items[position]
        val views = RemoteViews(context.packageName, R.layout.widget_course_list_item)

        views.setTextViewText(R.id.course_list_name, item.name)
        views.setTextViewText(R.id.course_list_location, item.location)
        views.setTextViewText(R.id.course_list_time, item.time)
        views.setInt(R.id.course_list_indicator, "setBackgroundColor", item.color)

        return views
    }

    override fun getLoadingView(): RemoteViews = loadingView()

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return if (position in items.indices) items[position].id else position.toLong()
    }

    override fun hasStableIds(): Boolean = true

    private fun loadingView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_course_list_item)
        views.setTextViewText(R.id.course_list_name, "加载中...")
        views.setTextViewText(R.id.course_list_location, "")
        views.setTextViewText(R.id.course_list_time, "")
        return views
    }

    /**
     * 根据 source 加载课程列表
     */
    private fun loadItems() {
        items = try {
            when (source) {
                WidgetCourseListService.SOURCE_TODAY -> {
                    // 今日课程小组件：仅未上的课
                    val result = loadTodayCoursesEx(includeFinished = false)
                    when {
                        result.items.isNotEmpty() -> result.items
                        result.allFinished -> listOf(emptyItem("今日课程已完成", "明日继续加油", ""))
                        else -> listOf(emptyItem("今天没有课程", "好好休息", ""))
                    }
                }
                WidgetCourseListService.SOURCE_UPCOMING_TODAY -> {
                    // 近日课程小组件的"今天"：仅未上的课
                    val result = loadTodayCoursesEx(includeFinished = false)
                    when {
                        result.items.isNotEmpty() -> result.items
                        result.allFinished -> listOf(emptyItem("今日课程已完成", "明日继续加油", ""))
                        else -> listOf(emptyItem("今天没有课程", "好好休息", ""))
                    }
                }
                WidgetCourseListService.SOURCE_TOMORROW -> loadTomorrowCourses()
                else -> emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("WidgetCourseListFactory", "loadItems failed for source=$source", e)
            emptyList()
        }
    }

    /**
     * 加载今日课程
     * @param includeFinished true: 全部今日课程；false: 仅未上的课
     * @return 返回 (列表项, 是否所有今日课程已结束, 今日是否有任何课程)
     *         - 三元组用于上层判断"今日没课" vs "今日课程已完成"
     */
    private data class TodayLoadResult(
        val items: List<WidgetCourseItem>,
        val hasAnyTodayCourse: Boolean,
        val allFinished: Boolean
    )

    private fun loadTodayCoursesEx(includeFinished: Boolean): TodayLoadResult {
        val calendar = Calendar.getInstance()
        val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else calendar.get(Calendar.DAY_OF_WEEK) - 1
        val currentTime = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val settingsManager = SettingsManager(context)
        val currentWeek = calculateCurrentWeek(settingsManager)
        val timeTableManager = TimeTableManager.getInstance(context)
        val timeSlots = timeTableManager.getTimeSlots()
        val colors = ThemeManager.getCourseColors()

        val allCourses = CourseDataManager.getInstance(context).getAllCourses()
        val todayCourses = allCourses
            .filter {
                it.dayOfWeek == dayOfWeek &&
                it.isActiveInWeek(currentWeek)
            }
            .sortedBy { it.startTime }

        val hasAnyTodayCourse = todayCourses.isNotEmpty()
        val allFinished = hasAnyTodayCourse && todayCourses.all { getCourseEndMinutes(context, it) <= currentTime }

        val filtered = if (includeFinished) todayCourses
        else todayCourses.filter { getCourseEndMinutes(context, it) > currentTime }

        if (filtered.isEmpty()) {
            return TodayLoadResult(
                items = emptyList(),
                hasAnyTodayCourse = hasAnyTodayCourse,
                allFinished = allFinished
            )
        }

        val items = filtered.map { course ->
            val color = colors[(course.id % colors.size).toInt()]
            WidgetCourseItem(
                id = course.id,
                name = course.name,
                location = course.classroom,
                time = formatCourseTime(timeSlots, course),
                color = color
            )
        }
        return TodayLoadResult(items = items, hasAnyTodayCourse = hasAnyTodayCourse, allFinished = allFinished)
    }

    /**
     * 加载明日课程
     */
    private fun loadTomorrowCourses(): List<WidgetCourseItem> {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else calendar.get(Calendar.DAY_OF_WEEK) - 1

        val settingsManager = SettingsManager(context)
        val targetWeek = calculateWeekForDay(settingsManager, calendar)
        val timeTableManager = TimeTableManager.getInstance(context)
        val timeSlots = timeTableManager.getTimeSlots()
        val colors = ThemeManager.getCourseColors()

        val allCourses = CourseDataManager.getInstance(context).getAllCourses()
        val tomorrowCourses = allCourses
            .filter {
                it.dayOfWeek == dayOfWeek &&
                it.isActiveInWeek(targetWeek)
            }
            .sortedBy { it.startTime }

        if (tomorrowCourses.isEmpty()) {
            return listOf(emptyItem("明天没有课程", "", ""))
        }

        return tomorrowCourses.map { course ->
            val color = colors[(course.id % colors.size).toInt()]
            WidgetCourseItem(
                id = course.id,
                name = course.name,
                location = course.classroom,
                time = formatCourseTime(timeSlots, course),
                color = color
            )
        }
    }

    private fun emptyItem(name: String, location: String, time: String): WidgetCourseItem {
        return WidgetCourseItem(
            id = -1,
            name = name,
            location = location,
            time = time,
            color = Color.parseColor("#CCCCCC")
        )
    }

    private fun formatCourseTime(timeSlots: List<TimeTableManager.TimeSlot>, course: Course): String {
        return try {
            val start = timeSlots.find { it.node == course.startTime }
            val end = timeSlots.find { it.node == course.endTime }
            if (start != null && end != null) "${start.startTime} - ${end.endTime}"
            else "第${course.startTime}-${course.endTime}节"
        } catch (e: Exception) {
            "第${course.startTime}-${course.endTime}节"
        }
    }

    private fun getCourseEndMinutes(context: Context, course: Course): Int {
        return try {
            val timeSlots = TimeTableManager.getInstance(context).getTimeSlots()
            val slot = timeSlots.find { it.node == course.endTime }
            if (slot != null) {
                val p = slot.endTime.split(":")
                if (p.size == 2) p[0].toInt() * 60 + p[1].toInt() else (8 + course.endTime) * 60 + 45
            } else (8 + course.endTime) * 60 + 45
        } catch (e: Exception) {
            (8 + course.endTime) * 60 + 45
        }
    }

    private fun calculateCurrentWeek(settingsManager: SettingsManager): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return settingsManager.getDefaultWeek()
        return (((System.currentTimeMillis() - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks())
    }

    private fun calculateWeekForDay(settingsManager: SettingsManager, calendar: Calendar): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return settingsManager.getDefaultWeek()
        return (((calendar.timeInMillis - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks())
    }
}

/**
 * 小组件列表中显示的课程数据
 */
data class WidgetCourseItem(
    val id: Long,
    val name: String,
    val location: String,
    val time: String,
    val color: Int
)
