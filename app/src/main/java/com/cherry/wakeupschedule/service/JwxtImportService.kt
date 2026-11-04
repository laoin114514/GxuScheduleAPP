package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.model.SemesterEntity
import com.gxu.jwxt.model.ClassScheduleResponse
import com.gxu.jwxt.model.CourseEntry
import com.gxu.jwxt.model.ScheduleAdjustment
import com.gxu.jwxt.model.ScheduleResponse
import com.gxu.jwxt.model.Semester
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 教务系统课表导入转换服务。
 */
object JwxtImportService {

    /** 教务用来占位的「空值」写法 */
    private val PLACEHOLDER_VALUES = setOf("", "无", "*", "-", "/")

    /**
     * 将教务课表转换为本地 Course 列表，并计算学期开始日期。
     *
     * @param adjustments 教学班 ID → 该班的调课记录列表（来自「查看教学班调课记录」接口）。
     *        传入后会把调课精确落到具体课次/周次（[Course.adjustWeeksBitmap]）；
     *        不传或该教学班没有记录时所有行都不带调课标记 —— 宁可无标签，也不整班误标。
     */
    fun convertScheduleResponse(
        response: ScheduleResponse,
        adjustments: Map<String, List<ScheduleAdjustment>> = emptyMap(),
    ): Pair<List<Course>, Long?> {
        val allEntries = response.allCourses ?: emptyList()
        // 与教务网页一致：顶层 xskbsfxstkzt == "1" 才显示调课状态，此时再按调课记录
        // 精确匹配到具体课表行/周次；关闭或没有记录时一律不标。
        val adjustWeeksByEntry = if (response.isAdjustmentStatusVisible) {
            matchAdjustWeeks(allEntries, adjustments)
        } else {
            emptyMap()
        }
        val courses = allEntries.mapNotNull { convertEntry(it, adjustWeeksByEntry[it] ?: 0L) }
        val semesterStart = calculateSemesterStart(courses)
        return Pair(courses, semesterStart)
    }

    /**
     * 将班级课表响应（ClassScheduleResponse）转换为本地 Course 列表。
     * 班级课表接口返回的课程条目结构与通用课表相同，但没有顶层 xskbsfxstkzt
     * （调课状态全局开关）也没有调课记录，因此调课标记统一为 false。
     */
    fun convertClassScheduleResponse(response: ClassScheduleResponse): Pair<List<Course>, Long?> {
        val allEntries = response.allCourses ?: emptyList()
        val courses = allEntries.mapNotNull { convertEntry(it, adjustWeeks = 0L) }
        val semesterStart = calculateSemesterStart(courses)
        return Pair(courses, semesterStart)
    }

