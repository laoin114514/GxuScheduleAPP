package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

/**
 * 设置管理器
 * 负责应用所有设置的读取和保存，使用SharedPreferences存储
 *
 * @param context 上下文环境
 */
class SettingsManager(context: Context) {

    private val gson = Gson()

    // SharedPreferences实例，用于存储键值对数据
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        // SharedPreferences键名常量
        private const val KEY_CURRENT_SEMESTER = "current_semester"           // 当前学期
        private const val KEY_DEFAULT_WEEK = "default_week"                    // 默认显示周
        private const val KEY_DEFAULT_ALARM_MINUTES = "default_alarm_minutes" // 默认闹钟提前时间
        private const val KEY_AUTO_SWITCH_WEEK = "auto_switch_week"           // 是否自动切换周
        private const val KEY_THEME = "theme"                                  // 主题设置
        private const val KEY_FONT_SIZE = "font_size"                          // 字体大小
        private const val KEY_ALARM_ENABLED = "alarm_enabled"                  // 闹钟是否启用
        private const val KEY_SEMESTER_START_DATE = "semester_start_date"      // 学期开始日期
        private const val KEY_TOTAL_WEEKS = "total_weeks"                     // 学期总周数
        private const val KEY_CUSTOM_BACKGROUND_PATH = "custom_background_path"// 自定义背景图片路径
        private const val KEY_COURSE_CARD_ALPHA = "course_card_alpha"         // 课程卡片透明度
        private const val KEY_SHOW_NON_CURRENT_WEEK_COURSES = "show_non_current_week_courses" // 是否显示非本周课程
        private const val KEY_NON_CURRENT_WEEK_ALPHA = "non_current_week_alpha"// 非本周课程透明度
        private const val KEY_VIEW_MODE = "view_mode"                          // 视图模式（周视图/日视图）
        private const val KEY_CUSTOM_SEMESTERS = "custom_semesters"            // 自定义学期列表
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"        // 上次检查更新日期
        private const val KEY_FLOAT_BUTTON_X = "float_button_x"             // 悬浮球X位置
        private const val KEY_FLOAT_BUTTON_Y = "float_button_y"             // 悬浮球Y位置
        private const val KEY_VIEW_STATE = "view_state"                     // 视图状态（week/day/overview）
        private const val KEY_ENABLE_UPDATE_REMIND = "enable_update_remind"  // 是否允许更新提醒
        private const val KEY_LAST_LOG_CLEAR = "last_log_clear"            // 上次清理日志日期
        private const val KEY_HIDE_HOLIDAY_COURSES = "hide_holiday_courses" // 是否在节假日隐藏课程

