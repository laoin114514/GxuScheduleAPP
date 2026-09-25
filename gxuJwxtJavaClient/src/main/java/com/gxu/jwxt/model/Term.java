package com.gxu.jwxt.model;

/**
 * 学期枚举。
 *
 * <p>教务系统使用数字编码表示学期：</p>
 * <ul>
 *   <li>{@link #AUTUMN} — 第一学期（9月–次年1月），编码 {@code "3"}</li>
 *   <li>{@link #SPRING} — 第二学期（2月–7月），编码 {@code "12"}</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 * client.schedule().personal("2025", Term.SPRING);
 * }</pre>
 */
public enum Term {

    AUTUMN("3", "第一学期"),
    SPRING("12", "第二学期");

    private final String code;
    private final String label;

    Term(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 教务系统学期编码（"3" 或 "12"） */
    public String code() { return code; }

    /** 中文名称（"第一学期" 或 "第二学期"） */
    public String label() { return label; }

    /** 根据编码查找，找不到返回 null */
    public static Term fromCode(String code) {
        for (Term t : values()) {
            if (t.code.equals(code)) return t;
        }
        return null;
    }

    /** 根据当前月份推断学期 */
    public static Term current() {
        int m = java.time.LocalDate.now().getMonthValue();
        return (m >= 9 || m < 2) ? AUTUMN : SPRING;
    }

    @Override
    public String toString() {
        return label + " (" + code + ")";
    }
}
