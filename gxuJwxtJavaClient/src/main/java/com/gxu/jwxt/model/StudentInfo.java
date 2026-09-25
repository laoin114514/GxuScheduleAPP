package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学生基本信息（personal 接口 xsxx 字段） */
public class StudentInfo {

    @SerializedName("XH")
    private String studentId;           // 学号

    @SerializedName("XM")
    private String name;                // 姓名

    @SerializedName("BJMC")
    private String className;           // 班级名称

    @SerializedName("ZYMC")
    private String major;               // 专业名称

    @SerializedName("XNMC")
    private String schoolYearName;      // 学年名称（如 "2024-2025"）

    @SerializedName("XNM")
    private String schoolYear;          // 学年代码

    @SerializedName("XQM")
    private String term;                // 学期代码（3/12）

    @SerializedName("XQMMC")
    private String termName;            // 学期名称

    @SerializedName("NJDM_ID")
    private String grade;               // 年级

    @SerializedName("KCMS")
    private double courseCount;         // 课程门数

    @SerializedName("JFZT")
    private double feeStatus;           // 缴费状态

    // ---- getters ----

    public String getStudentId() { return studentId; }
    public String getName() { return name; }
    public String getClassName() { return className; }
    public String getMajor() { return major; }
    public String getSchoolYearName() { return schoolYearName; }
    public String getSchoolYear() { return schoolYear; }
    public String getTerm() { return term; }
    public String getTermName() { return termName; }
    public String getGrade() { return grade; }
    public double getCourseCount() { return courseCount; }
    public double getFeeStatus() { return feeStatus; }

    @Override
    public String toString() {
        return name + " (" + studentId + ") " + major + " " + className;
    }
}
