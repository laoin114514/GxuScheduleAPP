package com.cherry.wakeupschedule.model

data class AccountSettings(
    val silentRelogin: Boolean = false,
    val currentSemester: String = "",
    val defaultWeek: Int = 1,
    val alarmEnabled: Boolean = true,
    val semesterStartDate: Long = 0,
    val totalWeeks: Int = 20,
    val customSemesters: List<String> = emptyList()
)
