package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.ClassCode;
import com.gxu.jwxt.model.MajorCode;
import com.gxu.jwxt.model.MajorDirection;
import java.io.IOException;
import java.util.List;

/** 公共代码字典查询模块。 */
public class DictionaryModule {
    private static final String REFERER = "/jwglxt/xtgl/index_initMenu.html";
    private final JwxtSession session;

    public DictionaryModule(JwxtSession session) { this.session = session; }

    /** 指定年级的班级代码。 */
    public List<ClassCode> classes(String gradeCode) throws IOException {
        return get("/jwglxt/xtgl/comm_cxBjdmList.html?njdm_id=" + gradeCode, ClassCode.class);
    }

    /** 指定年级的专业代码。 */
    public List<MajorCode> majors(String gradeCode) throws IOException {
        return get("/jwglxt/xtgl/comm_cxZydmList.html?njdm_id=" + gradeCode, MajorCode.class);
    }

    /** 指定专业的专业方向。 */
    public List<MajorDirection> majorDirections(String majorCode) throws IOException {
        return get("/jwglxt/xtgl/comm_cxZyfxList.html?zyh_id=" + majorCode, MajorDirection.class);
    }

    private <T> List<T> get(String path, Class<T> type) throws IOException {
        session.ensureLogin();
        return JsonSupport.list(session.get(path, REFERER), type);
    }
}
