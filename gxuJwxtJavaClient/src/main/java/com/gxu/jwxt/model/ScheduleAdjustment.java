package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/**
 * 一条教学班调课记录（「查看教学班调课记录」弹层里的 modelList 元素）。
 *
 * <p>接口 {@code GET /jwglxt/kbdy/xskbdy_cxJxbtkjlView.html?gnmkdm=N2151&jxb_id=...}
 * 返回 HTML，内嵌 {@code var modelList = eval([...]);}，本类对应数组中的一项。
 * 字段名映射教务系统拼音缩写。</p>
 *
 * <p>三种记录类型由 {@link #adjustmentTypeName}（{@code tklxmc}）区分，实测值：</p>
 * <ul>
 *   <li><b>调课</b>：原时间 → 现时间（周次/星期/节次可同可不同，教师/教室可换），
 *       原、现字段都有值；</li>
 *   <li><b>补课</b>：{@code yzcd/yxqj/yjc} 为 null，只有现周次/现星期/现节次（新增的一次课）；</li>
 *   <li><b>停课</b>：只有原字段，现字段为 null（该周教务课表里本就没有这节课的行，无需标记）。</li>
 * </ul>
 *
 * <p>周次字段形如 {@code "12-13周"}、星期字段形如 {@code "六"}、节次字段形如
 * {@code "第5-8节"}，具体解析在客户端导入层完成。</p>
 */
public class ScheduleAdjustment {

    // ---- 记录 / 教学班标识 ----
    @SerializedName("ttkxx_id")
    private String adjustmentDetailId;      // 调课记录明细 ID
    @SerializedName("ttk_id")
    private String adjustmentId;            // 调课记录 ID（同一次申请）
    @SerializedName("jxb_id")
    private String classId;                 // 教学班 ID（与课表条目 jxb_id 对应）
    @SerializedName("kcmc")
    private String courseName;              // 课程名
    @SerializedName("jxbmc")
    private String className;               // 教学班名称

    // ---- 类型 ----
    @SerializedName("tklxmc")
    private String adjustmentTypeName;      // 调课 / 停课 / 补课
    @SerializedName("tklxdm")
    private String adjustmentTypeCode;      // 01 调课 / 02 补课 / 03 停课

    // ---- 原时间（停课只有原字段）----
    @SerializedName("yzcd")
    private String originalWeeks;           // 原周次（如 "12-13周"）
    @SerializedName("yxqj")
    private String originalWeekday;         // 原星期（如 "六"）
    @SerializedName("yjc")
    private String originalPeriod;          // 原节次（如 "第5-8节"）
    @SerializedName("yjsxm")
    private String originalTeacher;         // 原教师
    @SerializedName("ycdmc")
    private String originalClassroom;       // 原教室

    // ---- 现时间（停课为 null）----
    @SerializedName("xzcd")
    private String currentWeeks;            // 现周次（如 "12-13周"）
    @SerializedName("xxqj")
    private String currentWeekday;          // 现星期（如 "六"）
    @SerializedName("xjc")
    private String currentPeriod;           // 现节次（如 "第5-8节"）
    @SerializedName("xjsxm")
    private String currentTeacher;          // 现教师
    @SerializedName("xcdmc")
    private String currentClassroom;        // 现教室

    // ---- 原因 / 备注 ----
    @SerializedName("yylb")
    private String reasonCategory;          // 原因类别（如 教学需要…）
    @SerializedName("tkyy")
    private String reason;                  // 调课原因
    @SerializedName("tksm")
    private String remark;                  // 调课说明

    // ---- 学期 ----
    @SerializedName("xnm")
    private String schoolYear;              // 学年（如 "2025"）
    @SerializedName("xqm")
    private String term;                    // 学期代码（如 "12"）

    // ---- getters ----

    public String getAdjustmentDetailId() { return adjustmentDetailId; }
    public String getAdjustmentId() { return adjustmentId; }
    public String getClassId() { return classId; }
    public String getCourseName() { return courseName; }
    public String getClassName() { return className; }
    public String getAdjustmentTypeName() { return adjustmentTypeName; }
    public String getAdjustmentTypeCode() { return adjustmentTypeCode; }
    public String getOriginalWeeks() { return originalWeeks; }
    public String getOriginalWeekday() { return originalWeekday; }
    public String getOriginalPeriod() { return originalPeriod; }
    public String getOriginalTeacher() { return originalTeacher; }
    public String getOriginalClassroom() { return originalClassroom; }
    public String getCurrentWeeks() { return currentWeeks; }
    public String getCurrentWeekday() { return currentWeekday; }
    public String getCurrentPeriod() { return currentPeriod; }
    public String getCurrentTeacher() { return currentTeacher; }
    public String getCurrentClassroom() { return currentClassroom; }
    public String getReasonCategory() { return reasonCategory; }
    public String getReason() { return reason; }
    public String getRemark() { return remark; }
    public String getSchoolYear() { return schoolYear; }
    public String getTerm() { return term; }

    /**
     * 是否是「停课」记录：只有原时间，没有现时间。
     * 停课不会在课表里留下可标记的行，展示层通常直接跳过。
     */
    public boolean isCancellation() {
        return (currentWeeks == null || currentWeeks.trim().isEmpty())
                && (currentWeekday == null || currentWeekday.trim().isEmpty())
                && (currentPeriod == null || currentPeriod.trim().isEmpty());
    }

    @Override
    public String toString() {
        return adjustmentTypeName + " / " + className + " / "
                + originalWeeks + " " + originalWeekday + " " + originalPeriod
                + " -> " + currentWeeks + " " + currentWeekday + " " + currentPeriod;
    }
}
