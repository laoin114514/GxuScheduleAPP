package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/** personal() 接口完整返回 */
public class ScheduleResponse {

    @SerializedName("xsxx")
    private StudentInfo studentInfo;            // 学生信息

    @SerializedName("kbList")
    private List<CourseEntry> courses;          // 理论课表

    @SerializedName("sjkList")
    private List<CourseEntry> practiceCourses;  // 实践课表

    @SerializedName("xqjmcMap")
    private Map<String, String> weekdayMap;     // 星期映射 {"1":"星期一",...}

    @SerializedName("xsbjList")
    private List<ScheduleType> scheduleTypes;   // 学时类型

    @SerializedName("qsxqj")
    private String termFlag;                    // 学期标记

    @SerializedName("sjfwkg")
    private boolean practiceOpen;               // 实践课是否开放

    @SerializedName("kblx")
    private double scheduleTypeCode;            // 课表类型代码

    @SerializedName("sfxsd")
    private String displayMode;                 // 是否显示地点

    @SerializedName("xskbsfxstkzt")
    private String adjustmentStatusFlag;        // 学生课表是否显示调课状态（"1" 显示）

    @SerializedName("sxgykbbz")
    private String scheduleMark;                // 课表标记

    @SerializedName("xkkg")
    private boolean courseSelectionOpen;        // 选课是否开放

    // ---- getters ----

    public StudentInfo getStudentInfo() { return studentInfo; }
    public List<CourseEntry> getCourses() { return courses; }
    public List<CourseEntry> getPracticeCourses() { return practiceCourses; }
    public Map<String, String> getWeekdayMap() { return weekdayMap; }
    public List<ScheduleType> getScheduleTypes() { return scheduleTypes; }
    public String getTermFlag() { return termFlag; }
    public boolean isPracticeOpen() { return practiceOpen; }

    /**
     * 原始字段值（{@code xskbsfxstkzt}）：学生课表是否显示调课状态，"1" 表示显示。
     * 一般用 {@link #isAdjustmentStatusVisible()} 判定，不要直接比对字符串。
     */
    public String getScheduleStatus() { return adjustmentStatusFlag; }

    /**
     * 学生课表是否显示调课状态（教务顶层字段 {@code xskbsfxstkzt == "1"}）。
     *
     * <p>正方学生课表页的调课标记判定式是
     * {@code xskbsfxstkzt == "1" && jxbsftkbj == "1"}：本方法是前半段（全局开关），
     * 条目级后半段是 {@link CourseEntry#hasAdjustment()}。顶层字段缺失或非 "1" 时
     * 一律视为不显示，与教务网页行为一致。</p>
     */
    public boolean isAdjustmentStatusVisible() { return "1".equals(adjustmentStatusFlag); }
    public String getScheduleMark() { return scheduleMark; }
    public boolean isCourseSelectionOpen() { return courseSelectionOpen; }

    /**
     * 获取所有课程（理论 + 实践）
     */
    public List<CourseEntry> getAllCourses() {
        List<CourseEntry> all = new java.util.ArrayList<>();
        if (courses != null) all.addAll(courses);
        if (practiceCourses != null) all.addAll(practiceCourses);
        return all;
    }
}
