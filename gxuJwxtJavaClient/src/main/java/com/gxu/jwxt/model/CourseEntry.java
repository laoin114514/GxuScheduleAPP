package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/**
 * 课程安排条目（课表 kbList / 实践课 sjkList 共用）。
 * 字段名映射教务系统拼音缩写，忽略 queryModel/userModel 等框架噪音。
 */
public class CourseEntry {

    // ---- 课程基本信息 ----
    @SerializedName("kcmc")
    private String courseName;          // 课程名

    @SerializedName("kch")
    private String courseCode;          // 课程号

    @SerializedName("kch_id")
    private String courseId;            // 课程 ID（UUID，同名课程唯一标识）

    @SerializedName("kclb")
    private String courseCategory;      // 课程类别（通识选修课/学科核心课 等）

    @SerializedName("kcxz")
    private String courseNature;        // 课程性质（通识/学科/必修）

    @SerializedName("xf")
    private String credits;             // 学分

    @SerializedName("qqqh")
    private String qqGroup;             // QQ群号（个人课表接口直接返回）

    @SerializedName("zhxs")
    private String weeklyHours;         // 周学时

    @SerializedName("kcxszc")
    private String hourComposition;     // 学时组成（如 "理论:80,实验:16"）

    @SerializedName("kczxs")
    private String totalHours;          // 总学时（与 zxs 同值）

    @SerializedName("zxs")
    private String totalHoursBackup;    // 总学时（数值，备用）

    // ---- 教师信息 ----
    @SerializedName("xm")
    private String teacherName;         // 教师名（kbList 用）

    @SerializedName("jsxm")
    private String teacherName2;        // 教师名（sjkList 用）

    @SerializedName("jgh_id")
    private String teacherId;           // 教师工号

    @SerializedName("zcmc")
    private String teacherTitle;        // 职称（副教授/教授 等）

    @SerializedName("zzmm")
    private String teacherPolitics;     // 政治面貌（群众/党员 等）

    @SerializedName("zfjmc")
    private String teacherRole;         // 授课角色（主讲/辅导）

    // ---- 教学班 ----
    @SerializedName("jxbmc")
    private String className;           // 教学班名称

    @SerializedName("jxbzc")
    private String classComposition;    // 教学班组成

    @SerializedName("jxb_id")
    private String classId;             // 教学班 ID

    @SerializedName("jxbsftkbj")
    private String classSuspended;      // 教学班是否停开（"1" 为停开）

    // ---- 教室 ----
    @SerializedName("cdmc")
    private String classroom;           // 教室名

    @SerializedName("cd_id")
    private String classroomId;         // 教室 ID

    @SerializedName("cdbh")
    private String classroomCode;       // 教室编号

    @SerializedName("cdlbmc")
    private String classroomType;       // 教室类别（多媒体/智慧教室）

    @SerializedName("lh")
    private String building;            // 楼名（东六教/西二教 等）

    // ---- 时间安排 ----
    @SerializedName("jc")
    private String period;              // 节次（如 "1-2节"）

    @SerializedName("jcs")
    private String periodNum;           // 节次数（如 "1-2"）

    @SerializedName("jcor")
    private String periodOrder;         // 节次排序

    @SerializedName("zcd")
    private String weeks;               // 周次（如 "1-5周,7-14周,16周"）

    @SerializedName("oldzc")
    private String weekMask;            // 周次位掩码（第 n 位为 1 表示第 n 周有课）

    @SerializedName("oldjc")
    private String periodMask;          // 节次位掩码（第 n 位为 1 表示第 n 节有课）

    @SerializedName("xqj")
    private String weekday;             // 星期几（数字 1-7）

    @SerializedName("xqjmc")
    private String weekdayName;         // 星期几（中文）

    @SerializedName("rk")
    private String dayOrder;            // 当日课程序号（课表网格行号，1 起）

    @SerializedName("px")
    private String displayOrder;        // 当日展示排序

    // ---- 所属日期（不同接口返回格式不同，取其一即可）----
    @SerializedName("date")
    private String dateCn;              // 中文日期（如 "二○二六年八月三日"）

    @SerializedName("dateDigit")
    private String dateCnDigit;         // 中文数字日期（如 "2026年8月3日"）

    @SerializedName("dateDigitSeparator")
    private String dateIso;             // 横线分隔日期（如 "2026-8-3"）

    // ---- 考核 ----
    @SerializedName("khfsmc")
    private String examType;            // 考核方式（考试/考查）

    @SerializedName("ksfsmc")
    private String examForm;            // 考试形式

    @SerializedName("skfsmc")
    private String teachForm;           // 授课方式

    // ---- 开课 ----
    @SerializedName("kkzt")
    private String courseStatus;        // 开课状态

    @SerializedName("sxbj")
    private String selected;            // 是否已选

    @SerializedName("xkrs")
    private String enrolledCount;       // 选课人数（已选；课表页「选课人数」列就是它）

