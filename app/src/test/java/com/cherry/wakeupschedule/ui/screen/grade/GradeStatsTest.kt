package com.cherry.wakeupschedule.ui.screen.grade

import com.cherry.wakeupschedule.model.GradeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GradeStats] 的筛选 / 排序 / 汇总测试（纯逻辑，不依赖 Android）。 */
class GradeStatsTest {

    private fun grade(
        name: String = "课程",
        score: String = "90",
        percentage: String = "",
        creditGpa: String = "0",
        credits: String = "0",
        nature: String = "",
        type: String = "",
        mark: String = "",
        voided: String = "否"
    ) = GradeEntity(
        semesterId = 1L,
        courseName = name,
        score = score,
        percentageScore = percentage,
        creditGradePoint = creditGpa,
        credits = credits,
        courseNature = nature,
        courseType = type,
        courseMark = mark,
        scoreVoided = voided
    )

    // ── 必修 / 选修 ────────────────────────────────────────

    @Test
    fun `选修课从开课类型识别`() {
        // 实测：通识选修课的课程性质是「公共艺术课程模块」，只有开课类型带「选修」
        val elective = grade(nature = "公共艺术课程模块", type = "通识选修课")

        assertTrue(GradeStats.isElective(elective))
        assertFalse(GradeStats.isRequired(elective))
    }

    @Test
    fun `专业选修课从课程性质识别`() {
        val elective = grade(nature = "专业选修课", type = "主修课程")

        assertTrue(GradeStats.isElective(elective))
    }

    @Test
    fun `核心课与通识必修课算必修`() {
        // 实测这些都不含「选修」二字，属于必修侧
        listOf(
            grade(nature = "学类核心课", type = "主修课程"),
            grade(nature = "学门核心课", type = "主修课程"),
            grade(nature = "通识必修课", type = "主修课程"),
            grade(nature = "集中实践必修", type = "特殊课程")
        ).forEach {
            assertTrue(GradeStats.isRequired(it))
            assertFalse(GradeStats.isElective(it))
        }
    }

    // ── 不及格 ────────────────────────────────────────────

    @Test
    fun `数值成绩低于60算不及格`() {
        assertTrue(GradeStats.isFailed(grade(score = "54")))
        assertFalse(GradeStats.isFailed(grade(score = "60")))
        assertFalse(GradeStats.isFailed(grade(score = "92")))
    }

    @Test
    fun `百分制成绩优先用于判定`() {
        // 成绩栏是等级、百分制栏是数值时，以百分制为准
        assertTrue(GradeStats.isFailed(grade(score = "不及格", percentage = "40")))
        assertFalse(GradeStats.isFailed(grade(score = "及格", percentage = "75")))
    }

    @Test
    fun `等级制不及格写法也能识别`() {
        assertTrue(GradeStats.isFailed(grade(score = "F")))
        assertTrue(GradeStats.isFailed(grade(score = "不及格")))
        assertFalse(GradeStats.isFailed(grade(score = "优")))
        assertFalse(GradeStats.isFailed(grade(score = "A")))
    }

    @Test
    fun `作废成绩不算不及格也不计入汇总`() {
        val voided = grade(score = "30", credits = "3", creditGpa = "12", voided = "是")

        assertFalse(GradeStats.isFailed(voided))
        assertFalse(GradeStats.isPassed(voided))

        val summary = GradeStats.summarize(listOf(voided))
        assertNull(summary.averageGpa)
        assertEquals(0.0, summary.totalCredits, 0.0001)
    }

    // ── 筛选 ──────────────────────────────────────────────

    @Test
    fun `筛选全部必修选修不及格`() {
        val list = listOf(
            grade(name = "必修A", score = "90", nature = "通识必修课"),
            grade(name = "选修B", score = "85", nature = "专业选修课"),
            grade(name = "挂科C", score = "50", nature = "学类核心课")
        )

        assertEquals(3, GradeStats.filter(list, GradeFilter.ALL).size)
        assertEquals(
            listOf("必修A", "挂科C"),
            GradeStats.filter(list, GradeFilter.REQUIRED).map { it.courseName }
        )
        assertEquals(
            listOf("选修B"),
            GradeStats.filter(list, GradeFilter.ELECTIVE).map { it.courseName }
        )
        assertEquals(
            listOf("挂科C"),
            GradeStats.filter(list, GradeFilter.FAILED).map { it.courseName }
        )
    }

    // ── 排序 ──────────────────────────────────────────────

    @Test
    fun `按分数排序时等级制排末尾`() {
        val list = listOf(
            grade(name = "92分", score = "92"),
            grade(name = "54分", score = "54"),
            grade(name = "等级A", score = "A"),
            grade(name = "88分", score = "88")
        )

        assertEquals(
            listOf("92分", "88分", "54分", "等级A"),
            GradeStats.sort(list, descending = true).map { it.courseName }
        )
        assertEquals(
            listOf("54分", "88分", "92分", "等级A"),
            GradeStats.sort(list, descending = false).map { it.courseName }
        )
    }

    // ── 汇总 ──────────────────────────────────────────────

    @Test
    fun `平均学分绩点按学分加权且总学分只算通过课程`() {
        val list = listOf(
            // 4 学分，学分绩点 16（绩点 4.0），通过
            grade(name = "A", score = "92", credits = "4.0", creditGpa = "16.0"),
            // 2 学分，学分绩点 6，等级制通过
            grade(name = "B", score = "良", credits = "2.0", creditGpa = "6.0"),
            // 3.5 学分，不及格，不计入已获学分
            grade(name = "C", score = "54", credits = "3.5", creditGpa = "3.5"),
            // 作废，整体忽略
            grade(name = "D", score = "88", credits = "3.0", creditGpa = "12.0", voided = "是")
        )

        val summary = GradeStats.summarize(list)

        // (16 + 6 + 3.5) / (4 + 2 + 3.5) = 25.5 / 9.5
        assertEquals(25.5 / 9.5, summary.averageGpa!!, 0.0001)
        // 只累计通过课程：A(4) + B(2)
        assertEquals(6.0, summary.totalCredits, 0.0001)
    }

    @Test
    fun `空列表汇总为无绩点零学分`() {
        val summary = GradeStats.summarize(emptyList())

        assertNull(summary.averageGpa)
        assertEquals(0.0, summary.totalCredits, 0.0001)
        assertEquals("—", GradeStats.formatGpa(summary.averageGpa))
    }

    // ── 展示格式化 ────────────────────────────────────────

    @Test
    fun `学分整数不带小数位`() {
        assertEquals("4", GradeStats.formatCredits(4.0))
        assertEquals("4.5", GradeStats.formatCredits(4.5))
        assertEquals("4 学分", GradeStats.formatCreditBadge(grade(credits = "4")))
        assertEquals("4.5 学分", GradeStats.formatCreditBadge(grade(credits = "4.5")))
        assertEquals("学分未知", GradeStats.formatCreditBadge(grade(credits = "")))
    }
}
