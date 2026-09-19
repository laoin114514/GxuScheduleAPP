package com.cherry.wakeupschedule.ui.screen.exam

import com.cherry.wakeupschedule.model.ExamScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [ExamScheduleStats] 的时间解析 / 状态判定 / 筛选 / 排序测试（纯逻辑，不依赖 Android）。
 *
 * 一律用固定时区 + 固定 now：状态判定是「现在」的函数，用系统默认时区会随机失败。
 */
class ExamScheduleStatsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun exam(
        name: String = "课程",
        time: String = "2026-07-16(15:00-17:00)",
        classroom: String = "6B-301",
        locations: String = "",
        id: Long = 0L
    ) = ExamScheduleEntity(
        id = id,
        semesterId = 1L,
        courseName = name,
        examTime = time,
        classroom = classroom,
        locations = locations
    )

    // ── 时间解析 ──────────────────────────────────────────

    @Test
    fun `标准格式解析出开始与结束时刻`() {
        val text = "2026-07-16(15:00-17:00)"

        assertEquals(at(2026, 7, 16, 15, 0), ExamScheduleStats.startAt(text, zone))
        assertEquals(at(2026, 7, 16, 17, 0), ExamScheduleStats.endAt(text, zone))
    }

    @Test
    fun `全角括号与冒号也能解析`() {
        val text = "2026-07-16（15：00-17：00）"

        assertEquals(at(2026, 7, 16, 15, 0), ExamScheduleStats.startAt(text, zone))
        assertEquals(at(2026, 7, 16, 17, 0), ExamScheduleStats.endAt(text, zone))
    }

    @Test
    fun `只有日期时按当天整天处理`() {
        val text = "2026-07-16"

        assertEquals(at(2026, 7, 16, 0, 0), ExamScheduleStats.startAt(text, zone))
        assertEquals(at(2026, 7, 16, 23, 59), ExamScheduleStats.endAt(text, zone))
        // 当天中午仍算进行中，而不是因为缺时间段被判成待定
        assertEquals(ExamStatus.ONGOING, ExamScheduleStats.statusOf(text, at(2026, 7, 16, 12, 0), zone))
    }

    @Test
    fun `结束时间早于开始时间按跨零点处理`() {
        val text = "2026-07-16(23:00-01:00)"

        assertEquals(at(2026, 7, 16, 23, 0), ExamScheduleStats.startAt(text, zone))
        assertEquals(at(2026, 7, 17, 1, 0), ExamScheduleStats.endAt(text, zone))
    }

    @Test
    fun `解析不出日期时状态为待定`() {
        listOf("", "   ", "--", "另行通知", "2026/07/16 15:00-17:00").forEach { text ->
            assertNull(text, ExamScheduleStats.startAt(text, zone))
            assertNull(text, ExamScheduleStats.endAt(text, zone))
            assertEquals(
                text,
                ExamStatus.UNKNOWN,
                ExamScheduleStats.statusOf(text, at(2026, 7, 16, 12, 0), zone)
            )
        }
    }

    // ── 状态边界 ──────────────────────────────────────────

    @Test
    fun `状态边界取闭区间`() {
        val text = "2026-07-16(15:00-17:00)"
        val start = at(2026, 7, 16, 15, 0)
        val end = at(2026, 7, 16, 17, 0)

        assertEquals(ExamStatus.NOT_STARTED, ExamScheduleStats.statusOf(text, start - 1, zone))
        assertEquals(ExamStatus.ONGOING, ExamScheduleStats.statusOf(text, start, zone))
        assertEquals(ExamStatus.ONGOING, ExamScheduleStats.statusOf(text, end, zone))
        assertEquals(ExamStatus.FINISHED, ExamScheduleStats.statusOf(text, end + 1, zone))
    }

    // ── 排序 ──────────────────────────────────────────────

    /** 未开始近 / 未开始远 / 进行中 / 已结束近 / 已结束早 / 时间待定 */
    private fun mixedList() = listOf(
        exam(id = 1, name = "已结束早", time = "2026-07-10(09:00-11:00)"),
        exam(id = 2, name = "未开始远", time = "2026-07-20(09:00-11:00)"),
        exam(id = 3, name = "进行中", time = "2026-07-16(11:00-13:00)"),
        exam(id = 4, name = "未开始近", time = "2026-07-17(09:00-11:00)"),
        exam(id = 5, name = "已结束近", time = "2026-07-15(09:00-11:00)"),
        exam(id = 6, name = "时间待定", time = "")
    )

    @Test
    fun `未开始排最前其余按距现在最近`() {
        val now = at(2026, 7, 16, 12, 0)

        assertEquals(
            listOf("未开始近", "未开始远", "进行中", "已结束近", "已结束早", "时间待定"),
            ExamScheduleStats.sort(mixedList(), now, zone).map { it.courseName }
        )
    }

    @Test
    fun `排序结果与输入顺序无关`() {
        val now = at(2026, 7, 16, 12, 0)
        val expected = listOf("未开始近", "未开始远", "进行中", "已结束近", "已结束早", "时间待定")

        assertEquals(expected, ExamScheduleStats.sort(mixedList(), now, zone).map { it.courseName })
        assertEquals(
            expected,
            ExamScheduleStats.sort(mixedList().reversed(), now, zone).map { it.courseName }
        )
    }

    // ── 筛选 ──────────────────────────────────────────────

    @Test
    fun `按状态筛选`() {
        val now = at(2026, 7, 16, 12, 0)
        val list = mixedList()

        assertEquals(6, ExamScheduleStats.filter(list, ExamFilter.ALL, now, zone).size)
        assertEquals(
            setOf("未开始近", "未开始远"),
            ExamScheduleStats.filter(list, ExamFilter.NOT_STARTED, now, zone).map { it.courseName }.toSet()
        )
        assertEquals(
            setOf("进行中"),
            ExamScheduleStats.filter(list, ExamFilter.ONGOING, now, zone).map { it.courseName }.toSet()
        )
        assertEquals(
            setOf("已结束近", "已结束早"),
            ExamScheduleStats.filter(list, ExamFilter.FINISHED, now, zone).map { it.courseName }.toSet()
        )
    }

    @Test
    fun `时间待定只出现在全部里`() {
        val now = at(2026, 7, 16, 12, 0)
        val list = mixedList()

        ExamFilter.values().filter { it != ExamFilter.ALL }.forEach { filter ->
            assertTrue(
                filter.name,
                ExamScheduleStats.filter(list, filter, now, zone).none { it.courseName == "时间待定" }
            )
        }
    }

    // ── 场地 ──────────────────────────────────────────────

    @Test
    fun `场地只用cdmc为空显示地点待定`() {
        assertEquals("6B-301", ExamScheduleStats.locationLabel(exam(classroom = "6B-301")))
        // 前后空白要 trim 掉再判断
        assertEquals("6B-301", ExamScheduleStats.locationLabel(exam(classroom = "  6B-301  ")))
        assertEquals("地点待定", ExamScheduleStats.locationLabel(exam(classroom = "")))
        assertEquals("地点待定", ExamScheduleStats.locationLabel(exam(classroom = "   ")))
        // 即便 jxdd 有值也不回退
        assertEquals(
            "地点待定",
            ExamScheduleStats.locationLabel(exam(classroom = "", locations = "6B-301;6B-501"))
        )
    }
}
