package com.cherry.wakeupschedule.viewmodel

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.HolidayManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 课程ViewModel
 * 管理课程数据的增删改查
 */
class CourseViewModel(application: Application) : AndroidViewModel(application) {

    // 当前显示的课程列表
    private val _courses = MutableLiveData<List<Course>>()
    val courses: LiveData<List<Course>> = _courses

    // 当前浏览的周次（ViewModel 作用域为 Activity，tab 切换不丢失，进程死亡后重建为默认值）
    var displayWeek: Int = 0
    var currentWeek: Int = 0

    private val courseDataManager = CourseDataManager.getInstance(application)
    private val settingsManager = SettingsManager(application)
    private val holidayManager = HolidayManager.getInstance(application)
    @Volatile
    private var activeWeek: Int = calculateCurrentWeek()

    // 计算某周第一天的日历，用于检查节假日
    private fun getWeekStartCalendar(week: Int): Calendar? {
        val semesterStartDate = settingsManager.getSemesterStartDate()
        if (semesterStartDate <= 0L) return null
        
        return Calendar.getInstance().apply {
            timeInMillis = semesterStartDate
            add(Calendar.DAY_OF_YEAR, (week - 1) * 7)
        }
    }

    init {
        // 监听课程数据变化
        viewModelScope.launch {
            courseDataManager.coursesFlow.collect { allCourses ->
                val week = activeWeek
                val coursesForWeek = allCourses.filter { course ->
                    if (!course.isActiveInWeek(week)) {
                        return@filter false
                    }
                    
                    // 检查是否需要过滤节假日课程
                    if (settingsManager.isHideHolidayCourses()) {
                        val weekStart = getWeekStartCalendar(week)
                        if (weekStart != null) {
                            val courseDayCalendar = (weekStart.clone() as Calendar).apply {
                                add(Calendar.DAY_OF_YEAR, course.dayOfWeek - 1)
                            }
                            if (holidayManager.isHoliday(courseDayCalendar)) {
                                return@filter false
                            }
                        }
                    }
                    true
                }
                _courses.postValue(coursesForWeek)
            }
        }
    }

    // 获取所有课程
    fun getAllCourses(): Flow<List<Course>> {
        return flow { emit(courseDataManager.getAllCourses()) }
    }

    // 按星期获取课程
    fun getCoursesByDay(dayOfWeek: Int): Flow<List<Course>> {
        return flow {
            emit(courseDataManager.getAllCourses().filter { it.dayOfWeek == dayOfWeek })
        }
    }

    // 加载指定周的课程
    fun loadCoursesForWeek(week: Int) {
        activeWeek = week
        viewModelScope.launch {
            val allCourses = courseDataManager.getAllCourses()
            val coursesForWeek = allCourses.filter { course ->
                if (!course.isActiveInWeek(week)) {
                    return@filter false
                }

                // 检查是否需要过滤节假日课程
                if (settingsManager.isHideHolidayCourses()) {
                    val weekStart = getWeekStartCalendar(week)
                    if (weekStart != null) {
                        val courseDayCalendar = (weekStart.clone() as Calendar).apply {
                            add(Calendar.DAY_OF_YEAR, course.dayOfWeek - 1)
                        }
                        if (holidayManager.isHoliday(courseDayCalendar)) {
                            return@filter false
                        }
                    }
                }
                true
            }
            _courses.postValue(coursesForWeek)
        }
    }

    // 添加课程
    fun addCourse(course: Course) {
        viewModelScope.launch {
            courseDataManager.addCourse(course)
        }
    }

    // 批量添加课程
    fun addCourses(courses: List<Course>) {
        viewModelScope.launch {
            courseDataManager.addCourses(courses)
        }
    }

    // 更新课程
    fun updateCourse(course: Course) {
        viewModelScope.launch {
            courseDataManager.updateCourse(course)
        }
    }

    // 删除课程
    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            courseDataManager.deleteCourse(course)
        }
    }

    // 清空所有课程
    fun clearAllCourses() {
        viewModelScope.launch {
            courseDataManager.clearAllCourses()
        }
    }

    // 刷新课程
    fun refreshCourses() {
        viewModelScope.launch {
            courseDataManager.refreshCourses()
        }
    }

    // 计算当前周数
    private fun calculateCurrentWeek(): Int {
        val totalWeeks = settingsManager.getTotalWeeks()
        val semesterStartDate = settingsManager.getSemesterStartDate()
        if (semesterStartDate <= 0L) {
            return settingsManager.getDefaultWeek().coerceIn(1, totalWeeks)
        }
        val diffMillis = System.currentTimeMillis() - semesterStartDate
        val diffDays = (diffMillis / (24 * 60 * 60 * 1000L)).toInt()
        return ((diffDays / 7) + 1).coerceIn(1, totalWeeks)
    }
}
