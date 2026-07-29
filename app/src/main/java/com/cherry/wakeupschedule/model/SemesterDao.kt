package com.cherry.wakeupschedule.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterDao {

    @Query("SELECT * FROM semesters WHERE account_id = :accountId ORDER BY sort_order")
    fun getAllSemestersFlow(accountId: Int = 1): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters WHERE account_id = :accountId ORDER BY sort_order")
    suspend fun getAllSemesters(accountId: Int = 1): List<SemesterEntity>

    @Query("SELECT * FROM semesters WHERE account_id = :accountId AND sort_order = :sortOrder")
    suspend fun getSemesterByIndex(accountId: Int = 1, sortOrder: Int): SemesterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemesters(semesters: List<SemesterEntity>)

    @Query("UPDATE semesters SET start_date = :startDate, total_weeks = :totalWeeks WHERE id = :id")
    suspend fun updateSemesterDates(id: Long, startDate: Long, totalWeeks: Int)

    @Query("DELETE FROM semesters")
    suspend fun clearSemesters()
}
