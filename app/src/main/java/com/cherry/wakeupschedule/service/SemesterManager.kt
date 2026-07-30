package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.model.SemesterEntity
import com.gxu.jwxt.model.StudentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

object SemesterManager {

    private lateinit var dao: com.cherry.wakeupschedule.model.SemesterDao

    @Volatile
    private var cached: List<SemesterEntity>? = null

    private var prefs: SharedPreferences? = null

    private const val KEY_CURRENT_SEMESTER_INDEX = "current_semester_index"

    fun init(context: Context) {
        dao = AppDatabase.getInstance(context).semesterDao()
        prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        runBlocking(Dispatchers.IO) {
            cached = dao.getAllSemesters()
        }
        // 从持久化恢复当前学期索引
        val idx = prefs?.getInt(KEY_CURRENT_SEMESTER_INDEX, -1) ?: -1
        currentIndex = idx
    }

    // ── 计算 ──

    fun calculateSemesters(enrollmentYear: String): List<SemesterEntity> {
        val gradeYear = enrollmentYear.toIntOrNull() ?: return emptyList()
        val labels = listOf("大一上", "大一下", "大二上", "大二下", "大三上", "大三下", "大四上", "大四下")

        return labels.mapIndexed { index, label ->
            val offset = index / 2
            val academicStart = gradeYear + offset
            val isAutumn = index % 2 == 0

            SemesterEntity(
                label = label,
                academicYear = "${academicStart}-${academicStart + 1}",
                termName = if (isAutumn) "第一学期" else "第二学期",
                termCode = if (isAutumn) "3" else "12",
                enrollmentYear = enrollmentYear,
                sortOrder = index
            )
        }
    }

    fun inferCurrentSemesterIndex(enrollmentYear: String): Int {
        val gradeYear = enrollmentYear.toIntOrNull() ?: return 0
        val now = LocalDate.now()
        val month = now.monthValue

        // 学年起始年：9 月后属于新学年
        val currentAcademicStart = if (month >= 9) now.year else now.year - 1
        val academicOffset = currentAcademicStart - gradeYear

        // termOffset: 上学期(9-1月)=0, 下学期(2-8月)=1
        val termOffset = if (month in 2..8) 1 else 0

        return (academicOffset * 2 + termOffset).coerceIn(0, 7)
    }

    // ── 持久化 ──

    suspend fun initialize(profile: StudentProfile) {
        val enrollmentYear = profile.grade ?: return
        val semesters = calculateSemesters(enrollmentYear)
        if (semesters.isEmpty()) return

        dao.clearSemesters()
        dao.insertSemesters(semesters)
        cached = semesters
    }

    suspend fun clear() {
        dao.clearSemesters()
        cached = emptyList()
    }

    // ── 查询（同步，内存缓存） ──

    fun getAll(): List<SemesterEntity> = cached ?: emptyList()

    fun getCurrent(): SemesterEntity? {
        val idx = getCurrentIndex()
        if (idx < 0) return null
        return cached?.getOrNull(idx)
    }

    fun getStartDate(): Long = getCurrent()?.startDate ?: 0L

    fun getTotalWeeks(): Int = getCurrent()?.totalWeeks?.takeIf { it > 0 } ?: 20

    // ── 更新 ──

    suspend fun updateDates(sortOrder: Int, startDate: Long, totalWeeks: Int) {
        val entity = cached?.find { it.sortOrder == sortOrder } ?: return
        dao.updateSemesterDates(entity.id, startDate, totalWeeks)
        // 更新缓存
        cached = cached?.map {
            if (it.sortOrder == sortOrder) it.copy(startDate = startDate, totalWeeks = totalWeeks) else it
        }
    }

    fun updateDatesSync(sortOrder: Int, startDate: Long, totalWeeks: Int) {
        runBlocking(Dispatchers.IO) { updateDates(sortOrder, startDate, totalWeeks) }
    }

    // ── 当前学期索引 ──

    @Volatile
    private var currentIndex: Int = -1

    fun setCurrentIndex(index: Int) {
        currentIndex = index
        prefs?.edit()?.putInt(KEY_CURRENT_SEMESTER_INDEX, index)?.apply()
    }

    fun getCurrentIndex(): Int = currentIndex
}
