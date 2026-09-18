package com.cherry.wakeupschedule.service

import android.content.Context
import androidx.room.withTransaction
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.model.GradeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 成绩数据管理器。
 *
 * 与 [CourseDataManager] 不同，这里的「当前学期」**只作用于成绩页**，
 * 不会写回 [SemesterManager]（翻看历史成绩不应把课表的当前学期也切走）。
 *
 * 每次查询按学期整体覆盖：先删该学期旧成绩再插入新结果。
 */
class GradeDataManager private constructor(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.gradeDao()

    /** 成绩页当前查看的学期 ID（页面私有，不影响课表） */
    @Volatile
    var selectedSemesterId: Long = SemesterManager.getCurrent()?.id ?: 0L
        private set

    fun selectSemester(semesterId: Long) {
        selectedSemesterId = semesterId
    }

    suspend fun loadGrades(semesterId: Long = selectedSemesterId): List<GradeEntity> =
        withContext(Dispatchers.IO) { dao.getGradesBySemesterId(semesterId) }

    /** 全部学期的成绩（绩点计算页的「至今 / 全部」范围用一次查询取回） */
    suspend fun loadAllGrades(): List<GradeEntity> =
        withContext(Dispatchers.IO) { dao.getAllGrades() }

    /** 覆盖写入指定学期的成绩（删旧 + 插新，同一事务内完成） */
    suspend fun replaceGradesForSemester(grades: List<GradeEntity>, semesterId: Long) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                dao.deleteGradesBySemesterId(semesterId)
                if (grades.isNotEmpty()) dao.insertGrades(grades)
            }
        }
    }

    /** 各学期已缓存的成绩条数（学期 ID → 条数），供学期列表标识「已查询过」 */
    suspend fun getSemesterGradeCounts(): Map<Long, Int> =
        withContext(Dispatchers.IO) {
            dao.getAllGrades().groupingBy { it.semesterId }.eachCount()
        }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) { dao.deleteAllGrades() }
    }

    companion object {
        @Volatile
        private var instance: GradeDataManager? = null

        fun getInstance(context: Context): GradeDataManager {
            return instance ?: synchronized(this) {
                instance ?: GradeDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
