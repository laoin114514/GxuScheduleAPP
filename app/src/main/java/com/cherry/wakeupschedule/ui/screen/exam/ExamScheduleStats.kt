package com.cherry.wakeupschedule.ui.screen.exam

import com.cherry.wakeupschedule.model.ExamScheduleEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 考试状态。UNKNOWN 表示教务没给可解析的时间（例如时间栏为空或格式异常）。 */
enum class ExamStatus(val label: String) {
    NOT_STARTED("未开始"),
    ONGOING("进行中"),
    FINISHED("已结束"),
    UNKNOWN("时间待定")
}

/** 考试列表筛选条件。[status] 为 null 表示不过滤（全部）。 */
enum class ExamFilter(val label: String, val status: ExamStatus?) {
    ALL("全部", null),
    NOT_STARTED("未开始", ExamStatus.NOT_STARTED),
    ONGOING("进行中", ExamStatus.ONGOING),
    FINISHED("已结束", ExamStatus.FINISHED)
}

/**
 * 考试安排的解析 / 判定 / 筛选 / 排序纯逻辑（不依赖 Android，可单测）。
 *
 * 口径说明：
 * - 考试时间 `kssj` 实测形如 `"2026-07-16(15:00-17:00)"`，解析前先做全角→半角归一
 * - 只有日期、没有时间段时，按当天 `00:00 ~ 23:59` 处理，不因为缺时间段丢掉整条记录
 * - 日期都解析不出来 → [ExamStatus.UNKNOWN]，由调用方决定是否展示「时间待定」
 * - 状态边界取闭区间：`now == start` 与 `now == end` 都算「进行中」
 * - 排序：未开始 → 进行中 → 已结束 → 时间待定；桶内按「距 now 的时长」升序，
 *   因此未开始按开始时间由近到远、已结束按结束时间由近到远
 */
object ExamScheduleStats {

    private val DATE = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val TIME_RANGE = Regex("""(\d{1,2}):(\d{2})\s*[-~]\s*(\d{1,2}):(\d{2})""")

    private const val END_OF_DAY_HOUR = 23
    private const val END_OF_DAY_MINUTE = 59

    // ── 展示 ──────────────────────────────────────────────

    /**
     * 场地文案：只用教务的 `cdmc`。
     *
     * 为空（或只有空白）时显示「地点待定」，**不回退** `jxdd`。
     */
    fun locationLabel(exam: ExamScheduleEntity): String {
        val classroom = exam.classroom.trim()
        return if (classroom.isEmpty()) LOCATION_PLACEHOLDER else classroom
    }

    // ── 时间解析 ──────────────────────────────────────────

    /** 考试开始时刻（epoch ms）；日期解析不出来时返回 null */
    fun startAt(examTime: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
        windowOf(examTime, 0L, zone).start

    /** 考试结束时刻（epoch ms）；日期解析不出来时返回 null */
    fun endAt(examTime: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
        windowOf(examTime, 0L, zone).end

    // ── 状态 ──────────────────────────────────────────────

    fun status(
        exam: ExamScheduleEntity,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): ExamStatus = statusOf(exam.examTime, now, zone)

    fun statusOf(
        examTime: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): ExamStatus = windowOf(examTime, now, zone).status

    // ── 筛选 / 排序 ────────────────────────────────────────

    fun filter(
        exams: List<ExamScheduleEntity>,
        filter: ExamFilter,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<ExamScheduleEntity> {
        val target = filter.status ?: return exams
        return exams.filter { statusOf(it.examTime, now, zone) == target }
    }

    /**
     * 排序：未开始优先 → 其余按距 now 最近 → 时间未知垫底。
     *
     * 桶内用「距 now 的绝对时长」做键：未开始是 `start - now`（越近越前），
     * 已结束是 `now - end`（刚结束的越前），进行中恒为 0。
     */
    fun sort(
        exams: List<ExamScheduleEntity>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<ExamScheduleEntity> =
        exams.map { it to windowOf(it.examTime, now, zone) }
            .sortedWith(
                compareBy<Pair<ExamScheduleEntity, Window>> { bucketOf(it.second.status) }
                    .thenBy { it.second.distanceTo(now) }
                    .thenBy { it.second.start ?: Long.MAX_VALUE }
                    .thenBy { it.first.id }
            )
            .map { it.first }

    // ── 内部 ──────────────────────────────────────────────

    private const val LOCATION_PLACEHOLDER = "地点待定"

    /** 一次解析出的考试时间窗；[start] / [end] 为 null 表示时间未知 */
    private data class Window(val start: Long?, val end: Long?, val status: ExamStatus) {
        fun distanceTo(now: Long): Long {
            val begin = start ?: return Long.MAX_VALUE
            val finish = end ?: return Long.MAX_VALUE
            return when {
                begin > now -> begin - now
                finish < now -> now - finish
                else -> 0L
            }
        }
    }

    /** 排序桶序：未开始 0 < 进行中 1 < 已结束 2 < 时间待定 3 */
    private fun bucketOf(status: ExamStatus): Int = when (status) {
        ExamStatus.NOT_STARTED -> 0
        ExamStatus.ONGOING -> 1
        ExamStatus.FINISHED -> 2
        ExamStatus.UNKNOWN -> 3
    }

    private fun windowOf(examTime: String, now: Long, zone: ZoneId): Window {
        val parsed = parse(examTime)
            ?: return Window(start = null, end = null, status = ExamStatus.UNKNOWN)

        val start = toEpochMilli(parsed.date, parsed.start, zone)
        val end = toEpochMilli(parsed.date, parsed.end, zone)
            .let { if (parsed.end < parsed.start) it + ONE_DAY_MILLIS else it }

        val status = when {
            now < start -> ExamStatus.NOT_STARTED
            now > end -> ExamStatus.FINISHED
            else -> ExamStatus.ONGOING
        }
        return Window(start = start, end = end, status = status)
    }

    private data class ParsedTime(val date: LocalDate, val start: LocalTime, val end: LocalTime)

    private fun parse(examTime: String): ParsedTime? {
        val text = normalize(examTime)
        val dateMatch = DATE.find(text) ?: return null
        val (year, month, day) = dateMatch.destructured
        val date = runCatching {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        }.getOrNull() ?: return null

        // 时间段必须出现在日期之后，避免把日期前的无关数字当成时间
        val range = TIME_RANGE.find(text, dateMatch.range.last + 1)
            ?: return ParsedTime(date, LocalTime.MIN, LocalTime.of(END_OF_DAY_HOUR, END_OF_DAY_MINUTE))
        val (startHour, startMinute, endHour, endMinute) = range.destructured
        val start = timeOrNull(startHour, startMinute)
        val end = timeOrNull(endHour, endMinute)
        if (start == null || end == null) {
            return ParsedTime(date, LocalTime.MIN, LocalTime.of(END_OF_DAY_HOUR, END_OF_DAY_MINUTE))
        }
        return ParsedTime(date, start, end)
    }

    private fun timeOrNull(hour: String, minute: String): LocalTime? {
        val h = hour.toIntOrNull() ?: return null
        val m = minute.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    private fun toEpochMilli(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    /** 全角 → 半角：教务偶尔用全角括号/冒号，直接匹配会漏 */
    private fun normalize(raw: String): String = raw
        .replace('（', '(')
        .replace('）', ')')
        .replace('：', ':')
        .replace('～', '~')
        .replace('－', '-')
        .replace('–', '-')
        .replace('—', '-')
        .replace('　', ' ')

    private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
}
