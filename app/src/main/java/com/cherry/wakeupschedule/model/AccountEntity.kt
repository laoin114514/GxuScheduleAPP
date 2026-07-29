package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "password")
    val password: String,

    @ColumnInfo(name = "session_json")
    val sessionJson: String = "",

    @ColumnInfo(name = "bound_at")
    val boundAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_active")
    val lastActive: Long = System.currentTimeMillis()
)
