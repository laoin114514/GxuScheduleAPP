package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.Course
import com.gxu.jwxt.model.CourseEntry
import com.gxu.jwxt.model.ScheduleResponse
import com.gxu.jwxt.model.Semester
import java.util.Calendar

/**
 * 教务系统课表导入转换服务。
 */
object JwxtImportService {

    /**
     * 将教务课表转换为本地 Course 列表，并计算学期开始日期。
     */
    fun convertScheduleResponse(response: ScheduleResponse): Pair<List<Course>, Long?> {
        val allEntries = response.allCourses ?: emptyList()
        val courses = allEntries.mapNotNull { convertEntry(it) }
        val semesterStart = calculateSemesterStart(courses)
        return Pair(courses, semesterStart)
    }

    private fun convertEntry(e: CourseEntry): Course? {
        val name = e.courseName ?: return null
        val teacher = e.teacherName ?: ""
        val classroom = e.classroom ?: ""
        val dayOfWeek = e.weekday?.toIntOrNull()?.coerceIn(1, 7) ?: return null

        val periodRange = parsePeriod(e.periodNum ?: e.period ?: return null) ?: return null
        val weekList = parseWeeks(e.weeks ?: return null)
        if (weekList.isEmpty()) return null

        return Course(
            name = name, teacher = teacher, classroom = classroom,
            dayOfWeek = dayOfWeek,
            startTime = periodRange.first, endTime = periodRange.second,
            startWeek = weekList.first(), endWeek = weekList.last(),
            weekType = 0,
            alarmEnabled = true, alarmMinutesBefore = 15
        )
    }

    private fun parsePeriod(period: String): Pair<Int, Int>? {
        val cleaned = period.replace("节", "").trim()
        val parts = cleaned.split("-")
        if (parts.size < 2) {
            val s = cleaned.toIntOrNull() ?: return null
            return Pair(s, s)
        }
        val start = parts[0].toIntOrNull() ?: return null
        val end = parts[1].toIntOrNull() ?: return null
        return Pair(start, end)
    }

    private fun parseWeeks(weeks: String): List<Int> {
        val result = mutableListOf<Int>()
        val cleaned = weeks.replace("周", "").trim()
        for (part in cleaned.split(",")) {
            val t = part.trim()
            if (t.contains("-")) {
                val r = t.split("-")
                val s = r[0].toIntOrNull() ?: continue
                val e = r[1].toIntOrNull() ?: continue
                for (w in s..e) result.add(w)
            } else {
                t.toIntOrNull()?.let { result.add(it) }
            }
        }
        return result.sorted()
    }

    /**
     * 根据课表数据计算学期开始日期（第一周周一的 00:00）。
     * 算法：查今天有哪些课 → 确定当前是第几周 → 反推第一周周一。
     */
    private fun calculateSemesterStart(courses: List<Course>): Long? {
        if (courses.isEmpty()) return null

        val cal = Calendar.getInstance()
        val todayDow = cal.get(Calendar.DAY_OF_WEEK)
        val adjustedToday = if (todayDow == Calendar.SUNDAY) 7 else todayDow - 1

        // 找今天的课程
        val todayCourses = courses.filter { it.dayOfWeek == adjustedToday }
        val currentWeek: Int = if (todayCourses.isNotEmpty()) {
            todayCourses.minOf { it.startWeek }
        } else {
            // 今天没课 → 往后找最近有课的一天
            for (offset in 1..7) {
                val checkDow = ((adjustedToday + offset - 1) % 7) + 1
                val checkCourses = courses.filter { it.dayOfWeek == checkDow }
                if (checkCourses.isNotEmpty()) {
                    return@calculateSemesterStart computeStart(cal, offset, checkCourses.minOf { it.startWeek })
                }
            }
            // 所有天都没课 → 默认第 1 周
            1
        }

        return computeStart(cal, 0, currentWeek)
    }

    private fun computeStart(today: Calendar, dayOffset: Int, currentWeek: Int): Long {
        val clone = today.clone() as Calendar
        // 第一周周一 = 今天 - dayOffset天 - (currentWeek - 1)*7天
        clone.add(Calendar.DAY_OF_YEAR, -dayOffset - (currentWeek - 1) * 7)
        // 设置到周一
        clone.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (clone.after(today)) {
            // 如果周一在今天之后，回退一周
            clone.add(Calendar.WEEK_OF_YEAR, -1)
        }
        clone.set(Calendar.HOUR_OF_DAY, 0)
        clone.set(Calendar.MINUTE, 0)
        clone.set(Calendar.SECOND, 0)
        clone.set(Calendar.MILLISECOND, 0)
        return clone.timeInMillis
    }

    /** 获取当前的学年和学期编码（基于当前日期） */
    fun getCurrentYearTerm(): Pair<String, String> {
        val sem = Semester.current()
        return Pair(sem.year, sem.termCode)
    }

    /**
     * 根据用户选择的学期名称解析对应的学年和学期编码。
     * 例如 "2024-2025学年 第一学期" → ("2024", "3")，即 AUTUMN
     * "2024-2025学年 第二学期" → ("2024", "12")，即 SPRING
     *
     * 解析失败时回退到 [getCurrentYearTerm]。
     */
    fun getYearTermForSemester(semesterName: String): Pair<String, String> {
        val yearRegex = Regex("""(\d{4})-\d{4}学年""")
        val year = yearRegex.find(semesterName)?.groupValues?.getOrNull(1)
        val termCode = when {
            semesterName.contains("第一学期") -> "3"   // AUTUMN
            semesterName.contains("第二学期") -> "12"  // SPRING
            else -> null
        }
        if (year != null && termCode != null) {
            return Pair(year, termCode)
        }
        // 回退到基于当前日期的学期检测
        return getCurrentYearTerm()
    }
}
