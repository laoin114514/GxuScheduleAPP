package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 课程的线上上课地址与群号。 */
public class OnlineLearningAddress {
    @SerializedName("kch") private String courseCode;
    @SerializedName("kcmc") private String courseName;
    @SerializedName("jxb_id") private String teachingClassId;
    @SerializedName("jxbmc") private String teachingClassName;
    @SerializedName("jgh") private String teacherId;
    @SerializedName("jsxm") private String teacherName;
    @SerializedName("jxdd") private String address;
    @SerializedName("qqqh") private String qqGroup;
    @SerializedName("sksj") private String schedule;
    @SerializedName("xf") private String credits;

    public String getCourseCode() { return courseCode; }
    public String getCourseName() { return courseName; }
    public String getTeachingClassId() { return teachingClassId; }
    public String getTeachingClassName() { return teachingClassName; }
    public String getTeacherId() { return teacherId; }
    public String getTeacherName() { return teacherName; }
    public String getAddress() { return address; }
    public String getQqGroup() { return qqGroup; }
    public String getSchedule() { return schedule; }
    public String getCredits() { return credits; }
}
