package com.gxu.jwxt.module;

import com.google.gson.Gson;
import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.exceptions.SessionExpiredException;
import com.gxu.jwxt.model.ClassScheduleResponse;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.PeriodLevel;
import com.gxu.jwxt.model.ScheduleAdjustment;
import com.gxu.jwxt.model.ScheduleResponse;
import com.gxu.jwxt.model.ScheduleDisplayField;
import com.gxu.jwxt.model.TeachingTimeModel;
import com.gxu.jwxt.model.TeacherScheduleResponse;
import com.gxu.jwxt.model.Term;
import com.gxu.jwxt.model.TimePeriod;
import com.gxu.jwxt.model.TimeSegment;
import com.gxu.jwxt.model.WeekInfo;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 课表查询模块 */
public class ScheduleModule {

    private static final String GNMKDM = "N2151";
    /** 学生课表页，业务请求统一作为 Referer（在校生课表相关接口要求带课表页来源）。 */
    private static final String SCHEDULE_REFERER =
        "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=" + GNMKDM;
    private static final Gson gson = new Gson();

    private final JwxtSession session;

    public ScheduleModule(JwxtSession session) {
        this.session = session;
    }

    /**
     * 个人课表数据
     */
    public ScheduleResponse personal(String year, Term term) throws IOException {
        return personal(year, term.code());
    }

    /** @deprecated 使用 {@link #personal(String, Term)} */
    @Deprecated
    public ScheduleResponse personal(String year, String term) throws IOException {
        session.ensureLogin();
        PageQuery q = new PageQuery();
        Map<String, String> data = q.toMap(Map.of("xnm", year, "xqm", term));
        String body = session.post(
            "/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=" + GNMKDM,
            data,
            "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=" + GNMKDM
        );
        return gson.fromJson(body, ScheduleResponse.class);
    }

    /**
     * 教师课表。教师查询必须传教师工号，按姓名查询在学生角色下实测为空。
     */
    public TeacherScheduleResponse teacher(String year, Term term, String teacherId) throws IOException {
        return teacher(year, term.code(), teacherId);
    }

    /** @deprecated 使用 {@link #teacher(String, Term, String)} */
    @Deprecated
    public TeacherScheduleResponse teacher(String year, String term, String teacherId) throws IOException {
        session.ensureLogin();
        PageQuery q = new PageQuery();
        Map<String, String> data = q.toMap(Map.of(
            "xnm", year,
            "xqm", term,
            "jgh_id", teacherId != null ? teacherId : ""
        ));
        String body = session.post(
            "/jwglxt/kbcx/jskbcx_cxJsKb.html?gnmkdm=" + GNMKDM,
            data,
            "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=" + GNMKDM
        );
        return gson.fromJson(body, TeacherScheduleResponse.class);
    }

    /** 个人实践课表。 */
    public ScheduleResponse practice(String year, Term term) throws IOException {
        session.ensureLogin();
        PageQuery q = new PageQuery();
        String body = session.post(
            "/jwglxt/kbcx/xskbcx_cxXsywKb.html?gnmkdm=" + GNMKDM,
            q.toMap(Map.of("xnm", year, "xqm", term.code())),
            "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=" + GNMKDM
        );
        return gson.fromJson(body, ScheduleResponse.class);
    }

    /**
     * 教学班调课记录（弹层「查看教学班调课记录」）。
     *
     * <p>对应 {@code GET /jwglxt/kbdy/xskbdy_cxJxbtkjlView.html?gnmkdm=N2151&jxb_id=...}，
     * 返回 HTML（内嵌 modelList），由 {@link ScheduleAdjustmentParser} 解析。</p>
     *
     * <p>失败策略：调课记录只是课表上的「调课」标记来源，缺失时降级为无标记即可，
     * 不能让一次课表导入失败。因此非 200、网络错误、登录页 / 会话过期等情况
     * 一律返回空列表（不抛异常）；只有「未登录」属于调用方编程错误，仍照常抛出。</p>
     *
     * @param classId 教学班 ID（课表条目的 {@code jxb_id}），会做 URL 编码；空值返回空列表
     */
    public List<ScheduleAdjustment> adjustmentRecords(String classId) throws IOException {
        if (classId == null || classId.trim().isEmpty()) return List.of();
        session.ensureLogin();
        String path = "/jwglxt/kbdy/xskbdy_cxJxbtkjlView.html?gnmkdm=" + GNMKDM
                + "&jxb_id=" + URLEncoder.encode(classId.trim(), StandardCharsets.UTF_8);
        try {
            String body = session.get(path, SCHEDULE_REFERER);
            return ScheduleAdjustmentParser.parse(body);
        } catch (SessionExpiredException e) {
            // 登录页：静默降级为无调课记录（再次登录由上层统一处理）
            return List.of();
        } catch (IOException e) {
            // 非 200 / 网络异常：同样降级，不打断整次课表导入
            return List.of();
        }
    }

