package com.gxu.jwxt;

import com.gxu.jwxt.exceptions.NotLoggedInException;
import com.gxu.jwxt.model.Term;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 不需要真实教务系统账号的客户端状态测试。 */
class JwxtSessionTest {

    @Test
    void defaultRetryConfigUsesVerifiedRequestInterval() {
        RetryConfig config = RetryConfig.DEFAULT;

        assertEquals(3, config.getMaxRetries());
        assertEquals(1_200, config.getMinRequestInterval());
        assertEquals(1_000, config.getBackoffMs(0));
        assertEquals(2_000, config.getBackoffMs(1));
        assertEquals(4_000, config.getBackoffMs(2));
    }

    @Test
    void currentSemesterReturnsSupportedTerm() {
        var semester = JwxtClient.currentSemester();
        assertNotNull(semester.getYear());
        assertTrue(semester.getTerm() == Term.AUTUMN || semester.getTerm() == Term.SPRING);
    }

    @Test
    void exposesConfiguredUsernameWithoutLoggingIn() {
        JwxtClient client = new JwxtClient("student", "password");
        assertEquals("student", client.getUsername());
        assertFalse(client.isLoggedIn());
        assertTrue(client.toString().contains("student"));
    }

    @Test
    void structuredModuleRequiresLogin() {
        JwxtClient client = new JwxtClient("student", "password");
        assertThrows(NotLoggedInException.class,
            () -> client.schedule().personal("2025", Term.SPRING));
    }

    @Test
    void clearBrowserIdentityRotatesUserAgent() {
        JwxtClient client = new JwxtClient("student", "password");
        String before = client.getSession().getUserAgent();
        client.clearBrowserIdentity();
        String after = client.getSession().getUserAgent();
        assertNotEquals(before, after);
        assertTrue(after.startsWith("Mozilla/5.0 ("));
    }

    @Test
    void clearBrowserIdentityWipesCookies() {
        JwxtSession session = new JwxtSession("student", "password");
        var jar = (ClearableCookieJar) session.getHttpClient().cookieJar();
        HttpUrl url = HttpUrl.parse("https://jwxt2018.gxu.edu.cn/jwglxt/xtgl/index_initMenu.html");
        jar.saveFromResponse(url, List.of(new Cookie.Builder()
            .name("JSESSIONID").value("abc").domain("jwxt2018.gxu.edu.cn").build()));
        assertEquals(1, jar.loadForRequest(url).size());

        session.clearBrowserIdentity();

        assertTrue(jar.loadForRequest(url).isEmpty());
        assertFalse(session.isLoggedIn());
    }

    @Test
    void captchaEnforcedDetectsServerRenderedCaptcha() {
        // 失败次数超限后服务端渲染的登录页（含验证码输入框）
        Document captchaPage = Jsoup.parse(
            "<html><body><form><input name='yhm'/><div id='yzmDiv'>"
                + "<input id='yzm' name='yzm'/><img id='yzmPic'/></div></form></body></html>");
        assertTrue(JwxtSession.isCaptchaEnforced(captchaPage));

        // sfxyyzm 隐藏域变体
        Document flagPage = Jsoup.parse(
            "<html><body><input id='sfxyyzm' value='1'/></body></html>");
        assertTrue(JwxtSession.isCaptchaEnforced(flagPage));

        // 正常状态登录页（2026-08 实抓：无 yzm 元素，仅有 csrftoken/yhm 等）
        Document normalPage = Jsoup.parse(
            "<html><body><form>"
                + "<input name='csrftoken' value='x'/>"
                + "<input name='yhm'/><input name='mm'/>"
                + "<input id='yzcskz' value='3'/>"
                + "</form></body></html>");
        assertFalse(JwxtSession.isCaptchaEnforced(normalPage));
    }
}
