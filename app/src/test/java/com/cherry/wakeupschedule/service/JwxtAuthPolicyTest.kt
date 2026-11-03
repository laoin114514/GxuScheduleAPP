package com.cherry.wakeupschedule.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JwxtAuthPolicy] 纯函数策略的单元测试。
 *
 * 阈值来源见 [JwxtAuthPolicy] 的 KDoc 与 `docs/登录状态失效规律实测.md`：
 * 多数节点空闲 TTL ≈60min、`210.36.22.55` ≈30min、`210.36.22.60` ≈80–90s（异常）。
 */
class JwxtAuthPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test
    fun `从未确认过会话有效时应该预热`() {
        assertTrue(JwxtAuthPolicy.shouldPrewarm(now, 0L))
    }

    @Test
    fun `刚确认有效时不应预热`() {
        assertFalse(JwxtAuthPolicy.shouldPrewarm(now, now - 1_000L))
        assertFalse(JwxtAuthPolicy.shouldPrewarm(now, now - 24 * minute))
    }

    @Test
    fun `超过预热阈值应预热`() {
        assertTrue(JwxtAuthPolicy.shouldPrewarm(now, now - JwxtAuthPolicy.PREWARM_AGE_MS))
        assertTrue(JwxtAuthPolicy.shouldPrewarm(now, now - 60 * minute))
    }

    @Test
    fun `预热阈值按节点 TTL 上界收紧`() {
        // 默认 60min 档：0.75 × 60min = 45min，但被 25min 上限压住
        assertEquals(JwxtAuthPolicy.PREWARM_AGE_MS, JwxtAuthPolicy.prewarmAgeFor(null))
        // 学到 30min 档（.55 节点）：22.5min
        assertEquals(30 * minute * 3 / 4, JwxtAuthPolicy.prewarmAgeFor(30 * minute))
        // 学到 90s 异常节点：67.5s
        assertEquals(90_000L * 3 / 4, JwxtAuthPolicy.prewarmAgeFor(90_000L))
        // 极短 TTL 也不会低于 1 分钟下限
        assertEquals(JwxtAuthPolicy.MIN_PREWARM_AGE_MS, JwxtAuthPolicy.prewarmAgeFor(10_000L))
        // 上界超过默认档时不会让阈值晚于默认值
        assertEquals(JwxtAuthPolicy.PREWARM_AGE_MS, JwxtAuthPolicy.prewarmAgeFor(120 * minute))
    }

    @Test
    fun `按节点 TTL 上界判断是否需要预热`() {
        val lastOk24minAgo = now - 24 * minute
        // 默认阈值 25min：24min 还没到
        assertFalse(JwxtAuthPolicy.shouldPrewarm(now, lastOk24minAgo))
        // 学到该节点 30min TTL 上界 → 阈值 22.5min：24min 已该预热
        assertTrue(JwxtAuthPolicy.shouldPrewarm(now, lastOk24minAgo, 30 * minute))
    }

    @Test
    fun `登录后很短时间内失效视为节点异常`() {
        assertTrue(JwxtAuthPolicy.isNodeSuspicious(80_000L)) // 实测 210.36.22.60 ≈ 80-90s
        assertTrue(JwxtAuthPolicy.isNodeSuspicious(JwxtAuthPolicy.QUICK_EXPIRY_MS))
        assertFalse(JwxtAuthPolicy.isNodeSuspicious(JwxtAuthPolicy.QUICK_EXPIRY_MS + 1))
        assertFalse(JwxtAuthPolicy.isNodeSuspicious(0L))
        assertFalse(JwxtAuthPolicy.isNodeSuspicious(30 * minute))
    }

    @Test
    fun `学习到的异常节点七天后自动失效`() {
        assertTrue(JwxtAuthPolicy.isLearnedBadRoute(now - 6 * day, now))
        assertFalse(JwxtAuthPolicy.isLearnedBadRoute(now - 8 * day, now))
        assertFalse(JwxtAuthPolicy.isLearnedBadRoute(null, now))
        // 时钟回拨/未来时间戳按"仍然有效"处理（避免旧数据因时钟问题永久/立即失效）
        assertTrue(JwxtAuthPolicy.isLearnedBadRoute(now + minute, now))
    }

    @Test
    fun `凭据类失败有冷却_网络类失败不冷却`() {
        val failAt = now - 1_000L
        assertFalse(JwxtAuthPolicy.shouldAutoReloginAfterFailure(JwxtAuthFailureKind.CREDENTIALS, failAt, now))
        assertFalse(JwxtAuthPolicy.shouldAutoReloginAfterFailure(JwxtAuthFailureKind.CAPTCHA, failAt, now))
        assertTrue(JwxtAuthPolicy.shouldAutoReloginAfterFailure(JwxtAuthFailureKind.NETWORK, failAt, now))
        assertTrue(JwxtAuthPolicy.shouldAutoReloginAfterFailure(JwxtAuthFailureKind.NONE, failAt, now))
        // 从未失败过（failAt=0）不冷却
        assertTrue(JwxtAuthPolicy.shouldAutoReloginAfterFailure(JwxtAuthFailureKind.CREDENTIALS, 0L, now))
    }

    @Test
    fun `冷却期过后恢复自动重登`() {
        assertTrue(
            JwxtAuthPolicy.shouldAutoReloginAfterFailure(
                JwxtAuthFailureKind.CREDENTIALS,
                now - JwxtAuthPolicy.FAIL_COOLDOWN_MS,
                now
            )
        )
        assertFalse(
            JwxtAuthPolicy.shouldAutoReloginAfterFailure(
                JwxtAuthFailureKind.CREDENTIALS,
                now - JwxtAuthPolicy.FAIL_COOLDOWN_MS + 1,
                now
            )
        )
    }

    @Test
    fun `内置异常节点识别`() {
        // 210.36.22.60 的 route 常量（2026-11-03 实测 TTL ≈80-90s）
        assertTrue(JwxtAuthPolicy.isKnownBadRoute("2a353708a723ad925112de2eb5f2f993"))
        // 正常节点的 route 常量（172.16.40.49）
        assertFalse(JwxtAuthPolicy.isKnownBadRoute("801f0ff5190614777dd6fa87be35472d"))
        assertFalse(JwxtAuthPolicy.isKnownBadRoute(null))
        assertFalse(JwxtAuthPolicy.isKnownBadRoute(""))
    }
}
