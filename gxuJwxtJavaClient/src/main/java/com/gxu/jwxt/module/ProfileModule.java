package com.gxu.jwxt.module;

import com.google.gson.Gson;
import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 学生个人信息查询模块 */
public class ProfileModule {

    private static final String GNMKDM = "N100801";
    private static final Gson gson = new Gson();

    private final JwxtSession session;
    private String xhId; // 学号，首次查询时自动获取

    public ProfileModule(JwxtSession session) {
        this.session = session;
    }

    // ========== 学生基本信息 ==========

    /**
     * 获取当前学生基本信息
     */
    public StudentProfile profile() throws IOException {
        session.ensureLogin();
        String body = session.post(
            "/jwglxt/xsxxxggl/xsxxwh_cxCkDgxsxx.html?gnmkdm=" + GNMKDM,
            Map.of(),
            "/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=" + GNMKDM
        );
        StudentProfile p = gson.fromJson(body, StudentProfile.class);
        if (p.getStudentId() != null) {
            this.xhId = p.getStudentId();
        }
        return p;
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
