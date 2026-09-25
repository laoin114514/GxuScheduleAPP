package com.gxu.jwxt;

import java.util.function.IntFunction;

/**
 * 请求重试与限流配置。
 *
 * <p>模仿 luoguClient 的重试机制：指数退避重试 + 请求间隔限流。</p>
 *
 * <h3>重试规则</h3>
 * <ul>
 *   <li>网络错误（超时、连接异常、DNS 错误）→ 重试</li>
 *   <li>HTTP 5xx 服务端错误 → 重试</li>
 *   <li>每次重试前按退避策略等待</li>
 * </ul>
 *
 * <h3>限流规则</h3>
 * <ul>
 *   <li>两次请求之间至少间隔 {@code minRequestInterval} 毫秒</li>
 *   <li>不足时自动等待，避免触发服务端频率限制</li>
 * </ul>
 */
public class RetryConfig {

    private final int maxRetries;
    private final IntFunction<Long> backoffFn;  // attempt (0-based) → delay ms
    private final long minRequestInterval;       // ms

    /**
     * 默认配置：最多 3 次重试，指数退避 1s/2s/4s，请求起始间隔 1.2s。
     *
     * <p>该间隔来自 2026-07-31 的全接口实测：使用单个会话、每次请求间隔
     * 1.2 秒时未触发限流。教务系统未公布固定阈值，因此它是保守的默认值，
     * 不是服务端承诺的最大请求频率。</p>
     */
    public static final RetryConfig DEFAULT = new RetryConfig(3, RetryConfig::defaultBackoff, 1_200);

    /** 不重试、不限流 */
    public static final RetryConfig NO_RETRY = new RetryConfig(0, a -> 0L, 0);

    /**
     * @param maxRetries         最大重试次数（0 = 不重试）
     * @param backoffFn          退避函数，接收 attempt（从 0 开始），返回等待毫秒数
     * @param minRequestInterval 最小请求间隔（毫秒），0 表示不限流
     */
    public RetryConfig(int maxRetries, IntFunction<Long> backoffFn, long minRequestInterval) {
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffFn = backoffFn != null ? backoffFn : a -> 0L;
        this.minRequestInterval = Math.max(0, minRequestInterval);
    }

    public int getMaxRetries() { return maxRetries; }

    public long getBackoffMs(int attempt) { return backoffFn.apply(attempt); }

    public long getMinRequestInterval() { return minRequestInterval; }

    /**
     * 默认指数退避：1s, 2s, 4s, 8s, ...
     * 与 luoguClient defaultBackoff 一致。
     */
    public static long defaultBackoff(int attempt) {
        return (long) Math.pow(2, attempt) * 1000;
    }
}
