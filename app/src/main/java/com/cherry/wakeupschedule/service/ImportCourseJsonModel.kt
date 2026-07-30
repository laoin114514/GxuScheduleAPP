package com.cherry.wakeupschedule.service

import com.cherry.wakeupschedule.model.Course
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * 版本化课程导入 JSON 协议
 *
 * 借鉴拾光课表 COURSE_SCHEMA_VERSION 的设计：
 * - 版本化管理，向前兼容（ignoreUnknownKeys）
 * - 支持批量导入、课程配置、时间段配置
 * - 丰富的自定义字段（颜色、备注、提醒时间等）
 */
object ImportCourseProtocol {

    /** 当前协议版本 */
    const val SCHEMA_VERSION = 1

    private val gson = Gson()

    /**
     * 课程导入数据包 — 顶层协议
     */
    data class CourseImportPackage(
        @SerializedName("schema_version")
        val schemaVersion: Int = SCHEMA_VERSION,

        @SerializedName("export_time")
        val exportTime: String = "",

        @SerializedName("source")
        val source: String = "",

        @SerializedName("courses")
        val courses: List<ImportCourseModel> = emptyList(),

        @SerializedName("time_slots")
        val timeSlots: List<ImportTimeSlotModel> = emptyList(),

        @SerializedName("config")
        val config: CourseImportConfig? = null
    ) : Serializable

    /**
     * 单门课程导入模型
     * 忽略未知字段确保向前兼容
     */
    data class ImportCourseModel(
        @SerializedName("name")
        val name: String,

        @SerializedName("teacher")
        val teacher: String = "",

        @SerializedName("classroom")
        val classroom: String = "",

        @SerializedName("location")
        val location: String = "",  // classroom 的别名，兼容不同教务系统

        @SerializedName("day_of_week")
        val dayOfWeek: Int = 1,

        @SerializedName("day")
        val day: Int? = null,  // day_of_week 的别名

        @SerializedName("start_time")
        val startTime: Int = 1,

        @SerializedName("end_time")
        val endTime: Int = 2,

        @SerializedName("start_week")
        val startWeek: Int = 1,

        @SerializedName("end_week")
        val endWeek: Int = 16,

        @SerializedName("week_type")
        val weekType: Any? = 0,  // Int(0/1/2) 或 String("odd"/"even"/"all"/"单周"/"双周")

        @SerializedName("weeks")
        val weeks: List<Int>? = null,  // 明确的周列表（优先级高于 startWeek/endWeek）

        @SerializedName("color")
        val color: Int = 0,

        @SerializedName("notes")
        val notes: String = "",

        @SerializedName("alarm_enabled")
        val alarmEnabled: Boolean = true,

        @SerializedName("alarm_minutes_before")
        val alarmMinutesBefore: Int = 15,

        @SerializedName("cover_image_path")
        val coverImagePath: String = ""
    ) : Serializable

    /**
     * 时间段配置模型
     */
    data class ImportTimeSlotModel(
        @SerializedName("node")
        val node: Int,  // 节次序号

        @SerializedName("name")
        val name: String = "",

        @SerializedName("start_time")
        val startTime: String,  // 格式: "HH:mm"

        @SerializedName("end_time")
        val endTime: String,    // 格式: "HH:mm"

        @SerializedName("duration")
        val duration: Int = 45  // 分钟
    ) : Serializable

    /**
     * 课程配置模型
     */
    data class CourseImportConfig(
        @SerializedName("semester_start_date")
        val semesterStartDate: String? = null,  // 格式: "yyyy-MM-dd"

        @SerializedName("total_weeks")
        val totalWeeks: Int = 20,

        @SerializedName("show_weekends")
        val showWeekends: Boolean = false,

        @SerializedName("first_day_of_week")
        val firstDayOfWeek: Int = 1  // 1=周一, 7=周日
    ) : Serializable

    // ── 工具方法 ──

    /**
     * 解析 weekType 字段（兼容 Int / String 及 null）
     */
    private fun parseWeekType(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt().coerceIn(0, 2)
            is String -> when (value.lowercase()) {
                "odd", "单", "单周", "1" -> 1
                "even", "双", "双周", "2" -> 2
                "all", "全", "每周", "0" -> 0
                else -> value.toIntOrNull()?.coerceIn(0, 2) ?: 0
            }
            else -> 0
        }
    }

    /**
     * 将导入模型转换为 Course 数据类
     */
    fun ImportCourseModel.toCourse(baseId: Long = 0): Course {
        val effectiveDayOfWeek = day ?: dayOfWeek
        val effectiveLocation = location.ifBlank { classroom }

        // 处理 weeks 列表优先级高于 startWeek/endWeek
        val (effectiveStartWeek, effectiveEndWeek) = if (!weeks.isNullOrEmpty()) {
            weeks.min() to weeks.max()
        } else {
            startWeek to endWeek
        }

        // 先解析原始 weekType（字符串 / 整数 → 统一整数）
        val rawWeekType = parseWeekType(weekType)

        // 根据 weeks 列表自动推断 weekType（优先级高于原始值）
        val effectiveWeekType = if (!weeks.isNullOrEmpty()) {
            when {
                weeks.all { it % 2 == 1 } -> 1  // 全单周
                weeks.all { it % 2 == 0 } -> 2  // 全双周
                else -> rawWeekType  // 混合 → 保留原始值
            }
        } else {
            rawWeekType
        }

        return Course(
            id = baseId,
            name = name.trim(),
            teacher = teacher.trim(),
            classroom = effectiveLocation.trim(),
            dayOfWeek = effectiveDayOfWeek.coerceIn(1, 7),
            startTime = startTime.coerceIn(1, 14),
            endTime = endTime.coerceIn(1, 14),
            weekBitmap = Course.bitmapFromRange(
                effectiveStartWeek.coerceIn(1, 30),
                effectiveEndWeek.coerceIn(1, 30),
                effectiveWeekType
            ),
            alarmEnabled = alarmEnabled,
            alarmMinutesBefore = alarmMinutesBefore.coerceIn(0, 60),
            color = color,
            coverImagePath = coverImagePath
        )
    }

    /**
     * 解析 JSON 字符串为导入包
     */
    fun parse(json: String): CourseImportPackage? {
        return try {
            gson.fromJson(json, CourseImportPackage::class.java)
        } catch (e: Exception) {
            android.util.Log.e("ImportCourseProtocol", "JSON 解析失败", e)
            null
        }
    }

    /**
     * 将课程列表序列化为 JSON（导出用）
     */
    fun serialize(courses: List<Course>, source: String = "Schedule"): String {
        val importCourses = courses.map { course ->
            val weekList = Course.bitmapToWeekList(course.weekBitmap)
            ImportCourseModel(
                name = course.name,
                teacher = course.teacher,
                classroom = course.classroom,
                dayOfWeek = course.dayOfWeek,
                startTime = course.startTime,
                endTime = course.endTime,
                startWeek = weekList.firstOrNull() ?: 1,
                endWeek = weekList.lastOrNull() ?: 16,
                weeks = weekList,
                color = course.color,
                alarmEnabled = course.alarmEnabled,
                alarmMinutesBefore = course.alarmMinutesBefore,
                coverImagePath = course.coverImagePath
            )
        }
        val package_ = CourseImportPackage(
            schemaVersion = SCHEMA_VERSION,
            exportTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date()),
            source = source,
            courses = importCourses
        )
        return gson.toJson(package_)
    }
}
