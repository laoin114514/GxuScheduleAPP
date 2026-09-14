package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.GradeEntity
import com.cherry.wakeupschedule.model.SemesterEntity
import com.gxu.jwxt.model.GradeEntry
import com.gxu.jwxt.model.PageQuery
import com.gxu.jwxt.model.Term

/**
 * 成绩导入：从教务系统拉取指定学期的成绩并覆盖写入本地数据库。
 *
 * 与 [JwxtImportService] 的课表导入同构，区别是成绩按学期整体替换
 * （见 [GradeDataManager.replaceGradesForSemester]），且不修改学期日期。
 */
object GradeImportService {

    /** 单次最多取 500 条（教务单学期成绩远少于此） */
    private const val PAGE_SIZE = 500

    /**
     * 拉取并保存指定学期的成绩。
     *
     * @return 成功时返回成绩条数；失败时返回异常
     */
    suspend fun fetchAndSaveGradesForSemester(
        context: Context,
        semester: SemesterEntity
    ): Result<Int> {
        // 学年/学期直接用实体字段，避免解析展示文案的字符串
        val year = semester.academicYear.substringBefore("-")
        val termCode = semester.termCode
        val term = Term.fromCode(termCode) ?: Term.SPRING

        val result = JwxtAuthManager.doWithAuth { client ->
            client.grades().term(year, term, PageQuery(1, PAGE_SIZE)).items
        }

        return result.map { entries ->
            val now = System.currentTimeMillis()
            val grades = entries.map { it.toEntity(semester.id, now) }
            GradeDataManager.getInstance(context)
                .replaceGradesForSemester(grades, semester.id)
            grades.size
        }
    }

    private fun GradeEntry.toEntity(semesterId: Long, now: Long) = GradeEntity(
        semesterId = semesterId,
        courseName = courseName.orEmpty(),
        classId = classId.orEmpty(),
        studentId = studentId.orEmpty(),
        score = score.orEmpty(),
        percentageScore = percentageScore.orEmpty(),
        gradePoint = gradePoint.orEmpty(),
        creditGradePoint = creditGradePoint.orEmpty(),
        credits = credits.orEmpty(),
        courseNature = courseNature.orEmpty(),
        courseCategory = courseCategory.orEmpty(),
        courseType = courseType.orEmpty(),
        courseMark = courseMark.orEmpty(),
        assessmentMethod = assessmentMethod.orEmpty(),
        examNature = examNature.orEmpty(),
        teacherName = teacherName.orEmpty(),
        schoolYear = schoolYear.orEmpty(),
        term = term.orEmpty(),
        scoreVoided = scoreVoided.orEmpty(),
        publishedAt = publishedAt.orEmpty(),
        updatedAt = now
    )
}
