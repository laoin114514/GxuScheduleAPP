package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * 课程数据类
 * Room 实体 + Serializable（用于 Intent 传递）
 */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,                        // 课程唯一ID

    @ColumnInfo(name = "name")
    val name: String,                         // 课程名称

    @ColumnInfo(name = "teacher")
    val teacher: String,                      // 任课教师

    @ColumnInfo(name = "classroom")
    val classroom: String,                     // 上课地点

    @ColumnInfo(name = "day_of_week")
    val dayOfWeek: Int,                       // 星期几（1-7，周一到周日）

    @ColumnInfo(name = "start_time")
    val startTime: Int,                       // 开始节次

    @ColumnInfo(name = "end_time")
    val endTime: Int,                         // 结束节次

    @ColumnInfo(name = "week_bitmap", defaultValue = "0")
    val weekBitmap: Long = 0,

    @ColumnInfo(name = "course_category", defaultValue = "")
    val courseCategory: String = "",

    @ColumnInfo(name = "alarm_enabled", defaultValue = "1")
    val alarmEnabled: Boolean = true,         // 是否启用闹钟提醒

    @ColumnInfo(name = "alarm_minutes_before", defaultValue = "15")
    val alarmMinutesBefore: Int = 15,          // 提前提醒分钟数

    @ColumnInfo(name = "color", defaultValue = "0")
    val color: Int = 0,                       // 课程颜色，0为默认颜色

    @ColumnInfo(name = "cover_image_path", defaultValue = "")
    val coverImagePath: String = ""            // 课程封面图片路径
) : Serializable {
    /**
     * 判断课程在指定周是否有课（位图 bit 0 = 第 1 周）
     */
    fun isActiveInWeek(week: Int): Boolean {
        if (week < 1 || week > 64) return false
        return (weekBitmap shr (week - 1)) and 1L == 1L
    }

    companion object {
        /** 从 startWeek/endWeek/weekType 生成位图（迁移用） */
        fun bitmapFromRange(startWeek: Int, endWeek: Int, weekType: Int): Long {
            var bitmap = 0L
            for (w in startWeek..endWeek) {
                val include = when (weekType) {
                    1 -> w % 2 == 1  // 单周
                    2 -> w % 2 == 0  // 双周
                    else -> true     // 每周
                }
                if (include && w in 1..64) {
                    bitmap = bitmap or (1L shl (w - 1))
                }
            }
            return bitmap
        }

        /** 从连续范围生成位图（每周模式） */
        fun bitmapFromRange(startWeek: Int, endWeek: Int): Long =
            bitmapFromRange(startWeek, endWeek, 0)

        /** 获取位图中激活的周列表 */
        fun bitmapToWeekList(bitmap: Long): List<Int> {
            val result = mutableListOf<Int>()
            for (w in 1..64) {
                if ((bitmap shr (w - 1)) and 1L == 1L) result.add(w)
            }
            return result
        }

        /** 获取位图的首尾周（用于显示 "第X-Y周"） */
        fun bitmapToWeekRange(bitmap: Long): Pair<Int, Int>? {
            val list = bitmapToWeekList(bitmap)
            if (list.isEmpty()) return null
            return Pair(list.first(), list.last())
        }
    }
}
