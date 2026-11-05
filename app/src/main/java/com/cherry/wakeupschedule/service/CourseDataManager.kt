package com.cherry.wakeupschedule.service

import android.content.Context
import androidx.room.withTransaction
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

    /**
     * 各学期课程数量缓存（学期ID → 数量）。
     *
     * 供课表菜单「学期色块」与学期滚轮选择器使用；旧实现每次都同步查一次数据库
     * （`executeDb { dao.getAllCourses() }` 在打开菜单/弹窗时阻塞主线程）。
     * 现在由 Room 的 Flow 监听顺带算出；写操作后先置空，下一次读取回退到一次查询以保证准确，
     * 之后 Flow 新值到达再回填 —— 读取语义与旧实现一致（永远反映当前数据库内容）。
     */
    @Volatile
    private var semesterCountsCache: Map<Long, Int>? = null

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
                // 顺带更新「各学期课程数量」缓存（同一份全量数据，零额外查询）
                semesterCountsCache = courses.groupingBy { it.semesterId }.eachCount()
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

    /**
     * 各学期课程数量统计（学期ID → 数量）。
     * 供课表菜单标识"已填充课表"的学期（风景色块）。
     */
    fun getSemesterCourseCounts(): Map<Long, Int> {
        // 快路径：Flow 监听已顺带算好（打开菜单/学期弹窗时不再同步查库）
        semesterCountsCache?.let { return it }
        // 兜底：缓存尚未就绪（极早期）或刚写完尚未收到 Flow 新值时，仍按旧实现查一次库
        val counts = executeDb { dao.getAllCourses() }.groupingBy { it.semesterId }.eachCount()
        semesterCountsCache = counts
        return counts
    }

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
        // 写后先失效各学期课程数缓存：Flow 新值到达前若打开菜单，走一次查询保证数字准确
        semesterCountsCache = null
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
        semesterCountsCache = null
    }

    fun updateCourse(course: Course) {
        executeDb { dao.updateCourse(course) }
        semesterCountsCache = null
    }

    fun deleteCourse(course: Course) {
        executeDb { dao.deleteCourse(course) }
        semesterCountsCache = null
    }

    fun clearAllCourses() {
        executeDb { dao.deleteAllCourses() }
        semesterCountsCache = null
    }

    fun replaceAllCourses(courses: List<Course>) {
        replaceAllCoursesForSemester(courses, currentSemesterId)
    }

    /**
     * 替换指定学期的所有课程（显式传入学期 ID，避免并发切换时误存到错误学期）。
     *
     * 「先删后插」必须在同一个事务里：旧实现是两条独立的 Room 事务，插入一旦失败
     * （磁盘满、库损坏、约束/触发器异常等）删除已经提交，用户该学期课表会被清空 ——
     * 刷新失败本来只是「数据没更新」，不应该变成「数据没了」。
     */
    fun replaceAllCoursesForSemester(courses: List<Course>, semesterId: Long) {
        val colorMap = assignColorsForBatch(courses)
        executeDb {
            db.withTransaction {
                dao.deleteCoursesBySemesterId(semesterId)
                dao.insertCourses(courses.map {
                    it.copy(id = 0, semesterId = semesterId,
                        color = it.color.takeIf { c -> c != 0 } ?: (colorMap[it.name] ?: assignColorIndex(it.name, it.teacher)))
                })
            }
        }
        semesterCountsCache = null
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
            // 冷启动兜底：后台初始化完成前不要碰数据库/学期缓存。
            // 小组件广播可能在 App.onCreate 返回后立刻拉起（数据库还在后台恢复中），
            // 此时若直接构造本类，会读到还没恢复的学期缓存，把课程按 semesterId=0 加载成空表。
            // 初始化线程自己构造实例时 awaitReady 会立即返回（不会自己等自己）。
            AppInitializer.awaitReady()
            return instance ?: synchronized(this) {
                instance ?: CourseDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * 供小组件等**系统回调入口**使用：数据库不可用或读取异常时返回空列表，绝不抛异常。
         *
         * 为什么需要：AppWidgetProvider / RemoteViewsService 由系统直接回调，进程里没有
         * MainActivity 的恢复流程兜底；如果用户本地库损坏（AppInitializer 初始化失败），
         * 系统触发的组件刷新会让整个应用进程直接崩掉。小组件宁可显示空/旧数据，也不能崩 ——
         * 用户打开 App 后会看到「本地数据初始化失败」的恢复弹窗。
         */
        fun getAllCoursesSafely(context: Context): List<Course> = try {
            getInstance(context).getAllCourses()
        } catch (t: Throwable) {
            android.util.Log.e(
                "CourseDataManager",
                "系统回调读取课程失败（已兜底，返回空列表）",
                t,
            )
            emptyList()
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
