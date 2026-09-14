package com.cherry.wakeupschedule.ui.screen.grade

import com.cherry.wakeupschedule.model.GradeEntity
import java.util.Locale

/** 成绩列表筛选条件 */
enum class GradeFilter(val label: String) {
    ALL("全部"),
    REQUIRED("必修"),
    ELECTIVE("选修"),
    FAILED("不及格")
}

/** 学期成绩汇总 */
data class GradeSummary(
    /** 平均学分绩点；无有效数据时为 null */
    val averageGpa: Double?,
    /** 已通过课程的学分之和 */
    val totalCredits: Double
)

/**
 * 成绩的筛选 / 排序 / 汇总纯逻辑（不依赖 Android，可单测）。
 *
 * 口径说明：
 * - 成绩字段 `cj` 可能是数值也可能是等级（优/良/中/及格），百分制成绩 `bfzcj` 优先用于数值判断
 * - 作废成绩（`cjsfzf == "是"`）不计入汇总，也不参与成绩高低排序的有效值
 * - 平均学分绩点 = Σ 学分绩点(xfjd) / Σ 学分(xf)
 * - 总学分只累计已通过（数值 ≥ 60 或等级非不及格）的课程
 */
object GradeStats {

    /** 及格线 */
    const val PASS_SCORE = 60.0

    private const val VOIDED_YES = "是"
    private val NUMERIC = Regex("""\d+(?:\.\d+)?""")

    // ── 判定 ──────────────────────────────────────────────

    fun isVoided(grade: GradeEntity): Boolean = grade.scoreVoided.trim() == VOIDED_YES

    /** 百分制成绩优先，其次看成绩本身；两者都不是数值时返回 null（等级制） */
    fun numericScore(grade: GradeEntity): Double? =
        parseNumber(grade.percentageScore) ?: parseNumber(grade.score)

    /** 等级制里明确不及格的写法（各校不一，覆盖常见几种） */
    private fun isFailedGradeText(grade: GradeEntity): Boolean {
        val text = grade.score.trim().uppercase(Locale.ROOT)
        return text == "F" || text.contains("不及格") || text.contains("未通过")
    }

    fun isFailed(grade: GradeEntity): Boolean {
        if (isVoided(grade)) return false
        val numeric = numericScore(grade)
        if (numeric != null) return numeric < PASS_SCORE
        // 等级制：只有明确写着不及格才算
        return grade.score.isNotBlank() && isFailedGradeText(grade)
    }

    fun isPassed(grade: GradeEntity): Boolean {
        if (isVoided(grade) || grade.score.isBlank()) return false
        val numeric = numericScore(grade)
        if (numeric != null) return numeric >= PASS_SCORE
        return !isFailedGradeText(grade)
    }

    private fun matches(grade: GradeEntity, keyword: String): Boolean =
        grade.courseNature.contains(keyword) ||
            grade.courseType.contains(keyword) ||
            grade.courseMark.contains(keyword) ||
            grade.courseCategory.contains(keyword)

    /**
     * 选修课判定。
     *
     * 实测教务把课程分成必修/选修两类，但**选修只在开课类型(kklxdm)里体现**：
     * 9 条「通识选修课」的课程性质全是「xx模块课」，并不含「选修」二字；
     * 另外 4 条「专业选修课」在课程性质里带「选修」。两处都要看。
     */
    fun isElective(grade: GradeEntity): Boolean = matches(grade, "选修")

    /** 非选修即必修（与教务的二元划分一致） */
    fun isRequired(grade: GradeEntity): Boolean = !isElective(grade)

    /** 列表里展示的类别文案：课程性质 → 课程类别 → 课程标记 */
    fun categoryLabel(grade: GradeEntity): String = when {
        grade.courseNature.isNotBlank() -> grade.courseNature
        grade.courseCategory.isNotBlank() -> grade.courseCategory
        else -> grade.courseMark
    }

    /** 右侧成绩下方的小标签：数值给「成绩」，等级制给「等级」 */
    fun scoreCaption(grade: GradeEntity): String =
        if (numericScore(grade) != null) "成绩" else "等级"

    // ── 筛选 / 排序 ────────────────────────────────────────

    fun filter(grades: List<GradeEntity>, filter: GradeFilter): List<GradeEntity> = when (filter) {
        GradeFilter.ALL -> grades
        GradeFilter.REQUIRED -> grades.filter { isRequired(it) }
        GradeFilter.ELECTIVE -> grades.filter { isElective(it) }
        GradeFilter.FAILED -> grades.filter { isFailed(it) }
    }

    /** 按分数排序；等级制课程（无数值成绩）统一排在末尾 */
    fun sort(grades: List<GradeEntity>, descending: Boolean): List<GradeEntity> =
        grades.sortedWith(
            compareBy<GradeEntity> { numericScore(it) == null }
                .thenComparator { a, b ->
                    val sa = numericScore(a) ?: 0.0
                    val sb = numericScore(b) ?: 0.0
                    if (descending) sb.compareTo(sa) else sa.compareTo(sb)
                }
        )

    // ── 汇总 ──────────────────────────────────────────────

    fun summarize(grades: List<GradeEntity>): GradeSummary {
        var creditSum = 0.0
        var creditGpaSum = 0.0
        var passedCreditSum = 0.0

        grades.filterNot { isVoided(it) }.forEach { grade ->
            val credit = parseNumber(grade.credits) ?: 0.0
            val creditGpa = parseNumber(grade.creditGradePoint)
            if (credit > 0 && creditGpa != null) {
                creditSum += credit
                creditGpaSum += creditGpa
            }
            if (isPassed(grade) && credit > 0) {
                passedCreditSum += credit
            }
        }

        val gpa = if (creditSum > 0) creditGpaSum / creditSum else null
        return GradeSummary(averageGpa = gpa, totalCredits = passedCreditSum)
    }

    // ── 展示格式化 ────────────────────────────────────────

    fun formatGpa(gpa: Double?): String =
        if (gpa == null) "—" else String.format(Locale.ROOT, "%.2f", gpa)

    fun formatCredits(credits: Double): String =
        if (credits == credits.toLong().toDouble()) credits.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", credits)

    /** 学分胶囊文案，如「4.5 学分」 */
    fun formatCreditBadge(grade: GradeEntity): String {
        val credit = parseNumber(grade.credits)
        return if (credit == null || credit <= 0) "学分未知" else "${formatCredits(credit)} 学分"
    }

    /** 学分数值文案（不带单位，4.0 → 4）；解析不到返回 null，由调用方决定是否展示该行 */
    fun formatCreditValue(grade: GradeEntity): String? =
        parseNumber(grade.credits)?.let { formatCredits(it) }

    private fun parseNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val match = NUMERIC.find(raw.trim()) ?: return null
        return match.value.toDoubleOrNull()
    }
}