    /**
     * 转换单条课程条目。
     *
     * - 理论课（kbList）：星期 + 节次 + 周次齐备，正常进周课表网格；
     * - 实践课（sjkList）：只有课程名/教师/起止教学周（qsjsz），没有 xqj/jc/jcs，
     *   按「无固定时间」入库（星期/节次置 0），只在总课表列出；
     * - 周次与身份都不足以还原课程的条目仍然丢弃。
     *
     * @param adjustWeeks 本行被调课的周次位图（由 [matchAdjustWeeks] 精确匹配得到），
     *        0 表示该行没有调课。没有拿到调课记录时恒为 0 —— 不使用「教学班级」级标志
     *        jxbsftkbj 整行打标（v1 的做法会把一次调课扩散到整学期所有行，已废弃）。
     */
    private fun convertEntry(e: CourseEntry, adjustWeeks: Long = 0L): Course? {
        val name = e.courseName ?: return null
        val bitmap = parseBitmap(e)
        if (bitmap == 0L) return null

        val dayOfWeek = e.weekday?.toIntOrNull()?.coerceIn(1, 7)
        val periodRange = parsePeriod(e.periodNum ?: e.period ?: "")

        val isFixedTime = dayOfWeek != null && periodRange != null
        val isPractice = isPracticeEntry(e)
        if (!isFixedTime && !isPractice) return null

        // 实践课不进周课表网格，不会有对应调课行，恒不标
        val safeAdjustWeeks = if (isPractice) 0L else adjustWeeks

        return Course(
            name = name,
            teacher = e.teacherName ?: "",
            classroom = e.classroom ?: "",
            dayOfWeek = dayOfWeek ?: Course.NO_FIXED_DAY,
            startTime = periodRange?.first ?: Course.NO_FIXED_SLOT,
            endTime = periodRange?.second ?: Course.NO_FIXED_SLOT,
            weekBitmap = bitmap,
            courseCategory = e.courseCategory ?: "",
            credits = e.getCredits() ?: "",
            // 个人课表接口直接返回 QQ群号（qqqh），空值按无群处理
            qqGroup = e.getQqGroup()?.trim() ?: "",
            // 以下为详情展示用的补充信息
            isPractice = isPractice,
            // 调课精确到周次：is_adjusted 只是 adjustWeeksBitmap 的派生展示位
            isAdjusted = safeAdjustWeeks != 0L,
            adjustWeeksBitmap = safeAdjustWeeks,
            courseCode = clean(e.courseCode),
            courseNature = clean(e.courseNature),
            teachingClass = clean(e.className),
            classComposition = clean(e.classComposition),
            totalHours = clean(e.totalHours),
            hourComposition = clean(e.hourComposition),
            classroomType = clean(e.classroomType),
            assessmentMethod = clean(e.examType),
            examForm = clean(e.examForm),
            practiceDetail = clean(e.practiceDetail),
            // 教务语义：xkrs = 选课人数（已选），zzrl = 课程容量（见 CourseEntry）
            enrolled = clean(e.enrolledCount),
            maxStudents = clean(e.capacity),
            // 实践课没有固定时间，闹钟无从触发
            alarmEnabled = isFixedTime,
            alarmMinutesBefore = 15
        )
    }

    /**
     * 把调课记录精确匹配到具体课表行，返回「课表条目 → 被调课周次位图」。
     *
     * 匹配规则（已用真实教务数据核对过）：
     * 1. 只匹配同一教学班（课表行 classId == 记录 jxb_id）的行；
     * 2. 停课记录（现周次/现星期/现节次都为空）跳过 —— 该周课表里本来就没有可标记的卡片；
     * 3. 候选行条件：现星期 == 行星期、现节次 == 行节次、且现周次位图与行周次位图有交集；
     * 4. 去歧义：记录带现教师/现教室时，优先取「教师包含现教师且教室等于现教室」的行；
     *    没有这样的行时取周次交集位数最大的行，并列全取；
     * 5. 命中行累加交集位图（多条记录命中同一行时取并集）。
     *
     * 匹配不到任何行的记录直接丢弃 —— 宁可少标，也不把调课标记扩散到整门课/整学期。
     */
    private fun matchAdjustWeeks(
        entries: List<CourseEntry>,
        adjustments: Map<String, List<ScheduleAdjustment>>,
    ): Map<CourseEntry, Long> {
        if (adjustments.isEmpty()) return emptyMap()

        // 只给有固定上课时间的理论课行匹配；实践课没有星期/节次，天生不参与
        val byClass = entries
            .filter { !isPracticeEntry(it) }
            .filter { !it.classId.isNullOrBlank() }
            .groupBy { it.classId!! }
        if (byClass.isEmpty()) return emptyMap()

        val result = java.util.IdentityHashMap<CourseEntry, Long>()
        for ((classId, classEntries) in byClass) {
            val records = adjustments[classId] ?: continue
            val weekBitmaps = java.util.IdentityHashMap<CourseEntry, Long>()
            classEntries.forEach { weekBitmaps[it] = parseBitmap(it) }

            for (record in records) {
                if (record.isCancellation) continue
                val currentWeeks = parseWeekBitmap(record.currentWeeks ?: continue)
                val currentWeekday = parseWeekday(record.currentWeekday) ?: continue
                val currentPeriod = parsePeriod(record.currentPeriod ?: "") ?: continue
                if (currentWeeks == 0L) continue

                val candidates = classEntries.mapNotNull { entry ->
                    val weekday = parseWeekday(entry.weekday) ?: return@mapNotNull null
                    val period = parsePeriod(entry.periodNum ?: entry.period ?: "")
                        ?: return@mapNotNull null
                    if (weekday != currentWeekday || period != currentPeriod) {
                        return@mapNotNull null
                    }
                    val overlap = currentWeeks and (weekBitmaps[entry] ?: 0L)
                    if (overlap == 0L) return@mapNotNull null
                    entry to overlap
                }
                if (candidates.isEmpty()) continue

                val preferred = candidates.filter { (entry, _) -> matchesCurrentIdentity(record, entry) }
                val chosen = if (preferred.isNotEmpty()) {
                    preferred
                } else {
                    val maxOverlapBits = candidates.maxOf { (_, overlap) ->
                        java.lang.Long.bitCount(overlap)
                    }
                    candidates.filter { (_, overlap) ->
                        java.lang.Long.bitCount(overlap) == maxOverlapBits
                    }
                }

                for ((entry, overlap) in chosen) {
                    result[entry] = (result[entry] ?: 0L) or overlap
                }
            }
        }
        return result
    }

