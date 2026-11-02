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
        // 重新从 DB 加载，确保缓存中的实体有真实的数据库 ID
        cached = dao.getAllSemesters()
    }

    suspend fun clear() {
        dao.clearSemesters()
        cached = emptyList()
    }

    // ── 查询（同步，内存缓存） ──

    /**
     * 冷启动兜底：后台初始化（[AppInitializer]）还没完成时先等它就绪，避免读到空学期缓存。
     * 初始化线程自身调用会立即返回（不会自己等自己）；就绪后只是一次 volatile 读。
     */
    private fun awaitInit() {
        AppInitializer.awaitReady()
    }

    fun getAll(): List<SemesterEntity> {
        awaitInit()
        return cached ?: emptyList()
    }

    fun getCurrent(): SemesterEntity? {
        awaitInit()
        // 直接读 currentIndex，不再走 getCurrentIndex()：避免重复就绪判断
        // （极端超时场景下会把等待时间翻倍）
        val idx = currentIndex
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

    fun getCurrentIndex(): Int {
        // 同上：冷启动时索引也是 init 里从 SP 恢复的，未就绪就读会得到 -1
        awaitInit()
        return currentIndex
    }
}
