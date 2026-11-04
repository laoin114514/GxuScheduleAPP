package com.cherry.wakeupschedule.ui.screen.schedule

import com.cherry.wakeupschedule.model.Course

/**
 * 总课表里的单个上课时段（同一门课可能一周上多次、不同周次上不同教室）。
 */
data class CourseOverviewSession(
    val dayOfWeek: Int,
    val startTime: Int,
    val endTime: Int,
    val classroom: String,
    val weekBitmap: Long
)

/**
 * 总课表条目：同一课程名的全部课程合并为一条。
 *
 * 归并规则见 [build]；[courses] 保留原始成员，供课程详情弹层聚合展示。
 * 实践课（教务 sjkList）没有星期/节次：[hasFixedTime] 为 false 且 [sessions] 为空，
 * [isPractice] 为 true，用于列表的类型标签与顶部筛选。
 * [hasAdjustment] 是「整个教学班」级别：任一行有教务调课记录即整条带「调课」标签；
 * 具体被调课的周次由详情弹层的「调课周次」行说明。
 */
data class CourseOverviewGroup(
    val name: String,
    val teachers: String,
    val credits: String,
    val category: String,
    val colorIndex: Int,
    val weekBitmap: Long,
    val hasFixedTime: Boolean,
    val isPractice: Boolean,
    val hasAdjustment: Boolean,
    val sessions: List<CourseOverviewSession>,
    val courses: List<Course>
) {
    companion object {

        /** 课程名为空白时的展示名 */
        const val UNNAMED_COURSE = "未命名课程"

        /**
         * 把当前学期的课程按课程名（trim 后）归并为总课表条目。
         *
         * - 同一课程名的多门课合并为一条；只有「有固定时间」的成员才产生时段，
         *   时段去重后按 (星期, 开始节次, 结束节次) 升序；
         * - 教师取组内非空去重后「、」连接，学分/类别/颜色取第一个非空（颜色本就按课程名分配，同名必同色）；
         * - 周次取组内所有位图的并集；
         * - 组间按各自最早时段排序，同则按课程名；没有固定时段的实践课排在最后。
         */
        fun build(courses: List<Course>): List<CourseOverviewGroup> {
            if (courses.isEmpty()) return emptyList()

            return courses
                .groupBy { it.name.trim() }
                .map { (key, members) ->
                    // 实践课没有星期/节次，不产生时段（只在总课表按课程列出）
                    val fixedMembers = members.filter { it.hasFixedTime() }
                    val sessions = fixedMembers
                        .map {
                            CourseOverviewSession(
                                dayOfWeek = it.dayOfWeek,
                                startTime = it.startTime,
                                endTime = it.endTime,
                                classroom = it.classroom.trim(),
                                weekBitmap = it.weekBitmap
                            )
                        }
                        .distinct()
                        .sortedWith(compareBy({ it.dayOfWeek }, { it.startTime }, { it.endTime }))

                    CourseOverviewGroup(
                        name = key.ifBlank { UNNAMED_COURSE },
                        teachers = members
                            .map { it.teacher.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .joinToString("、"),
                        credits = members.firstOrNull { it.credits.isNotBlank() }?.credits?.trim() ?: "",
                        category = members.firstOrNull { it.courseCategory.isNotBlank() }
                            ?.courseCategory?.trim() ?: "",
                        colorIndex = members.first().color,
                        weekBitmap = members.fold(0L) { acc, c -> acc or c.weekBitmap },
                        hasFixedTime = fixedMembers.isNotEmpty(),
                        // 同名课程理论上不会又理论又实践；出现混合时按实践课归类
                        isPractice = members.any { it.isPractice },
                        // 调课标志是教学班级别：同一教学班的所有行都带标志，任一行命中即整条标记
                        hasAdjustment = members.any { it.isAdjusted },
                        sessions = sessions,
                        courses = members
                    )
                }
                .sortedWith(
                    compareBy(
                        { it.sessions.firstOrNull()?.dayOfWeek ?: Int.MAX_VALUE },
                        { it.sessions.firstOrNull()?.startTime ?: Int.MAX_VALUE },
                        { it.name }
                    )
                )
        }
    }
}

/**
 * 把周次位图格式化为原始信息：连续周合并为区间，其余逐周列出。
 * 例：第1-16周 / 第1-5、7-10、13周 / 第1、3、5周
 */
fun formatWeekRanges(bitmap: Long): String {
    val list = Course.bitmapToWeekList(bitmap)
    if (list.isEmpty()) return "周次未设置"
    val parts = mutableListOf<String>()
    var start = list[0]
    var prev = list[0]
    for (i in 1 until list.size) {
        if (list[i] == prev + 1) {
            prev = list[i]
            continue
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        start = list[i]
        prev = list[i]
    }
    parts.add(if (start == prev) "$start" else "$start-$prev")
    return "第" + parts.joinToString("、") + "周"
}

/**
 * 总课表顶部的类型筛选。
 *
 * 分类依据是教务的实践课标记（sfsjk）而不是「有没有固定时间」：
 * 集中实践必修的理论排课（如文献检索）有正常节次，仍算理论课。
 */
enum class CourseTypeFilter(val label: String) {
    ALL("全部"),
    THEORY("理论课"),
    PRACTICE("实践课");

    fun apply(groups: List<CourseOverviewGroup>): List<CourseOverviewGroup> = when (this) {
        ALL -> groups
        THEORY -> groups.filter { !it.isPractice }
        PRACTICE -> groups.filter { it.isPractice }
    }
}

/**
 * 选课人数文案：选课人数 / 容量（如 "70 / 90"）；容量缺失时只显示选课人数，两者都空返回空串。
 */
fun formatEnrollment(enrolled: String, maxStudents: String): String = when {
    enrolled.isEmpty() -> ""
    maxStudents.isEmpty() -> enrolled
    else -> "$enrolled / $maxStudents"
}

/** 星期几的中文标签：1..7 → 周一..周日，越界返回空串 */
fun dayOfWeekLabel(day: Int): String = when (day) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> ""
}
