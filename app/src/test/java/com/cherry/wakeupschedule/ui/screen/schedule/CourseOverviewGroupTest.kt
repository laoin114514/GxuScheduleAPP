package com.cherry.wakeupschedule.ui.screen.schedule

import com.cherry.wakeupschedule.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CourseOverviewGroup] 的归并规则与格式化工具测试（纯逻辑，不依赖 Android）。
 */
class CourseOverviewGroupTest {

    private fun course(
        name: String,
        teacher: String = "张三",
        classroom: String = "A101",
        dayOfWeek: Int = 1,
        startTime: Int = 1,
        endTime: Int = 2,
        weekBitmap: Long = Course.bitmapFromRange(1, 16),
        credits: String = "",
        category: String = "",
        color: Int = 1,
        isPractice: Boolean = false,
        isAdjusted: Boolean = false
    ) = Course(
        name = name,
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = dayOfWeek,
        startTime = startTime,
        endTime = endTime,
        weekBitmap = weekBitmap,
        credits = credits,
        courseCategory = category,
        color = color,
        isPractice = isPractice,
        isAdjusted = isAdjusted
    )

    // ── 归并 ─────────────────────────────────────────────

    @Test
    fun `同名课程合并为一条并保留全部原始课程`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("高等数学", dayOfWeek = 3, startTime = 3, endTime = 4),
                course("高等数学", dayOfWeek = 1, startTime = 1, endTime = 2)
            )
        )

        assertEquals(1, groups.size)
        val g = groups[0]
        assertEquals("高等数学", g.name)
        assertEquals(2, g.courses.size)
        assertEquals(2, g.sessions.size)
        // 时段按 (星期, 开始节次) 升序
        assertEquals(listOf(1 to 1, 3 to 3), g.sessions.map { it.dayOfWeek to it.startTime })
    }

    @Test
    fun `不同课程名不合并且按最早时段排序`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("大学英语", dayOfWeek = 2, startTime = 3, endTime = 4),
                course("高等数学", dayOfWeek = 1, startTime = 1, endTime = 2)
            )
        )

        assertEquals(listOf("高等数学", "大学英语"), groups.map { it.name })
    }

    @Test
    fun `同名课程教师去重合并空值不参与`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("离散数学", teacher = "张三"),
                course("离散数学", teacher = "李四"),
                course("离散数学", teacher = "张三"),
                course("离散数学", teacher = "  ")
            )
        )

        assertEquals("张三、李四", groups[0].teachers)
    }

    @Test
    fun `学分类别颜色取第一个非空值`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("数据结构", credits = "", category = "", color = 4),
                course("数据结构", credits = "3.0", category = "专业核心", color = 4)
            )
        )

        assertEquals("3.0", groups[0].credits)
        assertEquals("专业核心", groups[0].category)
        assertEquals(4, groups[0].colorIndex)
    }

    @Test
    fun `周次取组内位图并集`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("线性代数", weekBitmap = Course.bitmapFromRange(1, 8)),
                course("线性代数", weekBitmap = Course.bitmapFromRange(9, 16))
            )
        )

        assertEquals(Course.bitmapFromRange(1, 16), groups[0].weekBitmap)
        assertEquals("第1-16周", formatWeekRanges(groups[0].weekBitmap))
    }

    @Test
    fun `重复时段去重`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("体育", dayOfWeek = 5, startTime = 5, endTime = 6, classroom = "操场"),
                course("体育", dayOfWeek = 5, startTime = 5, endTime = 6, classroom = "操场")
            )
        )

        assertEquals(1, groups[0].sessions.size)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(CourseOverviewGroup.build(emptyList()).isEmpty())
    }

    @Test
    fun `课程名为空白时回退未命名课程`() {
        val groups = CourseOverviewGroup.build(listOf(course("   "), course("  ")))

        assertEquals(1, groups.size)
        assertEquals(CourseOverviewGroup.UNNAMED_COURSE, groups[0].name)
    }

    @Test
    fun `无固定时间的实践课只归并课程不产生时段`() {
        val groups = CourseOverviewGroup.build(
            listOf(course("数据结构综合实践", dayOfWeek = 0, startTime = 0, endTime = 0))
        )

        assertEquals(1, groups.size)
        assertEquals("数据结构综合实践", groups[0].name)
        assertTrue(!groups[0].hasFixedTime)
        assertTrue(groups[0].sessions.isEmpty())
    }

    @Test
    fun `无固定时间的课程排在固定时段课程之后`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("数据结构综合实践", dayOfWeek = 0, startTime = 0, endTime = 0),
                course("高等数学", dayOfWeek = 1, startTime = 1, endTime = 2)
            )
        )

        assertEquals(listOf("高等数学", "数据结构综合实践"), groups.map { it.name })
    }

    @Test
    fun `同名课程既有实践课条目又有固定时段时保留时段`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("数字电路与逻辑设计实践", dayOfWeek = 0, startTime = 0, endTime = 0),
                course("数字电路与逻辑设计实践", dayOfWeek = 3, startTime = 5, endTime = 6)
            )
        )

        assertEquals(1, groups.size)
        assertTrue(groups[0].hasFixedTime)
        assertEquals(1, groups[0].sessions.size)
        assertEquals(2, groups[0].courses.size)
    }

    // ── 门数口径（导入成功提示与总课表共用） ─────────────

    @Test
    fun `去重门数按课程名计算`() {
        val courses = listOf(
            course("高等数学", teacher = "张三", dayOfWeek = 1, startTime = 1, endTime = 2),
            course("高等数学", teacher = "李四", dayOfWeek = 3, startTime = 3, endTime = 4),
            course("大学英语", dayOfWeek = 2, startTime = 1, endTime = 2)
        )

        assertEquals(2, Course.distinctNameCount(courses))
    }

    @Test
    fun `去重门数与总课表条目数一致`() {
        val courses = listOf(
            course("高等数学", dayOfWeek = 1, startTime = 1, endTime = 2),
            course("高等数学", dayOfWeek = 3, startTime = 3, endTime = 4),
            course("数据结构综合实践", dayOfWeek = 0, startTime = 0, endTime = 0),
            course("  大学英语  ", dayOfWeek = 2, startTime = 1, endTime = 2),
            course("大学英语", dayOfWeek = 4, startTime = 1, endTime = 2)
        )

        assertEquals(CourseOverviewGroup.build(courses).size, Course.distinctNameCount(courses))
    }

    @Test
    fun `空列表去重门数为零`() {
        assertEquals(0, Course.distinctNameCount(emptyList()))
    }

    // ── 类型标签与筛选 ───────────────────────────────────

    @Test
    fun `实践课条目标记为实践课`() {
        val groups = CourseOverviewGroup.build(
            listOf(course("数据结构综合实践", dayOfWeek = 0, startTime = 0, endTime = 0, isPractice = true))
        )

        assertTrue(groups[0].isPractice)
    }

    @Test
    fun `同名课程出现实践课条目时整条按实践课归类`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("数字电路与逻辑设计实践", isPractice = true),
                course("数字电路与逻辑设计实践", isPractice = false)
            )
        )

        assertEquals(1, groups.size)
        assertTrue(groups[0].isPractice)
    }

    @Test
    fun `筛选按实践课标记而不是有没有固定时间`() {
        // 集中实践必修但正常排了课的理论课（如文献检索）仍算理论课
        val theory = course("文献检索", category = "集中实践必修", isPractice = false)
        val practice = course("数据结构综合实践", dayOfWeek = 0, startTime = 0, endTime = 0, isPractice = true)
        val groups = CourseOverviewGroup.build(listOf(theory, practice))

        assertEquals(groups, CourseTypeFilter.ALL.apply(groups))
        assertEquals(listOf("文献检索"), CourseTypeFilter.THEORY.apply(groups).map { it.name })
        assertEquals(listOf("数据结构综合实践"), CourseTypeFilter.PRACTICE.apply(groups).map { it.name })
    }

    @Test
    fun `筛选标签与顺序固定`() {
        assertEquals(listOf("全部", "理论课", "实践课"), CourseTypeFilter.entries.map { it.label })
    }

    // ── 调课标记 ─────────────────────────────────────────

    @Test
    fun `任一行调课则整组标记调课`() {
        // 调课标志是教学班级别：同一教学班多行里任一行命中，整条都要显示标签
        val groups = CourseOverviewGroup.build(
            listOf(
                course("高等数学", dayOfWeek = 1, startTime = 1, endTime = 2, isAdjusted = false),
                course("高等数学", dayOfWeek = 3, startTime = 3, endTime = 4, isAdjusted = true)
            )
        )

        assertEquals(1, groups.size)
        assertTrue(groups[0].hasAdjustment)
    }

    @Test
    fun `全部无调课则整组不标记调课`() {
        val groups = CourseOverviewGroup.build(
            listOf(
                course("高等数学", dayOfWeek = 1, startTime = 1, endTime = 2),
                course("大学英语", dayOfWeek = 2, startTime = 3, endTime = 4)
            )
        )

        assertTrue(groups.none { it.hasAdjustment })
    }

    // ── 选课人数文案 ─────────────────────────────────────

    @Test
    fun `选课人数带上限时显示已选与上限`() {
        assertEquals("68 / 70", formatEnrollment("68", "70"))
    }

    @Test
    fun `选课人数缺上限时只显示已选`() {
        assertEquals("68", formatEnrollment("68", ""))
    }

    @Test
    fun `选课人数都为空时返回空串`() {
        assertEquals("", formatEnrollment("", ""))
        assertEquals("", formatEnrollment("", "70"))
    }

    // ── 格式化 ───────────────────────────────────────────

    @Test
    fun `连续周合并为区间`() {
        assertEquals("第1-16周", formatWeekRanges(Course.bitmapFromRange(1, 16)))
    }

    @Test
    fun `间断周逐段列出`() {
        val bitmap = Course.bitmapFromRange(1, 5) or
            Course.bitmapFromRange(7, 10) or
            Course.bitmapFromRange(13, 13)

        assertEquals("第1-5、7-10、13周", formatWeekRanges(bitmap))
    }

    @Test
    fun `单周位图直接列出`() {
        assertEquals("第3、5周", formatWeekRanges(Course.bitmapFromRange(3, 3) or Course.bitmapFromRange(5, 5)))
    }

    @Test
    fun `空位图返回周次未设置`() {
        assertEquals("周次未设置", formatWeekRanges(0L))
    }

    @Test
    fun `星期标签覆盖一到日与越界`() {
        assertEquals(
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"),
            (1..7).map { dayOfWeekLabel(it) }
        )
        assertEquals("", dayOfWeekLabel(0))
        assertEquals("", dayOfWeekLabel(8))
    }
}
