package com.cherry.wakeupschedule.widget

import android.content.Context
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar

/**
 * 「下一门课提醒」系列（4×2 / 2×2）共用的今日/明日时间线快照。
 *
 * 两种尺寸只是排版不同，取数口径必须一致：同一次刷新里「进行中的课」「今天下一门」
 * 「明天第一节」不能因为两份实现各算各的而出现差异。这里把日期换算、周次过滤与
 * 课程排序收成一个不可变快照，Provider 只负责决定展示哪几门、怎么排版。
 */
internal data class NextCourseTimeline(
    val currentWeek: Int,
    val todayDayOfWeek: Int,
    val nowMinutes: Int,
    /** 今天仍在当前周上课的课程，按开始时间升序 */
    val todayCourses: List<Course>,
    /** 此刻正在上的课（重叠课程取最早开始的那门） */
    val current: Course?,
    /** 今天接下来要上的第一门 */
    val nextToday: Course?,
    val tomorrowMillis: Long,
    val tomorrowDayOfWeek: Int,
    /** 明天仍在（明天所在周的）课程，按开始时间升序 */
    val tomorrowCourses: List<Course>
) {
    val tomorrowFirst: Course? get() = tomorrowCourses.firstOrNull()

    companion object {
        fun resolve(context: Context): NextCourseTimeline {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val todayDayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)

            val allCourses = CourseDataManager.getAllCoursesSafely(context)
            val todayCourses = allCourses
                .filter { it.dayOfWeek == todayDayOfWeek && it.isActiveInWeek(currentWeek) }
                .sortedBy { CourseTimeUtils.getStartMinutes(context, it) }

            val current = todayCourses.firstOrNull {
                val start = CourseTimeUtils.getStartMinutes(context, it)
                val end = CourseTimeUtils.getEndMinutes(context, it)
                nowMinutes >= start && nowMinutes < end
            }
            val nextToday = todayCourses.firstOrNull {
                CourseTimeUtils.getStartMinutes(context, it) > nowMinutes
            }

            val todayMidnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val tomorrowMillis = todayMidnight + 24 * 60 * 60 * 1000L
            val tomorrowDayOfWeek = todayDayOfWeek % 7 + 1
            // 明天可能已经跨进下一周：按明天的日期取周次再过滤，不能直接复用当前周
            val tomorrowWeek = CourseTimeUtils.getWeekForDate(settingsManager, tomorrowMillis)
            val tomorrowCourses = allCourses
                .filter { it.dayOfWeek == tomorrowDayOfWeek && it.isActiveInWeek(tomorrowWeek) }
                .sortedBy { CourseTimeUtils.getStartMinutes(context, it) }

            return NextCourseTimeline(
                currentWeek = currentWeek,
                todayDayOfWeek = todayDayOfWeek,
                nowMinutes = nowMinutes,
                todayCourses = todayCourses,
                current = current,
                nextToday = nextToday,
                tomorrowMillis = tomorrowMillis,
                tomorrowDayOfWeek = tomorrowDayOfWeek,
                tomorrowCourses = tomorrowCourses
            )
        }
    }
}

/** 主课/次课的一次选取结果；primary 为 null 表示今明都没有课 */
internal data class NextCourseSelection(
    val primary: Course?,
    val secondary: Course?
)

/**
 * 4×2 与 2×2 共用的显示策略：
 * 主课 = 进行中 → 今天下一门 → 明天第一节；
 * 次课 = 主课之后最近的一门（今天后续课优先，否则明天第一节/第二节）。
 *
 * 次课要求开始时间严格晚于主课，因此同一开始时间的冲突课程只会出现在主课。
 */
internal fun NextCourseTimeline.selectPair(context: Context): NextCourseSelection {
    // tomorrowFirst 是计算属性，先取局部变量，null 检查后才能智能转换
    val firstTomorrow = tomorrowFirst
    val primary = current ?: nextToday ?: firstTomorrow
    val secondary = when {
        current != null -> nextToday ?: firstTomorrow
        nextToday != null -> {
            val nextStart = CourseTimeUtils.getStartMinutes(context, nextToday)
            todayCourses.firstOrNull {
                CourseTimeUtils.getStartMinutes(context, it) > nextStart
            } ?: firstTomorrow
        }
        firstTomorrow != null -> {
            val firstStart = CourseTimeUtils.getStartMinutes(context, firstTomorrow)
            tomorrowCourses.firstOrNull {
                CourseTimeUtils.getStartMinutes(context, it) > firstStart
            }
        }
        else -> null
    }
    return NextCourseSelection(primary, secondary)
}

/** 课程徽标：进行中 / N 分钟后 / N 小时后 / 明天（与主课/次课无关，只看课程本身） */
internal fun NextCourseTimeline.badgeFor(context: Context, course: Course): String {
    if (course.id == current?.id) return "进行中"
    if (course in todayCourses) {
        val gap = CourseTimeUtils.getStartMinutes(context, course) - nowMinutes
        return if (gap < 60) "$gap 分钟后" else "${(gap + 30) / 60} 小时后"
    }
    return "明天"
}

/** 小组件口径的分钟数格式化：610 → "10:10" */
internal fun formatWidgetMinutes(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)
