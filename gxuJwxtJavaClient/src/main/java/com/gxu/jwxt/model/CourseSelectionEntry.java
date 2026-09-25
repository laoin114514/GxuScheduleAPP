package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 选课信息条目（cxXsxkxx 接口 items 元素） */
public class CourseSelectionEntry {

    @SerializedName("kcmc")
    private String courseName;          // 课程名

    @SerializedName("kch")
    private String courseCode;          // 课程号

    @SerializedName("kclbmc")
    private String courseCategory;      // 课程类别

    @SerializedName("kcgsmc")
    private String courseBelong;        // 课程归属

    @SerializedName("jsxm")
    private String teacherName;         // 教师名

    @SerializedName("jxbmc")
    private String className;           // 教学班名

    @SerializedName("jxdd")
    private String location;            // 教学地点

    @SerializedName("kkxy")
    private String college;             // 开课学院

    @SerializedName("sksj")
    private String scheduleText;        // 上课时间描述

    @SerializedName("xf")
    private String credits;             // 学分

    @SerializedName("xnmc")
    private String schoolYearName;      // 学年名称

    @SerializedName("xnm")
    private String schoolYear;          // 学年代码

    @SerializedName("xqm")
    private String term;                // 学期代码

    @SerializedName("xqmmc")
    private String termName;            // 学期名称

    @SerializedName("row_id")
    private String rowId;               // 行号

    // ---- getters ----

    public String getCourseName() { return courseName; }
    public String getCourseCode() { return courseCode; }
    public String getCourseCategory() { return courseCategory; }
    public String getCourseBelong() { return courseBelong; }
    public String getTeacherName() { return teacherName; }
    public String getClassName() { return className; }
    public String getLocation() { return location; }
    public String getCollege() { return college; }
    public String getScheduleText() { return scheduleText; }
    public String getCredits() { return credits; }
    public String getSchoolYearName() { return schoolYearName; }
    public String getSchoolYear() { return schoolYear; }
    public String getTerm() { return term; }
    public String getTermName() { return termName; }
    public String getRowId() { return rowId; }

    @Override
    public String toString() {
        return courseName + " / " + teacherName + " / " + scheduleText;
    }
}
