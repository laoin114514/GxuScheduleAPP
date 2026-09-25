package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学生所属班级。 */
public class StudentClass {
    @SerializedName("bh_id") private String id;
    @SerializedName("bj") private String name;

    public String getId() { return id; }
    public String getName() { return name; }
}
