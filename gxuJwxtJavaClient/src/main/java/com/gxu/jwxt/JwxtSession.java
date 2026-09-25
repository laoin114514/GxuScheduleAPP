package com.gxu.jwxt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gxu.jwxt.exceptions.CaptchaRequiredException;
import com.gxu.jwxt.exceptions.LoginException;
import com.gxu.jwxt.exceptions.NotLoggedInException;
import com.gxu.jwxt.exceptions.SessionExpiredException;

import okhttp3.*;

import javax.crypto.Cipher;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** 教务系统核心客户端，管理 HTTP 会话、认证、RSA 加密 */
public class JwxtSession {

    private static final Logger LOG = Logger.getLogger(JwxtSession.class.getName());

    private static final String DEFAULT_BASE_URL = "https://jwxt2018.gxu.edu.cn";
    private static final String LOGIN_PATH = "/jwglxt/xtgl/login_slogin.html";
    private static final String PUBLIC_KEY_PATH = "/jwglxt/xtgl/login_getPublicKey.html";
    private static final String INIT_MENU_PATH = "/jwglxt/xtgl/index_initMenu.html";

    /**
     * 真实桌面浏览器 User-Agent 池。
     *
     * <p>服务端在登录失败次数超限后强制验证码，疑似与会话标识相关；
     * 触发验证码时轮换 UA 配合清空 Cookie 以重置身份。全部为完整、
     * 现代浏览器的 UA（旧实现是截断值，容易被识别为脚本流量）。</p>
     */
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"
    };

    private final String baseUrl;
    private final String username;
    private final String password;
    private final OkHttpClient httpClient;
    private final RetryConfig retryConfig;
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private String csrfToken;
    private volatile String userAgent = USER_AGENTS[0];

    /** 上一次请求完成的时间戳，用于限流 */
    private volatile long lastRequestTime = 0;

    // ========== 构造 ==========

    public JwxtSession(String username, String password) {
        this(username, password, DEFAULT_BASE_URL, RetryConfig.DEFAULT, null);
    }

    public JwxtSession(String username, String password, String baseUrl) {
        this(username, password, baseUrl, RetryConfig.DEFAULT, null);
    }

    public JwxtSession(String username, String password, String baseUrl, RetryConfig retryConfig) {
        this(username, password, baseUrl, retryConfig, null);
    }

    /**
     * 全参构造：可注入自定义 {@link CookieJar}（如持久化实现）。
     * cookieJar 为 null 时使用默认的内存 CookieJar。
     */
    public JwxtSession(String username, String password, String baseUrl, RetryConfig retryConfig, CookieJar cookieJar) {
        this.username = username;
        this.password = password;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.retryConfig = retryConfig != null ? retryConfig : RetryConfig.DEFAULT;

        this.httpClient = new OkHttpClient.Builder()
                .cookieJar(cookieJar != null ? cookieJar : buildDefaultCookieJar())
                .protocols(List.of(Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    /**
     * 默认内存 CookieJar：会话期间有效，进程退出后丢失。
     */
    private static final class InMemoryCookieJar implements ClearableCookieJar {
        private final CopyOnWriteArrayList<Cookie> cookies = new CopyOnWriteArrayList<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> list) {
            for (Cookie c : list) {
                cookies.removeIf(existing ->
                    existing.name().equals(c.name()) &&
                    existing.domain().equals(c.domain()) &&
                    existing.path().equals(c.path()));
                cookies.add(c);
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> matched = new java.util.ArrayList<>();
            for (Cookie cookie : cookies) {
                if (cookie.matches(url)) {
                    matched.add(cookie);
                }
            }
            return matched;
        }

        @Override
        public void clear() {
            cookies.clear();
        }
    }

    /**
     * 默认内存 CookieJar：会话期间有效，进程退出后丢失。
     */
    private static CookieJar buildDefaultCookieJar() {
        return new InMemoryCookieJar();
    }

    // ========== HTTP ==========

    private String fullUrl(String path) {
        if (path.startsWith("http")) return path;
        return baseUrl + path;
    }

    private String referer() {
        return baseUrl + "/jwglxt/xtgl/index_initMenu.html";
    }

    // ---- 请求头部构建 ----

    private Request.Builder baseRequestBuilder(String path, String refererPath) {
        return new Request.Builder()
                .url(fullUrl(path))
                .header("Referer", refererPath != null ? fullUrl(refererPath) : referer())
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    }

    // ---- 限流 ----

    /**
     * 请求间限流：确保两次请求至少间隔 {@link RetryConfig#getMinRequestInterval()} 毫秒。
     */
    private void rateLimit() {
        long interval = retryConfig.getMinRequestInterval();
        if (interval <= 0) return;
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        if (elapsed < interval) {
            sleep(interval - elapsed);
        }
        lastRequestTime = System.currentTimeMillis();
    }

    // ---- 重试判断 ----

    /**
     * 判断 IOException 是否应重试（超时、连接异常、DNS 错误）。
     * 模仿 luoguClient {@code shouldRetry}。
     */
    private boolean shouldRetry(IOException e) {
        if (e instanceof SocketTimeoutException) return true;
        if (e instanceof ConnectException) return true;
        if (e instanceof UnknownHostException) return true;
        // 检查嵌套的 cause（如 SSLException 包裹的 ConnectException）
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) return true;
            if (cause instanceof ConnectException) return true;
            if (cause instanceof UnknownHostException) return true;
            cause = cause.getCause();
        }
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("timeout") || lower.contains("timed out")) return true;
        }
        return false;
    }

    // ---- Session 过期检测 ----

    /**
     * 判断响应体是否为教务系统登录页面（说明 session 已过期被重定向）。
     *
     * <p>教务系统 session 过期后，所有需要认证的请求会被 302 重定向到登录页。
     * OkHttp 自动跟随重定向，最终拿到的是登录页 HTML。</p>
     *
     * <p>通过检测登录页独有的特征字段来判断，避免对登录流程本身的误判。</p>
     */
    private boolean isLoginPage(String body, String requestedPath) {
        if (body == null || body.isEmpty()) return false;
        // 排除有意访问登录流程的路径
        if (requestedPath.contains("login_slogin")
                || requestedPath.contains("login_getPublicKey")
                || requestedPath.contains("login_logoutAccount")) {
            return false;
        }
        // 登录页特征：同时包含 csrftoken 隐藏域和 yhm 用户名字段
        return body.contains("name=\"csrftoken\"") && body.contains("name=\"yhm\"");
    }

    // ---- 工具 ----

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 网络层重试（网络错误 / 5xx）----

    /**
     * GET 内部实现：仅处理网络层重试（网络错误 + 5xx），不处理 session 过期。
     */
    private String getInternal(String path) throws IOException {
        return getInternal(path, null);
    }

    private String getInternal(String path, String refererPath) throws IOException {
        String label = "GET " + path;
        IOException lastError = null;

        for (int attempt = 0; attempt <= retryConfig.getMaxRetries(); attempt++) {
            rateLimit();

            Request req = baseRequestBuilder(path, refererPath).build();

            try {
                Response resp = httpClient.newCall(req).execute();
                if (resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    lastRequestTime = System.currentTimeMillis();
                    return body;
                }

                int code = resp.code();
                resp.close();

                // 5xx 服务端错误 → 重试
                if (code >= 500 && attempt < retryConfig.getMaxRetries()) {
                    lastError = new IOException("HTTP " + code + " for " + label);
                    logRetry(attempt, label, lastError);
                    sleep(retryConfig.getBackoffMs(attempt));
                    continue;
                }

                throw new IOException("HTTP " + code + " for " + path);

            } catch (IOException e) {
                if (shouldRetry(e) && attempt < retryConfig.getMaxRetries()) {
                    lastError = e;
                    logRetry(attempt, label, e);
                    sleep(retryConfig.getBackoffMs(attempt));
                    continue;
                }
                throw e;
            }
        }

        throw new IOException("Max retries (" + retryConfig.getMaxRetries()
                + ") exceeded for " + label, lastError);
    }

    /**
     * POST 内部实现：仅处理网络层重试（网络错误 + 5xx），不处理 session 过期。
     */
    private String postInternal(String path, java.util.Map<String, String> data) throws IOException {
        return postInternal(path, data, null);
    }

    private String postInternal(String path, java.util.Map<String, String> data, String refererPath) throws IOException {
        String label = "POST " + path;
        IOException lastError = null;

        for (int attempt = 0; attempt <= retryConfig.getMaxRetries(); attempt++) {
            rateLimit();

            // 每次重试重建 FormBody（body 在上一次已被消费）
            FormBody.Builder fb = new FormBody.Builder();
            if (data != null) {
                data.forEach(fb::add);
            }

            Request req = baseRequestBuilder(path, refererPath)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .post(fb.build())
                    .build();

            try {
                Response resp = httpClient.newCall(req).execute();
                if (resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    lastRequestTime = System.currentTimeMillis();
                    return body;
                }

                int code = resp.code();
                resp.close();

                if (code >= 500 && attempt < retryConfig.getMaxRetries()) {
                    lastError = new IOException("HTTP " + code + " for " + label);
                    logRetry(attempt, label, lastError);
                    sleep(retryConfig.getBackoffMs(attempt));
                    continue;
                }

                throw new IOException("HTTP " + code + " for " + path);

            } catch (IOException e) {
                if (shouldRetry(e) && attempt < retryConfig.getMaxRetries()) {
                    lastError = e;
                    logRetry(attempt, label, e);
                    sleep(retryConfig.getBackoffMs(attempt));
                    continue;
                }
                throw e;
            }
        }

        throw new IOException("Max retries (" + retryConfig.getMaxRetries()
                + ") exceeded for " + label, lastError);
    }

    // ---- 公开 HTTP 方法（网络层重试 + session 过期检测）----

    /**
     * GET 请求（网络层重试 + 限流）。
     *
     * @throws SessionExpiredException 如果响应内容为登录页（session 已过期）
     */
    public String get(String path) throws IOException {
        return get(path, null);
    }

    /**
     * GET 请求，使用所属功能页面作为 Referer。
     */
    public String get(String path, String refererPath) throws IOException {
        String body = getInternal(path, refererPath);
        if (isLoginPage(body, path)) {
            loggedIn.set(false);
            throw new SessionExpiredException(path);
        }
        return body;
    }

    /**
     * POST 请求 (form-urlencoded)。
     * 网络层重试 + 限流。
     *
     * @throws SessionExpiredException 如果响应内容为登录页（session 已过期）
     */
    public String post(String path, java.util.Map<String, String> data) throws IOException {
        return post(path, data, null);
    }

    /**
     * POST 表单请求，使用所属功能页面作为 Referer。
     */
    public String post(String path, java.util.Map<String, String> data, String refererPath) throws IOException {
        String body = postInternal(path, data, refererPath);
        if (isLoginPage(body, path)) {
            loggedIn.set(false);
            throw new SessionExpiredException(path);
        }
        return body;
    }

    /**
     * POST 请求，返回完整 Response 对象。
     * 网络层重试 + 限流 + session 过期检测。
     */
    private Response postWithResponse(String path, java.util.Map<String, String> data) throws IOException {
        String label = "POST (stream) " + path;
        IOException lastError = null;

        for (int attempt = 0; attempt <= retryConfig.getMaxRetries(); attempt++) {
            rateLimit();

            FormBody.Builder fb = new FormBody.Builder();
            if (data != null) {
                data.forEach(fb::add);
            }

            Request req = baseRequestBuilder(path, null)
                    .removeHeader("Referer")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .post(fb.build())
                    .build();

            try {
                Response resp = httpClient.newCall(req).execute();
                if (resp.isSuccessful()) {
                    lastRequestTime = System.currentTimeMillis();
                    // 检测 session 过期
                    String body = resp.peekBody(Long.MAX_VALUE).string();
                    if (isLoginPage(body, path)) {
                        resp.close();
                        loggedIn.set(false);
                        throw new SessionExpiredException(path);
                    }
                    return resp;
                }

                int code = resp.code();
                resp.close();

                if (code >= 500 && attempt < retryConfig.getMaxRetries()) {
                    lastError = new IOException("HTTP " + code + " for " + label);
                    logRetry(attempt, label, lastError);
                    sleep(retryConfig.getBackoffMs(attempt));
                    continue;
                }

                throw new IOException("HTTP " + code + " for " + path);

            } catch (SessionExpiredException e) {
                throw e; // 不重试，直接向上抛
            } catch (IOException e) {
                if (shouldRetry(e) && attempt < retryConfig.getMaxRetries()) {
                    lastError = e;
                    logRetry(attempt, label, e);
                    sleep(retryConfig.getBackoffMs(attempt));
                    continue;
                }
                throw e;
            }
        }

        throw new IOException("Max retries (" + retryConfig.getMaxRetries()
                + ") exceeded for " + label, lastError);
    }

    private void logRetry(int attempt, String label, IOException e) {
        LOG.log(Level.WARNING,
                "Retry " + (attempt + 1) + "/" + retryConfig.getMaxRetries()
                        + " for " + label + ": " + e.getMessage());
    }

    // ========== RSA ==========

    /**
     * RSA 公钥加密密码
     */
    public String encryptPassword(String password) throws Exception {
        long ts = System.currentTimeMillis();
        String body = get(PUBLIC_KEY_PATH + "?time=" + ts);
        JsonObject key = JsonParser.parseString(body).getAsJsonObject();

        byte[] modulusBytes = Base64.getDecoder().decode(key.get("modulus").getAsString());
        byte[] exponentBytes = Base64.getDecoder().decode(key.get("exponent").getAsString());
        BigInteger modulus = new BigInteger(1, modulusBytes);
        BigInteger exponent = new BigInteger(1, exponentBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPublicKey pubKey = (RSAPublicKey) kf.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    // ========== 认证 ==========

    /**
     * 首次登录教务系统（如果已登录则跳过）。
     */
    public void login() throws LoginException {
        if (loggedIn.get()) return;
        doLogin();
    }

    /**
     * 强制重新登录，无视当前登录状态。
     *
     * <p>捕获 {@link SessionExpiredException} 后调用此方法恢复会话：</p>
     * <pre>{@code
     * try {
     *     data = client.schedule().personal("2025", "12");
     * } catch (SessionExpiredException e) {
     *     client.relogin();
     *     data = client.schedule().personal("2025", "12");
     * }
     * }</pre>
     */
    public void relogin() throws LoginException {
        loggedIn.set(false);
        doLogin();
    }

    /**
     * 尝试用 CookieJar 中已持久化的 cookie 恢复会话（免完整登录）。
     *
     * <p>用轻量认证请求（initMenu）探测会话是否仍有效：
     * 响应不是登录页 → 会话有效，标记已登录并返回 true；
     * 否则（无 cookie / 已过期 / 网络异常）返回 false，由调用方决定走完整登录。</p>
     */
    public boolean resumeSession() {
        if (loggedIn.get()) return true;
        try {
            get(INIT_MENU_PATH + "?jsdm=xs&_t=" + System.currentTimeMillis() + "&echarts=1");
            loggedIn.set(true);
            return true;
        } catch (SessionExpiredException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 执行登录流程。
     *
     * <p>遇到验证码类失败（服务端在登录失败次数超限后强制验证码）时，
     * 自动抹除浏览器身份（清 Cookie + 轮换 UA）后重试一次；重试前先
     * 检查新登录页是否仍渲染验证码，若仍强制则不再提交，直接以
     * {@link CaptchaRequiredException} 终止。密码错误等非验证码失败
     * 不重试，避免叠加失败计数导致账号锁定。</p>
     */
    private void doLogin() throws LoginException {
        try {
            loginAttempt();
        } catch (CaptchaRequiredException e) {
            clearBrowserIdentity();
            try {
                loginAttempt();
            } catch (CaptchaRequiredException e2) {
                throw new CaptchaRequiredException(
                    "教务系统触发验证码保护：登录失败次数过多，已自动重置登录环境仍无效。"
                        + "请等待约3分钟后再试，或先用浏览器登录一次教务系统", e2);
            }
        } catch (LoginException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginException("登录异常: " + e.getMessage(), e);
        }
    }

    /** 单次登录尝试：取登录页（含验证码强制检测）→ RSA 加密 → 提交 → 初始化会话。 */
    private void loginAttempt() throws LoginException {
        try {
            // 1. 获取 csrftoken（登录页不经过 session 检查，直接走内部方法）
            String loginHtml = getInternal(LOGIN_PATH);
            Document doc = Jsoup.parse(loginHtml);
            var csrfInput = doc.selectFirst("input[name=csrftoken]");
            this.csrfToken = csrfInput != null ? csrfInput.attr("value") : "";

            // 1.5 验证码强制检测：失败次数超限后服务端会在登录页渲染验证码输入框，
            //     正常状态无该元素。此时提交必然失败，先终止等待身份重置。
            if (isCaptchaEnforced(doc)) {
                throw new CaptchaRequiredException("教务系统要求输入验证码（登录失败次数过多）");
            }

            // 2. 加密密码
            String encryptedPwd = encryptPassword(password);

            // 3. 提交登录
            long ts = System.currentTimeMillis();
            try (Response loginResp = postWithResponse(
                LOGIN_PATH + "?time=" + ts,
                new java.util.LinkedHashMap<>() {{
                    put("csrftoken", csrfToken);
                    put("language", "zh_CN");
                    put("yhm", username);
                    put("mm", encryptedPwd);
                    put("ydType", "");
                }}
            )) {
                if (!checkLoginSuccess(loginResp)) {
                    raiseLoginError(loginResp);
                }
            }

            // 4. 初始化会话
            loggedIn.set(true);
            ts = System.currentTimeMillis();
            getInternal(INIT_MENU_PATH + "?jsdm=xs&_t=" + ts + "&echarts=1");

        } catch (LoginException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginException("登录异常: " + e.getMessage(), e);
        }
    }

    /**
     * 判断登录页是否强制要求验证码。
     *
     * <p>登录失败次数超过 yzcskz（默认 3）后，服务端渲染登录页时会出现
     * 验证码输入框（#yzm/#yzmDiv，图片来自 /kaptcha）；部分部署用
     * #sfxyyzm 隐藏域控制，一并列出。</p>
     */
    static boolean isCaptchaEnforced(Document doc) {
        if (doc.selectFirst("#yzm") != null || doc.selectFirst("#yzmDiv") != null) return true;
        var flag = doc.selectFirst("#sfxyyzm");
        return flag != null && !"0".equals(flag.attr("value").trim());
    }

    public void ensureLogin() {
        if (!loggedIn.get()) {
            throw new NotLoggedInException();
        }
    }

    public void logout() throws IOException {
        get("/jwglxt/xtgl/login_logoutAccount.html");
        loggedIn.set(false);
    }

    // ========== 身份重置（验证码保护应对） ==========

    /**
     * 抹除浏览器身份：清空全部 Cookie + 轮换 User-Agent + 置登录态为未登录。
     *
     * <p>服务端在登录失败次数超限后强制验证码。实测该强制疑似与会话
     * 标识（Cookie 等）绑定，抹除后重新获取登录页即可恢复正常登录；
     * 若服务端按账号维度强制，则本方法无效，重试后仍会要求验证码。</p>
     */
    public synchronized void clearBrowserIdentity() {
        CookieJar jar = httpClient.cookieJar();
        if (jar instanceof ClearableCookieJar) {
            ((ClearableCookieJar) jar).clear();
        }
        rotateUserAgent();
        loggedIn.set(false);
        LOG.info("Browser identity cleared: cookies wiped, UA rotated");
    }

    /** 从 UA 池随机换一个与当前不同的 User-Agent。 */
    private void rotateUserAgent() {
        if (USER_AGENTS.length < 2) return;
        String next = userAgent;
        while (next.equals(userAgent)) {
            next = USER_AGENTS[ThreadLocalRandom.current().nextInt(USER_AGENTS.length)];
        }
        this.userAgent = next;
    }

    // ========== 状态 ==========

    public boolean isLoggedIn() { return loggedIn.get(); }
    public String getUsername() { return username; }
    public String getUserAgent() { return userAgent; }
    public OkHttpClient getHttpClient() { return httpClient; }
    public String getBaseUrl() { return baseUrl; }
    public RetryConfig getRetryConfig() { return retryConfig; }

    // ========== 内部 ==========

    private boolean checkLoginSuccess(Response resp) {
        String url = resp.request().url().toString().toLowerCase();
        return !url.contains("login") || url.contains("index");
    }

    private void raiseLoginError(Response resp) throws IOException {
        String html = resp.body() != null ? resp.body().string() : "";
        Document doc = Jsoup.parse(html);
        String message = null;
        String[] selectors = {"#tips", "#errorMsg", ".error", ".alert-danger", ".form-msg"};
        for (String sel : selectors) {
            var el = doc.selectFirst(sel);
            if (el != null && !el.text().isBlank()) {
                message = el.text().trim();
                break;
            }
        }
        if (message == null) {
            var title = doc.selectFirst("title");
            if (title != null && (title.text().contains("错误") || title.text().contains("失败"))) {
                message = title.text().trim();
            }
        }
        if (message == null) {
            message = "用户名或密码错误，或账号已被锁定";
        }
        // 验证码类失败单独分类，供上层自动重置身份重试 / 友好提示
        if (message.contains("验证码")) {
            throw new CaptchaRequiredException(message);
        }
        throw new LoginException(message);
    }
}
