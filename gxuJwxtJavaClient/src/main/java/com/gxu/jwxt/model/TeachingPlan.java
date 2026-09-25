package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 教学执行计划。 */
public class TeachingPlan {
    @SerializedName("jxzxjhxx_id") private String id;
    @SerializedName("njdm") private String gradeCode;
    @SerializedName("njmc") private String gradeName;
    @SerializedName("zyh_id") private String majorCode;
    @SerializedName("zymc") private String majorName;
    @SerializedName("bjgs") private String classCount;
    @SerializedName("jhrs") private String plannedStudents;
    @SerializedName("kcs") private String courseCount;
    @SerializedName("xz") private String schoolingLength;

    public String getId() { return id; }
    public String getGradeCode() { return gradeCode; }
    public String getGradeName() { return gradeName; }
    public String getMajorCode() { return majorCode; }
    public String getMajorName() { return majorName; }
    public String getClassCount() { return classCount; }
    public String getPlannedStudents() { return plannedStudents; }
    public String getCourseCount() { return courseCount; }
    public String getSchoolingLength() { return schoolingLength; }
}
