package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 选课名单中的一名学生及其教学班。 */
public class CourseRosterEntry {
    @SerializedName("xh") private String studentId;
    @SerializedName("xm") private String studentName;
    @SerializedName("bjmc") private String className;
    @SerializedName("jxb_id") private String teachingClassId;
    @SerializedName("jxbmc") private String teachingClassName;
    @SerializedName("kch") private String courseCode;
    @SerializedName("kcmc") private String courseName;
    @SerializedName("jsmc") private String teacherName;
    @SerializedName("sksj") private String schedule;

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public String getTeachingClassId() { return teachingClassId; }
    public String getTeachingClassName() { return teachingClassName; }
    public String getCourseCode() { return courseCode; }
    public String getCourseName() { return courseName; }
    public String getTeacherName() { return teacherName; }
    public String getSchedule() { return schedule; }
}