    @SerializedName("zzrl")
    private String capacity;            // 课程容量（选课人数上限；实测恒有 xkrs <= zzrl）

    @SerializedName("sfjf")
    private String isCharge;            // 是否计费

    @SerializedName("cxbj")
    private String retake;              // 重修标记（"1" 为重修）

    @SerializedName("cxbjmc")
    private String retakeClassName;     // 重修教学班名

    @SerializedName("xnm")
    private String schoolYear;          // 学年（如 "2024"）

    @SerializedName("xqm")
    private String termCode;            // 学期代码（1/2/3）

    @SerializedName("xqdm")
    private String campusCode;          // 校区代码

    @SerializedName("xqmc")
    private String campusName;          // 校区名

    @SerializedName("xkbz")
    private String note;                // 选课备注

    // ---- 实践课专属 ----
    @SerializedName("sfsjk")
    private String isPractice;          // 是否实践课 "1"

    @SerializedName("qsjsz")
    private String practicePeriodRange; // 起止教学周

    @SerializedName("sjkcgs")
    private String practiceDetail;      // 实践课程描述

    @SerializedName("qtkcgs")
    private String practiceFullDesc;    // 实践课程完整描述

    @SerializedName("xksj")
    private String selectTime;          // 选课时间

    @SerializedName("kklxdm")
    private String courseTypeCode;      // 开课类型代码

    @SerializedName("kcbj")
    private String courseMark;          // 课程标记（主修/辅修）

    // ---- getters ----

    public String getCourseName() { return courseName; }
    public String getCourseCode() { return courseCode; }
    public String getCourseId() { return courseId; }
    public String getCourseCategory() { return courseCategory; }
    public String getCourseNature() { return courseNature; }
    public String getCredits() { return credits; }
    public String getQqGroup() { return qqGroup; }
    public String getTotalHours() { return totalHours; }
    public String getHourComposition() { return hourComposition; }
    public String getWeeklyHours() { return weeklyHours; }
    public String getTeacherName() { return teacherName != null ? teacherName : teacherName2; }
    public String getTeacherId() { return teacherId; }
    public String getTeacherTitle() { return teacherTitle; }
    public String getTeacherPolitics() { return teacherPolitics; }
    public String getTeacherRole() { return teacherRole; }
    public String getClassName() { return className; }
    public String getClassComposition() { return classComposition; }
    public String getClassId() { return classId; }
    public String getClassSuspended() { return classSuspended; }
    public String getClassroom() { return classroom; }
    public String getClassroomId() { return classroomId; }
    public String getClassroomCode() { return classroomCode; }
    public String getClassroomType() { return classroomType; }
    public String getBuilding() { return building; }
    public String getPeriod() { return period; }
    public String getPeriodNum() { return periodNum; }
    public String getWeeks() { return weeks; }
    public String getWeekMask() { return weekMask; }
    public String getPeriodMask() { return periodMask; }
    public String getWeekday() { return weekday; }
    public String getWeekdayName() { return weekdayName; }
    public String getDayOrder() { return dayOrder; }
    public String getDisplayOrder() { return displayOrder; }
    public String getDateCn() { return dateCn; }
    public String getDateCnDigit() { return dateCnDigit; }
    public String getDateIso() { return dateIso; }
    public String getExamType() { return examType; }
    public String getExamForm() { return examForm; }
    public String getTeachForm() { return teachForm; }
    public String getCourseStatus() { return courseStatus; }
    public String getSelected() { return selected; }
    public String getEnrolledCount() { return enrolledCount; }
    public String getCapacity() { return capacity; }

    /**
     * @deprecated 命名与语义不符：它返回的是<b>选课人数</b>而不是上限。
     *             请改用 {@link #getEnrolledCount()}。
     */
    @Deprecated
    public String getMaxStudents() { return enrolledCount; }

    /**
     * @deprecated 命名与语义不符：它返回的是<b>课程容量</b>而不是已选人数。
     *             请改用 {@link #getCapacity()}。
     */
    @Deprecated
    public String getEnrolled() { return capacity; }
    public String getIsCharge() { return isCharge; }
    public String getRetake() { return retake; }
    public String getRetakeClassName() { return retakeClassName; }
    public String getSchoolYear() { return schoolYear; }
    public String getTermCode() { return termCode; }
    public String getCampusCode() { return campusCode; }
    public String getCampusName() { return campusName; }
    public String getNote() { return note; }
    public String getIsPractice() { return isPractice; }
    public String getPracticePeriodRange() { return practicePeriodRange; }
    public String getPracticeDetail() { return practiceDetail; }
    public String getPracticeFullDesc() { return practiceFullDesc; }
    public String getSelectTime() { return selectTime; }
    public String getCourseTypeCode() { return courseTypeCode; }
    public String getCourseMark() { return courseMark; }

    @Override
    public String toString() {
        return getTeacherName() + " / " + courseName + " / " + period + " / " + classroom;
    }
}
