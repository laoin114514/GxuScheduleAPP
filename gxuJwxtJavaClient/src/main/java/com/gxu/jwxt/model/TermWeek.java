package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学期中一个可查询教学周。 */
public class TermWeek {
    @SerializedName("zc") private String number;
    @SerializedName("dxqzc") private String label;
    @SerializedName("rqfw") private String dateRange;
    @SerializedName("zczt") private String status;

    public String getNumber() { return number; }
    public String getLabel() { return label; }
    public String getDateRange() { return dateRange; }
    public String getStatus() { return status; }
}
