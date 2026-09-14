package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.model.SemesterEntity
import com.gxu.jwxt.model.ClassScheduleResponse
import com.gxu.jwxt.model.CourseEntry
import com.gxu.jwxt.model.ScheduleResponse
import com.gxu.jwxt.model.Semester
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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

    /**
     * 将班级课表响应（ClassScheduleResponse）转换为本地 Course 列表。
     * 班级课表接口返回的课程条目结构与通用课表相同。
     */
    fun convertClassScheduleResponse(response: ClassScheduleResponse): Pair<List<Course>, Long?> {
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
        var bitmap = e.weeks?.let { parseWeekBitmap(it) } ?: 0L
        // zcd 缺失或解析不出时，回退到周次位掩码 oldzc，避免整门课被丢弃
        if (bitmap == 0L) bitmap = parseWeekMask(e.weekMask)
        if (bitmap == 0L) return null

        val category = e.courseCategory ?: ""
        val credits = e.getCredits() ?: ""
        // 个人课表接口直接返回 QQ群号（qqqh），空值按无群处理
        val qqGroup = e.getQqGroup()?.trim() ?: ""

        return Course(
            name = name, teacher = teacher, classroom = classroom,
            dayOfWeek = dayOfWeek,
            startTime = periodRange.first, endTime = periodRange.second,
            weekBitmap = bitmap,
            courseCategory = category,
            credits = credits,
            qqGroup = qqGroup,
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

    /**
     * 解析教务系统周次字符串为位图。
     * bit 0 = 第1周
     *
     * 兼容写法（部分教务系统返回带「第」前缀、全角符号或用其他分隔符）：
     *   "1-5周"、"7-11周(单)"、"6-8周(双)"、"14周"
     *   "第1-16周"、"第3周"
     *   "1－5周"、"1~5周"、"1至5周"、"1—5周"
     *   "1、3、5周"、"1，3，5周"、"３-５周"
     * 组合示例："第1-5周,第7-11周(单),第12-16周"
     */
    internal fun parseWeekBitmap(weeks: String): Long {
        var bitmap = 0L
        for (part in normalizeWeekText(weeks).split(',')) {
            if (part.isEmpty()) continue

            val oddOnly = part.contains('单')
            val evenOnly = part.contains('双')
            val clean = part.replace("单", "")
                .replace("双", "")
                .replace("(", "")
                .replace(")", "")
                .trim()

            if (clean.contains('-')) {
                val bounds = clean.split('-')
                val s = bounds.getOrNull(0)?.toIntOrNull() ?: continue
                val e = bounds.getOrNull(1)?.toIntOrNull() ?: continue
                for (w in minOf(s, e)..maxOf(s, e)) {
                    if (oddOnly && w % 2 == 0) continue
                    if (evenOnly && w % 2 == 1) continue
                    if (w in 1..64) bitmap = bitmap or (1L shl (w - 1))
                }
            } else {
                val w = clean.toIntOrNull() ?: continue
                if (w in 1..64) bitmap = bitmap or (1L shl (w - 1))
            }
        }
        return bitmap
    }

    /**
     * 归一化周次文本：全角数字转半角、分隔符统一、去掉「第/周/次」等修饰字和空白。
     * 归一化后形如 "1-5,7-11(单),12-16"。
     */
    private fun normalizeWeekText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    in '０'..'９' -> '0' + (ch - '０')          // 全角数字
                    '，', '、', '；', ';' -> ','                 // 统一为半角逗号
                    '～', '〜', '~', '—', '–', '－', '─', '至', '到' -> '-'
                    '（' -> '('
                    '）' -> ')'
                    else -> ch
                }
            )
        }
        return sb.toString()
            .replace(" ", "")
            .replace("第", "")
            .replace("周", "")
            .replace("次", "")
    }

    /**
     * 回退解析周次位掩码（oldzc）：如 "1111000000000000"，从左起第 n 位为 1 表示第 n 周有课。
     * 仅在 zcd 缺失或解析不出时兜底；非 0/1 组成的串视为格式未知，返回 0。
     */
    internal fun parseWeekMask(mask: String?): Long {
        val s = mask?.trim().orEmpty()
        if (s.isEmpty() || s.length > 64) return 0L
        var bitmap = 0L
        for ((i, ch) in s.withIndex()) {
            when (ch) {
                '1' -> bitmap = bitmap or (1L shl i)
                '0' -> Unit
                else -> return 0L
            }
        }
        return bitmap
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
            todayCourses.minOf { c ->
                val range = Course.bitmapToWeekRange(c.weekBitmap)
                range?.first ?: 99
            }
        } else {
            // 今天没课 → 往后找最近有课的一天
            for (offset in 1..7) {
                val checkDow = ((adjustedToday + offset - 1) % 7) + 1
                val checkCourses = courses.filter { it.dayOfWeek == checkDow }
                if (checkCourses.isNotEmpty()) {
                    return@calculateSemesterStart computeStart(cal, offset, checkCourses.minOf { c ->
                        val range = Course.bitmapToWeekRange(c.weekBitmap)
                        range?.first ?: 99
                    })
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

    /**
     * 为指定学期从教务系统获取课表并保存到本地数据库。
     * 同时更新学期的开始日期和总周数。
     *
     * @return 成功时返回导入的课程数量，失败时返回异常
     */
    suspend fun fetchAndSaveScheduleForSemester(
        context: Context,
        semester: SemesterEntity
    ): Result<Int> {
        val fullName = "${semester.academicYear}学年 ${semester.termName}"
        val (year, termCode) = getYearTermForSemester(fullName)
        val term = com.gxu.jwxt.model.Term.fromCode(termCode)
            ?: com.gxu.jwxt.model.Term.SPRING

        val result = JwxtAuthManager.doWithAuth { client ->
            val scheduleResp = client.schedule().personal(year, term)
            val profile = JwxtAccountManager.getProfile()
            val classId = profile?.className ?: ""
            val gradeCode = profile?.grade ?: ""
            val majorCode = profile?.major ?: ""

            var classDetail: ClassScheduleResponse? = null
            if (classId.isNotEmpty() && gradeCode.isNotEmpty()) {
                try {
                    classDetail = client.schedule().classDetail(year, term, classId, gradeCode, majorCode)
                } catch (_: Exception) { }
            }
            Triple(scheduleResp, classDetail, semester.sortOrder)
        }

        return result.map { (response, classDetail, sortOrder) ->
            val (courses, _) = convertScheduleResponse(response)

            CourseDataManager.getInstance(context)
                .replaceAllCoursesForSemester(courses, semester.id)

            // 更新学期日期
            if (classDetail != null) {
                val startStr = classDetail.semesterStartDate
                val weeks = classDetail.weeks?.size ?: 0
                if (startStr != null && weeks > 0) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val startMs = sdf.parse(startStr)?.time ?: 0
                    SemesterManager.updateDates(sortOrder, startMs, weeks)
                }
            }

            courses.size
        }
    }
}
