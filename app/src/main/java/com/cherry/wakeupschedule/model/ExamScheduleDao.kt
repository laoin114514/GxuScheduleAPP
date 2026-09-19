package com.cherry.wakeupschedule.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExamScheduleDao {

    @Query("SELECT * FROM exam_schedules ORDER BY id")
    suspend fun getAllExams(): List<ExamScheduleEntity>

    @Query("SELECT * FROM exam_schedules WHERE semester_id = :semesterId ORDER BY id")
    suspend fun getExamsBySemesterId(semesterId: Long): List<ExamScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExams(exams: List<ExamScheduleEntity>)

    @Query("DELETE FROM exam_schedules WHERE semester_id = :semesterId")
    suspend fun deleteExamsBySemesterId(semesterId: Long)

    @Query("DELETE FROM exam_schedules")
    suspend fun deleteAllExams()

    @Query("SELECT COUNT(*) FROM exam_schedules")
    suspend fun getExamCount(): Int
}
