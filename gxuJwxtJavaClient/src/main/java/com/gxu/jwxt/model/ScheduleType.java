package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学时类型（教师课表 xsbjList 元素） */
public class ScheduleType {

    @SerializedName("xsdm")
    private String code;        // 学时代码（01/02/03/04）

    @SerializedName("xsmc")
    private String name;        // 学时名称（讲授/实验/实习/实训）

    @SerializedName("xslxbj")
    private String mark;        // 学时类型标记

    @SerializedName("ywxsmc")
    private String englishName; // 英文名称

    // ---- getters ----

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getMark() { return mark; }
    public String getEnglishName() { return englishName; }

    @Override
    public String toString() {
        return name + " (" + code + ")";
    }
}
