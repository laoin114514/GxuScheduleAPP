package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.cherry.wakeupschedule.model.Course
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 课程数据管理器
 * 负责课程数据的CRUD操作和持久化存储
 */
class CourseDataManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val holidayManager = HolidayManager.getInstance(context)
    private val settingsManager = SettingsManager(context)

    private val _coursesFlow = MutableStateFlow<List<Course>>(emptyList())
    val coursesFlow: StateFlow<List<Course>> = _coursesFlow

    init {
        loadCoursesFromPrefs()
    }

    private fun loadCoursesFromPrefs() {
        val coursesJson = prefs.getString(KEY_COURSES, null)
        android.util.Log.d("CourseDataManager", "loadCoursesFromPrefs: JSON数据存在=${coursesJson != null}")
        if (coursesJson != null) {
            try {
                val type = object : TypeToken<List<Course>>() {}.type
                val courses: List<Course> = gson.fromJson(coursesJson, type)
                android.util.Log.d("CourseDataManager", "loadCoursesFromPrefs: 解析到${courses.size}门课程")
                _coursesFlow.value = courses
            } catch (e: Exception) {
                android.util.Log.e("CourseDataManager", "loadCoursesFromPrefs: 解析失败", e)
                _coursesFlow.value = emptyList()
            }
        }
    }

    private fun saveCoursesToPrefs(courses: List<Course>, synchronous: Boolean = false) {
        val coursesJson = gson.toJson(courses)
        val editor = prefs.edit().putString(KEY_COURSES, coursesJson)
        if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    /**
     * 获取所有课程
     */
    fun getAllCourses(): List<Course> {
        return _coursesFlow.value
    }

    /**
     * 获取指定周的课程
     */
    fun getCoursesForWeek(week: Int): List<Course> {
        return _coursesFlow.value.filter { course ->
            val isInWeekRange = week in course.startWeek..course.endWeek
            val isWeekTypeMatch = when (course.weekType) {
                0 -> true // 每周
                1 -> week % 2 == 1 // 单周
                2 -> week % 2 == 0 // 双周
                else -> true
            }
            isInWeekRange && isWeekTypeMatch
        }
    }

    /**
     * 获取指定日期的课程
     */
    fun getCoursesForDate(date: Calendar): List<Course> {
        // 1. 如果设置了隐藏节假日课程，并且当天是节假日，则返回空
        if (settingsManager.isHideHolidayCourses() && holidayManager.isHoliday(date)) {
            return emptyList()
        }

        // 2. 计算周数
        val week = calculateWeekNumber(date)
        if (week <= 0) return emptyList()

        // 3. 获取该周的课程
        val coursesForWeek = getCoursesForWeek(week)

        // 4. 过滤出当天的课程
        val dayOfWeek = date.get(Calendar.DAY_OF_WEEK) - 1 // Calendar是1-7，转为0-6（周一-周日
        val adjustedDayOfWeek = if (dayOfWeek == 0) 7 else dayOfWeek // 周日调整为7

        return coursesForWeek.filter { it.dayOfWeek == adjustedDayOfWeek }
    }

    /**
     * 计算指定日期是学期第几周
     */
    private fun calculateWeekNumber(date: Calendar): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return -1

        val startCalendar = Calendar.getInstance().apply { timeInMillis = startDate }

        // 设置到当天的开始
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

        // 计算天数差
        val diffInMillis = dateCopy.timeInMillis - startCalendar.timeInMillis
        if (diffInMillis < 0) return -1 // 还没开学

        val daysDiff = TimeUnit.DAYS.convert(diffInMillis, TimeUnit.MILLISECONDS).toInt()

        // 计算周数
        return (daysDiff / 7) + 1
    }

    /**
     * 添加一门课程
     */
    @Synchronized
    fun addCourse(course: Course): Course {
        val currentCourses = _coursesFlow.value.toMutableList()
        val newId = if (currentCourses.isEmpty()) 1L else currentCourses.maxOf { it.id } + 1
        val newCourse = course.copy(id = newId)
        currentCourses.add(newCourse)
        _coursesFlow.value = currentCourses
        saveCoursesToPrefs(currentCourses)
        return newCourse
    }

    /**
     * 批量添加课程
     */
    @Synchronized
    fun addCourses(courses: List<Course>) {
        val currentCourses = _coursesFlow.value.toMutableList()
        var nextId = if (currentCourses.isEmpty()) 1L else currentCourses.maxOf { it.id } + 1
        courses.forEach { course ->
            val newCourse = course.copy(id = nextId++)
            currentCourses.add(newCourse)
        }
        _coursesFlow.value = currentCourses
        saveCoursesToPrefs(currentCourses)
    }

    /**
     * 更新课程（同步写入，避免进程被杀时数据丢失）
     */
    @Synchronized
    fun updateCourse(course: Course) {
        val currentCourses = _coursesFlow.value.toMutableList()
        val index = currentCourses.indexOfFirst { it.id == course.id }
        if (index != -1) {
            currentCourses[index] = course
            _coursesFlow.value = currentCourses
            saveCoursesToPrefs(currentCourses, synchronous = true)
        }
    }

    /**
     * 删除课程（同步写入，避免进程被杀时数据丢失）
     */
    @Synchronized
    fun deleteCourse(course: Course) {
        val currentCourses = _coursesFlow.value.toMutableList()
        currentCourses.removeAll { it.id == course.id }
        _coursesFlow.value = currentCourses
        saveCoursesToPrefs(currentCourses, synchronous = true)
    }

    /**
     * 清空所有课程
     */
    fun clearAllCourses() {
        synchronized(this) {
            _coursesFlow.value = emptyList()
            saveCoursesToPrefs(emptyList(), synchronous = true)
        }
    }

    /**
     * 替换所有课程（清空 + 批量添加）
     */
    fun replaceAllCourses(courses: List<com.cherry.wakeupschedule.model.Course>) {
        synchronized(this) {
            // 重新生成 ID
            var nextId = System.currentTimeMillis()
            val newCourses = courses.map { it.copy(id = nextId++) }
            _coursesFlow.value = newCourses
            saveCoursesToPrefs(newCourses, synchronous = true)
        }
    }

    /**
     * 刷新课程数据
     */
    fun refreshCourses() {
        loadCoursesFromPrefs()
    }

    companion object {
        private const val PREFS_NAME = "course_data"
        private const val KEY_COURSES = "courses"

        @Volatile
        private var instance: CourseDataManager? = null

        fun getInstance(context: Context): CourseDataManager {
            return instance ?: synchronized(this) {
                instance ?: CourseDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
