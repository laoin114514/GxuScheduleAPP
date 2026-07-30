package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.model.Course
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 课程数据管理器
 * 使用 Room (SQLite) 进行持久化存储
 *
 * 写操作通过单线程 Executor 排队执行，避免 runBlocking 阻塞主线程；
 * 读操作直接读取内存缓存 StateFlow。
 */
class CourseDataManager private constructor(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.courseDao()
    private val holidayManager = HolidayManager.getInstance(context)
    private val settingsManager = SettingsManager(context)

    private val _coursesFlow = MutableStateFlow<List<Course>>(emptyList())
    val coursesFlow: StateFlow<List<Course>> = _coursesFlow

    // 当前活跃学期 ID，用于过滤课程和自动附加到新增课程
    @Volatile
    private var currentSemesterId: Long = 0L

    // 单线程 Executor，保证写操作串行，Room 自身也线程安全
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "course-db").apply { isDaemon = true }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // 获取当前学期 ID
        currentSemesterId = SemesterManager.getCurrent()?.id ?: 0L
        // 迁移旧 SharedPreferences 数据（如果存在）
        migrateFromSharedPreferences(context)
        // 同步加载当前学期的课程
        _coursesFlow.value = executeDb { dao.getCoursesBySemesterId(currentSemesterId) }
        // 监听 Room 数据变化，按学期过滤后更新缓存
        scope.launch {
            dao.getAllCoursesFlow().collect { courses ->
                val semId = currentSemesterId
                _coursesFlow.value = courses.filter { it.semesterId == semId }
            }
        }
        // 颜色版本号：色板变更时递增，触发全量重新分配
        scope.launch {
            val appPrefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val colorVersion = appPrefs.getInt("color_scheme_version", 0)
            if (colorVersion < COURSE_COLOR_COUNT) {
                val courses = dao.getCoursesBySemesterId(currentSemesterId)
                if (courses.isNotEmpty()) {
                    // 重置所有颜色为 0，再用批量分配
                    val reset = courses.map { it.copy(color = 0) }
                    val colorMap = assignColorsForBatch(reset)
                    val updated = reset.map {
                        it.copy(color = colorMap[it.name] ?: assignColorIndex(it.name, it.teacher))
                    }
                    dao.insertCourses(updated)
                    appPrefs.edit().putInt("color_scheme_version", COURSE_COLOR_COUNT).apply()
                    android.util.Log.d(
                        "CourseDataManager",
                        "Re-assigned colors for ${updated.size} courses (version $colorVersion → $COURSE_COLOR_COUNT)"
                    )
                }
            }
        }
    }

    /**
     * 从旧的 SharedPreferences JSON 存储迁移到 Room（同步执行）
     */
    private fun migrateFromSharedPreferences(context: Context) {
        val prefs = context.getSharedPreferences("course_data", Context.MODE_PRIVATE)
        val coursesJson = prefs.getString("courses", null) ?: return

        try {
            val count = executeDb { dao.getCourseCount() }
            if (count > 0) {
                prefs.edit().remove("courses").apply()
                return
            }

            val gson = Gson()
            val type = object : TypeToken<List<Course>>() {}.type
            val courses: List<Course> = gson.fromJson(coursesJson, type)

            if (courses.isNotEmpty()) {
                val migratedCourses = courses.map { it.copy(id = 0, semesterId = currentSemesterId) }
                executeDb { dao.insertCourses(migratedCourses) }
                android.util.Log.d(
                    "CourseDataManager",
                    "Migrated ${migratedCourses.size} courses from SharedPreferences to Room"
                )
            }

            prefs.edit().remove("courses").apply()
        } catch (e: Exception) {
            android.util.Log.e("CourseDataManager", "Migration from SP failed", e)
        }
    }

    /**
     * 切换学期：更新当前学期 ID，重新从数据库加载对应学期的课程。
     * 会触发 StateFlow 更新 → LiveData → UI 自动刷新。
     */
    fun switchSemester(semesterId: Long) {
        if (semesterId == currentSemesterId) return
        currentSemesterId = semesterId
        scope.launch {
            _coursesFlow.value = dao.getCoursesBySemesterId(semesterId)
        }
    }

    /** 获取当前学期 ID */
    fun getCurrentSemesterId(): Long = currentSemesterId

    // ── 读操作（内存缓存，不访问数据库） ──

    fun getAllCourses(): List<Course> = _coursesFlow.value

    fun getCoursesForWeek(week: Int): List<Course> {
        return _coursesFlow.value.filter { course ->
            course.isActiveInWeek(week)
        }
    }

    fun getCoursesForDate(date: Calendar): List<Course> {
        if (settingsManager.isHideHolidayCourses() && holidayManager.isHoliday(date)) {
            return emptyList()
        }
        val week = calculateWeekNumber(date)
        if (week <= 0) return emptyList()
        val coursesForWeek = getCoursesForWeek(week)
        val dayOfWeek = date.get(Calendar.DAY_OF_WEEK) - 1
        val adjustedDayOfWeek = if (dayOfWeek == 0) 7 else dayOfWeek
        return coursesForWeek.filter { it.dayOfWeek == adjustedDayOfWeek }
    }

    private fun calculateWeekNumber(date: Calendar): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return -1
        val startCalendar = Calendar.getInstance().apply { timeInMillis = startDate }
        startCalendar.set(Calendar.HOUR_OF_DAY, 0)
        startCalendar.set(Calendar.MINUTE, 0)
        startCalendar.set(Calendar.SECOND, 0)
        startCalendar.set(Calendar.MILLISECOND, 0)
        val dateCopy = (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffInMillis = dateCopy.timeInMillis - startCalendar.timeInMillis
        if (diffInMillis < 0) return -1
        val daysDiff = TimeUnit.DAYS.convert(diffInMillis, TimeUnit.MILLISECONDS).toInt()
        return (daysDiff / 7) + 1
    }

    // ── 写操作（Executor + Future，避免 runBlocking） ──

    fun addCourse(course: Course): Course {
        val colorIndex = assignColorIndex(course.name, course.teacher)
        val courseToInsert = course.copy(id = 0, color = colorIndex, semesterId = currentSemesterId)
        val newId = executeDb { dao.insertCourse(courseToInsert) }
        return courseToInsert.copy(id = newId)
    }

    fun addCourses(courses: List<Course>) {
        val colorMap = assignColorsForBatch(courses)
        val semId = currentSemesterId
        executeDb {
            dao.insertCourses(courses.map {
                it.copy(id = 0, semesterId = semId,
                    color = it.color.takeIf { c -> c != 0 } ?: (colorMap[it.name] ?: assignColorIndex(it.name, it.teacher)))
            })
        }
    }

    fun updateCourse(course: Course) {
        executeDb { dao.updateCourse(course) }
    }

    fun deleteCourse(course: Course) {
        executeDb { dao.deleteCourse(course) }
    }

    fun clearAllCourses() {
        executeDb { dao.deleteAllCourses() }
    }

    fun replaceAllCourses(courses: List<Course>) {
        replaceAllCoursesForSemester(courses, currentSemesterId)
    }

    /**
     * 替换指定学期的所有课程（显式传入学期 ID，避免并发切换时误存到错误学期）。
     */
    fun replaceAllCoursesForSemester(courses: List<Course>, semesterId: Long) {
        val colorMap = assignColorsForBatch(courses)
        executeDb {
            dao.deleteCoursesBySemesterId(semesterId)
            dao.insertCourses(courses.map {
                it.copy(id = 0, semesterId = semesterId,
                    color = it.color.takeIf { c -> c != 0 } ?: (colorMap[it.name] ?: assignColorIndex(it.name, it.teacher)))
            })
        }
    }

    fun refreshCourses() {
        scope.launch {
            _coursesFlow.value = dao.getCoursesBySemesterId(currentSemesterId)
        }
    }

    // ── 内部工具 ──

    /**
     * 在 dbExecutor 上执行 Room 操作，阻塞当前线程等待结果。
     * 通过 Future.get() 实现，不涉及协程上下文，避免 runBlocking 的潜在死锁。
     */
    private fun <T> executeDb(block: suspend () -> T): T {
        val future = dbExecutor.submit<T> {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
        }
        return future.get()
    }

    companion object {
        @Volatile
        private var instance: CourseDataManager? = null

        /** 课程颜色数量，与 ThemeManager.COURSE_COLORS 对齐 */
        const val COURSE_COLOR_COUNT = 9

        fun getInstance(context: Context): CourseDataManager {
            return instance ?: synchronized(this) {
                instance ?: CourseDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * 为课程分配稳定的颜色索引 (1..COURSE_COLOR_COUNT)。
         * 同一课程名始终返回同一颜色索引，保证跨页面一致。
         * 单门添加时使用（无法做批量去重优化）。
         */
        fun assignColorIndex(name: String, teacher: String): Int {
            val key = name  // 只用课程名，同名课同色
            return (Math.abs(key.hashCode()) % COURSE_COLOR_COUNT) + 1
        }

        /**
         * 批量分配颜色：收集去重后的课程名，轮转分配（1→9→1→...），
         * 解决课程少时 hashCode 取模导致颜色大量重复的问题。
         * 同名课程始终分配到同一颜色。
         */
        fun assignColorsForBatch(courses: List<Course>): Map<String, Int> {
            if (courses.isEmpty()) return emptyMap()
            // 所有要分配颜色的课程（color == 0 表示未手动设置过）
            val names = courses.filter { it.color == 0 }
                .map { it.name }
                .distinct()
                .sorted() // 排序保证确定性
            val map = mutableMapOf<String, Int>()
            names.forEachIndexed { i, name ->
                map[name] = (i % COURSE_COLOR_COUNT) + 1
            }
            return map
        }
    }
}
