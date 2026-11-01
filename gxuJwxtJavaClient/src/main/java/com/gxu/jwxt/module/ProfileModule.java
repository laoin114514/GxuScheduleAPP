package com.gxu.jwxt.module;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 学生个人信息查询模块 */
public class ProfileModule {

    private static final String GNMKDM = "N100801";
    private static final String PROFILE_JSON_PATH =
        "/jwglxt/xsxxxggl/xsxxwh_cxCkDgxsxx.html?gnmkdm=" + GNMKDM;
    private static final String PROFILE_PAGE_PATH =
        "/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=" + GNMKDM;
    private static final Gson gson = new Gson();

    /**
     * 个人信息页面里 col_* 元素 id 与接口 JSON 字段的对应关系。
     * 页面是服务端直出，字段名与 JSON 接口一致（xh、xm、njdm_id…）。
     */
    private static final String[] PAGE_FIELDS = {
        "xh", "xm", "xmpy", "xbm", "csrq", "mzm", "zjhm", "zjlxm", "rxrq",
        "njdm_id", "bh_id", "zyh_id", "jg_id", "pyccdm", "pyfsdm", "xlccdm",
        "xjztdm", "sfzx", "ksh", "zkzh", "byzx", "rxzf", "xxnx", "xz", "lym",
        "syd", "zzmmm", "xslbdm", "zsnddm", "zszyh_id", "zsjg_id", "bdh", "bdzcbj"
    };

    private final JwxtSession session;
    private String xhId; // 学号，首次查询时自动获取

    public ProfileModule(JwxtSession session) {
        this.session = session;
    }

    // ========== 学生基本信息 ==========

    /**
     * 获取当前学生基本信息。
     *
     * <p>优先调用 JSON 接口；2026-11 实测部分部署该接口返回 HTTP 200 + 空 body，
     * 而个人信息页面（服务端直出 HTML）仍带完整字段，此时回退解析页面。</p>
     */
    public StudentProfile profile() throws IOException {
        session.ensureLogin();

        String body = session.post(PROFILE_JSON_PATH, Map.of(), PROFILE_PAGE_PATH);
        StudentProfile p = null;
        if (body != null && !body.isBlank() && !"null".equals(body.trim())) {
            p = gson.fromJson(body, StudentProfile.class);
        }

        // JSON 接口空包 / 字面量 null / 缺少学号 → 回退解析个人信息页面
        if (p == null || p.getStudentId() == null || p.getStudentId().isBlank()) {
            p = profileFromPage();
        }

        if (p == null || p.getStudentId() == null || p.getStudentId().isBlank()) {
            throw new IOException("教务系统未返回学生信息（账号可能不是学生账号，或学籍信息未完善）");
        }
        this.xhId = p.getStudentId();
        return p;
    }

    /** 解析个人信息页面 HTML（服务端直出，字段在 id="col_xxx" 的元素里）。 */
    private StudentProfile profileFromPage() throws IOException {
        String html = session.get(PROFILE_PAGE_PATH);
        return parseProfilePage(html);
    }

    /**
     * 从个人信息页面 HTML 还原 {@link StudentProfile}。
     * 独立成包级静态方法便于用固定 HTML 做离线单测。
     */
    static StudentProfile parseProfilePage(String html) throws IOException {
        Document doc = Jsoup.parse(html);
        JsonObject o = new JsonObject();

        for (String key : PAGE_FIELDS) {
            Element el = doc.getElementById("col_" + key);
            if (el == null) continue;
            String value = el.text().trim();
            if (!value.isEmpty()) o.addProperty(key, value);
        }

        // 学号兜底：页面头部/隐藏域（部分页面 col_xh 为空）
        if (!o.has("xh")) {
            Element xh = doc.selectFirst("input[name=xh_id][value]");
            if (xh != null && !xh.attr("value").isBlank()) {
                o.addProperty("xh", xh.attr("value").trim());
            }
        }

        // 当前学年/学期来自隐藏域，不在 col_* 里
        addHidden(doc, o, "xnm");
        addHidden(doc, o, "xqm");

        if (!o.has("xh")) {
            throw new IOException("个人信息页面未包含学号，登录状态可能已失效");
        }

        StudentProfile p = gson.fromJson(o, StudentProfile.class);
        if (p == null) throw new IOException("个人信息页面解析失败");
        return p;
    }

    private static void addHidden(Document doc, JsonObject o, String name) {
        Element el = doc.selectFirst("input[name=" + name + "][value]");
        if (el != null && !el.attr("value").isBlank()) {
            o.addProperty(name, el.attr("value").trim());
        }
    }

    // ========== 班级信息 ==========

    /**
     * 获取班级列表
     */
    public List<StudentClass> classList() throws IOException {
        session.ensureLogin();
        String xh = ensureXhId();
        String body = session.post(
            "/jwglxt/xsxxxggl/xsxxwh_cxBjxxList.html?gnmkdm=" + GNMKDM,
            Map.of("xh_id", xh),
            "/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=" + GNMKDM
        );
        return JsonSupport.list(body, StudentClass.class);
    }

    /** 获取指定学年的可查询学期。 */
    public List<TermOption> terms(String year) throws IOException {
        session.ensureLogin();
        String body = session.post(
            "/jwglxt/xsxxxggl/xsxxwh_cxXqm.html?gnmkdm=" + GNMKDM,
            Map.of("xh_id", ensureXhId(), "xnm", year),
            "/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=" + GNMKDM
        );
        return JsonSupport.list(body, TermOption.class);
    }

    /** 当前学生是否具备扩班查看资格。 */
    public boolean canViewExpandedClasses() throws IOException {
        session.ensureLogin();
        String body = session.post(
            "/jwglxt/xsxxxggl/xsxxwh_cxViewKbzg.html?gnmkdm=" + GNMKDM,
            Map.of("xh_id", ensureXhId()),
            "/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=" + GNMKDM
        );
        return "1".equals(gson.fromJson(body, String.class));
    }

    // ========== 等级考试 ==========

    /**
     * 等级考试成绩（CET-4/6 等）
     */
    public PagedResult<ExamEntry> exams() throws IOException {
        return exams(new PageQuery());
    }

    /** 等级考试成绩（CET-4/6 等）。 */
    public PagedResult<ExamEntry> exams(PageQuery page) throws IOException {
        session.ensureLogin();
        String xh = ensureXhId();
        Map<String, String> params = page.toMap(Map.of("xh_id", xh));
        String body = session.post(
            "/jwglxt/xsxxxggl/xsxxwh_cxDjksxx.html?gnmkdm=" + GNMKDM,
            params,
            "/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=" + GNMKDM
        );
        return JsonSupport.page(body, ExamEntry.class);
    }

    // ========== 选课信息 ==========

    /**
     * 选课信息列表
     */
    public PagedResult<CourseSelectionEntry> courseSelections() throws IOException {
        return courseSelections(new PageQuery());
    }

    /** 历史选课信息。 */
    public PagedResult<CourseSelectionEntry> courseSelections(PageQuery page) throws IOException {
        session.ensureLogin();
        String xh = ensureXhId();
        Map<String, String> params = page.toMap(Map.of("xh_id", xh));
        String body = session.post(
            "/jwglxt/xsxxxggl/xsxxwh_cxXsxkxx.html?gnmkdm=" + GNMKDM,
            params,
            "/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=" + GNMKDM
        );
        return JsonSupport.page(body, CourseSelectionEntry.class);
    }

    // ========== 内部 ==========

    private String ensureXhId() throws IOException {
        if (xhId == null) {
            StudentProfile p = profile();
            xhId = p.getStudentId();
        }
        return xhId;
    }
}
