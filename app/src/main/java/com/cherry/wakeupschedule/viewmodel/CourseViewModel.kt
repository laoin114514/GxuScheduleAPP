package com.cherry.wakeupschedule.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.model.SemesterInfoEntity
import com.cherry.wakeupschedule.service.*
import com.gxu.jwxt.model.Term
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class InitProgress(
    val totalSteps: Int = 0,
    val completedSteps: Int = 0,
    val currentLabel: String = "",
    val isComplete: Boolean = false,
    val hasError: Boolean = false
)

class CourseViewModel(application: Application) : AndroidViewModel(application) {

    private val _courses = MutableLiveData<List<Course>>()
    val courses: LiveData<List<Course>> = _courses

    private val _initProgress = MutableStateFlow(InitProgress())
    val initProgress: StateFlow<InitProgress> = _initProgress

    private val _semesters = MutableStateFlow<List<SemesterInfoEntity>>(emptyList())
    val semesters: StateFlow<List<SemesterInfoEntity>> = _semesters

    var displayWeek: Int = 0
    var currentWeek: Int = 0

    private val repo = AccountRepository.getInstance(application)
    private val settingsManager = SettingsManager(application)
    private val alarmService = App.instance.alarmService

    @Volatile
    private var activeWeek: Int = calculateCurrentWeek()

