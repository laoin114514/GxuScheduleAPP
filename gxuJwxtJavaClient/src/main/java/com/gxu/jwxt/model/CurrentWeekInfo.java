package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/** 空闲教室模块返回的当前周与全部可查询周。 */
public class CurrentWeekInfo {
    @SerializedName("dqzcxq") private Current current;
    @SerializedName("nxqzcList") private List<TermWeek> weeks;

    public Current getCurrent() { return current; }
    public List<TermWeek> getWeeks() { return weeks != null ? weeks : Collections.emptyList(); }

    public static class Current {
        @SerializedName("ZXRQ") private String date;
        @SerializedName("DQZC") private String week;
        @SerializedName("DQXQ") private String weekday;
        @SerializedName("ZDKXZC") private String maxWeek;

        public String getDate() { return date; }
        public String getWeek() { return week; }
        public String getWeekday() { return weekday; }
        public String getMaxWeek() { return maxWeek; }
    }
}
