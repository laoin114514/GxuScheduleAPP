package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.CampusPeriodOptions;
import com.gxu.jwxt.model.Classroom;
import com.gxu.jwxt.model.CurrentWeekInfo;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.PagedResult;
import com.gxu.jwxt.model.Term;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 空闲教室查询模块。 */
public class ClassroomModule {
    private static final String GNMKDM = "N2155";
    private static final String REFERER = "/jwglxt/cdjy/cdjy_cxKxcdlb.html?gnmkdm=" + GNMKDM;
    private final JwxtSession session;

    public ClassroomModule(JwxtSession session) { this.session = session; }

    /** 查询指定校区所有满足条件的教室。空字符串代表不限定该条件。 */
    public PagedResult<Classroom> search(String year, Term term, String campusId,
                                          String classroomName, String buildingCode) throws IOException {
        return search(year, term, campusId, classroomName, buildingCode, new PageQuery());
    }

    /** 查询指定校区所有满足条件的教室，支持 jqGrid 分页。 */
    public PagedResult<Classroom> search(String year, Term term, String campusId,
                                          String classroomName, String buildingCode, PageQuery page) throws IOException {
        session.ensureLogin();
        Map<String, String> data = page.toMap(new LinkedHashMap<>() {{
            put("xnm", year);
            put("xqm", term.code());
            put("xqh_id", campusId);
            put("jyfs", "1");
            put("qssj", "");
            put("jssj", "");
            put("cdmc", classroomName != null ? classroomName : "");
            put("lh", buildingCode != null ? buildingCode : "");
            put("cdlb_id", "");
            put("cdejlb_id", "");
            put("qszws", "");
            put("jszws", "");
            put("sjfw", "");
            put("cdjylx", "");
        }});
        String body = session.post("/jwglxt/cdjy/cdjy_cxKxcdlb.html?doType=query&gnmkdm=" + GNMKDM,
            data, REFERER);
        return JsonSupport.page(body, Classroom.class);
    }

    /** 教室查询页面的教学楼与节次筛选项。 */
    public CampusPeriodOptions periodOptions(String year, Term term, String campusId) throws IOException {
        session.ensureLogin();
        String body = session.get("/jwglxt/cdjy/cdjy_cxXqjc.html?xnm=" + year + "&xqm="
            + term.code() + "&xqh_id=" + campusId, REFERER);
        return JsonSupport.GSON.fromJson(body, CampusPeriodOptions.class);
    }

    /** 空调相关节次，学校未配置时返回空列表。 */
    public List<String> airConditioningPeriods(String year, Term term, String campusId) throws IOException {
        session.ensureLogin();
        String body = session.get("/jwglxt/cdjy/cdjy_cxKtjc.html?xnm=" + year + "&xqm="
            + term.code() + "&xqh_id=" + campusId, REFERER);
        if ("null".equals(body.trim())) return List.of();
        return JsonSupport.GSON.fromJson(body,
            new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
    }

    /** 当前教学周、星期及整学期可选周。 */
    public CurrentWeekInfo currentWeek(String year, Term term, String campusId) throws IOException {
        session.ensureLogin();
        String body = session.post("/jwglxt/cdjy/cdjy_cxQtlb.html?gnmkdm=" + GNMKDM,
            Map.of("xnm", year, "xqm", term.code(), "xqh_id", campusId, "flag", "0"), REFERER);
        return JsonSupport.GSON.fromJson(body, CurrentWeekInfo.class);
    }

    /** 指定周、星期对应的实际日期。 */
    public List<String> dates(String year, Term term, String weekdays, String week) throws IOException {
        session.ensureLogin();
        String body = session.post("/jwglxt/cdjy/cdjy_cxDateInforma.html?gnmkdm=" + GNMKDM,
            Map.of("xnm", year, "xqm", term.code(), "xqj", weekdays, "zcd", week), REFERER);
        return JsonSupport.GSON.fromJson(body,
            new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
    }
}
