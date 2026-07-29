package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "semester_infos",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["account_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("account_id")]
)
data class SemesterInfoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Long,

    @ColumnInfo(name = "academic_year")
    val academicYear: String,

    @ColumnInfo(name = "term_code")
    val termCode: String,

    @ColumnInfo(name = "grade_label")
    val gradeLabel: String,

    @ColumnInfo(name = "start_date")
    val startDate: Long = 0,

    @ColumnInfo(name = "total_weeks")
    val totalWeeks: Int = 20,

    @ColumnInfo(name = "is_data_loaded")
    val isDataLoaded: Boolean = false
)
