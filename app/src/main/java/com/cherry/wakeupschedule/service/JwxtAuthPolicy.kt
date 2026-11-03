package com.cherry.wakeupschedule.service

/**
 * 教务会话失效分类（用于失败冷却、错误文案与会话健康档案）。
 *
 * - [NETWORK]：网络/连接异常，会话状态未知，不清 cookie、不冷却；
 * - [CREDENTIALS]：账号密码被改/错误，短时间内不再自动重试（避免触发验证码保护）；
 * - [CAPTCHA]：服务端要求验证码，同上冷却；
 * - [KICKED]：账号在其它设备/浏览器登录，被同节点踢下线；
 * - [EXPIRED]：正常空闲过期（或节点清理）。
 */
enum class JwxtAuthFailureKind {
    NONE,
    NETWORK,
    CREDENTIALS,
    CAPTCHA,
    KICKED,
    EXPIRED;

    /** 凭据类失败：自动重登会再次失败并累加服务端失败计数，需要冷却。 */
    val isCredentialLike: Boolean
        get() = this == CREDENTIALS || this == CAPTCHA
}

/**
 * 教务登录态刷新策略（纯函数 + 常量，无 Android 依赖，便于单元测试）。
 *
 * <p>依据 2026-11-03 真实账号实测（`docs/登录状态失效规律实测.md`）：</p>
 * <ul>
 *   <li>正方 v5 由 nginx 负载均衡到 20 个后端节点，`route` cookie 决定落到哪个节点；</li>
 *   <li>会话有效 = 有 JSESSIONID 且空闲时长 &lt; 该节点 TTL（滑动过期，活跃即续期）；</li>
 *   <li>TTL 按节点分档：绝大多数节点 ≈60 分钟、`210.36.22.55` ≈30 分钟、
 *       `210.36.22.60` ≈80–90 秒（异常短，需识别并主动换节点）；</li>
 *   <li>同一节点上该账号再登录会立刻踢掉旧会话（302 带 `kickout=1`），跨节点互不影响。</li>
 * </ul>
 */
object JwxtAuthPolicy {

    /** 大多数节点的空闲 TTL 约 60 分钟（默认预热基准）。 */
    const val DEFAULT_NODE_TTL_MS = 60 * 60_000L

    /**
     * 前台预热阈值：距上次确认会话有效超过该时长，就在后台先探测/静默重登录。
     *
     * <p>取 25 分钟：正常档最短的节点是 30 分钟（留 5 分钟余量），而大多数节点 60 分钟，
     * 探测一次只有 ~1.5 秒且阈值节流，成本很低；这样在 30 分钟档节点上也能提前发现过期。</p>
     */
    const val PREWARM_AGE_MS = 25 * 60_000L

    /**
     * "快速失效"判定：登录/确认有效后 10 分钟内就失效，说明该节点 TTL 异常（正常档 ≥30 分钟）。
     * 命中即把该 route 记入异常节点，后续登录避免再落上去。
     */
    const val QUICK_EXPIRY_MS = 10 * 60_000L

    /**
     * 预热阈值下限：即使学到了很短的节点 TTL，也不低于 1 分钟一次探测，
     * 避免异常数据导致频繁探测（耗电/触发风控）。
     */
    const val MIN_PREWARM_AGE_MS = 60_000L

    /**
     * 运行时学习到的异常节点保留时长（7 天）。
     *
     * <p>学习可能来自偶发情况（人工 logout、服务端清理、密码变更导致会话失效等），
     * 不必永久避让；超过该时长自动失效，让正常节点重新参与随机落点。</p>
     */
    const val BAD_ROUTE_TTL_MS = 7 * 24 * 60 * 60_000L

    /**
     * 凭据/验证码类失败后的自动重登冷却：避免本地密码错误时每次前台预热都重试，
     * 反复累加服务端失败次数触发验证码保护甚至账号锁定（实测客户端 3 次失败即出验证码）。
     */
    const val FAIL_COOLDOWN_MS = 30 * 60_000L

    /** 登录落在已知异常节点时最多重掷次数：一次随机落点 1/20 撞异常节点，重掷 2 次足够。 */
    const val MAX_NODE_REROLL = 2

    /**
     * 实测已知异常节点（`route` 常量 ↔ 后端节点一一对应）。
     *
     * <p>`2a353708a723ad925112de2eb5f2f993` = `210.36.22.60`，2026-11-03 实测空闲 TTL ≈80–90 秒；
     * 模拟器中 App 当时恰好被钉在该节点，导致"每次回来都要重登"。运行时还会从
     * "快速失效"样本里继续学习补充（见 [JwxtSessionHealthStore.markRouteBad]）。</p>
     */
    val KNOWN_BAD_ROUTES: Set<String> = setOf("2a353708a723ad925112de2eb5f2f993")

    /** 是否到了该做前台预热的时机（从未确认过有效时也预热一次）。 */
    fun shouldPrewarm(now: Long, lastOkAt: Long, ttlUpperMs: Long? = null): Boolean =
        lastOkAt <= 0L || now - lastOkAt >= prewarmAgeFor(ttlUpperMs)

    /**
     * 按"该节点已学到的 TTL 上界"计算预热阈值。
     *
     * <p>默认按 60 分钟档的 3/4 = 45 分钟，但被 [PREWARM_AGE_MS]（25 分钟，覆盖 30 分钟档）
     * 压住；若该节点学到更短 TTL（例如失败样本显示 20 分钟内必失效），则提前到 15 分钟预热，
     * 避免用户操作时才发现过期。下限 [MIN_PREWARM_AGE_MS] 防止异常数据导致高频探测。</p>
     */
    fun prewarmAgeFor(ttlUpperMs: Long?): Long =
        ((ttlUpperMs ?: DEFAULT_NODE_TTL_MS) * 3 / 4).coerceIn(MIN_PREWARM_AGE_MS, PREWARM_AGE_MS)

    /** 运行时学习到的异常节点是否仍在有效期内（超过 [BAD_ROUTE_TTL_MS] 自动失效）。 */
    fun isLearnedBadRoute(badSince: Long?, now: Long): Boolean =
        badSince != null && now - badSince < BAD_ROUTE_TTL_MS

    /** 会话在"确认有效后很短时间"内就失效 → 该节点 TTL 异常。 */
    fun isNodeSuspicious(aliveSpanMs: Long): Boolean =
        aliveSpanMs in 1..QUICK_EXPIRY_MS

    /** 失败冷却是否已过（只有凭据类失败需要冷却）。 */
    fun shouldAutoReloginAfterFailure(kind: JwxtAuthFailureKind, failAt: Long, now: Long): Boolean =
        !(kind.isCredentialLike && failAt > 0L && now - failAt < FAIL_COOLDOWN_MS)

    /** 是否是内置的已知异常节点。 */
    fun isKnownBadRoute(route: String?): Boolean = route != null && route in KNOWN_BAD_ROUTES
}
