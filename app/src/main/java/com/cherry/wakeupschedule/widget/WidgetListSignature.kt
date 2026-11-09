package com.cherry.wakeupschedule.widget

import android.content.Context
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar
import java.util.UUID

/**
 * 今日课程列表的轻量内容签名（2×2 与 4×2 两个今日课程组件共用）：
 * 星期、周次、课程集合（id/名称/教室/节次），以及每门课相对当前时刻的
 * 阶段标记（未开始/下一节/进行中/已上完的组合决定每行底色与文字色）。
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
        val courses = CourseDataManager.getAllCoursesSafely(context)
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

    /**
     * 近日课程组件两个列表（今天 / 明天）的可见内容签名。
     *
     * 今天那部分与 [todaySignature] 同口径，但要额外说明「明天」那份必须单独带：
     * 只改了明天的课，今天的课程集合不会变，若只看今天签名就会漏通知、明天列表停在旧数据。
     * 明天列表的取数口径与 [WidgetCourseListService] 的 tomorrow 渠道保持一致
     * （明天星期 + 明天所在周次），不含颜色（配色是固定色板，不随设置变化）。
     */
    fun upcomingSignature(context: Context): String = try {
        val settingsManager = SettingsManager(context)
        val calendar = Calendar.getInstance()
        val todayDayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
        else calendar.get(Calendar.DAY_OF_WEEK) - 1
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)
        val allCourses = CourseDataManager.getAllCoursesSafely(context)

        // 今天：今日列表只展示未结束的课，"已上完"标记决定某门课是否还在列表里
        val todayPart = allCourses
            .filter { it.dayOfWeek == todayDayOfWeek && it.isActiveInWeek(currentWeek) }
            .sortedBy { CourseTimeUtils.getStartMinutes(context, it) }
            .joinToString("|") { course ->
                val ended = if (CourseTimeUtils.getEndMinutes(context, course) > nowMinutes) 0 else 1
                "${course.id}-${course.name}-${course.classroom}-${course.startTime}-${course.endTime}:$ended"
            }

        // 明天：跨天/跨周都会改变 tomorrow 的星期与周次，这里一起参与签名
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        val tomorrowDayOfWeek = if (tomorrow.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
        else tomorrow.get(Calendar.DAY_OF_WEEK) - 1
        val tomorrowWeek = CourseTimeUtils.getWeekForDate(settingsManager, tomorrow.timeInMillis)
        val tomorrowPart = allCourses
            .filter { it.dayOfWeek == tomorrowDayOfWeek && it.isActiveInWeek(tomorrowWeek) }
            .sortedBy { it.startTime }
            .joinToString("|") { course ->
                "${course.id}-${course.name}-${course.classroom}-${course.startTime}-${course.endTime}"
            }

        "T$todayPart|$todayDayOfWeek-$currentWeek|M$tomorrowPart|$tomorrowDayOfWeek-$tomorrowWeek"
    } catch (e: Exception) {
        e.printStackTrace()
        // 取不到数据时放弃去重，保持原有的每次都通知行为
        UUID.randomUUID().toString()
    }
}
