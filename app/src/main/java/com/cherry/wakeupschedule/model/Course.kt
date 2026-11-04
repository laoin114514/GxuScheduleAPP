package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * 课程数据类
 * Room 实体 + Serializable（用于 Intent 传递）
 */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,                        // 课程唯一ID

    @ColumnInfo(name = "name")
    val name: String,                         // 课程名称

    @ColumnInfo(name = "teacher")
    val teacher: String,                      // 任课教师

    @ColumnInfo(name = "classroom")
    val classroom: String,                     // 上课地点

    @ColumnInfo(name = "day_of_week")
    val dayOfWeek: Int,                       // 星期几（1-7，周一到周日）

    @ColumnInfo(name = "start_time")
    val startTime: Int,                       // 开始节次

    @ColumnInfo(name = "end_time")
    val endTime: Int,                         // 结束节次

    @ColumnInfo(name = "week_bitmap", defaultValue = "0")
    val weekBitmap: Long = 0,

    @ColumnInfo(name = "course_category", defaultValue = "")
    val courseCategory: String = "",

    @ColumnInfo(name = "credits", defaultValue = "")
    val credits: String = "",                    // 学分

    @ColumnInfo(name = "qq_group", defaultValue = "")
    val qqGroup: String = "",                    // QQ群号

    // ── 教务返回、供总课表详情展示的补充信息 ──

    @ColumnInfo(name = "is_practice", defaultValue = "0")
    val isPractice: Boolean = false,          // 是否实践课（教务 sfsjk）

    @ColumnInfo(name = "is_adjusted", defaultValue = "0")
    val isAdjusted: Boolean = false,          // 本行是否有被调课的周次（v2 语义：= adjustWeeksBitmap != 0）

    @ColumnInfo(name = "adjust_weeks", defaultValue = "0")
    val adjustWeeksBitmap: Long = 0,          // 被调课的周次位图（bit0=第1周，语义同 weekBitmap；由调课记录精确匹配）

    @ColumnInfo(name = "course_code", defaultValue = "")
    val courseCode: String = "",               // 课程代码（kch）

    @ColumnInfo(name = "course_nature", defaultValue = "")
    val courseNature: String = "",             // 课程性质（kcxz，如 学类/通识）

    @ColumnInfo(name = "teaching_class", defaultValue = "")
    val teachingClass: String = "",            // 教学班（jxbmc）

    @ColumnInfo(name = "class_composition", defaultValue = "")
    val classComposition: String = "",         // 教学班组成（jxbzc）

    @ColumnInfo(name = "total_hours", defaultValue = "")
    val totalHours: String = "",               // 总学时（kczxs）

    @ColumnInfo(name = "hour_composition", defaultValue = "")
    val hourComposition: String = "",          // 学时组成（kcxszc，如 理论:56,实验:16）

    @ColumnInfo(name = "classroom_type", defaultValue = "")
    val classroomType: String = "",            // 教室类别（cdlbmc，如 机房/体育场地）

    @ColumnInfo(name = "assessment_method", defaultValue = "")
    val assessmentMethod: String = "",         // 考核方式（khfsmc，考试/考查）

    @ColumnInfo(name = "exam_form", defaultValue = "")
    val examForm: String = "",                 // 考试形式（ksfsmc）

    @ColumnInfo(name = "practice_detail", defaultValue = "")
    val practiceDetail: String = "",           // 实践课描述（sjkcgs）

    @ColumnInfo(name = "enrolled", defaultValue = "")
    val enrolled: String = "",                 // 选课人数 / 已选（教务 xkrs）

    @ColumnInfo(name = "max_students", defaultValue = "")
    val maxStudents: String = "",              // 课程容量（教务 zzrl）

    @ColumnInfo(name = "alarm_enabled", defaultValue = "1")
    val alarmEnabled: Boolean = true,         // 是否启用闹钟提醒

    @ColumnInfo(name = "alarm_minutes_before", defaultValue = "15")
    val alarmMinutesBefore: Int = 15,          // 提前提醒分钟数

    @ColumnInfo(name = "color", defaultValue = "0")
    val color: Int = 0,                       // 课程颜色，0为默认颜色

    @ColumnInfo(name = "cover_image_path", defaultValue = "")
    val coverImagePath: String = "",           // 课程封面图片路径

    @ColumnInfo(name = "semester_id", defaultValue = "0")
    val semesterId: Long = 0                   // 所属学期ID，外键关联 semesters 表
) : Serializable {
    /**
     * 判断课程在指定周是否有课（位图 bit 0 = 第 1 周）
     */
    fun isActiveInWeek(week: Int): Boolean {
        if (week < 1 || week > 64) return false
        return (weekBitmap shr (week - 1)) and 1L == 1L
    }

    /**
     * 判断课程在指定周是否「被调课」——只标记具体课次/周次，而不是整门课。
     *
     * 来源：教务调课记录按教学班匹配到本行后，把命中的周次并成 [adjustWeeksBitmap]。
     * 同一门课的其他周、以及同教学班的其他课表行不会被误标。
     */
    fun isAdjustedInWeek(week: Int): Boolean {
        if (week < 1 || week > 64) return false
        return (adjustWeeksBitmap shr (week - 1)) and 1L == 1L
    }

    /**
     * 是否有固定上课时间（星期 + 节次）。
     *
     * 教务实践课（sjkList）只有课程名/教师/周次，没有星期与节次，
     * 以 [NO_FIXED_DAY] / [NO_FIXED_SLOT] 占位：放不进周课表网格，只在总课表列出。
     */
    fun hasFixedTime(): Boolean = dayOfWeek in 1..7 && startTime >= 1

    companion object {
        /** 「无固定时间」课程（实践课）的星期占位值 */
        const val NO_FIXED_DAY = 0

        /** 「无固定时间」课程（实践课）的节次占位值 */
        const val NO_FIXED_SLOT = 0

        /** 从 startWeek/endWeek/weekType 生成位图（迁移用） */
        fun bitmapFromRange(startWeek: Int, endWeek: Int, weekType: Int): Long {
            var bitmap = 0L
            for (w in startWeek..endWeek) {
                val include = when (weekType) {
                    1 -> w % 2 == 1  // 单周
                    2 -> w % 2 == 0  // 双周
                    else -> true     // 每周
                }
                if (include && w in 1..64) {
                    bitmap = bitmap or (1L shl (w - 1))
                }
            }
            return bitmap
        }

        /**
         * 按课程名（trim 后）去重后的门数。
         *
         * 与课表页「总课表」的归并口径一致：同名课程（不同教师/教室/时段）算一门。
         * 用于导入成功提示等「N 门课程」文案，避免按上课时段条数报到几十门。
         */
        fun distinctNameCount(courses: List<Course>): Int =
            courses.map { it.name.trim() }.distinct().size

        /** 从连续范围生成位图（每周模式） */
        fun bitmapFromRange(startWeek: Int, endWeek: Int): Long =
            bitmapFromRange(startWeek, endWeek, 0)

        /** 获取位图中激活的周列表 */
        fun bitmapToWeekList(bitmap: Long): List<Int> {
            val result = mutableListOf<Int>()
            for (w in 1..64) {
                if ((bitmap shr (w - 1)) and 1L == 1L) result.add(w)
            }
            return result
        }

        /** 获取位图的首尾周（用于显示 "第X-Y周"） */
        fun bitmapToWeekRange(bitmap: Long): Pair<Int, Int>? {
            val list = bitmapToWeekList(bitmap)
            if (list.isEmpty()) return null
            return Pair(list.first(), list.last())
        }
    }
}
