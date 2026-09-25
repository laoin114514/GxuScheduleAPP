package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学生考试安排或无排考课程条目。 */
public class ExamScheduleEntry {
    @SerializedName("kcmc") private String courseName;
    @SerializedName("kch") private String courseCode;
    @SerializedName("ksmc") private String examName;
    @SerializedName("kssj") private String examTime;
    @SerializedName("cdmc") private String classroom;
    @SerializedName("cdbh") private String classroomCode;
    @SerializedName("jxdd") private String locations;
    @SerializedName("khfs") private String assessmentMethod;
    @SerializedName("jxbmc") private String teachingClassName;
    @SerializedName("jsxx") private String teacher;
    @SerializedName("xf") private String credits;
    @SerializedName("xnm") private String schoolYear;
    @SerializedName("xqm") private String term;
    @SerializedName("sjbh") private String paperId;

    public String getCourseName() { return courseName; }
    public String getCourseCode() { return courseCode; }
    public String getExamName() { return examName; }
    public String getExamTime() { return examTime; }
    public String getClassroom() { return classroom; }
    public String getClassroomCode() { return classroomCode; }
    public String getLocations() { return locations; }
    public String getAssessmentMethod() { return assessmentMethod; }
    public String getTeachingClassName() { return teachingClassName; }
    public String getTeacher() { return teacher; }
    public String getCredits() { return credits; }
    public String getSchoolYear() { return schoolYear; }
    public String getTerm() { return term; }
    public String getPaperId() { return paperId; }
}
