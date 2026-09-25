package com.gxu.jwxt.module;

import com.gxu.jwxt.JwxtSession;
import com.gxu.jwxt.model.MenuItem;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 已授权功能菜单查询模块。 */
public class MenuModule {
    private final JwxtSession session;

    public MenuModule(JwxtSession session) { this.session = session; }

    /** 返回当前用户的菜单树。 */
    public List<MenuItem> items() throws IOException {
        session.ensureLogin();
        String body = session.post("/jwglxt/xtgl/index_cxMenuList.html", Map.of(),
            "/jwglxt/xtgl/index_initMenu.html");
        return JsonSupport.list(body, MenuItem.class);
    }
}
