package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.cherry.wakeupschedule.BuildConfig
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
        private const val KEY_CURRENT_SEMESTER_INDEX = "current_semester_index"
        private const val KEY_DEFAULT_WEEK = "default_week"                    // 默认显示周
        private const val KEY_AUTO_SWITCH_WEEK = "auto_switch_week"           // 是否自动切换周
        private const val KEY_THEME = "theme"                                  // 主题设置
        private const val KEY_FONT_SIZE = "font_size"                          // 字体大小
        private const val KEY_CUSTOM_BACKGROUND_PATH = "custom_background_path"// 自定义背景图片路径
        private const val KEY_COURSE_CARD_ALPHA = "course_card_alpha"         // 课程卡片透明度
        private const val KEY_SHOW_NON_CURRENT_WEEK_COURSES = "show_non_current_week_courses" // 是否显示非本周课程
        private const val KEY_NON_CURRENT_WEEK_ALPHA = "non_current_week_alpha"// 非本周课程透明度
        private const val KEY_VIEW_MODE = "view_mode"                          // 视图模式（周视图/日视图）
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"        // 上次检查更新日期
        private const val KEY_SKIPPED_UPDATE_VERSION = "skipped_update_version"      // 跳过的更新版本(versionCode)，-1 表示无
        private const val KEY_LAST_SEEN_LATEST_VERSION = "last_seen_latest_version"  // 最近一次检查到的服务器最新版本(versionCode)，-1 表示无
        private const val KEY_FLOAT_BUTTON_X = "float_button_x"             // 悬浮球X位置
        private const val KEY_FLOAT_BUTTON_Y = "float_button_y"             // 悬浮球Y位置
        private const val KEY_VIEW_STATE = "view_state"                     // 视图状态（week/day/overview）
        private const val KEY_ENABLE_UPDATE_REMIND = "enable_update_remind"  // 是否允许更新提醒
        private const val KEY_HIDE_HOLIDAY_COURSES = "hide_holiday_courses" // 是否在节假日隐藏课程
        private const val KEY_THEME_MODE = "theme_mode"                // 主题模式: light/dark/system
        private const val KEY_AUTO_SWITCH_THEME = "auto_switch_theme"  // 是否自动切换深浅色
        private const val KEY_AUTO_SWITCH_MODE = "auto_switch_mode"    // 自动切换方式: system/custom
        private const val KEY_DARK_TIME = "theme_dark_time"            // 深色开始时间 HH:mm
        private const val KEY_LIGHT_TIME = "theme_light_time"          // 浅色开始时间 HH:mm
        private const val DEFAULT_HIDE_HOLIDAY_COURSES = false              // 默认不隐藏
        private const val DEFAULT_THEME_MODE = "system"                  // 默认跟随系统
        private const val DEFAULT_AUTO_SWITCH_THEME = false              // 默认不自动切换
        private const val DEFAULT_AUTO_SWITCH_MODE = "system"            // 默认跟随系统
        private const val DEFAULT_DARK_TIME = "18:00"                    // 默认深色时间
        private const val DEFAULT_LIGHT_TIME = "07:00"                   // 默认浅色时间
        private const val DEFAULT_ENABLE_UPDATE_REMIND = true              // 默认开启更新提醒
        private const val DEFAULT_WEEK = 1                                     // 默认第1周
        private const val DEFAULT_AUTO_SWITCH = true                           // 默认自动切换
        private const val DEFAULT_THEME = "light"                              // 默认浅色主题
        private const val DEFAULT_FONT_SIZE = "normal"                         // 默认字体大小
        private const val DEFAULT_COURSE_CARD_ALPHA = 0.85f                    // 默认卡片透明度85%
        private const val DEFAULT_SHOW_NON_CURRENT_WEEK_COURSES = true         // 默认显示非本周课程
        private const val DEFAULT_NON_CURRENT_WEEK_ALPHA = 0.3f                // 非本周课程默认30%透明度
    }

    // ==================== 学期相关 ====================

    fun getCurrentSemester(): String {
        val entity = SemesterManager.getCurrent()
        return entity?.label ?: "未设置"
    }

    fun getCurrentSemesterFullName(): String {
        val entity = SemesterManager.getCurrent()
        return if (entity != null) "${entity.academicYear}学年 ${entity.termName}" else "未设置"
    }

    fun getCurrentSemesterIndex(): Int {
        return sharedPreferences.getInt(KEY_CURRENT_SEMESTER_INDEX, -1)
    }

    fun setCurrentSemesterIndex(index: Int) {
        sharedPreferences.edit().putInt(KEY_CURRENT_SEMESTER_INDEX, index).apply()
        SemesterManager.setCurrentIndex(index)
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
     * 获取学期开始日期
     * @return 学期开始日期的毫秒时间戳，0表示未设置
     */
    fun getSemesterStartDate(): Long = SemesterManager.getStartDate()

    /**
     * 设置学期开始日期
     * @param dateMillis 学期开始日期的毫秒时间戳
     */
    fun setSemesterStartDate(dateMillis: Long) {
        val idx = getCurrentSemesterIndex()
        if (idx >= 0) {
            val totalWeeks = getTotalWeeks()
            SemesterManager.updateDatesSync(idx, dateMillis, totalWeeks)
        }
    }

    /** 获取学期总周数 */
    fun getTotalWeeks(): Int = SemesterManager.getTotalWeeks()

    /** 设置学期总周数 */
    fun setTotalWeeks(weeks: Int) {
        val idx = getCurrentSemesterIndex()
        if (idx >= 0) {
            val startDate = getSemesterStartDate()
            SemesterManager.updateDatesSync(idx, startDate, weeks)
        }
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

    /**
     * 获取用户跳过的更新版本号（versionCode），-1 表示没有跳过任何版本
     */
    fun getSkippedUpdateVersionCode(): Long =
        sharedPreferences.getLong(KEY_SKIPPED_UPDATE_VERSION, -1L)

    /**
     * 记录用户跳过的更新版本号（versionCode）
     */
    fun setSkippedUpdateVersionCode(versionCode: Long) {
        sharedPreferences.edit().putLong(KEY_SKIPPED_UPDATE_VERSION, versionCode).apply()
    }

    /**
     * 获取最近一次检查到的服务器最新版本号（versionCode），-1 表示从未检查到更新
     */
    fun getLastSeenLatestVersionCode(): Long =
        sharedPreferences.getLong(KEY_LAST_SEEN_LATEST_VERSION, -1L)

    /**
     * 记录最近一次检查到的服务器最新版本号（versionCode）
     */
    fun setLastSeenLatestVersionCode(versionCode: Long) {
        sharedPreferences.edit().putLong(KEY_LAST_SEEN_LATEST_VERSION, versionCode).apply()
    }

    /**
     * 是否有未处理的新版本更新提示（红色小圆点）。
     * 条件：检查到的最新版本高于当前安装版本（"跳过此版本"只抑制每日静默提醒，不影响红点）；
     * 安装新版本后当前版本号追平记录值，红点自然熄灭。
     */
    fun hasNewUpdateHint(): Boolean =
        getLastSeenLatestVersionCode() > BuildConfig.VERSION_CODE

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

    // ==================== 主题模式相关 ====================

    fun getThemeMode(): String {
        return sharedPreferences.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
    }

    fun setThemeMode(mode: String) {
        sharedPreferences.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    // ==================== 深浅色自动切换相关 ====================

    /**
     * 是否开启深浅色自动切换
     */
    fun isAutoSwitchTheme(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTO_SWITCH_THEME, DEFAULT_AUTO_SWITCH_THEME)
    }

    /**
     * 设置是否开启深浅色自动切换
     */
    fun setAutoSwitchTheme(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_AUTO_SWITCH_THEME, enabled).apply()
    }

    /**
     * 获取自动切换方式
     * @return "system"跟随系统 / "custom"自定时间
     */
    fun getAutoSwitchMode(): String {
        return sharedPreferences.getString(KEY_AUTO_SWITCH_MODE, DEFAULT_AUTO_SWITCH_MODE) ?: DEFAULT_AUTO_SWITCH_MODE
    }

    /**
     * 设置自动切换方式
     * @param mode "system"或"custom"
     */
    fun setAutoSwitchMode(mode: String) {
        sharedPreferences.edit().putString(KEY_AUTO_SWITCH_MODE, mode).apply()
    }

    /**
     * 获取深色开始时间
     * @return "HH:mm"格式字符串
     */
    fun getDarkTime(): String {
        return sharedPreferences.getString(KEY_DARK_TIME, DEFAULT_DARK_TIME) ?: DEFAULT_DARK_TIME
    }

    /**
     * 设置深色开始时间
     * @param time "HH:mm"格式字符串
     */
    fun setDarkTime(time: String) {
        sharedPreferences.edit().putString(KEY_DARK_TIME, time).apply()
    }

    /**
     * 获取浅色开始时间
     * @return "HH:mm"格式字符串
     */
    fun getLightTime(): String {
        return sharedPreferences.getString(KEY_LIGHT_TIME, DEFAULT_LIGHT_TIME) ?: DEFAULT_LIGHT_TIME
    }

    /**
     * 设置浅色开始时间
     * @param time "HH:mm"格式字符串
     */
    fun setLightTime(time: String) {
        sharedPreferences.edit().putString(KEY_LIGHT_TIME, time).apply()
    }
}
