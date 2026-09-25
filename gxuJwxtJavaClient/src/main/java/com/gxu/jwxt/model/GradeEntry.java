package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 课程成绩条目。 */
public class GradeEntry {
    @SerializedName("kcmc") private String courseName;
    @SerializedName("kch") private String courseCode;
    @SerializedName("kch_id") private String courseId;
    @SerializedName("kcywmc") private String englishCourseName;
    /** 教学班 ID，与 {@link #studentId}、{@link #schoolYear}/{@link #term} 一起用于查成绩详情。 */
    @SerializedName("jxb_id") private String classId;
    @SerializedName("jxbmc") private String className;
    /** 学号 ID（哈希串），成绩详情接口的 xh_id 参数。 */
    @SerializedName("xh_id") private String studentId;
    @SerializedName("cj") private String score;
    @SerializedName("bfzcj") private String percentageScore;
    @SerializedName("jd") private String gradePoint;
    @SerializedName("xf") private String credits;
    @SerializedName("zxs") private String totalHours;
    @SerializedName("rwzxs") private String requiredHours;
    @SerializedName("xfjd") private String creditGradePoint;
    @SerializedName("khfsmc") private String assessmentMethod;
    @SerializedName("ksxz") private String examNature;
    @SerializedName("ksxzdm") private String examNatureCode;
    /** 成绩是否作废（"是"/"否"）。 */
    @SerializedName("cjsfzf") private String scoreVoided;
    @SerializedName("jsxm") private String teacherName;
    @SerializedName("cjbdsj") private String publishedAt;
    @SerializedName("cjbdczr") private String publishedBy;
    @SerializedName("xnm") private String schoolYear;
    @SerializedName("xqm") private String term;
    @SerializedName("xnmmc") private String schoolYearName;
    @SerializedName("xqmmc") private String termName;
    @SerializedName("kclbmc") private String courseCategory;
    @SerializedName("kcxzmc") private String courseNature;
    /** 开课类型，如「主修课程」。 */
    @SerializedName("kklxdm") private String courseType;
    /** 课程标记，如「主修」。 */
    @SerializedName("kcbj") private String courseMark;
    /** 是否学位课程（"是"/"否"）。 */
    @SerializedName("sfxwkc") private String degreeCourse;
    /** 开课部门名称。 */
    @SerializedName("kkbmmc") private String offeringDepartment;
    @SerializedName("jgmc") private String collegeName;

    public String getCourseName() { return courseName; }
    public String getCourseCode() { return courseCode; }
    public String getCourseId() { return courseId; }
    public String getEnglishCourseName() { return englishCourseName; }
    public String getClassId() { return classId; }
    public String getClassName() { return className; }
    public String getStudentId() { return studentId; }
    public String getScore() { return score; }
    public String getPercentageScore() { return percentageScore; }
    public String getGradePoint() { return gradePoint; }
    public String getCredits() { return credits; }
    public String getTotalHours() { return totalHours; }
    public String getRequiredHours() { return requiredHours; }
    public String getCreditGradePoint() { return creditGradePoint; }
    public String getAssessmentMethod() { return assessmentMethod; }
    public String getExamNature() { return examNature; }
    public String getExamNatureCode() { return examNatureCode; }
    public String getScoreVoided() { return scoreVoided; }
    public String getTeacherName() { return teacherName; }
    public String getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public String getSchoolYear() { return schoolYear; }
    public String getTerm() { return term; }
    public String getSchoolYearName() { return schoolYearName; }
    public String getTermName() { return termName; }
    public String getCourseCategory() { return courseCategory; }
    public String getCourseNature() { return courseNature; }
    public String getCourseType() { return courseType; }
    public String getCourseMark() { return courseMark; }
    public String getDegreeCourse() { return degreeCourse; }
    public String getOfferingDepartment() { return offeringDepartment; }
    public String getCollegeName() { return collegeName; }
}
