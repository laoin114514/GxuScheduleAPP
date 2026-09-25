package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/**
 * 教学周信息（weekNum 列表条目）。
 *
 * <p>格式示例：第 1 周的 {@code rq} 为 {@code "2026-02-23/2026-03-01"}，
 * 即该周从 2 月 23 日到 3 月 1 日。</p>
 */
public class WeekInfo {

    @SerializedName("zs")
    private String weekNum;          // 周次序号，如 "1", "2", ...

    @SerializedName("rq")
    private String dateRange;        // 日期范围，如 "2026-02-23/2026-03-01"

    @SerializedName("zcrq")
    private String weekDateRange;    // 如 "1(2026-02-23～2026-03-01)"

    @SerializedName("zcrq2")
    private String weekDateRange2;   // 如 "第1周(2026-02-23～2026-03-01)"

    @SerializedName("year")
    private String year;

    @SerializedName("month")
    private String month;

    @SerializedName("day")
    private String day;

    @SerializedName("zsmc")
    private String weekLabel;        // 周次标签，如 "1"

    // ---- getters ----

    /** 周次序号，如 "1" */
    public String getWeekNum() { return weekNum; }

    /** 日期范围，格式 "YYYY-MM-DD/YYYY-MM-DD"，如 "2026-02-23/2026-03-01" */
    public String getDateRange() { return dateRange; }

    /** 人类可读的周次+日期，如 "第1周(2026-02-23～2026-03-01)" */
    public String getWeekDateRange2() { return weekDateRange2; }

    /** 人类可读的周次+日期，如 "1(2026-02-23～2026-03-01)" */
    public String getWeekDateRange() { return weekDateRange; }

    public String getYear() { return year; }
    public String getMonth() { return month; }
    public String getDay() { return day; }
    public String getWeekLabel() { return weekLabel; }

    /**
     * 获取该周的开始日期（rq 中 "/" 之前的部分）。
     * @return 如 "2026-02-23"，若格式异常则返回 null
     */
    public String getStartDate() {
        if (dateRange == null || dateRange.isEmpty()) return null;
        int idx = dateRange.indexOf('/');
        return idx > 0 ? dateRange.substring(0, idx) : null;
    }

    /**
     * 获取该周的结束日期（rq 中 "/" 之后的部分）。
     * @return 如 "2026-03-01"，若格式异常则返回 null
     */
    public String getEndDate() {
        if (dateRange == null || dateRange.isEmpty()) return null;
        int idx = dateRange.indexOf('/');
        return idx > 0 && idx < dateRange.length() - 1 ? dateRange.substring(idx + 1) : null;
    }

    @Override
    public String toString() {
        return weekDateRange2 != null ? weekDateRange2 : (weekNum + ": " + dateRange);
    }
}
