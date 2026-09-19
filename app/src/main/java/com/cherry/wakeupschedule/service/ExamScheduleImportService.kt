package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.ExamScheduleEntity
import com.cherry.wakeupschedule.model.SemesterEntity
import com.gxu.jwxt.model.ExamScheduleEntry
import com.gxu.jwxt.model.PageQuery
import com.gxu.jwxt.model.Term

/**
 * 考试安排导入：从教务系统拉取指定学期的考试并覆盖写入本地数据库。
 *
 * 与 [GradeImportService] 同构，只取「已排考」的考试列表
 * （[com.gxu.jwxt.module.ExamModule.schedules]）；「无排考课程」是没有考试时间的
 * 课程清单，不属于考试安排，不导入。
 */
object ExamScheduleImportService {

    /** 单次最多取 500 条（教务单学期考试远少于此） */
    private const val PAGE_SIZE = 500

    /**
     * 拉取并保存指定学期的考试安排。
     *
     * @return 成功时返回条数；失败时返回异常
     */
    suspend fun fetchAndSaveExamsForSemester(
        context: Context,
        semester: SemesterEntity
    ): Result<Int> {
        // 学年/学期直接用实体字段，避免解析展示文案的字符串
        val year = semester.academicYear.substringBefore("-")
        val termCode = semester.termCode
        val term = Term.fromCode(termCode) ?: Term.SPRING

        val result = JwxtAuthManager.doWithAuth { client ->
            client.exams().schedules(year, term, PageQuery(1, PAGE_SIZE)).items
        }

        return result.map { entries ->
            val now = System.currentTimeMillis()
            val exams = entries.map { it.toEntity(semester.id, now) }
            ExamScheduleDataManager.getInstance(context)
                .replaceExamsForSemester(exams, semester.id)
            exams.size
        }
    }

    private fun ExamScheduleEntry.toEntity(semesterId: Long, now: Long) = ExamScheduleEntity(
        semesterId = semesterId,
        courseName = courseName.orEmpty(),
        courseCode = courseCode.orEmpty(),
        examName = examName.orEmpty(),
        examTime = examTime.orEmpty(),
        classroom = classroom.orEmpty(),
        classroomCode = classroomCode.orEmpty(),
        locations = locations.orEmpty(),
        assessmentMethod = assessmentMethod.orEmpty(),
        teachingClassName = teachingClassName.orEmpty(),
        teacher = teacher.orEmpty(),
        credits = credits.orEmpty(),
        schoolYear = schoolYear.orEmpty(),
        term = term.orEmpty(),
        paperId = paperId.orEmpty(),
        updatedAt = now
    )
}
