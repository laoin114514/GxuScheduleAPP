package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "account_profiles",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["account_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("account_id", unique = true)]
)
data class AccountProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Long,

    @ColumnInfo(name = "profile_json")
    val profileJson: String,

    @ColumnInfo(name = "grade_year")
    val gradeYear: Int,

    @ColumnInfo(name = "study_years")
    val studyYears: Int = 4,

    @ColumnInfo(name = "saved_at")
    val savedAt: Long = System.currentTimeMillis()
)
