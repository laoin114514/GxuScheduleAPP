package com.gxu.jwxt.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 成绩详情（「查看成绩详情」弹层解析结果）。
 *
 * <p>教务该页面返回 HTML 而非 JSON。实测结构为一张「成绩分项 | 成绩分项比例 | 成绩」
 * 三列表格，常见两种形态：有分项拆分（平时/期末/总评）与无分项拆分（总评成绩/总评）。
 * 解析器除结构化字段外还保留 {@link #getRows()} 明细键值对，便于直接展示原始分项。</p>
 */
public class GradeDetail {

    private String courseName;
    /** 总评成绩 */
    private String totalScore;
    /** 平时成绩 */
    private String usualScore;
    /** 期末成绩 */
    private String finalScore;
    /** 平时成绩占比，如 "30" */
    private String usualRatio;
    /** 期末成绩占比，如 "70" */
    private String finalRatio;
    /** 期末强制达标线；页面未设置时为空 */
    private String passLine;

    private final List<Row> rows = new ArrayList<>();

    /** 详情里的一行键值对 */
    public static class Row {
        private final String label;
        private final String value;

        public Row(String label, String value) {
            this.label = label;
            this.value = value;
        }

        public String getLabel() { return label; }
        public String getValue() { return value; }

        @Override
        public String toString() { return label + "=" + value; }
    }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public String getTotalScore() { return totalScore; }
    public void setTotalScore(String totalScore) { this.totalScore = totalScore; }

    public String getUsualScore() { return usualScore; }
    public void setUsualScore(String usualScore) { this.usualScore = usualScore; }

    public String getFinalScore() { return finalScore; }
    public void setFinalScore(String finalScore) { this.finalScore = finalScore; }

    public String getUsualRatio() { return usualRatio; }
    public void setUsualRatio(String usualRatio) { this.usualRatio = usualRatio; }

    public String getFinalRatio() { return finalRatio; }
    public void setFinalRatio(String finalRatio) { this.finalRatio = finalRatio; }

    public String getPassLine() { return passLine; }
    public void setPassLine(String passLine) { this.passLine = passLine; }

    public List<Row> getRows() { return Collections.unmodifiableList(rows); }

    public void addRow(String label, String value) {
        if (label == null || value == null) return;
        String l = label.trim();
        String v = value.trim();
        if (l.isEmpty() || v.isEmpty()) return;
        rows.add(new Row(l, v));
    }

    /** 是否解析出了平时/期末的拆分信息 */
    public boolean hasBreakdown() {
        return notBlank(usualScore) || notBlank(finalScore)
            || notBlank(usualRatio) || notBlank(finalRatio);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "GradeDetail{course=" + courseName + ", total=" + totalScore
            + ", usual=" + usualScore + ", final=" + finalScore
            + ", usualRatio=" + usualRatio + ", finalRatio=" + finalRatio
            + ", passLine=" + passLine + "}";
    }
}
