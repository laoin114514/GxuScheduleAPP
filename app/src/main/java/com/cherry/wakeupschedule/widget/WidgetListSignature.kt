package com.cherry.wakeupschedule.widget

import android.content.Context
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar
import java.util.UUID

/**
 * 今日课程列表的轻量内容签名（2×2 与 4×2 两个今日课程组件共用）：
 * 星期、周次、课程集合（id/名称/教室/节次），以及每门课相对当前时刻的
 * 阶段标记（未开始/进行中/已上完的组合决定每行徽标）。
 * 阶段标记只在某节课开始或结束的分钟边界变化，供组件去重 ListView 的数据重载通知，
 * 避免每次刷新都重置滚动条（右侧滑动条闪烁的根源）。
 */
object WidgetListSignature {

    fun todaySignature(context: Context): String = try {
        val settingsManager = SettingsManager(context)
        val calendar = Calendar.getInstance()
        val todayDayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
        else calendar.get(Calendar.DAY_OF_WEEK) - 1
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)
        val courses = CourseDataManager.getInstance(context).getAllCourses()
            .filter { it.dayOfWeek == todayDayOfWeek && it.isActiveInWeek(currentWeek) }
            .sortedBy { CourseTimeUtils.getStartMinutes(context, it) }
        courses.joinToString("|") { course ->
            val started = if (CourseTimeUtils.getStartMinutes(context, course) > nowMinutes) 0 else 1
            val ended = if (CourseTimeUtils.getEndMinutes(context, course) > nowMinutes) 0 else 1
            "${course.id}-${course.name}-${course.classroom}-${course.startTime}-${course.endTime}:$started$ended"
        } + "|$todayDayOfWeek|$currentWeek"
    } catch (e: Exception) {
        e.printStackTrace()
        // 取不到数据时放弃去重，保持原有的每次都通知行为
        UUID.randomUUID().toString()
    }
}
