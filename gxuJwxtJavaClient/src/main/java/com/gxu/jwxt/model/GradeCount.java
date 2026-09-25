package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 成绩查询结果的学生与课程数量。 */
public class GradeCount {
    @SerializedName("xss") private int studentCount;
    @SerializedName("kcs") private int courseCount;

    public int getStudentCount() { return studentCount; }
    public int getCourseCount() { return courseCount; }
}
