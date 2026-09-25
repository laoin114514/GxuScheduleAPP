package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.ExamScheduleEntry;
import com.gxu.jwxt.model.ExamSession;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.PagedResult;
import com.gxu.jwxt.model.Term;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 考试信息查询模块。 */
public class ExamModule {
    private static final String GNMKDM = "N358105";
    private static final String REFERER = "/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=" + GNMKDM;
    private final JwxtSession session;

    public ExamModule(JwxtSession session) { this.session = session; }

    /** 期末或其他已安排考试。 */
    public PagedResult<ExamScheduleEntry> schedules(String year, Term term) throws IOException {
        return schedules(year, term, new PageQuery());
    }

    public PagedResult<ExamScheduleEntry> schedules(String year, Term term, PageQuery page) throws IOException {
        return query("/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=" + GNMKDM, year, term, page);
    }

    /** 无需排考的课程。 */
    public PagedResult<ExamScheduleEntry> unscheduledCourses(String year, Term term) throws IOException {
        return unscheduledCourses(year, term, new PageQuery());
    }

    public PagedResult<ExamScheduleEntry> unscheduledCourses(String year, Term term, PageQuery page) throws IOException {
        return query("/jwglxt/kwgl/kscx_cxWpkskcList.html?doType=query&gnmkdm=" + GNMKDM, year, term, page);
    }

    /** 指定学年学期的考试场次。 */
    public List<ExamSession> sessions(String year, Term term) throws IOException {
        session.ensureLogin();
        String body = session.get("/jwglxt/ksglcommon/common_cxKsmcByXnxq.html?xnm=" + year
            + "&xqm=" + term.code(), REFERER);
        return JsonSupport.list(body, ExamSession.class);
    }

    private PagedResult<ExamScheduleEntry> query(String path, String year, Term term, PageQuery page) throws IOException {
        session.ensureLogin();
        String body = session.post(path, page.toMap(Map.of("xnm", year, "xqm", term.code())), REFERER);
        return JsonSupport.page(body, ExamScheduleEntry.class);
    }
}
