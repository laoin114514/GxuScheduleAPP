package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 待确认或已确认的选课结果。 */
public class CourseConfirmation {
    @SerializedName("jxb_id") private String teachingClassId;
    @SerializedName("kch_id") private String courseId;
    @SerializedName("kch") private String courseCode;
    @SerializedName("kcmc") private String courseName;
    @SerializedName("jxbmc") private String teachingClassName;
    @SerializedName("kcxz") private String courseNature;
    @SerializedName("xf") private String credits;
    @SerializedName("jsxx") private String teacher;
    @SerializedName("jxdd") private String location;
    @SerializedName("sksj") private String schedule;
    @SerializedName("sfqr") private String confirmed;
    @SerializedName("bixbj") private String compulsory;
    @SerializedName("cxbj") private String retake;
    @SerializedName("fxbj") private String minor;

    public String getTeachingClassId() { return teachingClassId; }
    public String getCourseId() { return courseId; }
    public String getCourseCode() { return courseCode; }
    public String getCourseName() { return courseName; }
    public String getTeachingClassName() { return teachingClassName; }
    public String getCourseNature() { return courseNature; }
    public String getCredits() { return credits; }
    public String getTeacher() { return teacher; }
    public String getLocation() { return location; }
    public String getSchedule() { return schedule; }
    public boolean isConfirmed() { return "1".equals(confirmed); }
    public boolean isCompulsory() { return "1".equals(compulsory); }
    public boolean isRetake() { return "1".equals(retake); }
    public boolean isMinor() { return "1".equals(minor); }
}
