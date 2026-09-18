package com.cherry.wakeupschedule.ui.screen.grade

import com.cherry.wakeupschedule.model.GradeEntity
import com.cherry.wakeupschedule.model.SemesterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 绩点计算页的纯逻辑测试：汇总新指标 + 成绩估算 + 模拟试算 + 展示格式化。
 *
 * 既有的筛选 / 排序 / 旧汇总口径见 [GradeStatsTest]。
 */
class GpaCalcTest {

    private fun grade(
        name: String = "课程",
        score: String = "90",
        creditGpa: String = "0",
        credits: String = "0",
        semesterId: Long = 1L,
        voided: String = "否"
    ) = GradeEntity(
        semesterId = semesterId,
        courseName = name,
        score = score,
        creditGradePoint = creditGpa,
        credits = credits,
        scoreVoided = voided
    )

    private fun semester(id: Long, sortOrder: Int, label: String) = SemesterEntity(
        id = id,
        label = label,
        academicYear = "2024-2025",
        termName = "第一学期",
        termCode = "3",
        enrollmentYear = "2024",
        sortOrder = sortOrder
    )

    // ── 学期标题 ──────────────────────────────────────────

    @Test
    fun `学期标题包含年级学年与学期`() {
        assertEquals(
            "大二上 · 2024-2025学年 第一学期",
            GradeStats.semesterTitle(semester(3L, 2, "大二上"))
        )
    }

    // ── 汇总新指标 ────────────────────────────────────────

    @Test
    fun `加权平均分按学分加权且等级制课程不参与`() {
        val list = listOf(
            grade(name = "A", score = "90", credits = "4.0", creditGpa = "16.0"),
            // 等级制：没有百分制分数，不进加权平均分
            grade(name = "B", score = "良", credits = "2.0", creditGpa = "6.0"),
            grade(name = "C", score = "80", credits = "1.0", creditGpa = "3.0")
        )

        val summary = GradeStats.summarize(list)

        // (90×4 + 80×1) / (4 + 1)
        assertEquals(88.0, summary.weightedAverageScore!!, 0.0001)
        assertEquals(3, summary.courseCount)
    }

    @Test
    fun `课程门数排除作废与无成绩条目, 不及格单独计数`() {
        val list = listOf(
            grade(name = "通过", score = "90", credits = "2.0", creditGpa = "8.0"),
            grade(name = "挂科", score = "54", credits = "3.0", creditGpa = "3.0"),
            grade(name = "没出分", score = "", credits = "1.0", creditGpa = "0"),
            grade(name = "作废", score = "88", credits = "3.0", creditGpa = "12.0", voided = "是")
        )

        val summary = GradeStats.summarize(list)

        assertEquals(2, summary.courseCount)
        assertEquals(1, summary.failedCount)
        // 作废与无成绩都不参与加权平均分
        assertEquals((90 * 2.0 + 54 * 3.0) / 5.0, summary.weightedAverageScore!!, 0.0001)
    }

    @Test
    fun `没有百分制成绩时加权平均分为空`() {
        val summary = GradeStats.summarize(
            listOf(grade(name = "等级课", score = "优", credits = "2.0", creditGpa = "0"))
        )

        assertNull(summary.weightedAverageScore)
        assertEquals(1, summary.courseCount)
        assertEquals(0, summary.failedCount)
    }

    @Test
    fun `全部学期成绩合并后按总学分加权`() {
        // 「计算」会把所有学期写进同一张表，这里验证跨学期汇总的口径
        val list = listOf(
            grade(name = "大一上A", score = "90", credits = "4.0", creditGpa = "16.0", semesterId = 1L),
            grade(name = "大一下B", score = "80", credits = "2.0", creditGpa = "4.0", semesterId = 2L),
            grade(name = "大二上C", score = "60", credits = "1.0", creditGpa = "1.0", semesterId = 3L)
        )

        val summary = GradeStats.summarize(list)

        // (16 + 4 + 1) / (4 + 2 + 1)
        assertEquals(3.0, summary.averageGpa!!, 0.0001)
        assertEquals(7.0, summary.totalCredits, 0.0001)
        assertEquals((90 * 4.0 + 80 * 2.0 + 60 * 1.0) / 7.0, summary.weightedAverageScore!!, 0.0001)
    }

