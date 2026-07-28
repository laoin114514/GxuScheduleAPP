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

    // 单线程 Executor，保证写操作串行，Room 自身也线程安全
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "course-db").apply { isDaemon = true }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // 迁移旧 SharedPreferences 数据（如果存在）
        migrateFromSharedPreferences(context)
        // 同步加载初始数据，确保调用方能立即获取
        _coursesFlow.value = executeDb { dao.getAllCourses() }
        // 监听 Room 数据变化，自动更新缓存
        scope.launch {
            dao.getAllCoursesFlow().collect { courses ->
                _coursesFlow.value = courses
            }
        }
        // 为已有但未分配颜色的课程补分配颜色（一次性的迁移）
        scope.launch {
            val courses = dao.getAllCourses()
            if (courses.isNotEmpty() && courses.any { it.color == 0 }) {
                val updated = courses.map {
                    if (it.color == 0) it.copy(color = assignColorIndex(it.name, it.teacher))
                    else it
                }
                dao.insertCourses(updated)
                android.util.Log.d(
                    "CourseDataManager",
                    "Assigned colors to ${updated.count { it.color > 0 }} existing courses"
                )
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
                val migratedCourses = courses.map { it.copy(id = 0) }
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

    // ── 读操作（内存缓存，不访问数据库） ──

    fun getAllCourses(): List<Course> = _coursesFlow.value

    fun getCoursesForWeek(week: Int): List<Course> {
        return _coursesFlow.value.filter { course ->
            val isInWeekRange = week in course.startWeek..course.endWeek
            val isWeekTypeMatch = when (course.weekType) {
                0 -> true
                1 -> week % 2 == 1
                2 -> week % 2 == 0
                else -> true
            }
            isInWeekRange && isWeekTypeMatch
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
        val courseToInsert = course.copy(id = 0, color = colorIndex)
        val newId = executeDb { dao.insertCourse(courseToInsert) }
        return courseToInsert.copy(id = newId)
    }

    fun addCourses(courses: List<Course>) {
        executeDb {
            dao.insertCourses(courses.map {
                it.copy(id = 0, color = assignColorIndex(it.name, it.teacher))
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
        executeDb {
            dao.deleteAllCourses()
            dao.insertCourses(courses.map {
                it.copy(id = 0, color = assignColorIndex(it.name, it.teacher))
            })
        }
    }

    fun refreshCourses() {
        scope.launch {
            _coursesFlow.value = dao.getAllCourses()
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
        const val COURSE_COLOR_COUNT = 10

        fun getInstance(context: Context): CourseDataManager {
            return instance ?: synchronized(this) {
                instance ?: CourseDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * 为课程分配稳定的颜色索引 (1..COURSE_COLOR_COUNT)。
         * 同一「课程名+教师」组合始终返回同一颜色索引，保证跨页面一致。
         */
        fun assignColorIndex(name: String, teacher: String): Int {
            val key = "$name|$teacher"
            return (Math.abs(key.hashCode()) % COURSE_COLOR_COUNT) + 1
        }
    }
}
