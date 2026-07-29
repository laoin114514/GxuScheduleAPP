package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.model.Course
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CourseDataManager private constructor(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.courseDao()

    private val _coursesFlow = MutableStateFlow<List<Course>>(emptyList())
    val coursesFlow: StateFlow<List<Course>> = _coursesFlow

    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "course-db").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(Dispatchers.IO)

    private var currentAccountId: Long = -1

    init {
        currentAccountId = AccountRepository.getInstance(context).getActiveAccountId()
        if (currentAccountId > 0) {
            loadCoursesForAccount(currentAccountId)
        }
    }

    fun switchAccount(accountId: Long) {
        if (accountId == currentAccountId) return
        currentAccountId = accountId
        loadCoursesForAccount(accountId)
    }

    fun clearAccount() {
        currentAccountId = -1
        _coursesFlow.value = emptyList()
    }

    private fun loadCoursesForAccount(accountId: Long) {
        _coursesFlow.value = executeDb { dao.getAllCourses(accountId) }
        scope.launch {
            dao.getAllCoursesFlow(accountId).collect { courses ->
                _coursesFlow.value = courses
            }
        }
    }

    // ── 读操作 ──

    fun getAllCourses(): List<Course> = _coursesFlow.value

    fun getCoursesForWeek(week: Int): List<Course> {
        return _coursesFlow.value.filter { course ->
            val isInWeekRange = week in course.startWeek..course.endWeek
            val isWeekTypeMatch = when (course.weekType) {
                0 -> true; 1 -> week % 2 == 1; 2 -> week % 2 == 0; else -> true
            }
            isInWeekRange && isWeekTypeMatch
        }
    }

    fun getCoursesForDate(date: Calendar): List<Course> {
        val week = calculateWeekNumber(date)
        if (week <= 0) return emptyList()
        val coursesForWeek = getCoursesForWeek(week)
        val dayOfWeek = date.get(Calendar.DAY_OF_WEEK) - 1
        val adjustedDayOfWeek = if (dayOfWeek == 0) 7 else dayOfWeek
        return coursesForWeek.filter { it.dayOfWeek == adjustedDayOfWeek }
    }

    private fun calculateWeekNumber(date: Calendar): Int {
        val accountId = currentAccountId
        if (accountId <= 0) return -1
        val settingsManager = SettingsManager(context)
        val startDate = settingsManager.getSemesterStartDate(accountId)
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

    // ── 写操作 ──

    fun addCourse(course: Course): Course {
        val accountId = currentAccountId
        val colorIndex = assignColorIndex(course.name, course.teacher)
        val courseToInsert = course.copy(id = 0, accountId = accountId, color = colorIndex)
        val newId = executeDb { dao.insertCourse(courseToInsert) }
        return courseToInsert.copy(id = newId)
    }

    fun addCourses(courses: List<Course>) {
        val accountId = currentAccountId
        executeDb {
            dao.insertCourses(courses.map {
                it.copy(id = 0, accountId = accountId, color = assignColorIndex(it.name, it.teacher))
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
        val accountId = currentAccountId
        executeDb { dao.deleteAllCourses(accountId) }
    }

    fun replaceAllCourses(courses: List<Course>) {
        val accountId = currentAccountId
        executeDb {
            dao.deleteAllCourses(accountId)
            dao.insertCourses(courses.map {
                it.copy(id = 0, accountId = accountId, color = assignColorIndex(it.name, it.teacher))
            })
        }
    }

    private fun <T> executeDb(block: suspend () -> T): T {
        val future = dbExecutor.submit<T> {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
        }
        return future.get()
    }

    companion object {
        @Volatile
        private var instance: CourseDataManager? = null

        const val COURSE_COLOR_COUNT = 9

        fun getInstance(context: Context): CourseDataManager {
            return instance ?: synchronized(this) {
                instance ?: CourseDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun assignColorIndex(name: String, teacher: String): Int {
            val key = "$name|$teacher"
            return (Math.abs(key.hashCode()) % COURSE_COLOR_COUNT) + 1
        }
    }
}
