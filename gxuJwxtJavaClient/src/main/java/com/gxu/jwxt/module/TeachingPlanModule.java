package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.PagedResult;
import com.gxu.jwxt.model.TeachingPlan;
import com.gxu.jwxt.model.Term;
import java.io.IOException;
import java.util.Map;

/** 教学执行计划查询模块。 */
public class TeachingPlanModule {
    private static final String GNMKDM = "N153540";
    private static final String REFERER = "/jwglxt/jxzxjhgl/jxzxjhck_cxJxzxjhckIndex.html?gnmkdm=" + GNMKDM;
    private final JwxtSession session;

    public TeachingPlanModule(JwxtSession session) { this.session = session; }

    /** 查询教学执行计划。 */
    public PagedResult<TeachingPlan> plans(String year, Term term) throws IOException {
        return plans(year, term, new PageQuery());
    }

    /** 查询教学执行计划，支持 jqGrid 分页。 */
    public PagedResult<TeachingPlan> plans(String year, Term term, PageQuery page) throws IOException {
        session.ensureLogin();
        String body = session.post(
            "/jwglxt/jxzxjhgl/jxzxjhck_cxJxzxjhckIndex.html?doType=query&gnmkdm=" + GNMKDM,
            page.toMap(Map.of("xnm", year, "xqm", term.code())), REFERER);
        return JsonSupport.page(body, TeachingPlan.class);
    }

}
