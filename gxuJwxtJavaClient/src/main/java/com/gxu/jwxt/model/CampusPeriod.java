package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 校区节次。 */
public class CampusPeriod {
    @SerializedName("JCMC") private String name;
    @SerializedName("RSDMC") private String segmentName;
    @SerializedName("RSDZJS") private String totalPeriods;

    public String getName() { return name; }
    public String getSegmentName() { return segmentName; }
    public String getTotalPeriods() { return totalPeriods; }
}