    /** 每日节次及其起止时间。 */
    public List<TimePeriod> timePeriods(String year, Term term, String campusId) throws IOException {
        return list("/jwglxt/kbcx/xskbcx_cxRjc.html?gnmkdm=" + GNMKDM,
            Map.of("xnm", year, "xqm", term.code(), "xqh_id", campusId), TimePeriod.class,
            "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=" + GNMKDM);
    }

    /** 每日作息时段。 */
    public List<TimeSegment> timeSegments(String year, Term term, String campusId) throws IOException {
        return list("/jwglxt/kbcx/xskbcx_cxRsd.html?gnmkdm=" + GNMKDM,
            Map.of("xnm", year, "xqm", term.code(), "xqh_id", campusId), TimeSegment.class,
            "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=" + GNMKDM);
    }

    /** 上午、下午、晚上等作息时间模型。 */
    public List<TeachingTimeModel> timeModels(String year, Term term, String campusId) throws IOException {
        return list("/jwglxt/kbdy/jskbdy_cxTimeModelList.html?gnmkdm=" + GNMKDM,
            Map.of("xnm", year, "xqm", term.code(), "xqh_id", campusId), TeachingTimeModel.class,
            "/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM);
    }

    /** 学时等级列表。 */
    public List<PeriodLevel> periodLevels(String year, Term term, String campusId) throws IOException {
        return list("/jwglxt/kbdy/jskbdy_cxXsdjList2.html?gnmkdm=" + GNMKDM,
            Map.of("xnm", year, "xqm", term.code(), "xqh_id", campusId), PeriodLevel.class,
            "/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM);
    }

    // ========== 班级课表 ==========

    private static final String CLASS_SCHEDULE_GNMKDM = "N214505";

    /**
     * 班级课表详情（结构化数据）。
     *
     * @param year      学年，如 {@code "2025"}
     * @param term      学期，{@link Term#AUTUMN} 或 {@link Term#SPRING}
     * @param classId   班级 ID，如 {@code "24071101"}
     * @param gradeCode 年级代码，如 {@code "2024"}
     * @param majorCode 专业号 ID，如 {@code "0711"}
     * @return 班级课表数据
     */
    public ClassScheduleResponse classDetail(String year, Term term, String classId,
                                              String gradeCode, String majorCode) throws IOException {
        return classDetail(year, term.code(), classId, gradeCode, majorCode);
    }

    /**
     * 班级课表详情（结构化数据，使用学期编码）。
     *
     * @deprecated 使用 {@link #classDetail(String, Term, String, String, String)}
     */
    @Deprecated
    public ClassScheduleResponse classDetail(String year, String termCode, String classId,
                                              String gradeCode, String majorCode) throws IOException {
        session.ensureLogin();
        PageQuery q = new PageQuery();
        Map<String, String> data = q.toMap(new LinkedHashMap<>() {{
            put("xnm", year);
            put("xqm", termCode);
            put("bh_id", classId);
            put("njdm_id", gradeCode);
            put("zyh_id", majorCode);
            put("xqh_id", "1");
            put("tjkbzdm", "1");
            put("tjkbzxsdm", "0");
            put("kzlx", "ck");
            put("sfcxxqh", "1");
        }});
        String body = session.post(
            "/jwglxt/kbdy/bjkbdy_cxBjKb.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM,
            data,
            "/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM
        );
        return gson.fromJson(body, ClassScheduleResponse.class);
    }

    /** 学期教学周。 */
    public List<WeekInfo> weeks(String year, Term term) throws IOException {
        return list("/jwglxt/kbdy/bjkbdy_cxZcxx.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM,
            Map.of("xnm", year, "xqm", term.code()), WeekInfo.class,
            "/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM);
    }

    /** 班级课表页面显示字段。 */
    public List<ScheduleDisplayField> classDisplayFields(String year, Term term) throws IOException {
        return list("/jwglxt/kbdy/bjkbdy_cxKbzdxsxx.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM,
            Map.of("doType", "query", "kbzl", "bj", "xnm", year, "xqm", term.code()),
            ScheduleDisplayField.class,
            "/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM);
    }

    /** 指定学年学期是否处于课表开放控制中。 */
    public boolean isTermOpen(String year, Term term) throws IOException {
        session.ensureLogin();
        String body = session.post(
            "/jwglxt/kbdy/bjkbdy_cxXnxqsfkz.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM,
            Map.of("xnm", year, "xqm", term.code()),
            "/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=" + CLASS_SCHEDULE_GNMKDM
        );
        return Boolean.parseBoolean(gson.fromJson(body, String.class));
    }

    private <T> List<T> list(String path, Map<String, String> data, Class<T> type, String referer) throws IOException {
        session.ensureLogin();
        String body = session.post(path, data, referer);
        return JsonSupport.list(body, type);
    }
}
