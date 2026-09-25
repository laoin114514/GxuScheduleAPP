package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.CourseConfirmation;
import com.gxu.jwxt.model.CourseRosterEntry;
import com.gxu.jwxt.model.CourseSelectionStatus;
import com.gxu.jwxt.model.CreditConfirmationSummary;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.PagedResult;
import com.gxu.jwxt.model.Term;
import java.io.IOException;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 选课确认与选课名单的只读查询模块。 */
public class SelectionModule {
    private static final String CONFIRM_GNMKDM = "N2158";
    private static final String CONFIRM_REFERER = "/jwglxt/kbcx/xskbqr_cxXskbqrIndex.html?gnmkdm=" + CONFIRM_GNMKDM;
    private static final String ROSTER_GNMKDM = "N255010";
    private static final String ROSTER_REFERER = "/jwglxt/xkcx/xkmdcx_cxXkmdcxIndex.html?gnmkdm=" + ROSTER_GNMKDM;
    private final JwxtSession session;

    public SelectionModule(JwxtSession session) { this.session = session; }

    /** 正选页面的开放状态。 */
    public CourseSelectionStatus regularSelectionStatus() throws IOException {
        return selectionStatus(CourseSelectionStatus.Stage.REGULAR,
            "/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default");
    }

    /** 预选页面的开放状态。缺少 {@code iskxk} 时状态为 {@code UNKNOWN}。 */
    public CourseSelectionStatus preselectionStatus() throws IOException {
        return selectionStatus(CourseSelectionStatus.Stage.PRESELECTION,
            "/jwglxt/xsxk/tjxkyzb_cxTjxkYzbIndex.html?gnmkdm=N253511&layout=default");
    }

    /** 本学期的选课确认列表。 */
    public PagedResult<CourseConfirmation> confirmations(String year, Term term) throws IOException {
        return confirmations(year, term, new PageQuery());
    }

    public PagedResult<CourseConfirmation> confirmations(String year, Term term, PageQuery page) throws IOException {
        session.ensureLogin();
        String body = session.post("/jwglxt/kbcx/xskbqr_cxXskbqrIndex.html?doType=query&gnmkdm=" + CONFIRM_GNMKDM,
            page.toMap(Map.of("xnm", year, "xqm", term.code())), CONFIRM_REFERER);
        return JsonSupport.page(body, CourseConfirmation.class);
    }

    /** 选课确认页面显示的课程门数。 */
    public int selectedCourseCount(String year, Term term) throws IOException {
        session.ensureLogin();
        PageQuery page = new PageQuery();
        String body = session.post("/jwglxt/kbcx/xskbqr_cxXsxkxx.html?gnmkdm=" + CONFIRM_GNMKDM,
            page.toMap(Map.of("xnm", year, "xqm", term.code())), CONFIRM_REFERER);
        return Integer.parseInt(body.trim());
    }

    /** 未确认、已确认和总学分。 */
    public CreditConfirmationSummary creditSummary(String year, Term term) throws IOException {
        session.ensureLogin();
        String body = session.post("/jwglxt/kbcx/xskbqr_cxXsxkxfxx.html?gnmkdm=" + CONFIRM_GNMKDM,
            Map.of("xnm", year, "xqm", term.code()), CONFIRM_REFERER);
        return JsonSupport.GSON.fromJson(body, CreditConfirmationSummary.class);
    }

    /** 当前学期是否已确认选课。 */
    public boolean isConfirmed(String year, Term term) throws IOException {
        session.ensureLogin();
        String body = session.post("/jwglxt/kbcx/xskbqr_cxSfyqr.html?gnmkdm=" + CONFIRM_GNMKDM,
            Map.of("xnm", year, "xqm", term.code()), CONFIRM_REFERER);
        return "1".equals(JsonSupport.GSON.fromJson(body, String.class));
    }

    /** 可见范围内的选课名单，可用教学班或课程 ID 进一步筛选。 */
    public PagedResult<CourseRosterEntry> roster(String year, Term term, String courseId, String teachingClassId)
            throws IOException {
        return roster(year, term, courseId, teachingClassId, new PageQuery());
    }

    public PagedResult<CourseRosterEntry> roster(String year, Term term, String courseId, String teachingClassId,
                                                  PageQuery page) throws IOException {
        session.ensureLogin();
        Map<String, String> data = page.toMap(Map.of(
            "xnm", year, "xqm", term.code(),
            "kch_id", courseId != null ? courseId : "",
            "jxb_id", teachingClassId != null ? teachingClassId : ""
        ));
        String body = session.post("/jwglxt/xkcx/xkmdcx_cxXkmdcxIndex.html?doType=query&gnmkdm=" + ROSTER_GNMKDM,
            data, ROSTER_REFERER);
        return JsonSupport.page(body, CourseRosterEntry.class);
    }

    private CourseSelectionStatus selectionStatus(CourseSelectionStatus.Stage stage, String path) throws IOException {
        session.ensureLogin();
        String body = session.get(path, path);
        Document document = Jsoup.parse(body);
        Element flag = document.selectFirst("#iskxk");
        String value = flag != null ? flag.attr("value") : "";
        CourseSelectionStatus.State state = "1".equals(value) ? CourseSelectionStatus.State.OPEN
            : "0".equals(value) ? CourseSelectionStatus.State.CLOSED
            : CourseSelectionStatus.State.UNKNOWN;
        Element control = document.selectFirst("#xkkz_id, input[name=xkkz_id]");
        Element notice = document.selectFirst(".alert, .error, #tips, .form-msg");
        return new CourseSelectionStatus(stage, state,
            control != null ? control.attr("value") : null,
            notice != null ? notice.text().trim() : null);
    }
}
