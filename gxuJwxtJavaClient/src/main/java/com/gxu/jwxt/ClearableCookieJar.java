package com.gxu.jwxt;

import okhttp3.CookieJar;

/**
 * 支持整体清空的 CookieJar。
 *
 * <p>教务系统在登录失败次数超限后会强制验证码，客户端需要
 * 抹掉会话标识（Cookie）并更换 User-Agent 后重试登录，
 * 因此持久化 CookieJar 需要实现本接口以支持清空。</p>
 */
public interface ClearableCookieJar extends CookieJar {

    /** 清空全部 Cookie。 */
    void clear();
}