    init {
        val accountId = repo.getActiveAccountId()
        if (accountId > 0) {
            loadSemestersForAccount(accountId)
        }
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).coursesFlow.collect { allCourses ->
                val week = activeWeek
                val coursesForWeek = allCourses.filter { course ->
                    val isInWeekRange = week in course.startWeek..course.endWeek
                    val isWeekTypeMatch = when (course.weekType) {
                        0 -> true; 1 -> week % 2 == 1; 2 -> week % 2 == 0; else -> true
                    }
                    val match = isInWeekRange && isWeekTypeMatch
                    if (match && settingsManager.isHideHolidayCourses()) {
                        val accountId2 = repo.getActiveAccountId()
                        val startDate = settingsManager.getSemesterStartDate(accountId2)
                        if (startDate > 0) {
                            val holidayManager = HolidayManager.getInstance(getApplication())
                            val cal = Calendar.getInstance().apply {
                                timeInMillis = startDate
                                add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + course.dayOfWeek - 1)
                            }
                            !holidayManager.isHoliday(cal)
                        } else true
                    } else match
                }
                _courses.postValue(coursesForWeek)
            }
        }
    }

    // ── 初始化流程 ──

    fun startInitFlow(accountId: Long) {
        viewModelScope.launch {
            val account = repo.getAccountById(accountId) ?: return@launch
            try {
                _initProgress.value = InitProgress(
                    totalSteps = 0, completedSteps = 0, currentLabel = "正在获取个人信息..."
                )

                JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                    client.profile().profile()
                }.onSuccess { profile ->
                    repo.saveProfile(accountId, profile)
                    val gradeYear = profile.getGrade()?.toIntOrNull() ?: 0
                    val studyYears = profile.getSchoolingLength()?.toIntOrNull() ?: 4

                    val semesterList = repo.generateSemesterList(gradeYear, studyYears)
                        .map { it.copy(accountId = accountId) }
                    repo.saveSemesters(semesterList)

                    val totalSteps = 1 + semesterList.size
                    _initProgress.value = InitProgress(
                        totalSteps = totalSteps, completedSteps = 1,
                        currentLabel = "个人信息获取完成"
                    )

                    var completed = 1
                    for (sem in semesterList) {
                        val year = sem.academicYear.substringBefore("-")
                        val term = Term.fromCode(sem.termCode) ?: Term.AUTUMN

                        _initProgress.value = InitProgress(
                            totalSteps = totalSteps, completedSteps = completed,
                            currentLabel = "正在获取课表（${sem.gradeLabel}·${if (sem.termCode == "3") "第一学期" else "第二学期"}）..."
                        )

                        val result = JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                            client.schedule().personal(year, term)
                        }

                        result.onSuccess { response ->
                            val (courses, startDate) = JwxtImportService.convertScheduleResponse(response)
                            if (courses.isNotEmpty()) {
                                CourseDataManager.getInstance(getApplication()).addCourses(courses)
                                val totalWeeks = courses.maxOfOrNull { it.endWeek } ?: 20
                                val updatedSem = sem.copy(
                                    startDate = startDate ?: 0,
                                    totalWeeks = totalWeeks,
                                    isDataLoaded = true
                                )
                                repo.updateSemester(updatedSem)
                            } else {
                                repo.updateSemester(sem.copy(isDataLoaded = true))
                            }
                        }.onFailure {
                            // 失败跳过
                        }

                        completed++
                    }

                    _initProgress.value = InitProgress(
                        totalSteps = totalSteps, completedSteps = completed,
                        currentLabel = "初始化完成", isComplete = true
                    )

                    loadSemestersForAccount(accountId)
                    CourseDataManager.getInstance(getApplication()).switchAccount(accountId)
                    settingsManager.loadAccountSettings(accountId)
                    alarmService?.registerAllCourseNotifications()
                }.onFailure { e ->
                    _initProgress.value = InitProgress(
                        currentLabel = "初始化失败: ${e.message}", hasError = true
                    )
                }
            } catch (e: Exception) {
                _initProgress.value = InitProgress(
                    currentLabel = "初始化失败: ${e.message}", hasError = true
                )
            }
        }
    }

    fun refreshSemester(semester: SemesterInfoEntity) {
        viewModelScope.launch {
            val accountId = semester.accountId
            val account = repo.getAccountById(accountId) ?: return@launch
            val year = semester.academicYear.substringBefore("-")
            val term = Term.fromCode(semester.termCode) ?: Term.AUTUMN

            _initProgress.value = InitProgress(
                totalSteps = 1, completedSteps = 0,
                currentLabel = "正在刷新课表（${semester.gradeLabel}·${if (semester.termCode == "3") "第一学期" else "第二学期"}）..."
            )

            JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                client.schedule().personal(year, term)
            }.onSuccess { response ->
                val (courses, startDate) = JwxtImportService.convertScheduleResponse(response)
                if (courses.isNotEmpty()) {
                    CourseDataManager.getInstance(getApplication()).addCourses(courses)
                }
                val totalWeeks = courses.maxOfOrNull { it.endWeek } ?: 20
                repo.updateSemester(semester.copy(
                    startDate = startDate ?: semester.startDate,
                    totalWeeks = totalWeeks,
                    isDataLoaded = true
                ))
                loadSemestersForAccount(accountId)
                _initProgress.value = _initProgress.value.copy(isComplete = true, currentLabel = "刷新完成")
            }.onFailure { e ->
                _initProgress.value = InitProgress(currentLabel = "刷新失败: ${e.message}", hasError = true)
            }
        }
    }

    fun refreshAllSemesters(accountId: Long) {
        viewModelScope.launch {
            val account = repo.getAccountById(accountId) ?: return@launch
            val semesters = repo.getSemesters(accountId)
            val totalSteps = semesters.size
            var completed = 0

            for (sem in semesters) {
                val year = sem.academicYear.substringBefore("-")
                val term = Term.fromCode(sem.termCode) ?: Term.AUTUMN

                _initProgress.value = InitProgress(
                    totalSteps = totalSteps, completedSteps = completed,
                    currentLabel = "正在刷新课表（${sem.gradeLabel}·${if (sem.termCode == "3") "第一学期" else "第二学期"}）..."
                )

                JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                    client.schedule().personal(year, term)
                }.onSuccess { response ->
                    val (courses, startDate) = JwxtImportService.convertScheduleResponse(response)
                    if (courses.isNotEmpty()) {
                        CourseDataManager.getInstance(getApplication()).addCourses(courses)
                    }
                    val totalWeeks = courses.maxOfOrNull { it.endWeek } ?: 20
                    repo.updateSemester(sem.copy(
                        startDate = startDate ?: sem.startDate,
                        totalWeeks = totalWeeks,
                        isDataLoaded = true
                    ))
                }
                completed++
                _initProgress.value = _initProgress.value.copy(completedSteps = completed)
            }

            loadSemestersForAccount(accountId)
            _initProgress.value = InitProgress(
                totalSteps = totalSteps, completedSteps = completed,
                currentLabel = "全部刷新完成", isComplete = true
            )
        }
    }

    private fun loadSemestersForAccount(accountId: Long) {
        viewModelScope.launch {
            repo.getSemestersFlow(accountId).collect { list ->
                _semesters.value = list
            }
        }
    }

    // ── 课程操作 ──

    fun getAllCourses(): Flow<List<Course>> = flow {
        emit(CourseDataManager.getInstance(getApplication()).getAllCourses())
    }

    fun getCoursesByDay(dayOfWeek: Int): Flow<List<Course>> = flow {
        emit(CourseDataManager.getInstance(getApplication())
            .getAllCourses().filter { it.dayOfWeek == dayOfWeek })
    }

    fun loadCoursesForWeek(week: Int) {
        activeWeek = week
        viewModelScope.launch {
            val allCourses = CourseDataManager.getInstance(getApplication()).getAllCourses()
            val accountId = repo.getActiveAccountId()
            val coursesForWeek = allCourses.filter { course ->
                val isInWeekRange = week in course.startWeek..course.endWeek
                val isWeekTypeMatch = when (course.weekType) {
                    0 -> true; 1 -> week % 2 == 1; 2 -> week % 2 == 0; else -> true
                }
                if (!isInWeekRange || !isWeekTypeMatch) return@filter false
                if (settingsManager.isHideHolidayCourses()) {
                    val startDate = settingsManager.getSemesterStartDate(accountId)
                    if (startDate > 0) {
                        val holidayManager = HolidayManager.getInstance(getApplication())
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = startDate
                            add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + course.dayOfWeek - 1)
                        }
                        !holidayManager.isHoliday(cal)
                    } else true
                } else true
            }
            _courses.postValue(coursesForWeek)
        }
    }

    fun addCourse(course: Course) {
        viewModelScope.launch {
            val newCourse = CourseDataManager.getInstance(getApplication()).addCourse(course)
            alarmService?.setCourseAlarm(newCourse)
            withContext(Dispatchers.IO) { App.instance.registerAllCourseNotifications() }
        }
    }

    fun addCourses(courses: List<Course>) {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).addCourses(courses)
            withContext(Dispatchers.IO) { App.instance.registerAllCourseNotifications() }
        }
    }

    fun updateCourse(course: Course) {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).updateCourse(course)
            alarmService?.setCourseAlarm(course)
            withContext(Dispatchers.IO) { App.instance.registerAllCourseNotifications() }
        }
    }

    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).deleteCourse(course)
            alarmService?.cancelCourseAlarm(course)
        }
    }

    fun clearAllCourses() {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).clearAllCourses()
        }
    }

    private fun calculateCurrentWeek(): Int {
        val accountId = repo.getActiveAccountId()
        val startDate = settingsManager.getSemesterStartDate(accountId)
        val totalWeeks = settingsManager.getTotalWeeks(accountId)
        if (startDate <= 0L) return 1
        val diffMillis = System.currentTimeMillis() - startDate
        val diffDays = (diffMillis / (24 * 60 * 60 * 1000L)).toInt()
        return ((diffDays / 7) + 1).coerceIn(1, totalWeeks)
    }
}
