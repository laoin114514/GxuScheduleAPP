package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "username")
    val username: String = "",
    @ColumnInfo(name = "password")
    val password: String = "",
    @ColumnInfo(name = "is_bound")
    val isBound: Boolean = false,
    @ColumnInfo(name = "profile_json")
    val profileJson: String? = null
)
