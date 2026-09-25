package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 专业方向代码字典条目。 */
public class MajorDirection {
    @SerializedName("zyfx_id") private String id;
    @SerializedName("zyfxdm") private String code;
    @SerializedName("zyfxmc") private String name;
    @SerializedName("njdm_id") private String gradeCode;

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getGradeCode() { return gradeCode; }
}
