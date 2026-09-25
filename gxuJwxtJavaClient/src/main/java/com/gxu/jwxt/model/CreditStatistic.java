package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 按课程性质汇总的已修学分。 */
public class CreditStatistic {
    @SerializedName("kcxzdm") private String courseNatureCode;
    @SerializedName("kcxzmc") private String courseNature;
    @SerializedName("fxxf") private String completedCredits;
    @SerializedName("tgxf") private String passedCredits;
    @SerializedName("wtgxf") private String failedCredits;

    public String getCourseNatureCode() { return courseNatureCode; }
    public String getCourseNature() { return courseNature; }
    public String getCompletedCredits() { return completedCredits; }
    public String getPassedCredits() { return passedCredits; }
    public String getFailedCredits() { return failedCredits; }
}
