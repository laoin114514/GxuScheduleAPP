package com.cherry.wakeupschedule.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses ORDER BY day_of_week, start_time")
    fun getAllCoursesFlow(): Flow<List<Course>>

    @Query("SELECT * FROM courses ORDER BY day_of_week, start_time")
    suspend fun getAllCourses(): List<Course>

    @Query("SELECT * FROM courses WHERE semester_id = :semesterId ORDER BY day_of_week, start_time")
    suspend fun getCoursesBySemesterId(semesterId: Long): List<Course>

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

    @Query("DELETE FROM courses")
    suspend fun deleteAllCourses()

    @Query("DELETE FROM courses WHERE semester_id = :semesterId")
    suspend fun deleteCoursesBySemesterId(semesterId: Long)

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun getCourseCount(): Int
}