    /**
     * 记录带了现教师/现教室时，候选行能否对上。
     *
     * 教师按「包含」匹配（课表行的教师字段可能是「张三,李四」这样的拼接串）；
     * 两者都带时要求同时满足。记录没带任何现身份信息时返回 false，
     * 由调用方退回「周次交集最大」的候选。
     */
    private fun matchesCurrentIdentity(record: ScheduleAdjustment, entry: CourseEntry): Boolean {
        val teacher = record.currentTeacher?.trim().orEmpty()
        val classroom = record.currentClassroom?.trim().orEmpty()
        if (teacher.isEmpty() && classroom.isEmpty()) return false
        if (teacher.isNotEmpty() && entry.teacherName?.contains(teacher) != true) return false
        if (classroom.isNotEmpty() && entry.classroom?.trim() != classroom) return false
        return true
    }

    /** 调课记录的现星期：「一」~「七」/「日」/「天」或数字 1~7；解析不出返回 null。 */
    private fun parseWeekday(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toIntOrNull()?.let { return it.takeIf { day -> day in 1..7 } }
        return WEEKDAY_CHARS.entries.firstOrNull { value.endsWith(it.key) }?.value
    }

    /** 调课记录里星期的中文写法：「一」~「六」/「七」、「日」、「天」都表示 1~7 */
    private val WEEKDAY_CHARS = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4,
        "五" to 5, "六" to 6, "七" to 7, "日" to 7, "天" to 7,
    )

    /** 教务用空格 /「无」/「*」/「-」表示空值，统一清成空串，详情页据此隐藏该行 */
    private fun clean(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value in PLACEHOLDER_VALUES) "" else value
    }

    /**
     * 解析周次位图：zcd 优先；实践课的周次在 qsjsz（起止教学周，如 "1-16周"）；
     * 最后回退到周次位掩码 oldzc。
     */
    private fun parseBitmap(e: CourseEntry): Long {
        var bitmap = e.weeks?.let { parseWeekBitmap(it) } ?: 0L
        if (bitmap == 0L) bitmap = e.practicePeriodRange?.let { parseWeekBitmap(it) } ?: 0L
        if (bitmap == 0L) bitmap = parseWeekMask(e.weekMask)
        return bitmap
    }

    /**
     * 是否实践课条目：sfsjk == "1"；
     * 标记缺失时按「没有星期、也没有节次，但有起止教学周」兜底判断。
     */
    private fun isPracticeEntry(e: CourseEntry): Boolean {
        if (e.isPractice == "1") return true
        val hasWeekday = !e.weekday.isNullOrBlank()
        val hasPeriod = !(e.periodNum.isNullOrBlank() && e.period.isNullOrBlank())
        return !hasWeekday && !hasPeriod && !e.practicePeriodRange.isNullOrBlank()
    }

    /**
     * 解析节次文本为闭区间，如 "7-8" / "7-8节" / "第7-8节" → 7..8、"7" → 7..7。
     *
     * 兼容课表条目的 "N-M节"/"N-M" 与调课记录的「第N-M节」两种写法（去掉「第」「节」
     * 与空白后统一按 "-" 切分）。
     */
    private fun parsePeriod(period: String): Pair<Int, Int>? {
        val cleaned = period
            .replace("第", "")
            .replace("节", "")
            .replace("－", "-")
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace("\u3000", "")
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
        // 实践课没有星期/节次，不参与「今天属于第几周」的反推
        val fixedCourses = courses.filter { it.hasFixedTime() }
        if (fixedCourses.isEmpty()) return null

        val cal = Calendar.getInstance()
        val todayDow = cal.get(Calendar.DAY_OF_WEEK)
        val adjustedToday = if (todayDow == Calendar.SUNDAY) 7 else todayDow - 1

        // 找今天的课程
        val todayCourses = fixedCourses.filter { it.dayOfWeek == adjustedToday }
        val currentWeek: Int = if (todayCourses.isNotEmpty()) {
            todayCourses.minOf { c ->
                val range = Course.bitmapToWeekRange(c.weekBitmap)
                range?.first ?: 99
            }
        } else {
            // 今天没课 → 往后找最近有课的一天
            for (offset in 1..7) {
                val checkDow = ((adjustedToday + offset - 1) % 7) + 1
                val checkCourses = fixedCourses.filter { it.dayOfWeek == checkDow }
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
     * @return 成功时返回导入的课程门数（同名课程算一门，与总课表口径一致），失败时返回异常
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

            // v2：调课标记精确到具体课次/周次。仅当顶层 xskbsfxstkzt == "1"（课表显示调课状态）、
            // 且确有教学班带调课标志（jxbsftkbj）时，才把教学班 ID 去重后逐个拉取调课记录，
            // 每个教学班一次 GET。单个教学班失败只降级为该班无调课标记（内部已 catch），
            // 不会打断整次导入；匹配不到具体课次的行同样不标 —— 宁可少标，也不整班误标。
            val adjustments: Map<String, List<ScheduleAdjustment>> =
                if (scheduleResp.isAdjustmentStatusVisible) {
                    scheduleResp.allCourses.orEmpty()
                        .filter { it.hasAdjustment() }
                        .mapNotNull { it.classId?.trim()?.takeIf { id -> id.isNotEmpty() } }
                        .distinct()
                        .associateWith { classId ->
                            try {
                                client.schedule().adjustmentRecords(classId)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                } else {
                    emptyMap()
                }

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
            Triple(scheduleResp, adjustments, classDetail)
        }

        return result.map { (response, adjustments, classDetail) ->
            val (courses, _) = convertScheduleResponse(response, adjustments)

            CourseDataManager.getInstance(context)
                .replaceAllCoursesForSemester(courses, semester.id)

            // 更新学期日期
            if (classDetail != null) {
                val startStr = classDetail.semesterStartDate
                val weeks = classDetail.weeks?.size ?: 0
                if (startStr != null && weeks > 0) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val startMs = sdf.parse(startStr)?.time ?: 0
                    SemesterManager.updateDates(semester.sortOrder, startMs, weeks)
                }
            }

            // 提示按「同名课程算一门」的口径报数，与总课表条目数一致
            Course.distinctNameCount(courses)
        }
    }
}
