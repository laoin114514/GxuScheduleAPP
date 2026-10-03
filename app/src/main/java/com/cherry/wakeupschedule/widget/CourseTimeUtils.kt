package com.cherry.wakeupschedule.widget

import android.content.Context
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager

/**
 * 小组件侧"节次 → 当天分钟数"的统一换算。
 *
 * 各 Provider 的倒计时/状态判断都依赖它：优先用时间表里该节次的 HH:mm，
 * 时间表缺失或格式异常时兜底为"第 1 节 8:00 开始、每节 45 分钟"的估算值。
 */
object CourseTimeUtils {

    /** 课程当天开始时间（分钟数） */
    fun getStartMinutes(context: Context, course: Course): Int = try {
        val slot = TimeTableManager.getInstance(context).getTimeSlots().find { it.node == course.startTime }
        val parts = slot?.startTime?.split(":")
        if (parts != null && parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
        else (8 + course.startTime) * 60
    } catch (e: Exception) {
        (8 + course.startTime) * 60
    }

    /** 课程当天结束时间（分钟数） */
    fun getEndMinutes(context: Context, course: Course): Int = try {
        val slot = TimeTableManager.getInstance(context).getTimeSlots().find { it.node == course.endTime }
        val parts = slot?.endTime?.split(":")
        if (parts != null && parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
        else (8 + course.endTime) * 60 + 45
    } catch (e: Exception) {
        (8 + course.endTime) * 60 + 45
    }

    /** 当前周次（学期未设置时回落到手动选择的默认周） */
    fun getCurrentWeek(settingsManager: SettingsManager): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return settingsManager.getDefaultWeek()
        return (((System.currentTimeMillis() - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1)
            .coerceIn(1, settingsManager.getTotalWeeks())
    }

    /** 指定时刻所在的周次（跨周判断用，如"明天"已进入下一周） */
    fun getWeekForDate(settingsManager: SettingsManager, timeMillis: Long): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return settingsManager.getDefaultWeek()
        return (((timeMillis - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1)
            .coerceIn(1, settingsManager.getTotalWeeks())
    }
}
