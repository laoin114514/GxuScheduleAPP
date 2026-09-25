package com.gxu.jwxt.exceptions;

/**
 * 会话已过期（服务端 JSESSIONID 失效，请求被重定向到登录页）。
 *
 * <p>调用方应捕获此异常并自行决定处理策略：</p>
 * <pre>{@code
 * try {
 *     data = client.schedule().personal("2025", "12");
 * } catch (SessionExpiredException e) {
 *     client.login();  // 或 client.relogin()
 *     data = client.schedule().personal("2025", "12");
 * }
 * }</pre>
 */
public class SessionExpiredException extends JwxtException {
    public SessionExpiredException() {
        super("会话已过期，请调用 login() 或 relogin() 重新登录");
    }

    public SessionExpiredException(String path) {
        super("会话已过期（" + path + " 返回了登录页），请重新登录");
    }
}
