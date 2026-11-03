package com.gxu.jwxt.exceptions;

/**
 * 会话被踢下线：同一后端节点上该账号在其它设备（或另一次登录）重新登录，
 * 导致当前会话立即失效。
 *
 * <p>判定依据是响应重定向链：业务请求被 302 重定向到
 * {@code /jwglxt/xtgl/login_slogin.html?kickout=1}，OkHttp 自动跟随后最终
 * 拿到登录页 HTML（HTTP 200）。普通过期（空闲超时 / route 丢失）同样会
 * 重定向到登录页，但 302 的 {@code Location} 不带 {@code kickout=1}。</p>
 *
 * <p>本异常是 {@link SessionExpiredException} 的子类，因此已有的
 * 「捕获 {@code SessionExpiredException} → 重新登录」逻辑保持有效；
 * 需要向用户区分提示（如「账号已在其它设备登录」）时，可先单独捕获本异常。</p>
 *
 * <pre>{@code
 * try {
 *     data = client.schedule().personal("2025", "12");
 * } catch (KickedOutException e) {
 *     // 账号在其它设备登录，当前会话被踢
 *     client.relogin();
 * } catch (SessionExpiredException e) {
 *     client.relogin();
 * }
 * }</pre>
 */
public class KickedOutException extends SessionExpiredException {

    /** 无 path 上下文时的默认消息。 */
    private static final String DEFAULT_MESSAGE = "账号已在其它设备登录（会话被踢下线），请重新登录";

    /**
     * 完整、面向用户的消息。
     *
     * <p>父类 {@link SessionExpiredException#SessionExpiredException(String)} 会把入参
     * 当作 path 再套一层「会话已过期（… 返回了登录页）」模板，若直接使用父类消息会
     * 出现「会话已过期（账号已在其它设备登录…… 返回了登录页），请重新登录」的重复
     * 嵌套。这里单独保存消息并覆盖 {@link #getMessage()}，对外只暴露本异常自己的
     * 一层文案，同时保持继承关系不变（旧代码捕获 {@code SessionExpiredException}
     * 仍然有效）。</p>
     */
    private final String message;

    public KickedOutException() {
        super(DEFAULT_MESSAGE);
        this.message = DEFAULT_MESSAGE;
    }

    /**
     * @param path 触发被踢判定的请求路径（响应为登录页且重定向链含 kickout=1）
     */
    public KickedOutException(String path) {
        super(messageFor(path));
        this.message = messageFor(path);
    }

    private static String messageFor(String path) {
        return "账号已在其它设备登录（会话被踢下线）：" + path
                + " 被重定向到登录页（kickout=1），请重新登录";
    }

    @Override
    public String getMessage() {
        return message;
    }
}

