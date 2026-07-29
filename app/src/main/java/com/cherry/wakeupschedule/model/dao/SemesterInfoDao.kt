package com.cherry.wakeupschedule.model.dao

import androidx.room.*
import com.cherry.wakeupschedule.model.SemesterInfoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterInfoDao {

    @Query("SELECT * FROM semester_infos WHERE account_id = :accountId ORDER BY academic_year, term_code")
    fun getSemestersFlow(accountId: Long): Flow<List<SemesterInfoEntity>>

    @Query("SELECT * FROM semester_infos WHERE account_id = :accountId ORDER BY academic_year, term_code")
    suspend fun getSemesters(accountId: Long): List<SemesterInfoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemesters(semesters: List<SemesterInfoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemester(semester: SemesterInfoEntity): Long

    @Update
    suspend fun updateSemester(semester: SemesterInfoEntity)

    @Query("DELETE FROM semester_infos WHERE account_id = :accountId")
    suspend fun deleteSemesters(accountId: Long)

    @Query("SELECT * FROM semester_infos WHERE account_id = :accountId AND academic_year = :year AND term_code = :termCode")
    suspend fun getSemester(accountId: Long, year: String, termCode: String): SemesterInfoEntity?
}
