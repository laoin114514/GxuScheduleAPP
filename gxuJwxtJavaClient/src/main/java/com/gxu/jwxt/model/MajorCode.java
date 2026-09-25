package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 专业代码字典条目。 */
public class MajorCode {
    @SerializedName("zyh_id") private String id;
    @SerializedName("zyh") private String code;
    @SerializedName("zymc") private String name;
    @SerializedName("jgmc") private String college;

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCollege() { return college; }
}
