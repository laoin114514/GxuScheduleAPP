package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 学生基本信息（cxCkDgxsxx 接口返回） */
public class StudentProfile {

    @SerializedName("xh")
    private String studentId;           // 学号

    @SerializedName("xm")
    private String name;                // 姓名

    @SerializedName("xmpy")
    private String namePinyin;          // 姓名拼音

    @SerializedName("xbm")
    private String gender;              // 性别（男/女）

    @SerializedName("csrq")
    private String birthDate;           // 出生日期

    @SerializedName("mzm")
    private String ethnicity;           // 民族

    @SerializedName("zjhm")
    private String idNumber;            // 身份证号

    @SerializedName("zjlxm")
    private String idType;              // 证件类型

    @SerializedName("rxrq")
    private String enrollDate;          // 入学日期

    @SerializedName("njdm_id")
    private String grade;               // 年级

    @SerializedName("bh_id")
    private String className;           // 班级名称

    @SerializedName("zyh_id")
    private String major;               // 专业

    @SerializedName("jg_id")
    private String college;             // 学院

    @SerializedName("pyccdm")
    private String eduLevel;            // 培养层次（本科/硕士）

    @SerializedName("pyfsdm")
    private String eduMode;             // 培养方式（普通全日制）

    @SerializedName("xlccdm")
    private String degreeLevel;         // 学历层次

    @SerializedName("xjztdm")
    private String status;              // 学籍状态（在读/休学）

    @SerializedName("sfzx")
    private String isAtSchool;          // 是否在校

    @SerializedName("ksh")
    private String examNumber;          // 考生号

    @SerializedName("zkzh")
    private String admissionTicket;     // 准考证号

    @SerializedName("byzx")
    private String highSchool;          // 毕业中学

    @SerializedName("rxzf")
    private String entranceScore;       // 入学总分

    @SerializedName("xxnx")
    private String studyYears;          // 学习年限

    @SerializedName("xz")
    private String schoolingLength;     // 学制

    @SerializedName("lym")
    private String origin;              // 来源

    @SerializedName("syd")
    private String hometown;            // 生源地

    @SerializedName("xnm")
    private String schoolYear;          // 学年代码

    @SerializedName("xnmc")
    private String schoolYearName;      // 学年名称

    @SerializedName("xqm")
    private String term;                // 学期代码

    @SerializedName("xqmc")
    private String termName;            // 学期名称

    @SerializedName("zzmmm")
    private String politicalStatus;     // 政治面貌

    @SerializedName("xslbdm")
    private String studentType;         // 学生类别

    @SerializedName("zsnddm")
    private String admitYear;           // 招生年度

    @SerializedName("zszyh_id")
    private String admitMajor;          // 招生专业

    @SerializedName("zsjg_id")
    private String admitCollege;        // 招生学院

    @SerializedName("bdh")
    private String reportNumber;        // 报到号

    @SerializedName("bdzcbj")
    private String reportStatus;        // 报到注册标记

    @SerializedName("jlNum")
    private double awardCount;          // 奖励数

    @SerializedName("cyNum")
    private double penaltyCount;        // 惩处数

    @SerializedName("jdNum")
    private double reviewCount;         // 鉴定数

    // ---- getters ----

    public String getStudentId() { return studentId; }
    public String getName() { return name; }
    public String getNamePinyin() { return namePinyin; }
    public String getGender() { return gender; }
    public String getBirthDate() { return birthDate; }
    public String getEthnicity() { return ethnicity; }
    public String getIdNumber() { return idNumber; }
    public String getIdType() { return idType; }
    public String getEnrollDate() { return enrollDate; }
    public String getGrade() { return grade; }
    public String getClassName() { return className; }
    public String getMajor() { return major; }
    public String getCollege() { return college; }
    public String getEduLevel() { return eduLevel; }
    public String getEduMode() { return eduMode; }
    public String getDegreeLevel() { return degreeLevel; }
    public String getStatus() { return status; }
    public String getIsAtSchool() { return isAtSchool; }
    public String getExamNumber() { return examNumber; }
    public String getAdmissionTicket() { return admissionTicket; }
    public String getHighSchool() { return highSchool; }
    public String getEntranceScore() { return entranceScore; }
    public String getStudyYears() { return studyYears; }
    public String getSchoolingLength() { return schoolingLength; }
    public String getOrigin() { return origin; }
    public String getHometown() { return hometown; }
    public String getSchoolYear() { return schoolYear; }
    public String getSchoolYearName() { return schoolYearName; }
    public String getTerm() { return term; }
    public String getTermName() { return termName; }
    public String getPoliticalStatus() { return politicalStatus; }
    public String getStudentType() { return studentType; }
    public String getAdmitYear() { return admitYear; }
    public String getAdmitMajor() { return admitMajor; }
    public String getAdmitCollege() { return admitCollege; }
    public String getReportNumber() { return reportNumber; }
    public String getReportStatus() { return reportStatus; }
    public double getAwardCount() { return awardCount; }
    public double getPenaltyCount() { return penaltyCount; }
    public double getReviewCount() { return reviewCount; }

    @Override
    public String toString() {
        return name + " (" + studentId + ") " + college + " " + major + " " + className;
    }
}
