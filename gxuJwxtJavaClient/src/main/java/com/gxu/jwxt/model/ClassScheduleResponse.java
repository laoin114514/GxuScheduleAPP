package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * 班级课表详情接口返回。
 *
 * <p>对应 {@code /kbdy/bjkbdy_cxBjKb.html} 的 JSON 响应。</p>
 */
public class ClassScheduleResponse {

    @SerializedName("qsxqj")
    private String termFlag;                    // 学期标记

    @SerializedName("kbList")
    private List<CourseEntry> courses;          // 理论课表

    @SerializedName("sjkList")
    private List<CourseEntry> practiceCourses;  // 实践课表（通常为空壳条目）

    @SerializedName("xqjmcMap")
    private Map<String, String> weekdayMap;     // 星期映射 {"1":"星期一",...}

    @SerializedName("xsbjList")
    private List<ScheduleType> scheduleTypes;   // 学时类型

    @SerializedName("kblx")
    private double scheduleTypeCode;            // 课表类型代码

    @SerializedName("sfxsd")
    private String displayMode;                 // 是否显示地点

    @SerializedName("sxgykbbz")
    private String scheduleMark;                // 课表标记

    @SerializedName("xkkg")
    private boolean courseSelectionOpen;        // 选课是否开放

    @SerializedName("weekNum")
    private List<WeekInfo> weeks;               // 教学周列表，每周含日期范围 rq

    // ---- getters ----

    public String getTermFlag() { return termFlag; }
    public List<CourseEntry> getCourses() { return courses; }
    public List<CourseEntry> getPracticeCourses() { return practiceCourses; }
    public Map<String, String> getWeekdayMap() { return weekdayMap; }
    public List<ScheduleType> getScheduleTypes() { return scheduleTypes; }
    public double getScheduleTypeCode() { return scheduleTypeCode; }
    public String getDisplayMode() { return displayMode; }
    public String getScheduleMark() { return scheduleMark; }
    public boolean isCourseSelectionOpen() { return courseSelectionOpen; }
    public List<WeekInfo> getWeeks() { return weeks; }

    // ========== 学期日期 ==========

    /**
     * 学期开始日期（第 1 周的开始日期）。
     * @return 如 {@code "2026-02-23"}，若 weekNum 为空则返回 null
     */
    public String getSemesterStartDate() {
        if (weeks == null || weeks.isEmpty()) return null;
        for (WeekInfo w : weeks) {
            if ("1".equals(w.getWeekNum())) {
                return w.getStartDate();
            }
        }
        return weeks.get(0).getStartDate();
    }

    /**
     * 学期结束日期（最后一周的结束日期）。
     * @return 如 {@code "2026-07-19"}，若 weekNum 为空则返回 null
     */
    public String getSemesterEndDate() {
        if (weeks == null || weeks.isEmpty()) return null;
        WeekInfo last = weeks.get(weeks.size() - 1);
        return last.getEndDate();
    }

    /**
     * 学期日期范围。
     * @return 如 {@code "2026-02-23 ~ 2026-07-19"}
     */
    public String getSemesterDateRange() {
        String start = getSemesterStartDate();
        String end = getSemesterEndDate();
        if (start == null || end == null) return null;
        return start + " ~ " + end;
    }

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
