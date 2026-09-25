package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学时等级定义。 */
public class PeriodLevel {
    @SerializedName("jcmc") private String periodName;
    @SerializedName("xsdj") private String level;
    @SerializedName("xjgs") private String sectionCount;

    public String getPeriodName() { return periodName; }
    public String getLevel() { return level; }
    public String getSectionCount() { return sectionCount; }
}
