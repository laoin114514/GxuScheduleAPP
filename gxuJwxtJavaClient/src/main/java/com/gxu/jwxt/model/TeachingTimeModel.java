package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 作息时间模型中的一个时段。 */
public class TeachingTimeModel {
    @SerializedName("sdmc") private String name;
    @SerializedName("xsdj") private String level;
    @SerializedName("djgs") private String periodsPerSegment;
    @SerializedName("xjgs") private String sectionsPerPeriod;

    public String getName() { return name; }
    public String getLevel() { return level; }
    public String getPeriodsPerSegment() { return periodsPerSegment; }
    public String getSectionsPerPeriod() { return sectionsPerPeriod; }
}
