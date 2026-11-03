package com.gxu.jwxt;

/**
 * 会话探测结果（三态）。
 *
 * <p>由 {@link JwxtSession#probeSession()} / {@link JwxtClient#probeSession()}
 * 返回：总是发送一次真实的认证请求，用于判断当前会话是否仍然有效。</p>
 *
 * <ul>
 *   <li>{@link #ALIVE}：会话有效，可继续调用业务接口；</li>
 *   <li>{@link #EXPIRED}：会话已失效（空闲过期或被其它设备踢下线），
 *       需要重新登录；</li>
 *   <li>{@link #NETWORK_ERROR}：网络/连接异常，无法判断会话状态，
 *       不应据此清除本地凭据或提示用户重新登录。</li>
 * </ul>
 */
public enum SessionProbeResult {

    /** 会话有效（探测请求返回了正常业务页）。 */
    ALIVE,

    /** 会话已失效（探测请求返回了登录页：空闲过期或被踢下线）。 */
    EXPIRED,

    /** 网络异常（连接失败/超时等），无法判断会话状态。 */
    NETWORK_ERROR
}
