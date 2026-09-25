package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学年下可查询的学期。 */
public class TermOption {
    @SerializedName("dm") private String code;
    @SerializedName("mc") private String name;

    public String getCode() { return code; }
    public String getName() { return name; }
}
