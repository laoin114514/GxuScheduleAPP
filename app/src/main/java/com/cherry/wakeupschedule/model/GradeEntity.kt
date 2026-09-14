package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单条课程成绩（对应教务成绩列表接口的一个条目）。
 *
 * [semesterId] 外键关联 [SemesterEntity]，学期被清理时成绩级联删除
 * ——重新绑定教务会重建学期表，旧成绩随之失效，符合预期。
 */
@Entity(
    tableName = "grades",
    foreignKeys = [
        ForeignKey(
            entity = SemesterEntity::class,
            parentColumns = ["id"],
            childColumns = ["semester_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("semester_id")]
)
data class GradeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "semester_id")
    val semesterId: Long,

    /** 课程名 kcmc */
    @ColumnInfo(name = "course_name")
    val courseName: String,

    /** 教学班 ID jxb_id，查成绩详情用 */
    @ColumnInfo(name = "class_id")
    val classId: String = "",

    /** 学号 ID xh_id（哈希串），查成绩详情用 */
    @ColumnInfo(name = "student_id")
    val studentId: String = "",

    /** 成绩 cj，可能是数值也可能是等级（优/良/中/及格） */
    @ColumnInfo(name = "score")
    val score: String = "",

    /** 百分制成绩 bfzcj */
    @ColumnInfo(name = "percentage_score")
    val percentageScore: String = "",

    /** 绩点 jd */
    @ColumnInfo(name = "grade_point")
    val gradePoint: String = "",

    /** 学分绩点 xfjd（= 学分 × 绩点），用于算平均学分绩点 */
    @ColumnInfo(name = "credit_grade_point")
    val creditGradePoint: String = "",

    /** 学分 xf */
    @ColumnInfo(name = "credits")
    val credits: String = "",

    /** 课程性质 kcxzmc，如「通识必修课」，必修/选修筛选依据 */
    @ColumnInfo(name = "course_nature")
    val courseNature: String = "",

    /** 课程类别 kclbmc */
    @ColumnInfo(name = "course_category")
    val courseCategory: String = "",

    /** 开课类型 kklxdm，如「主修课程」，必修/选修筛选回退依据 */
    @ColumnInfo(name = "course_type")
    val courseType: String = "",

    /** 课程标记 kcbj，如「主修」，必修/选修筛选回退依据 */
    @ColumnInfo(name = "course_mark")
    val courseMark: String = "",

    /** 考核方式 khfsmc，如「考试」「考查」 */
    @ColumnInfo(name = "assessment_method")
    val assessmentMethod: String = "",

    /** 考试性质 ksxz，如「正考」「补考」 */
    @ColumnInfo(name = "exam_nature")
    val examNature: String = "",

    /** 任课教师 jsxm */
    @ColumnInfo(name = "teacher_name")
    val teacherName: String = "",

    /** 学年代码 xnm，如「2024」 */
    @ColumnInfo(name = "school_year")
    val schoolYear: String = "",

    /** 学期代码 xqm，「3」=第一学期 / 「12」=第二学期 */
    @ColumnInfo(name = "term")
    val term: String = "",

    /** 是否作废 cjsfzf（"是"/"否"） */
    @ColumnInfo(name = "score_voided")
    val scoreVoided: String = "",

    /** 成绩录入时间 cjbdsj */
    @ColumnInfo(name = "published_at")
    val publishedAt: String = "",

    /** 本地写入时间戳（毫秒） */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
