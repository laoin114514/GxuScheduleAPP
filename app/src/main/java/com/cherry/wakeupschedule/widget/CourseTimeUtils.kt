package com.cherry.wakeupschedule.widget

import android.content.Context
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager

/**
 * 小组件侧"节次 → 当天分钟数"的统一换算。
 *
 * 各 Provider 的倒计时/状态判断都依赖它：优先用时间表里该节次的 HH:mm，
 * 时间表缺失或格式异常时兜底为"第 1 节 8:00 开始、每节 45 分钟"的估算值。
 *
 * 性能：换算结果只取决于 `TimeTableManager.getTimeSlots()` 的内容，而后者本身已是
 * 内存缓存；这里再按「时间表快照引用」缓存 node → 分钟 的映射，避免每次换算都
 * `find` 扫描 + `split(":")` 字符串解析。6 款小组件的刷新路径都会走这里，倒计时
 * 组件的内容计算在每秒 tick 里还会遍历今日课程的所有起止分钟。
 */
object CourseTimeUtils {

    private val cacheLock = Any()

    /** 上次建立映射所用的时间表快照（引用比较：保存时间表后 TimeTableManager 会换新实例） */
    @Volatile
    private var cachedSlots: List<TimeTableManager.TimeSlot>? = null

    /** 节次 → 开始分钟（只收录能解析成 HH:mm 的节次，其余走旧的估算兜底） */
    @Volatile
    private var startByNode: Map<Int, Int> = emptyMap()

    /** 节次 → 结束分钟 */
    @Volatile
    private var endByNode: Map<Int, Int> = emptyMap()

    /** 课程当天开始时间（分钟数） */
    fun getStartMinutes(context: Context, course: Course): Int = try {
        ensureTimeCache(context)
        startByNode[course.startTime] ?: ((8 + course.startTime) * 60)
    } catch (e: Exception) {
        (8 + course.startTime) * 60
    }

    /** 课程当天结束时间（分钟数） */
    fun getEndMinutes(context: Context, course: Course): Int = try {
        ensureTimeCache(context)
        endByNode[course.endTime] ?: ((8 + course.endTime) * 60 + 45)
    } catch (e: Exception) {
        (8 + course.endTime) * 60 + 45
    }

    /** 当前周次（学期未设置时回落到手动选择的默认周） */
    fun getCurrentWeek(settingsManager: SettingsManager): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return settingsManager.getDefaultWeek()
        return (((System.currentTimeMillis() - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1)
            .coerceIn(1, settingsManager.getTotalWeeks())
    }

    /** 指定时刻所在的周次（跨周判断用，如"明天"已进入下一周） */
    fun getWeekForDate(settingsManager: SettingsManager, timeMillis: Long): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return settingsManager.getDefaultWeek()
        return (((timeMillis - startDate) / (1000 * 60 * 60 * 24)).toInt() / 7 + 1)
            .coerceIn(1, settingsManager.getTotalWeeks())
    }

    /**
     * 确保 node → 分钟 映射与当前时间表快照一致。
     *
     * 与旧实现逐条对齐：
     * - 同一 node 在时间表里出现多次时取第一条（旧实现是 `find`），因此用 containsKey 判断；
     * - 节次存在但时间字符串不是合法 HH:mm 时，旧实现走估算兜底，这里不写入映射让调用方兜底；
     * - 解析异常同样兜底（旧实现由外层 try/catch 兜底）。
     */
    private fun ensureTimeCache(context: Context) {
        val slots = TimeTableManager.getInstance(context).getTimeSlots()
        if (slots === cachedSlots) return
        synchronized(cacheLock) {
            if (slots === cachedSlots) return
            val starts = HashMap<Int, Int>(slots.size.coerceAtLeast(4) * 2)
            val ends = HashMap<Int, Int>(slots.size.coerceAtLeast(4) * 2)
            for (slot in slots) {
                parseClockMinutes(slot.startTime)?.let { if (!starts.containsKey(slot.node)) starts[slot.node] = it }
                parseClockMinutes(slot.endTime)?.let { if (!ends.containsKey(slot.node)) ends[slot.node] = it }
            }
            startByNode = starts
            endByNode = ends
            cachedSlots = slots
        }
    }

    /** "HH:mm" → 当天分钟数；格式不合法返回 null（调用方走旧估算兜底） */
    private fun parseClockMinutes(clock: String): Int? {
        val parts = clock.split(":")
        if (parts.size != 2) return null
        return try {
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) {
            null
        }
    }
}
