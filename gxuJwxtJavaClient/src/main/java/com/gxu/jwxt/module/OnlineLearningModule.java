package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.OnlineLearningAddress;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.PagedResult;
import com.gxu.jwxt.model.Term;
import java.io.IOException;
import java.util.Map;

/** 网上上课地址查询模块。 */
public class OnlineLearningModule {
    private static final String GNMKDM = "N1598";
    private static final String REFERER = "/jwglxt/rwlscx/wsskdzwh_cxWsskdzwhIndex.html?gnmkdm=" + GNMKDM;
    private final JwxtSession session;

    public OnlineLearningModule(JwxtSession session) { this.session = session; }

    /** 当前学生可见课程的线上上课地址和群号。 */
    public PagedResult<OnlineLearningAddress> addresses(String year, Term term) throws IOException {
        return addresses(year, term, new PageQuery());
    }

    /** 当前学生可见课程的线上上课地址和群号，支持 jqGrid 分页。 */
    public PagedResult<OnlineLearningAddress> addresses(String year, Term term, PageQuery page) throws IOException {
        session.ensureLogin();
        String body = session.post(
            "/jwglxt/rwlscx/wsskdzwh_cxWsskdzwhIndex.html?doType=query&gnmkdm=" + GNMKDM,
            page.toMap(Map.of("xnm", year, "xqm", term.code())), REFERER);
        return JsonSupport.page(body, OnlineLearningAddress.class);
    }
}
