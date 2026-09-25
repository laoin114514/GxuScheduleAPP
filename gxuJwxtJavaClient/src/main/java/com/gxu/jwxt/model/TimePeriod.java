package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 单个教学节次及其起止时间。 */
public class TimePeriod {
    @SerializedName("jc") private String period;
    @SerializedName("jcmc") private String name;
    @SerializedName("qssj") private String startTime;
    @SerializedName("jssj") private String endTime;
    @SerializedName("rsdmc") private String segmentName;

    public String getPeriod() { return period; }
    public String getName() { return name; }
    public String getStartTime() { return startTime; }
    public String getEndTime() { return endTime; }
    public String getSegmentName() { return segmentName; }
}
