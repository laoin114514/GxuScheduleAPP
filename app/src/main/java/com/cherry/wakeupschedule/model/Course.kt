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

    @ColumnInfo(name = "start_week")
    val startWeek: Int,                       // 开始周

    @ColumnInfo(name = "end_week")
    val endWeek: Int,                         // 结束周

    @ColumnInfo(name = "week_type", defaultValue = "0")
    val weekType: Int = 0,                    // 周类型（0:每周, 1:单周, 2:双周）

    @ColumnInfo(name = "alarm_enabled", defaultValue = "1")
    val alarmEnabled: Boolean = true,         // 是否启用闹钟提醒

    @ColumnInfo(name = "alarm_minutes_before", defaultValue = "15")
    val alarmMinutesBefore: Int = 15,          // 提前提醒分钟数

    @ColumnInfo(name = "color", defaultValue = "0")
    val color: Int = 0,                       // 课程颜色，0为默认颜色

    @ColumnInfo(name = "cover_image_path", defaultValue = "")
    val coverImagePath: String = ""            // 课程封面图片路径
) : Serializable
