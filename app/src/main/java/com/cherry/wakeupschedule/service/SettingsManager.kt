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
        private const val KEY_BACKGROUND_TYPE = "background_type"              // 背景类型
        private const val KEY_FONT_SIZE = "font_size"                          // 字体大小
        private const val KEY_ALARM_ENABLED = "alarm_enabled"                  // 闹钟是否启用
        private const val KEY_SEMESTER_START_DATE = "semester_start_date"      // 学期开始日期
        private const val KEY_TOTAL_WEEKS = "total_weeks"                     // 学期总周数
        private const val KEY_CUSTOM_BACKGROUND_PATH = "custom_background_path"// 自定义背景图片路径
        private const val KEY_SOLID_BACKGROUND_COLOR = "solid_background_color"// 纯色背景颜色
        private const val KEY_COURSE_CARD_ALPHA = "course_card_alpha"         // 课程卡片透明度
        private const val KEY_SHOW_NON_CURRENT_WEEK_COURSES = "show_non_current_week_courses" // 是否显示非本周课程
        private const val KEY_NON_CURRENT_WEEK_ALPHA = "non_current_week_alpha"// 非本周课程透明度
        private const val KEY_VIEW_MODE = "view_mode"                          // 视图模式（周视图/日视图）
        private const val KEY_CUSTOM_SEMESTERS = "custom_semesters"            // 自定义学期列表
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"        // 上次检查更新日期
        private const val KEY_COURSE_COLOR_THEME = "course_color_theme"     // 卡片配色主题索引
        private const val KEY_BACKGROUND_THEME = "background_theme"         // 背景主题索引
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
        private const val DEFAULT_BACKGROUND_TYPE = "default"                  // 默认背景类型
        private const val DEFAULT_FONT_SIZE = "normal"                         // 默认字体大小
        private const val DEFAULT_ALARM_ENABLED = true                         // 默认启用闹钟
        private const val DEFAULT_SOLID_COLOR = -1                             // 默认白色背景
        private const val DEFAULT_COURSE_CARD_ALPHA = 0.85f                    // 默认卡片透明度85%
        private const val DEFAULT_SHOW_NON_CURRENT_WEEK_COURSES = true         // 默认显示非本周课程
        private const val DEFAULT_NON_CURRENT_WEEK_ALPHA = 0.3f                // 非本周课程默认30%透明度
        private const val DEFAULT_COLOR_THEME = 1                              // 默认莫兰迪低灰
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
     * 获取背景类型（旧版兼容）
     */
    fun getBackgroundTypeString(): String {
        return sharedPreferences.getString(KEY_BACKGROUND_TYPE, DEFAULT_BACKGROUND_TYPE) ?: DEFAULT_BACKGROUND_TYPE
    }

    /**
     * 设置背景类型（旧版兼容）
     */
    fun setBackgroundTypeString(backgroundType: String) {
        sharedPreferences.edit().putString(KEY_BACKGROUND_TYPE, backgroundType).apply()
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
     * 获取纯色背景颜色
     * @return 颜色值（-1表示白色）
     */
    fun getSolidBackgroundColor(): Int {
        return sharedPreferences.getInt(KEY_SOLID_BACKGROUND_COLOR, DEFAULT_SOLID_COLOR)
    }

    /**
     * 设置纯色背景颜色
     * @param color 颜色值
     */
    fun setSolidBackgroundColor(color: Int) {
        sharedPreferences.edit().putInt(KEY_SOLID_BACKGROUND_COLOR, color).apply()
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

    // ==================== 卡片配色主题相关 ====================

    data class ColorTheme(val name: String, val colors: IntArray, val textColor: Int, val lightTextColor: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ColorTheme
            return name == other.name && colors.contentEquals(other.colors)
        }
        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + colors.contentHashCode()
            return result
        }
    }

    val colorThemes: List<ColorTheme> = listOf(
        ColorTheme(
            "清新马卡龙", intArrayOf(
                0xFFF8BBD0.toInt(), 0xFFFFCCBC.toInt(), 0xFFFFF59D.toInt(),
                0xFFA5D6A7.toInt(), 0xFF90CAF9.toInt(), 0xFFCE93D8.toInt(),
                0xFF80CBC4.toInt(), 0xFFBCAAA4.toInt(), 0xFFFFCDD2.toInt(),
                0xFFBBDEFB.toInt(), 0xFFC8E6C9.toInt(), 0xFFFFE0B2.toInt(),
                0xFFDCEDC8.toInt(), 0xFFFFF9C4.toInt(), 0xFFFFE082.toInt(),
                0xFFCFD8DC.toInt()
            ), 0xFF333333.toInt(), 0xFFFFFFFF.toInt()
        ),
        ColorTheme(
            "莫兰迪低灰", intArrayOf(
                0xFFD8C3A5.toInt(), 0xFFE0BEA2.toInt(), 0xFFA8B5A0.toInt(),
                0xFF8FA3AD.toInt(), 0xFF9D8189.toInt(), 0xFF7D9290.toInt(),
                0xFFC9ADA7.toInt(), 0xFFB4C7DC.toInt(), 0xFFB7C8C4.toInt(),
                0xFFD1C4E9.toInt(), 0xFFC5CAE9.toInt(), 0xFFD7CCC8.toInt(),
                0xFFCFD8DC.toInt(), 0xFFB0BEC5.toInt(), 0xFFE0E0E0.toInt(),
                0xFF90A4AE.toInt()
            ), 0xFF2C3E50.toInt(), 0xFF2C3E50.toInt()
        ),
        ColorTheme(
            "校园标准正色", intArrayOf(
                0xFFE55555.toInt(), 0xFF3478DB.toInt(), 0xFF27AE60.toInt(),
                0xFF9B59B6.toInt(), 0xFFE67E22.toInt(), 0xFFF1C40F.toInt(),
                0xFF1ABC9C.toInt(), 0xFF607D8B.toInt(), 0xFF00BCD4.toInt(),
                0xFFFF9800.toInt(), 0xFF03A9F4.toInt(), 0xFF4CAF50.toInt(),
                0xFFE91E63.toInt(), 0xFF673AB7.toInt(), 0xFFFFEB3B.toInt(),
                0xFF795548.toInt()
            ), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()
        ),
        ColorTheme(
            "冷淡极简高级", intArrayOf(
                0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFF78909C.toInt(),
                0xFFAB47BC.toInt(), 0xFFEF5350.toInt(), 0xFFFFA726.toInt(),
                0xFF66BB6A.toInt(), 0xFF8D6E63.toInt(), 0xFF42A5F5.toInt(),
                0xFFFF7043.toInt(), 0xFF26C6DA.toInt(), 0xFF9CCC65.toInt(),
                0xFF7E57C2.toInt(), 0xFFFFCA28.toInt(), 0xFF546E7A.toInt(),
                0xFFEC407A.toInt()
            ), 0xFF1A1A1A.toInt(), 0xFFFFFFFF.toInt()
        ),
        ColorTheme(
            "春日治愈温柔", intArrayOf(
                0xFFFFE6EC.toInt(), 0xFFFFF0E6.toInt(), 0xFFF9F8E6.toInt(),
                0xFFE6F4EA.toInt(), 0xFFE6F0FF.toInt(), 0xFFF0E6FF.toInt(),
                0xFFE6F8F5.toInt(), 0xFFF2EBE6.toInt(), 0xFFFFF0F4.toInt(),
                0xFFECF5FF.toInt(), 0xFFF0FFF4.toInt(), 0xFFFFF4E6.toInt(),
                0xFFFAF0FF.toInt(), 0xFFE6F9FF.toInt(), 0xFFF0FFF0.toInt(),
                0xFFFFF0F0.toInt()
            ), 0xFF444444.toInt(), 0xFF444444.toInt()
        ),
        ColorTheme(
            "暗色模式专属", intArrayOf(
                0xFFB71C1C.toInt(), 0xFF0D47A1.toInt(), 0xFF1B5E20.toInt(),
                0xFF4A148C.toInt(), 0xFFE65100.toInt(), 0xFF827717.toInt(),
                0xFF00695C.toInt(), 0xFF4E342E.toInt(), 0xFF311B92.toInt(),
                0xFF01579B.toInt(), 0xFF004D40.toInt(), 0xFFBF360C.toInt(),
                0xFF1A237E.toInt(), 0xFF880E4F.toInt(), 0xFF006064.toInt(),
                0xFF3E2723.toInt()
            ), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()
        )
    )

    fun getCourseColorThemeIndex(): Int {
        return sharedPreferences.getInt(KEY_COURSE_COLOR_THEME, DEFAULT_COLOR_THEME)
    }

    fun setCourseColorThemeIndex(index: Int) {
        sharedPreferences.edit().putInt(KEY_COURSE_COLOR_THEME, index.coerceIn(0, colorThemes.size - 1)).apply()
    }

    fun getCurrentColorTheme(): ColorTheme {
        return colorThemes.getOrElse(getCourseColorThemeIndex()) { colorThemes[0] }
    }

    fun getCourseColors(): IntArray {
        return getCurrentColorTheme().colors
    }

    fun getCourseTextColor(): Int {
        return getCurrentColorTheme().textColor
    }

    fun getCourseLightTextColor(): Int {
        return getCurrentColorTheme().lightTextColor
    }

    // ==================== 背景主题相关 ====================

    enum class BackgroundType {
        SOLID,      // 纯色/主题色背景
        FROSTED,    // 磨砂透明背景
        IMAGE       // 自定义图片背景
    }

    data class BackgroundTheme(
        val name: String,
        val type: BackgroundType,
        val color: Int,        // ARGB 颜色值，磨砂透明背景使用包含透明度的 ARGB
        val isLight: Boolean   // 是否为浅色背景（决定卡片文字颜色）
    )

    val backgroundThemes: List<BackgroundTheme> = listOf(
        // ==================== 浅色模式 ====================
        // 温柔护眼款
        BackgroundTheme("纯白偏灰", BackgroundType.SOLID, 0xFFF8F9FA.toInt(), true),       // #F8F9FA
        BackgroundTheme("淡冷奶白", BackgroundType.SOLID, 0xFFF5F7FB.toInt(), true),       // #F5F7FB
        BackgroundTheme("米杏底色", BackgroundType.SOLID, 0xFFF6F5F1.toInt(), true),       // #F6F5F1
        // 极简高级款
        BackgroundTheme("浅雾灰", BackgroundType.SOLID, 0xFFEFF1F5.toInt(), true),         // #EFF1F5
        BackgroundTheme("社交浅灰", BackgroundType.SOLID, 0xFFF0F2F5.toInt(), true),        // #F0F2F5
        // 低饱和淡彩底
        BackgroundTheme("极浅蓝底", BackgroundType.SOLID, 0xFFF5F7FF.toInt(), true),       // #F5F7FF
        BackgroundTheme("极浅绿底", BackgroundType.SOLID, 0xFFF5FBF7.toInt(), true),       // #F5FBF7
        BackgroundTheme("极浅粉底", BackgroundType.SOLID, 0xFFFBF5F8.toInt(), true),       // #FBF5F8

        // ==================== 深色模式 ====================
        BackgroundTheme("暗紫灰", BackgroundType.SOLID, 0xFF1E1E2E.toInt(), false),      // #1E1E2E
        BackgroundTheme("谷歌深灰", BackgroundType.SOLID, 0xFF202124.toInt(), false),       // #202124
        BackgroundTheme("藏青暗底", BackgroundType.SOLID, 0xFF2C2C3A.toInt(), false),      // #2C2C3A
        BackgroundTheme("冷调深空灰", BackgroundType.SOLID, 0xFF1A1D25.toInt(), false),  // #1A1D25

        // ==================== 磨砂透明风 ====================
        BackgroundTheme("浅透磨砂", BackgroundType.FROSTED, 0xD9F8F9FA.toInt(), true),    // rgba(248,249,250,0.85)
        BackgroundTheme("深透磨砂", BackgroundType.FROSTED, 0xCC1E1E2E.toInt(), false),   // rgba(30,30,46,0.8)
    )

    fun getBackgroundThemeIndex(): Int {
        return sharedPreferences.getInt(KEY_BACKGROUND_THEME, 8)
    }

    fun setBackgroundThemeIndex(index: Int) {
        sharedPreferences.edit().putInt(KEY_BACKGROUND_THEME, index.coerceIn(0, backgroundThemes.size - 1)).apply()
    }

    fun getCurrentBackgroundTheme(): BackgroundTheme {
        return backgroundThemes.getOrElse(getBackgroundThemeIndex()) { backgroundThemes[0] }
    }

    fun isBackgroundLight(): Boolean {
        return getCurrentBackgroundTheme().isLight
    }

    /**
     * 获取当前背景模式（图片 vs 颜色主题）
     * IMAGE: 使用自定义图片（KEY=custom）
     * SOLID/FROSTED: 使用颜色主题（KEY=default/solid）
     */
    fun getBackgroundMode(): BackgroundType {
        return when (sharedPreferences.getString(KEY_BACKGROUND_TYPE, DEFAULT_BACKGROUND_TYPE)) {
            "custom" -> BackgroundType.IMAGE
            else -> getCurrentBackgroundTheme().type
        }
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
