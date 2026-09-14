package com.cherry.wakeupschedule.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GradeDao {

    @Query("SELECT * FROM grades ORDER BY id")
    fun getAllGradesFlow(): Flow<List<GradeEntity>>

    @Query("SELECT * FROM grades ORDER BY id")
    suspend fun getAllGrades(): List<GradeEntity>

    @Query("SELECT * FROM grades WHERE semester_id = :semesterId ORDER BY id")
    suspend fun getGradesBySemesterId(semesterId: Long): List<GradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrades(grades: List<GradeEntity>)

    @Query("DELETE FROM grades WHERE semester_id = :semesterId")
    suspend fun deleteGradesBySemesterId(semesterId: Long)

    @Query("DELETE FROM grades")
    suspend fun deleteAllGrades()

    @Query("SELECT COUNT(*) FROM grades")
    suspend fun getGradeCount(): Int
}
