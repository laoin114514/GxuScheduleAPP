package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 等级考试成绩条目（cxDjksxx 接口 items 元素） */
public class ExamEntry {

    @SerializedName("xmmc")
    private String examName;            // 考试名称（大学英语四级考试）

    @SerializedName("xmlbmc")
    private String examCategory;        // 考试类别

    @SerializedName("cj")
    private String score;               // 成绩

    @SerializedName("sftg")
    private double passed;              // 是否通过（1=是）

    @SerializedName("zkzh")
    private String ticketNumber;        // 准考证号

    @SerializedName("zsbh")
    private String certNumber;          // 证书编号

    @SerializedName("xnmmc")
    private String schoolYearName;      // 学年名称

    @SerializedName("xnm")
    private String schoolYear;          // 学年代码

    @SerializedName("xqm")
    private String term;                // 学期代码

    @SerializedName("xqmmc")
    private String termName;            // 学期名称

    @SerializedName("xh")
    private String studentId;           // 学号

    @SerializedName("xm")
    private String studentName;         // 姓名

    // ---- getters ----

    public String getExamName() { return examName; }
    public String getExamCategory() { return examCategory; }
    public String getScore() { return score; }
    public boolean isPassed() { return passed == 1.0; }
    public String getTicketNumber() { return ticketNumber; }
    public String getCertNumber() { return certNumber; }
    public String getSchoolYearName() { return schoolYearName; }
    public String getSchoolYear() { return schoolYear; }
    public String getTerm() { return term; }
    public String getTermName() { return termName; }
    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }

    @Override
    public String toString() {
        return examName + " " + score + " " + (isPassed() ? "通过" : "未通过");
    }
}
