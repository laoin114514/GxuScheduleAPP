package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 课表纵向显示字段设置。 */
public class ScheduleDisplayField {
    @SerializedName("ZDM") private String field;
    @SerializedName("ZDMC") private String label;
    @SerializedName("SFXS") private String visible;

    public String getField() { return field; }
    public String getLabel() { return label; }
    public boolean isVisible() { return "1".equals(visible) || "true".equalsIgnoreCase(visible); }
}
