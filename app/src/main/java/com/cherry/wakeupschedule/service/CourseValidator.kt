package com.cherry.wakeupschedule.service

import com.cherry.wakeupschedule.model.Course

/**
 * 课程数据校验器
 *
 * 借鉴拾光课表 CourseConversionRepository 中的严谨校验策略：
 * - 时间格式校验
 * - 周范围合法性检查
 * - 节次连续性检查
 * - 课程时间重叠检测
 */
object CourseValidator {

    /** 课程名称最大长度 */
    private const val MAX_COURSE_NAME_LENGTH = 100
    /** 教师名称最大长度 */
    private const val MAX_TEACHER_NAME_LENGTH = 50
    /** 教室名称最大长度 */
    private const val MAX_CLASSROOM_LENGTH = 100
    /** 周数范围 */
    private const val MIN_WEEK = 1
    private const val MAX_WEEK = 30
    /** 节次范围 */
    private const val MIN_TIME_SLOT = 1
    private const val MAX_TIME_SLOT = 14

    /**
     * 校验结果密封类
     */
    sealed class ValidationResult {
        /** 校验通过 */
        data object Valid : ValidationResult()
        /** 校验警告（可继续但建议检查） */
        data class Warning(val message: String) : ValidationResult()
        /** 校验失败 */
        data class Error(val message: String) : ValidationResult()
    }

    /**
     * 单门课程完整校验
     *
     * @return 所有校验问题的列表（空表示完全通过）
     */
    fun validate(course: Course): List<ValidationResult> {
        val results = mutableListOf<ValidationResult>()

        // 1. 基础字段非空校验
        validateFieldPresence(course, results)

        // 2. 字段长度校验
        validateFieldLengths(course, results)

        // 3. 数值范围校验
        validateRanges(course, results)

        // 4. 逻辑一致性校验
        validateLogicConsistency(course, results)

        return results
    }

    /**
     * 批量课程校验（含重叠检测）
     *
     * 返回值中的 key：若课程 id != 0 则使用原始 id，若 id == 0
     * 则使用 -(index+1) 作为临时 key，避免所有未入库课程挤在同一 key 下。
     *
     * @return 每门课程对应的校验结果映射
     */
    fun validateBatch(courses: List<Course>): Map<Long, List<ValidationResult>> {
        val resultMap = linkedMapOf<Long, List<ValidationResult>>()

        // 单门课程校验
        courses.forEachIndexed { index, course ->
            val courseResults = validate(course).toMutableList()

            // 重叠检测
            for (other in courses.drop(index + 1)) {
                if (isOverlapping(course, other)) {
                    courseResults.add(ValidationResult.Warning(
                        "课程「${course.name}」与「${other.name}」存在时间重叠"
                    ))
                }
            }

            val mapKey = if (course.id != 0L) course.id else -(index + 1).toLong()
            resultMap[mapKey] = courseResults
        }

        return resultMap
    }

    /**
     * 检查两门课程是否时间重叠
     * 条件：同一天、周范围有交集、节次有交集
     */
    fun isOverlapping(course1: Course, course2: Course): Boolean {
        // 不同天 → 不重叠
        if (course1.dayOfWeek != course2.dayOfWeek) return false

        // 周范围无交集 → 不重叠
        if ((course1.weekBitmap and course2.weekBitmap) == 0L) return false

        // 节次无交集 → 不重叠
        if (course1.endTime < course2.startTime || course2.endTime < course1.startTime) return false

        return true
    }

    /**
     * 检查节次是否连续
     */
    fun isTimeSlotContinuous(courses: List<Course>): Boolean {
        val sorted = courses.sortedBy { it.startTime }
        for (i in 0 until sorted.size - 1) {
            if (sorted[i].endTime + 1 >= sorted[i + 1].startTime) {
                // 有重叠或连续，可以继续
                continue
            } else {
                return false
            }
        }
        return true
    }

    // ── 内部实现 ──

    private fun validateFieldPresence(course: Course, results: MutableList<ValidationResult>) {
        if (course.name.isBlank()) {
            results.add(ValidationResult.Error("课程名称不能为空"))
        }
        if (course.name.trim().matches(Regex("^[A-Za-z0-9_]+$")) && course.name.length < 3) {
            results.add(ValidationResult.Warning("课程名称「${course.name}」可能是课程代码而非课程名称"))
        }
    }

    private fun validateFieldLengths(course: Course, results: MutableList<ValidationResult>) {
        if (course.name.length > MAX_COURSE_NAME_LENGTH) {
            results.add(ValidationResult.Error("课程名称过长（最大 $MAX_COURSE_NAME_LENGTH 字符）"))
        }
        if (course.teacher.length > MAX_TEACHER_NAME_LENGTH) {
            results.add(ValidationResult.Warning("教师名称过长，已超过 $MAX_TEACHER_NAME_LENGTH 字符"))
        }
        if (course.classroom.length > MAX_CLASSROOM_LENGTH) {
            results.add(ValidationResult.Warning("教室名称过长，已超过 $MAX_CLASSROOM_LENGTH 字符"))
        }
    }

    private fun validateRanges(course: Course, results: MutableList<ValidationResult>) {
        if (course.dayOfWeek !in 1..7) {
            results.add(ValidationResult.Error("星期必须在 1-7 范围内（当前: ${course.dayOfWeek}）"))
        }
        if (course.startTime !in MIN_TIME_SLOT..MAX_TIME_SLOT) {
            results.add(ValidationResult.Error("开始节次必须在 $MIN_TIME_SLOT-$MAX_TIME_SLOT 范围内（当前: ${course.startTime}）"))
        }
        if (course.endTime !in MIN_TIME_SLOT..MAX_TIME_SLOT) {
            results.add(ValidationResult.Error("结束节次必须在 $MIN_TIME_SLOT-$MAX_TIME_SLOT 范围内（当前: ${course.endTime}）"))
        }
        if (course.weekBitmap == 0L) {
            results.add(ValidationResult.Error("周次不能为空"))
        }
        val weekList = Course.bitmapToWeekList(course.weekBitmap)
        if (weekList.isNotEmpty()) {
            if (weekList.first() < MIN_WEEK || weekList.last() > MAX_WEEK) {
                results.add(ValidationResult.Error(
                    "周次必须在 $MIN_WEEK-$MAX_WEEK 范围内（当前: ${weekList.first()}-${weekList.last()}）"
                ))
            }
        }
    }

    private fun validateLogicConsistency(course: Course, results: MutableList<ValidationResult>) {
        // 结束节次必须 >= 开始节次
        if (course.endTime < course.startTime) {
            results.add(ValidationResult.Error("结束节次(${course.endTime})不能小于开始节次(${course.startTime})"))
        }

        // 单节课程合理性检查
        if (course.startTime == course.endTime && course.endTime > 0) {
            results.add(ValidationResult.Warning("课程只有 1 节，确认是否正确"))
        }

        // 超长课程警告（超过 4 节）
        if (course.endTime - course.startTime > 4) {
            results.add(ValidationResult.Warning("课程节次跨度较大（${course.endTime - course.startTime + 1} 节），请确认"))
        }

        // 跨天课程警告
        if (course.endTime - course.startTime > 10) {
            results.add(ValidationResult.Error("课程节次跨度异常（${course.endTime - course.startTime + 1} 节），可能数据有误"))
        }
    }
}
