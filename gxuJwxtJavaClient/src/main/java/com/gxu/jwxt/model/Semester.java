package com.gxu.jwxt.model;

import java.time.LocalDate;

/** 学年学期 */
public class Semester {
    private final String year;
    private final Term term;

    public Semester(String year, Term term) {
        this.year = year;
        this.term = term;
    }

    /** @deprecated 使用 {@link #Semester(String, Term)} */
    @Deprecated
    public Semester(String year, String termCode) {
        this.year = year;
        this.term = Term.fromCode(termCode);
        if (this.term == null) {
            throw new IllegalArgumentException("无效的学期编码: " + termCode + "，请使用 Term.SPRING 或 Term.AUTUMN");
        }
    }

    public String getYear() { return year; }
    public Term getTerm() { return term; }
    /** 学期编码字符串（"3" 或 "12"），用于构造 API 参数 */
    public String getTermCode() { return term.code(); }

    /** 根据当前日期推断学期 */
    public static Semester current() {
        LocalDate now = LocalDate.now();
        int y = now.getYear();
        int m = now.getMonthValue();
        Term t = Term.current();
        if (t == Term.AUTUMN && m >= 9) {
            return new Semester(String.valueOf(y), t);
        } else if (t == Term.SPRING) {
            return new Semester(String.valueOf(y - 1), t);
        } else {
            return new Semester(String.valueOf(y - 1), t);
        }
    }

    @Override
    public String toString() {
        return year + "-" + (Integer.parseInt(year) + 1) + " " + term.label();
    }
}
