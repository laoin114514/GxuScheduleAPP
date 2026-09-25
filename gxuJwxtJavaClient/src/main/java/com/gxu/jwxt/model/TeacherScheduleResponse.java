package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/** teacher() 接口完整返回 */
public class TeacherScheduleResponse {

    @SerializedName("kbList")
    private List<CourseEntry> courses;          // 教师课表

    @SerializedName("sjkList")
    private List<CourseEntry> practiceCourses;  // 实践课表

    @SerializedName("bjsjkList")
    private List<CourseEntry> classPractice;    // 班级实践课表

    @SerializedName("xqjmcMap")
    private Map<String, String> weekdayMap;     // 星期映射

    @SerializedName("xsbjList")
    private List<ScheduleType> scheduleTypes;   // 学时类型列表

    @SerializedName("timeList")
    private List<?> timeList;                   // 时间列表

    @SerializedName("qsxqj")
    private String termFlag;                    // 学期标记

    @SerializedName("kblx")
    private double scheduleTypeCode;            // 课表类型

    @SerializedName("sfxsd")
    private String displayMode;                 // 是否显示地点

    // ---- getters ----

    public List<CourseEntry> getCourses() { return courses; }
    public List<CourseEntry> getPracticeCourses() { return practiceCourses; }
    public List<CourseEntry> getClassPractice() { return classPractice; }
    public Map<String, String> getWeekdayMap() { return weekdayMap; }
    public List<ScheduleType> getScheduleTypes() { return scheduleTypes; }
    public List<?> getTimeList() { return timeList; }
    public String getTermFlag() { return termFlag; }

    /**
     * 获取所有课程（理论 + 实践 + 班级实践）
     */
    public List<CourseEntry> getAllCourses() {
        List<CourseEntry> all = new java.util.ArrayList<>();
        if (courses != null) all.addAll(courses);
        if (practiceCourses != null) all.addAll(practiceCourses);
        if (classPractice != null) all.addAll(classPractice);
        return all;
    }
}
