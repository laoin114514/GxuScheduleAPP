package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 班级代码字典条目。 */
public class ClassCode {
    @SerializedName("bh_id") private String id;
    @SerializedName("bh") private String code;
    @SerializedName("bj") private String name;
    @SerializedName("jgmc") private String college;
    @SerializedName("njmc") private String grade;

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCollege() { return college; }
    public String getGrade() { return grade; }
}
