package com.cherry.wakeupschedule.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses WHERE account_id = :accountId ORDER BY day_of_week, start_time")
    fun getAllCoursesFlow(accountId: Long): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE account_id = :accountId ORDER BY day_of_week, start_time")
    suspend fun getAllCourses(accountId: Long): List<Course>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getCourseById(id: Long): Course?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<Course>)

    @Update
    suspend fun updateCourse(course: Course)

    @Delete
    suspend fun deleteCourse(course: Course)

    @Query("DELETE FROM courses WHERE account_id = :accountId")
    suspend fun deleteAllCourses(accountId: Long)

    @Query("SELECT COUNT(*) FROM courses WHERE account_id = :accountId")
    suspend fun getCourseCount(accountId: Long): Int
}
