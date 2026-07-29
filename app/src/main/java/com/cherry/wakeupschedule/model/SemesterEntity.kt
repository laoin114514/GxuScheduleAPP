package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Int = 1,

    @ColumnInfo(name = "label")
    val label: String,                          // "大一上" … "大四下"

    @ColumnInfo(name = "academic_year")
    val academicYear: String,                   // "2024-2025"

    @ColumnInfo(name = "term_name")
    val termName: String,                       // "第一学期" / "第二学期"

    @ColumnInfo(name = "term_code")
    val termCode: String,                       // "3" (AUTUMN) / "12" (SPRING)

    @ColumnInfo(name = "enrollment_year")
    val enrollmentYear: String,                 // 入学年份 "2024"

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,                         // 0-7

    @ColumnInfo(name = "start_date")
    val startDate: Long = 0,                    // 学期开始日期 epoch ms

    @ColumnInfo(name = "total_weeks")
    val totalWeeks: Int = 0                     // 总周数
)
