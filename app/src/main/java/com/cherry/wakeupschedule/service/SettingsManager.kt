package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.cherry.wakeupschedule.model.AccountSettings
import com.google.gson.Gson
import java.util.Calendar

class SettingsManager(context: Context) {

    private val gson = Gson()
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val accountRepo: AccountRepository by lazy {
        AccountRepository.getInstance(context)
    }

    // ── 内存缓存：确保同步调用方可读取账号设置 ──
    @Volatile
    private var cachedAccountSettings: AccountSettings? = null
    @Volatile
    private var cachedAccountId: Long = -1L

    /**
     * 切换活跃账号时调用，将账号设置加载到内存缓存。
     */
    fun loadAccountSettings(accountId: Long) {
        cachedAccountId = accountId
        cachedAccountSettings = kotlinx.coroutines.runBlocking {
            accountRepo.getAccountSettings(accountId)
        }
    }

    fun clearAccountCache() {
        cachedAccountSettings = null
        cachedAccountId = -1L
    }

    // ═══════════════════════════════════════════
    // 全局设置（SharedPreferences，跟账号无关）
    // ═══════════════════════════════════════════

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_COURSE_CARD_ALPHA = "course_card_alpha"
        private const val KEY_SHOW_NON_CURRENT_WEEK_COURSES = "show_non_current_week_courses"
        private const val KEY_NON_CURRENT_WEEK_ALPHA = "non_current_week_alpha"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_CUSTOM_BACKGROUND_PATH = "custom_background_path"
        private const val KEY_FLOAT_BUTTON_X = "float_button_x"
        private const val KEY_FLOAT_BUTTON_Y = "float_button_y"
        private const val KEY_VIEW_STATE = "view_state"
        private const val KEY_AUTO_SWITCH_WEEK = "auto_switch_week"
        private const val KEY_DEFAULT_ALARM_MINUTES = "default_alarm_minutes"
        private const val KEY_HIDE_HOLIDAY_COURSES = "hide_holiday_courses"
        private const val KEY_ENABLE_UPDATE_REMIND = "enable_update_remind"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_LAST_LOG_CLEAR = "last_log_clear"
    }

    // 全局 getter/setter（保持不变）
    fun getTheme(): String = sharedPreferences.getString(KEY_THEME, "light") ?: "light"
    fun setTheme(theme: String) = sharedPreferences.edit().putString(KEY_THEME, theme).apply()
    fun getFontSize(): String = sharedPreferences.getString(KEY_FONT_SIZE, "normal") ?: "normal"
    fun setFontSize(fontSize: String) = sharedPreferences.edit().putString(KEY_FONT_SIZE, fontSize).apply()
    fun getCourseCardAlpha(): Float = sharedPreferences.getFloat(KEY_COURSE_CARD_ALPHA, 0.85f)
    fun setCourseCardAlpha(alpha: Float) = sharedPreferences.edit().putFloat(KEY_COURSE_CARD_ALPHA, alpha.coerceIn(0.2f, 1.0f)).apply()
    fun isShowNonCurrentWeekCourses(): Boolean = sharedPreferences.getBoolean(KEY_SHOW_NON_CURRENT_WEEK_COURSES, true)
    fun setShowNonCurrentWeekCourses(show: Boolean) = sharedPreferences.edit().putBoolean(KEY_SHOW_NON_CURRENT_WEEK_COURSES, show).apply()
    fun getNonCurrentWeekAlpha(): Float = sharedPreferences.getFloat(KEY_NON_CURRENT_WEEK_ALPHA, 0.3f)
    fun setNonCurrentWeekAlpha(alpha: Float) = sharedPreferences.edit().putFloat(KEY_NON_CURRENT_WEEK_ALPHA, alpha.coerceIn(0.1f, 0.8f)).apply()
    fun getViewMode(): String = sharedPreferences.getString(KEY_VIEW_MODE, "week") ?: "week"
    fun setViewMode(mode: String) = sharedPreferences.edit().putString(KEY_VIEW_MODE, mode).apply()
    fun getCustomBackgroundPath(): String = sharedPreferences.getString(KEY_CUSTOM_BACKGROUND_PATH, "") ?: ""
    fun setCustomBackgroundPath(path: String) = sharedPreferences.edit().putString(KEY_CUSTOM_BACKGROUND_PATH, path).apply()
    fun getFloatButtonX(): Float = sharedPreferences.getFloat(KEY_FLOAT_BUTTON_X, -1f)
    fun setFloatButtonX(x: Float) = sharedPreferences.edit().putFloat(KEY_FLOAT_BUTTON_X, x).apply()
    fun getFloatButtonY(): Float = sharedPreferences.getFloat(KEY_FLOAT_BUTTON_Y, -1f)
    fun setFloatButtonY(y: Float) = sharedPreferences.edit().putFloat(KEY_FLOAT_BUTTON_Y, y).apply()
    fun getViewState(): String = sharedPreferences.getString(KEY_VIEW_STATE, "week") ?: "week"
    fun setViewState(state: String) = sharedPreferences.edit().putString(KEY_VIEW_STATE, state).apply()
    fun getAutoSwitchWeek(): Boolean = sharedPreferences.getBoolean(KEY_AUTO_SWITCH_WEEK, true)
    fun setAutoSwitchWeek(autoSwitch: Boolean) = sharedPreferences.edit().putBoolean(KEY_AUTO_SWITCH_WEEK, autoSwitch).apply()
    fun getDefaultAlarmMinutes(): Int = sharedPreferences.getInt(KEY_DEFAULT_ALARM_MINUTES, 15)
    fun setDefaultAlarmMinutes(minutes: Int) = sharedPreferences.edit().putInt(KEY_DEFAULT_ALARM_MINUTES, minutes).apply()
    fun isHideHolidayCourses(): Boolean = sharedPreferences.getBoolean(KEY_HIDE_HOLIDAY_COURSES, false)
    fun setHideHolidayCourses(hide: Boolean) = sharedPreferences.edit().putBoolean(KEY_HIDE_HOLIDAY_COURSES, hide).apply()
    fun isUpdateRemindEnabled(): Boolean = sharedPreferences.getBoolean(KEY_ENABLE_UPDATE_REMIND, true)
    fun setUpdateRemindEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean(KEY_ENABLE_UPDATE_REMIND, enabled).apply()
    fun getLastUpdateCheckDate(): String? = sharedPreferences.getString(KEY_LAST_UPDATE_CHECK, null)
    fun setLastUpdateCheckDate(date: String) = sharedPreferences.edit().putString(KEY_LAST_UPDATE_CHECK, date).apply()
    fun isCheckedForUpdateToday(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        return today == getLastUpdateCheckDate()
    }
    fun markUpdateCheckedToday() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        setLastUpdateCheckDate(today)
    }
    fun getLastLogClearDate(): String? = sharedPreferences.getString(KEY_LAST_LOG_CLEAR, null)
    fun setLastLogClearDate(date: String) = sharedPreferences.edit().putString(KEY_LAST_LOG_CLEAR, date).apply()
    fun needClearLogs(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val lastClear = getLastLogClearDate() ?: return true
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return try {
            val d1 = sdf.parse(lastClear)!!; val d2 = sdf.parse(today)!!
            val days = (d2.time - d1.time) / (1000 * 60 * 60 * 24)
            days >= 7
        } catch (_: Exception) { true }
    }
    fun markLogClearedToday() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        setLastLogClearDate(today)
    }

    // ═══════════════════════════════════════════
    // 账号级设置（Room，内存缓存加速同步读取）
    // ═══════════════════════════════════════════

    private fun getCachedOrThrow(accountId: Long): AccountSettings {
        if (accountId == cachedAccountId) {
            return cachedAccountSettings ?: AccountSettings()
        }
        val settings = kotlinx.coroutines.runBlocking { accountRepo.getAccountSettings(accountId) }
        cachedAccountId = accountId
        cachedAccountSettings = settings
        return settings
    }

    fun getAutoDetectedSemester(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        return when {
            month in 1..6 -> "${year - 1}-${year}学年 第二学期"
            month in 7..8 -> "${year - 1}-${year}学年 第二学期"
            else -> "${year}-${year + 1}学年 第一学期"
        }
    }

    // ── 无感重载 ──
    fun isSilentReloginEnabled(accountId: Long): Boolean {
        return getCachedOrThrow(accountId).silentRelogin
    }
    suspend fun setSilentReloginEnabled(accountId: Long, enabled: Boolean) {
        val settings = getCachedOrThrow(accountId).copy(silentRelogin = enabled)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 闹钟启用 ──
    fun isAlarmEnabled(accountId: Long): Boolean {
        return getCachedOrThrow(accountId).alarmEnabled
    }
    suspend fun setAlarmEnabled(accountId: Long, enabled: Boolean) {
        val settings = getCachedOrThrow(accountId).copy(alarmEnabled = enabled)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 当前学期 ──
    fun getCurrentSemester(accountId: Long): String {
        val v = getCachedOrThrow(accountId).currentSemester
        return v.ifEmpty { getAutoDetectedSemester() }
    }
    suspend fun setCurrentSemester(accountId: Long, semester: String) {
        val settings = getCachedOrThrow(accountId).copy(currentSemester = semester)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 学期开始日期 ──
    fun getSemesterStartDate(accountId: Long): Long {
        return getCachedOrThrow(accountId).semesterStartDate
    }
    suspend fun setSemesterStartDate(accountId: Long, dateMillis: Long) {
        val settings = getCachedOrThrow(accountId).copy(semesterStartDate = dateMillis)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 总周数 ──
    fun getTotalWeeks(accountId: Long): Int {
        val v = getCachedOrThrow(accountId).totalWeeks
        return if (v > 0) v else 20
    }
    suspend fun setTotalWeeks(accountId: Long, weeks: Int) {
        val settings = getCachedOrThrow(accountId).copy(totalWeeks = weeks)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 默认显示周 ──
    fun getDefaultWeek(accountId: Long): Int {
        return getCachedOrThrow(accountId).defaultWeek
    }
    suspend fun setDefaultWeek(accountId: Long, week: Int) {
        val settings = getCachedOrThrow(accountId).copy(defaultWeek = week)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 自定义学期列表 ──
    suspend fun getCustomSemesters(accountId: Long): List<String> {
        return getCachedOrThrow(accountId).customSemesters.ifEmpty { getDefaultSemesters() }
    }
    suspend fun addCustomSemester(accountId: Long, semester: String) {
        val settings = getCachedOrThrow(accountId)
        val list = settings.customSemesters.toMutableList()
        if (!list.contains(semester)) { list.add(semester) }
        accountRepo.saveAccountSettings(accountId, settings.copy(customSemesters = list))
        cachedAccountSettings = settings.copy(customSemesters = list)
    }
    suspend fun removeCustomSemester(accountId: Long, semester: String) {
        val settings = getCachedOrThrow(accountId)
        val list = settings.customSemesters.filter { it != semester }
        accountRepo.saveAccountSettings(accountId, settings.copy(customSemesters = list))
        cachedAccountSettings = settings.copy(customSemesters = list)
    }
    private fun getDefaultSemesters(): List<String> {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val currentAcademicStart = if (month >= 9) year else year - 1
        val semesters = mutableListOf<String>()
        for (offset in -1 until 9) {
            val start = currentAcademicStart - offset
            semesters.add("${start}-${start + 1}学年 第一学期")
            semesters.add("${start}-${start + 1}学年 第二学期")
        }
        return semesters
    }

    // ═══════════════════════════════════════════
    // 兼容旧版（无 accountId 参数，已废弃）
    // ═══════════════════════════════════════════

    @Deprecated("使用 getSemesterStartDate(accountId) 代替", ReplaceWith("getSemesterStartDate(accountId)"))
    fun getSemesterStartDate(): Long = sharedPreferences.getLong("semester_start_date", 0L)

    @Deprecated("使用 getCurrentSemester(accountId) 代替")
    fun getCurrentSemester(): String = getAutoDetectedSemester()

    @Deprecated("使用 isAlarmEnabled(accountId) 代替")
    fun isAlarmEnabled(): Boolean = true

    @Deprecated("使用 setAlarmEnabled(accountId, enabled) 代替")
    fun setAlarmEnabled(enabled: Boolean) { /* no-op: account-level */ }

    @Deprecated("使用 setSemesterStartDate(accountId, dateMillis) 代替")
    fun setSemesterStartDate(dateMillis: Long) { /* no-op: account-level */ }

    @Deprecated("使用 getTotalWeeks(accountId) 代替")
    fun getTotalWeeks(): Int = 20

    @Deprecated("使用 setTotalWeeks(accountId, weeks) 代替")
    fun setTotalWeeks(weeks: Int) { /* no-op: account-level */ }

    @Deprecated("使用 getDefaultWeek(accountId) 代替")
    fun getDefaultWeek(): Int = 1

    @Deprecated("使用 setDefaultWeek(accountId, week) 代替")
    fun setDefaultWeek(week: Int) { /* no-op: account-level */ }

    @Deprecated("使用 setCurrentSemester(accountId, semester) 代替")
    fun setCurrentSemester(semester: String) { /* no-op: account-level */ }

    @Deprecated("使用 getCustomSemesters(accountId) 代替")
    fun getCustomSemesters(): List<String> = getDefaultSemesters()

    @Deprecated("使用 addCustomSemester(accountId, semester) 代替")
    fun addCustomSemester(semester: String) { /* no-op: account-level */ }

    @Deprecated("使用 removeCustomSemester(accountId, semester) 代替")
    fun removeCustomSemester(semester: String) { /* no-op: account-level */ }

    @Deprecated("使用 saveCustomSemesters 的 account 版本")
    fun saveCustomSemesters(semesters: List<String>) { /* no-op: account-level */ }
}
