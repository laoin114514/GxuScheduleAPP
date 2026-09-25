package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.CreditStatistic;
import com.gxu.jwxt.model.GradeCount;
import com.gxu.jwxt.model.GradeDetail;
import com.gxu.jwxt.model.GradeEntry;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.PagedResult;
import com.gxu.jwxt.model.Term;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 成绩查询模块。 */
public class GradeModule {
    private static final String GNMKDM = "N305005";
    private static final String REFERER = "/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=" + GNMKDM;
    /**
     * 成绩详情弹层，返回 HTML 而非 JSON。
     *
     * <p>实测该端点必须带 {@code gnmkdm=N305005}（页面 JS 的 {@code $.getURL} 会自动追加）：
     * 缺失时 POST 返回 500、GET 被重定向到登录页。</p>
     */
    private static final String DETAIL_PATH = "/jwglxt/cjcx/cjcx_cxCjxqGjh.html?gnmkdm=" + GNMKDM;
    private final JwxtSession session;

    public GradeModule(JwxtSession session) {
        this.session = session;
    }

    /** 查询一个学期的课程成绩。 */
    public PagedResult<GradeEntry> term(String year, Term term) throws IOException {
        return term(year, term, new PageQuery());
    }

    public PagedResult<GradeEntry> term(String year, Term term, PageQuery page) throws IOException {
        return query(year, term.code(), page);
    }

    /** 查询全部学期的课程成绩。 */
    public PagedResult<GradeEntry> all() throws IOException {
        return all(new PageQuery());
    }

    public PagedResult<GradeEntry> all(PageQuery page) throws IOException {
        return query("", "", page);
    }

    /** 指定学期的课程数与学生数。 */
    public GradeCount count(String year, Term term) throws IOException {
        session.ensureLogin();
        PageQuery page = new PageQuery();
        String body = session.post("/jwglxt/cjcx/cjcx_cxXxCount.html?gnmkdm=" + GNMKDM,
            page.toMap(Map.of("xnm", year, "xqm", term.code())), REFERER);
        return JsonSupport.GSON.fromJson(body, GradeCount.class);
    }

    /** 按课程性质汇总已修学分。 */
    public List<CreditStatistic> creditStatistics() throws IOException {
        session.ensureLogin();
        String body = session.get("/jwglxt/cjcx/cjcx_cxXsxftj.html", REFERER);
        return JsonSupport.list(body, CreditStatistic.class);
    }

    /** 成绩页项目类别下拉选项。 */
    public List<String> projectCategories(String year, Term term) throws IOException {
        session.ensureLogin();
        String body = session.get("/jwglxt/cjcx/cjcx_cxXmblbzlist.html?xnm=" + year
            + "&xqm=" + term.code(), REFERER);
        return JsonSupport.GSON.fromJson(body,
            new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
    }

    /**
     * 查看单条成绩的详情（平时/期末/总评拆分）。
     *
     * <p>该接口返回 HTML 弹层，字段随教务版本变化，解析结果可能不完整，
     * 调用方需对空字段做降级展示（列表接口已有的成绩仍可用）。</p>
     */
    public GradeDetail detail(GradeEntry entry) throws IOException {
        if (entry == null) return new GradeDetail();
        return detail(entry.getClassId(), entry.getSchoolYear(), entry.getTerm(),
            entry.getStudentId(), entry.getCourseName());
    }

    public GradeDetail detail(String classId, String year, Term term,
                              String studentId, String courseName) throws IOException {
        return detail(classId, year, term != null ? term.code() : "", studentId, courseName);
    }

    /**
     * @param classId   教学班 ID（列表条目 jxb_id）
     * @param year      学年（列表条目 xnm）
     * @param term      学期代码（列表条目 xqm）
     * @param studentId 学号 ID（列表条目 xh_id，哈希串）
     * @param courseName 课程名，仅用于页面回显缺失时的兜底
     */
    public GradeDetail detail(String classId, String year, String term,
                              String studentId, String courseName) throws IOException {
        session.ensureLogin();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("jxb_id", nullToEmpty(classId));
        data.put("xnm", nullToEmpty(year));
        data.put("xqm", nullToEmpty(term));
        data.put("xh_id", nullToEmpty(studentId));
        data.put("kcmc", nullToEmpty(courseName));

        String body;
        try {
            body = session.post(DETAIL_PATH, data, REFERER);
        } catch (IOException e) {
            // 个别部署只认 query 参数，POST 取不到内容时回退 GET
            body = session.get(DETAIL_PATH + "?" + toQuery(data), REFERER);
        }
        return GradeDetailParser.parse(body, courseName);
    }

    private PagedResult<GradeEntry> query(String year, String term, PageQuery page) throws IOException {
        session.ensureLogin();
        String body = session.post("/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=" + GNMKDM,
            page.toMap(Map.of("xnm", year, "xqm", term)), REFERER);
        return JsonSupport.page(body, GradeEntry.class);
    }

    private static String toQuery(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(nullToEmpty(entry.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