        private const val DEFAULT_SEMESTER = "2024-2025学年 第一学期"
        private const val DEFAULT_HIDE_HOLIDAY_COURSES = false              // 默认不隐藏
        private const val DEFAULT_ENABLE_UPDATE_REMIND = true              // 默认开启更新提醒
        private const val DEFAULT_WEEK = 1                                     // 默认第1周
        private const val DEFAULT_ALARM_MINUTES = 15                           // 默认提前15分钟
        private const val DEFAULT_AUTO_SWITCH = true                           // 默认自动切换
        private const val DEFAULT_THEME = "light"                              // 默认浅色主题
        private const val DEFAULT_FONT_SIZE = "normal"                         // 默认字体大小
        private const val DEFAULT_ALARM_ENABLED = true                         // 默认启用闹钟
        private const val DEFAULT_COURSE_CARD_ALPHA = 0.85f                    // 默认卡片透明度85%
        private const val DEFAULT_SHOW_NON_CURRENT_WEEK_COURSES = true         // 默认显示非本周课程
        private const val DEFAULT_NON_CURRENT_WEEK_ALPHA = 0.3f                // 非本周课程默认30%透明度
    }

    // ==================== 学期相关 ====================

    /**
     * 获取当前设置的学期名称
     * @return 当前学期字符串，如"2024-2025学年 第一学期"
     */
    fun getCurrentSemester(): String {
        return sharedPreferences.getString(KEY_CURRENT_SEMESTER, getAutoDetectedSemester()) ?: getAutoDetectedSemester()
    }

    /**
     * 自动检测当前学期
     * 根据当前日期自动推断学期
     * @return 自动检测的学期名称
     */
    fun getAutoDetectedSemester(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        return when {
            month in 1..6 -> "${year - 1}-${year}学年 第二学期"
            month in 7..8 -> "${year - 1}-${year}学年 第二学期" // 暑假期间仍显示上学期
            else -> "${year}-${year + 1}学年 第一学期"
        }
    }

    /**
     * 设置当前学期
     * @param semester 学期名称
     */
    fun setCurrentSemester(semester: String) {
        sharedPreferences.edit().putString(KEY_CURRENT_SEMESTER, semester).apply()
    }

    /**
     * 获取默认显示周
     * @return 周数（1-20）
     */
    fun getDefaultWeek(): Int {
        return sharedPreferences.getInt(KEY_DEFAULT_WEEK, DEFAULT_WEEK)
    }

    /**
     * 设置默认显示周
     * @param week 周数（1-20）
     */
    fun setDefaultWeek(week: Int) {
        sharedPreferences.edit().putInt(KEY_DEFAULT_WEEK, week).apply()
    }

    // ==================== 闹钟相关 ====================

    /**
     * 获取默认闹钟提前时间
     * @return 分钟数（5/10/15/20/30）
     */
    fun getDefaultAlarmMinutes(): Int {
        return sharedPreferences.getInt(KEY_DEFAULT_ALARM_MINUTES, DEFAULT_ALARM_MINUTES)
    }

    /**
     * 设置默认闹钟提前时间
     * @param minutes 分钟数（5/10/15/20/30）
     */
    fun setDefaultAlarmMinutes(minutes: Int) {
        sharedPreferences.edit().putInt(KEY_DEFAULT_ALARM_MINUTES, minutes).apply()
    }

    /**
     * 获取是否启用课前提醒
     * @return true表示启用
     */
    fun isAlarmEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_ALARM_ENABLED, DEFAULT_ALARM_ENABLED)
    }

    /**
     * 设置是否启用课前提醒
     * @param enabled true启用，false禁用
     */
    fun setAlarmEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_ALARM_ENABLED, enabled).apply()
    }

    /**
     * 获取是否自动切换周
     * @return true表示自动切换
     */
    fun getAutoSwitchWeek(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTO_SWITCH_WEEK, DEFAULT_AUTO_SWITCH)
    }

    /**
     * 设置是否自动切换周
     * @param autoSwitch true启用自动切换
     */
    fun setAutoSwitchWeek(autoSwitch: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_AUTO_SWITCH_WEEK, autoSwitch).apply()
    }

    // ==================== 界面显示相关 ====================

    /**
     * 获取当前主题设置
     * @return "light"浅色主题，"dark"深色主题
     */
    fun getTheme(): String {
        return sharedPreferences.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
    }

    /**
     * 设置主题
     * @param theme "light"或"dark"
     */
    fun setTheme(theme: String) {
        sharedPreferences.edit().putString(KEY_THEME, theme).apply()
    }

    /**
     * 获取自定义背景图片路径
     * @return 图片文件路径，默认为空
     */
    fun getCustomBackgroundPath(): String {
        return sharedPreferences.getString(KEY_CUSTOM_BACKGROUND_PATH, "") ?: ""
    }

    /**
     * 设置自定义背景图片路径
     * @param path 图片文件的绝对路径
     */
    fun setCustomBackgroundPath(path: String) {
        sharedPreferences.edit().putString(KEY_CUSTOM_BACKGROUND_PATH, path).apply()
    }

    /**
     * 获取课程卡片透明度
     * @return 透明度值（0.0-1.0）
     */
    fun getCourseCardAlpha(): Float {
        return sharedPreferences.getFloat(KEY_COURSE_CARD_ALPHA, DEFAULT_COURSE_CARD_ALPHA)
    }

    /**
     * 设置课程卡片透明度
     * @param alpha 透明度值，范围0.2-1.0
     */
    fun setCourseCardAlpha(alpha: Float) {
        sharedPreferences.edit().putFloat(KEY_COURSE_CARD_ALPHA, alpha.coerceIn(0.2f, 1.0f)).apply()
    }

    /**
     * 获取字体大小设置
     * @return "small"、"normal"或"large"
     */
    fun getFontSize(): String {
        return sharedPreferences.getString(KEY_FONT_SIZE, DEFAULT_FONT_SIZE) ?: DEFAULT_FONT_SIZE
    }

    /**
     * 设置字体大小
     * @param fontSize "small"、"normal"或"large"
     */
    fun setFontSize(fontSize: String) {
        sharedPreferences.edit().putString(KEY_FONT_SIZE, fontSize).apply()
    }

    /**
     * 获取是否显示非本周课程
     * @return true表示显示
     */
    fun isShowNonCurrentWeekCourses(): Boolean {
        return sharedPreferences.getBoolean(KEY_SHOW_NON_CURRENT_WEEK_COURSES, DEFAULT_SHOW_NON_CURRENT_WEEK_COURSES)
    }

    /**
     * 设置是否显示非本周课程
     * @param show true显示，false隐藏
     */
    fun setShowNonCurrentWeekCourses(show: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SHOW_NON_CURRENT_WEEK_COURSES, show).apply()
    }

    /**
     * 获取非本周课程透明度
     * @return 透明度值（0.0-1.0）
     */
    fun getNonCurrentWeekAlpha(): Float {
        return sharedPreferences.getFloat(KEY_NON_CURRENT_WEEK_ALPHA, DEFAULT_NON_CURRENT_WEEK_ALPHA)
    }

    /**
     * 设置非本周课程透明度
     * @param alpha 透明度值，范围0.1-0.8
     */
    fun setNonCurrentWeekAlpha(alpha: Float) {
        sharedPreferences.edit().putFloat(KEY_NON_CURRENT_WEEK_ALPHA, alpha.coerceIn(0.1f, 0.8f)).apply()
    }

    /**
     * 获取视图模式
     * @return "week"周视图，"day"日视图
     */
    fun getViewMode(): String {
        return sharedPreferences.getString(KEY_VIEW_MODE, "week") ?: "week"
    }

    /**
     * 设置视图模式
     * @param mode "week"或"day"
     */
    fun setViewMode(mode: String) {
        sharedPreferences.edit().putString(KEY_VIEW_MODE, mode).apply()
    }

    // ==================== 学期日期相关 ====================

    /**
     * 获取当前学期间对应的学期开始日期 key。
     * 不同学期独立存储各自的开始日期。
     */
    private fun getSemesterStartDateKey(): String {
        val semester = getCurrentSemester()
        return "${KEY_SEMESTER_START_DATE}_$semester"
    }

    /**
     * 获取学期开始日期
     * @return 学期开始日期的毫秒时间戳，0表示未设置
     */
    fun getSemesterStartDate(): Long {
        return sharedPreferences.getLong(getSemesterStartDateKey(), 0L)
    }

    /**
     * 设置学期开始日期
     * @param dateMillis 学期开始日期的毫秒时间戳
     */
    fun setSemesterStartDate(dateMillis: Long) {
        sharedPreferences.edit().putLong(getSemesterStartDateKey(), dateMillis).apply()
    }

    /**
     * 获取当前学期间对应的总周数 key。
     */
    private fun getTotalWeeksKey(): String {
        val semester = getCurrentSemester()
        return "${KEY_TOTAL_WEEKS}_$semester"
    }

    /** 获取学期总周数 */
    fun getTotalWeeks(): Int = sharedPreferences.getInt(getTotalWeeksKey(), 20)

    /** 设置学期总周数 */
    fun setTotalWeeks(weeks: Int) {
        sharedPreferences.edit().putInt(getTotalWeeksKey(), weeks).apply()
    }

    // ==================== 自定义学期列表相关 ====================

    /**
     * 获取自定义学期列表
     * @return 学期名称列表
     */
    fun getCustomSemesters(): List<String> {
        val json = sharedPreferences.getString(KEY_CUSTOM_SEMESTERS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type)
            } catch (e: Exception) {
                getDefaultSemesters()
            }
        } else {
            getDefaultSemesters()
        }
    }

    /**
     * 获取默认学期列表
     * 生成最近 10 个学年的全部学期（每年第一/第二学期共 20 个选项）
     * @return 学期名称列表
     */
    private fun getDefaultSemesters(): List<String> {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1

        // 当前学年起始年：9 月后属于新学年
        val currentAcademicStart = if (month >= 9) year else year - 1

        val semesters = mutableListOf<String>()
        // 最近 10 个学年（含下一年，方便选未来学期）
        for (offset in -1 until 9) {
            val start = currentAcademicStart - offset
            semesters.add("${start}-${start + 1}学年 第一学期")
            semesters.add("${start}-${start + 1}学年 第二学期")
        }
        return semesters
    }

    /**
     * 保存自定义学期列表
     * @param semesters 学期名称列表
     */
    fun saveCustomSemesters(semesters: List<String>) {
        val json = gson.toJson(semesters)
        sharedPreferences.edit().putString(KEY_CUSTOM_SEMESTERS, json).apply()
    }

    /**
     * 添加自定义学期
     * @param semester 学期名称
     */
    fun addCustomSemester(semester: String) {
        val currentList = getCustomSemesters().toMutableList()
        if (!currentList.contains(semester)) {
            currentList.add(semester)
            saveCustomSemesters(currentList)
        }
    }

    /**
     * 删除自定义学期
     * @param semester 学期名称
     */
    fun removeCustomSemester(semester: String) {
        val currentList = getCustomSemesters().toMutableList()
        currentList.remove(semester)
        saveCustomSemesters(currentList)
    }

    // ==================== 更新检查相关 ====================

    /**
     * 获取上次检查更新的日期
     * @return 上次检查的日期（格式：yyyy-MM-dd），null表示从未检查
     */
    fun getLastUpdateCheckDate(): String? {
        return sharedPreferences.getString(KEY_LAST_UPDATE_CHECK, null)
    }

    /**
     * 设置上次检查更新的日期
     * @param date 日期字符串（格式：yyyy-MM-dd）
     */
    fun setLastUpdateCheckDate(date: String) {
        sharedPreferences.edit().putString(KEY_LAST_UPDATE_CHECK, date).apply()
    }

    /**
     * 检查今天是否已检查过更新
     * @return true表示今天已检查
     */
    fun isCheckedForUpdateToday(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        return today == getLastUpdateCheckDate()
    }

    /**
     * 标记今天已检查更新
     */
    fun markUpdateCheckedToday() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        setLastUpdateCheckDate(today)
    }

    // ==================== 悬浮球相关 ====================

    /**
     * 获取悬浮球X位置
     */
    fun getFloatButtonX(): Float {
        return sharedPreferences.getFloat(KEY_FLOAT_BUTTON_X, -1f)
    }

    /**
     * 设置悬浮球X位置
     */
    fun setFloatButtonX(x: Float) {
        sharedPreferences.edit().putFloat(KEY_FLOAT_BUTTON_X, x).apply()
    }

    /**
     * 获取悬浮球Y位置
     */
    fun getFloatButtonY(): Float {
        return sharedPreferences.getFloat(KEY_FLOAT_BUTTON_Y, -1f)
    }

    /**
     * 设置悬浮球Y位置
     */
    fun setFloatButtonY(y: Float) {
        sharedPreferences.edit().putFloat(KEY_FLOAT_BUTTON_Y, y).apply()
    }

    /**
     * 获取视图状态
     * @return "week"周视图，"day"日视图，"overview"课程全览
     */
    fun getViewState(): String {
        return sharedPreferences.getString(KEY_VIEW_STATE, "week") ?: "week"
    }

    /**
     * 设置视图状态
     */
    fun setViewState(state: String) {
        sharedPreferences.edit().putString(KEY_VIEW_STATE, state).apply()
    }

    // ==================== 更新提醒相关 ====================

    /**
     * 获取是否允许更新提醒
     * @return true表示允许，false表示不允许
     */
    fun isUpdateRemindEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_ENABLE_UPDATE_REMIND, DEFAULT_ENABLE_UPDATE_REMIND)
    }

    /**
     * 设置是否允许更新提醒
     * @param enabled true允许，false不允许
     */
    fun setUpdateRemindEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_ENABLE_UPDATE_REMIND, enabled).apply()
    }

    // ==================== 日志清理相关 ====================

    /**
     * 获取上次清理日志的日期
     * @return 上次清理的日期（格式：yyyy-MM-dd），null表示从未清理
     */
    fun getLastLogClearDate(): String? {
        return sharedPreferences.getString(KEY_LAST_LOG_CLEAR, null)
    }

    /**
     * 设置上次清理日志的日期
     * @param date 日期字符串（格式：yyyy-MM-dd）
     */
    fun setLastLogClearDate(date: String) {
        sharedPreferences.edit().putString(KEY_LAST_LOG_CLEAR, date).apply()
    }

    /**
     * 检查是否需要清理日志（每周清理一次）
     * @return true表示需要清理
     */
    fun needClearLogs(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val lastClear = getLastLogClearDate() ?: return true
        
        // 计算两个日期之间的天数差
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        try {
            val d1 = sdf.parse(lastClear)
            val d2 = sdf.parse(today)
            val diff = d2.time - d1.time
            val days = diff / (1000 * 60 * 60 * 24)
            return days >= 7 // 7天或更久未清理
        } catch (e: Exception) {
            return true
        }
    }

    /**
     * 标记今天已清理日志
     */
    fun markLogClearedToday() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        setLastLogClearDate(today)
    }

    // ==================== 节假日相关 ====================

    /**
     * 获取是否在节假日隐藏课程
     */
    fun isHideHolidayCourses(): Boolean {
        return sharedPreferences.getBoolean(KEY_HIDE_HOLIDAY_COURSES, DEFAULT_HIDE_HOLIDAY_COURSES)
    }

    /**
     * 设置是否在节假日隐藏课程
     */
    fun setHideHolidayCourses(hide: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_HIDE_HOLIDAY_COURSES, hide).apply()
    }
}
