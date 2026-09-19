package com.cherry.wakeupschedule.service

import android.content.Context
import androidx.room.withTransaction
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.model.ExamScheduleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 考试安排数据管理器。
 *
 * 与 [GradeDataManager] 同构：这里的「当前学期」**只作用于考试页**，
 * 不会写回 [SemesterManager]（翻看历史学期的考试不应把课表的当前学期也切走）。
 *
 * 每次查询按学期整体覆盖：先删该学期旧数据再插入新结果。
 */
class ExamScheduleDataManager private constructor(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.examScheduleDao()

    /** 考试页当前查看的学期 ID（页面私有，不影响课表） */
    @Volatile
    var selectedSemesterId: Long = SemesterManager.getCurrent()?.id ?: 0L
        private set

    fun selectSemester(semesterId: Long) {
        selectedSemesterId = semesterId
    }

    suspend fun loadExams(semesterId: Long = selectedSemesterId): List<ExamScheduleEntity> =
        withContext(Dispatchers.IO) { dao.getExamsBySemesterId(semesterId) }

    /** 覆盖写入指定学期的考试安排（删旧 + 插新，同一事务内完成） */
    suspend fun replaceExamsForSemester(exams: List<ExamScheduleEntity>, semesterId: Long) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                dao.deleteExamsBySemesterId(semesterId)
                if (exams.isNotEmpty()) dao.insertExams(exams)
            }
        }
    }

    /** 各学期已缓存的考试条数（学期 ID → 条数），供学期列表标识「已查询过」 */
    suspend fun getSemesterExamCounts(): Map<Long, Int> =
        withContext(Dispatchers.IO) {
            dao.getAllExams().groupingBy { it.semesterId }.eachCount()
        }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) { dao.deleteAllExams() }
    }

    companion object {
        @Volatile
        private var instance: ExamScheduleDataManager? = null

        fun getInstance(context: Context): ExamScheduleDataManager {
            return instance ?: synchronized(this) {
                instance ?: ExamScheduleDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
