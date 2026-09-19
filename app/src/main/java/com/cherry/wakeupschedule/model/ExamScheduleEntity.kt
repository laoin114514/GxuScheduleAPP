package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单条考试安排（对应教务考试信息接口 kscx_cxXsksxxIndex 的一个条目）。
 *
 * [semesterId] 外键关联 [SemesterEntity]，学期被清理时考试安排级联删除
 * ——重新绑定教务会重建学期表，旧考试随之失效，符合预期。
 *
 * 字段一律原样保存教务返回的字符串（与 [GradeEntity] 同风格）：
 * 时间解析、状态判定与排序都放在纯逻辑层 [com.cherry.wakeupschedule.ui.screen.exam.ExamScheduleStats]。
 */
@Entity(
    tableName = "exam_schedules",
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
data class ExamScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "semester_id")
    val semesterId: Long,

    /** 课程名称 kcmc */
    @ColumnInfo(name = "course_name")
    val courseName: String,

    /** 课程号 kch */
    @ColumnInfo(name = "course_code")
    val courseCode: String = "",

    /** 考试名称（场次）ksmc，如「2025-2026学年第二学期本科课程期末考试」 */
    @ColumnInfo(name = "exam_name")
    val examName: String = "",

    /** 考试时间 kssj，如「2026-07-16(15:00-17:00)」，原样存 */
    @ColumnInfo(name = "exam_time")
    val examTime: String = "",

    /**
     * 考试场地 cdmc —— 列表唯一展示的场地来源。
     *
     * 为空时界面显示「地点待定」，**不回退** [locations]（jxdd）。
     */
    @ColumnInfo(name = "classroom")
    val classroom: String = "",

    /** 场地编号 cdbh */
    @ColumnInfo(name = "classroom_code")
    val classroomCode: String = "",

    /** 考试地点 jxdd（可能含多个地点）。入库备用，本页不展示 */
    @ColumnInfo(name = "locations")
    val locations: String = "",

    /** 考核方式 khfs，如「考试」 */
    @ColumnInfo(name = "assessment_method")
    val assessmentMethod: String = "",

    /** 教学班名称 jxbmc */
    @ColumnInfo(name = "teaching_class_name")
    val teachingClassName: String = "",

    /** 教师 jsxx */
    @ColumnInfo(name = "teacher")
    val teacher: String = "",

    /** 学分 xf */
    @ColumnInfo(name = "credits")
    val credits: String = "",

    /** 学年代码 xnm，如「2025」 */
    @ColumnInfo(name = "school_year")
    val schoolYear: String = "",

    /** 学期代码 xqm，「3」=第一学期 / 「12」=第二学期 */
    @ColumnInfo(name = "term")
    val term: String = "",

    /** 试卷编号 sjbh */
    @ColumnInfo(name = "paper_id")
    val paperId: String = "",

    /** 本地写入时间戳（毫秒） */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
