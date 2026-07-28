package com.cherry.wakeupschedule.model

import java.io.Serializable

/**
 * 应用配置数据类
 * 用于备份导出时保存用户的所有设置
 */
data class AppSettings(
    val currentSemester: String = "",                // 当前学期名称
    val defaultWeek: Int = 1,                        // 默认显示周次
    val defaultAlarmMinutes: Int = 15,               // 闹钟提前分钟数
    val autoSwitchWeek: Boolean = true,              // 是否自动切换周次
    val alarmEnabled: Boolean = true,                // 是否启用闹钟
    val courseCardAlpha: Float = 0.85f,             // 课程卡片透明度
    val showNonCurrentWeekCourses: Boolean = true,   // 是否显示非本周课程
    val nonCurrentWeekAlpha: Float = 0.3f,           // 非本周课程透明度
    val fontSize: String = "normal",                 // 字体大小设置
    val semesterStartDate: Long = 0L,                 // 学期开始日期时间戳
    val customSemesters: List<String> = emptyList(),  // 自定义学期列表
    val customBackgroundPath: String = ""            // 自定义背景图片路径
) : Serializable
