package com.gxu.jwxt;

import com.gxu.jwxt.exceptions.KickedOutException;
import com.gxu.jwxt.exceptions.SessionExpiredException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话失效判定单元测试：三态探测 / 被踢识别 / 普通过期 / resumeSession 兼容。
 *
 * <p>只用 JDK 自带 {@link HttpServer} 在本地随机端口模拟正方教务系统，
 * 不访问真实网络、不依赖外部账号；配合 {@link RetryConfig#NO_RETRY}
 * 关闭重试与限流，保证测试快速且离线可跑。</p>
 *
 * <p>被踢的实测依据见仓库 {@code docs/登录状态失效规律实测.md}：业务请求
 * 302 → {@code login_slogin.html?kickout=1} → 200 登录页（含
 * {@code name="csrftoken"} 与 {@code name="yhm"}）。</p>
 */
class JwxtSessionKickoutTest {

    private static final String INIT_MENU_PATH = "/jwglxt/xtgl/index_initMenu.html";
    private static final String LOGIN_PATH = "/jwglxt/xtgl/login_slogin.html";

    /** 正方登录页特征：两个 name 同时出现才会被 isLoginPage 判定为登录页。 */
    private static final String LOGIN_PAGE_HTML = "<html><body><form>"
            + "<input type=\"hidden\" name=\"csrftoken\" value=\"dummy-token\"/>"
            + "<input name=\"yhm\"/><input name=\"mm\"/>"
            + "</form></body></html>";

    /** 正常业务页：不含登录页特征。 */
    private static final String NORMAL_PAGE_HTML =
            "<html><body><div id=\"main\">正常业务页面</div></body></html>";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private JwxtSession newSession() {
        return new JwxtSession("student", "password", baseUrl, RetryConfig.NO_RETRY);
    }

    // ========== probeSession 三态 ==========

    @Test
    void probeSessionReturnsAliveForNormalPage() throws IOException {
        server.createContext("/", exchange -> writeBody(exchange, 200, NORMAL_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();

        assertEquals(SessionProbeResult.ALIVE, session.probeSession());
        assertTrue(session.isLoggedIn(), "ALIVE 应标记为已登录");
    }

    @Test
    void probeSessionReturnsExpiredForLoginPage() throws IOException {
        server.createContext("/", exchange -> writeBody(exchange, 200, LOGIN_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();

        assertEquals(SessionProbeResult.EXPIRED, session.probeSession());
        assertFalse(session.isLoggedIn(), "EXPIRED 应标记为未登录");
    }

    @Test
    void probeSessionReturnsNetworkErrorWhenServerUnreachable() throws IOException {
        // 拿一个刚释放的本地端口：没有监听者 → 连接被拒绝
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        JwxtSession session = new JwxtSession("student", "password",
                "http://127.0.0.1:" + closedPort, RetryConfig.NO_RETRY);

        assertEquals(SessionProbeResult.NETWORK_ERROR, session.probeSession());
        assertFalse(session.isLoggedIn(), "网络异常不应把会话标记为已登录");
    }

    @Test
    void probeSessionAlwaysSendsRequestEvenWhenLoggedIn() throws IOException {
        AtomicBoolean loginPage = new AtomicBoolean(false);
        server.createContext("/", exchange ->
                writeBody(exchange, 200, loginPage.get() ? LOGIN_PAGE_HTML : NORMAL_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();
        assertEquals(SessionProbeResult.ALIVE, session.probeSession());
        assertTrue(session.isLoggedIn());

        // 已登录状态下服务端会话失效：probeSession 不能复用 loggedIn 短路，必须再发请求
        loginPage.set(true);
        assertEquals(SessionProbeResult.EXPIRED, session.probeSession());
        assertFalse(session.isLoggedIn());
    }

    // ========== 被踢 vs 普通过期 ==========

    @Test
    void kickedOutRedirectThrowsKickedOutException() throws IOException {
        server.createContext(INIT_MENU_PATH, exchange ->
                redirect(exchange, baseUrl + LOGIN_PATH + "?kickout=1"));
        server.createContext(LOGIN_PATH, exchange -> writeBody(exchange, 200, LOGIN_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();

        KickedOutException error = assertThrows(KickedOutException.class,
                () -> session.get(INIT_MENU_PATH));

        assertInstanceOf(SessionExpiredException.class, error, "被踢异常必须是会话过期异常的子类");
        assertTrue(error.getMessage().contains("其它设备登录"),
                () -> "消息应表明被踢下线: " + error.getMessage());
        assertTrue(error.getMessage().contains(INIT_MENU_PATH),
                () -> "消息应带上被请求 path: " + error.getMessage());
        // 父类 SessionExpiredException(String path) 的「会话已过期（… 返回了登录页）」模板
        // 不应再包裹本异常的消息（否则用户会看到两层重复文案）
        assertFalse(error.getMessage().contains("会话已过期"),
                () -> "消息不应被父类模板二次包裹: " + error.getMessage());
        assertEquals(expectedKickoutMessage(INIT_MENU_PATH), error.getMessage(),
                "消息应只有本异常自己的一层文案");
        assertFalse(session.isLoggedIn(), "被踢后应标记为未登录");
    }

    @Test
    void kickedOutExceptionMessageIsNotDoubleWrapped() {
        // 带 path 构造：完整消息只保留本异常的一层，且继承关系不变
        KickedOutException pathError = new KickedOutException(INIT_MENU_PATH);
        String message = pathError.getMessage();

        assertEquals(expectedKickoutMessage(INIT_MENU_PATH), message);
        assertInstanceOf(SessionExpiredException.class, pathError);
        assertFalse(message.contains("会话已过期"), () -> "消息不应包含父类模板: " + message);
        // 「被重定向到登录页」只应出现一次：父类模板会额外拼「… 返回了登录页」
        assertEquals(message.indexOf("被重定向到登录页"), message.lastIndexOf("被重定向到登录页"),
                () -> "消息不应重复拼接: " + message);

        // 无参构造
        KickedOutException noPath = new KickedOutException();
        assertEquals("账号已在其它设备登录（会话被踢下线），请重新登录", noPath.getMessage());
        assertFalse(noPath.getMessage().contains("会话已过期"));
    }

    @Test
    void probeSessionReportsExpiredWhenKickedOut() throws IOException {
        server.createContext(INIT_MENU_PATH, exchange ->
                redirect(exchange, baseUrl + LOGIN_PATH + "?kickout=1"));
        server.createContext(LOGIN_PATH, exchange -> writeBody(exchange, 200, LOGIN_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();

        // KickedOutException 是 SessionExpiredException 子类，三态探测归入 EXPIRED
        assertEquals(SessionProbeResult.EXPIRED, session.probeSession());
        assertFalse(session.isLoggedIn());
    }

    @Test
    void plainExpiryThrowsSessionExpiredExceptionButNotKickedOut() throws IOException {
        // 普通空闲过期：302 → 登录页，但 Location 不带 kickout=1
        server.createContext(INIT_MENU_PATH, exchange -> redirect(exchange, baseUrl + LOGIN_PATH));
        server.createContext(LOGIN_PATH, exchange -> writeBody(exchange, 200, LOGIN_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();

        SessionExpiredException error = assertThrows(SessionExpiredException.class,
                () -> session.get(INIT_MENU_PATH));

        assertFalse(error instanceof KickedOutException,
                "不带 kickout=1 的普通过期不能误判为被踢");
        assertFalse(session.isLoggedIn());
    }

    @Test
    void kickoutDetectedAcrossMultiHopRedirectChain() throws IOException {
        // 更长的重定向链：即使后续跳转把 kickout=1 从 URL 丢掉，
        // 只要链上任意一跳的 Location 带 kickout=1 仍应判定为被踢。
        server.createContext(INIT_MENU_PATH, exchange ->
                redirect(exchange, baseUrl + LOGIN_PATH + "?kickout=1"));
        server.createContext(LOGIN_PATH, exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("kickout=1")) {
                // 第二次 302：把 kickout 参数丢掉，只剩普通登录页地址
                redirect(exchange, baseUrl + LOGIN_PATH);
            } else {
                writeBody(exchange, 200, LOGIN_PAGE_HTML);
            }
        });
        server.start();

        JwxtSession session = newSession();

        assertThrows(KickedOutException.class, () -> session.get(INIT_MENU_PATH));
        assertFalse(session.isLoggedIn());
    }

    // ========== resumeSession 兼容（布尔二态语义不变） ==========

    @Test
    void resumeSessionReturnsFalseForLoginPage() throws IOException {
        server.createContext("/", exchange -> writeBody(exchange, 200, LOGIN_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();

        assertFalse(session.resumeSession());
        assertFalse(session.isLoggedIn());
    }

    @Test
    void resumeSessionReturnsTrueForNormalPage() throws IOException {
        server.createContext("/", exchange -> writeBody(exchange, 200, NORMAL_PAGE_HTML));
        server.start();

        JwxtSession session = newSession();

        assertTrue(session.resumeSession());
        assertTrue(session.isLoggedIn());
    }

    // ========== JwxtClient 门面 ==========

    @Test
    void clientFacadeExposesProbeSession() throws IOException {
        server.createContext("/", exchange -> writeBody(exchange, 200, NORMAL_PAGE_HTML));
        server.start();

        JwxtClient client = new JwxtClient("student", "password", baseUrl, RetryConfig.NO_RETRY);

        assertEquals(SessionProbeResult.ALIVE, client.probeSession());
        assertTrue(client.isLoggedIn());
    }

    // ========== 工具 ==========

    private static void writeBody(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /** KickedOutException(String path) 期望的完整用户可见消息（不应再被父类模板包裹）。 */
    private static String expectedKickoutMessage(String path) {
        return "账号已在其它设备登录（会话被踢下线）：" + path
                + " 被重定向到登录页（kickout=1），请重新登录";
    }
}
