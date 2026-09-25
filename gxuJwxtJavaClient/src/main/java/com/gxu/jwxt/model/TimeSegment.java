package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 作息时段。 */
public class TimeSegment {
    @SerializedName("rsdm") private String code;
    @SerializedName("rsdmc") private String name;
    @SerializedName("rsdywmc") private String englishName;
    @SerializedName("rsdzjs") private String totalPeriods;
    @SerializedName("kbsfxs") private String displayOnSchedule;

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getEnglishName() { return englishName; }
    public String getTotalPeriods() { return totalPeriods; }
    public String getDisplayOnSchedule() { return displayOnSchedule; }
}