    // ── 成绩 → 绩点估算 ───────────────────────────────────

    @Test
    fun `成绩换算绩点的边界`() {
        assertEquals(0.0, GradeStats.estimateGradePoint(0.0), 0.0001)
        assertEquals(0.0, GradeStats.estimateGradePoint(59.9), 0.0001)
        assertEquals(1.0, GradeStats.estimateGradePoint(60.0), 0.0001)
        assertEquals(4.0, GradeStats.estimateGradePoint(90.0), 0.0001)
        assertEquals(5.0, GradeStats.estimateGradePoint(100.0), 0.0001)
        // 越界由 clamp 兜底，不产生 > 5 的绩点
        assertEquals(5.0, GradeStats.estimateGradePoint(120.0), 0.0001)
    }

    // ── 模拟试算 ──────────────────────────────────────────

    @Test
    fun `模拟课程与真实成绩一起参与平均学分绩点`() {
        val base = listOf(grade(name = "A", score = "90", credits = "4.0", creditGpa = "16.0"))
        val simulated = listOf(SimulatedCourse(credits = 2.0, gradePoint = 3.0, passed = true))

        val summary = GradeStats.summarize(base, simulated)

        assertEquals((16.0 + 6.0) / 6.0, summary.averageGpa!!, 0.0001)
        assertEquals(6.0, summary.totalCredits, 0.0001)
        // 模拟课程没有百分制成绩，不改变加权平均分
        assertEquals(90.0, summary.weightedAverageScore!!, 0.0001)
    }

    @Test
    fun `模拟不及格只进绩点分母不进已获学分`() {
        val summary = GradeStats.summarize(
            emptyList(),
            listOf(SimulatedCourse(credits = 3.0, gradePoint = 0.0, passed = false))
        )

        assertEquals(0.0, summary.averageGpa!!, 0.0001)
        assertEquals(0.0, summary.totalCredits, 0.0001)
    }

    @Test
    fun `空基数的模拟结果只由假设课程决定`() {
        val summary = GradeStats.summarize(
            emptyList(),
            listOf(
                SimulatedCourse(credits = 4.0, gradePoint = 3.5, passed = true),
                SimulatedCourse(credits = 1.0, gradePoint = 4.5, passed = true)
            )
        )

        assertEquals((4.0 * 3.5 + 1.0 * 4.5) / 5.0, summary.averageGpa!!, 0.0001)
        assertEquals(5.0, summary.totalCredits, 0.0001)
    }

    @Test
    fun `学分非正的模拟行不参与计算`() {
        val summary = GradeStats.summarize(
            emptyList(),
            listOf(SimulatedCourse(credits = 0.0, gradePoint = 4.0, passed = true))
        )

        assertNull(summary.averageGpa)
        assertEquals(0.0, summary.totalCredits, 0.0001)
    }

    @Test
    fun `没有模拟课程时汇总与旧口径一致`() {
        val list = listOf(
            grade(name = "A", score = "92", credits = "4.0", creditGpa = "16.0"),
            grade(name = "B", score = "54", credits = "3.5", creditGpa = "3.5")
        )

        val summary = GradeStats.summarize(list, emptyList())

        assertEquals(19.5 / 7.5, summary.averageGpa!!, 0.0001)
        assertEquals(4.0, summary.totalCredits, 0.0001)
        assertEquals(GradeStats.summarize(list), summary)
    }

    // ── 输入解析 ──────────────────────────────────────────

    @Test
    fun `输入解析取第一个数字并忽略空值`() {
        assertEquals(4.5, GradeStats.parseNumber("4.5 学分")!!, 0.0001)
        assertEquals(88.0, GradeStats.parseNumber("88")!!, 0.0001)
        assertNull(GradeStats.parseNumber(""))
        assertNull(GradeStats.parseNumber("   "))
        assertNull(GradeStats.parseNumber(null))
        assertNull(GradeStats.parseNumber("没填"))
    }
}
