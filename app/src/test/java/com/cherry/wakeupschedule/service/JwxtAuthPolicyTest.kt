package com.cherry.wakeupschedule.service

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

    @Test
    fun `从未确认过会话有效时应该预热`() {
        assertTrue(JwxtAuthPolicy.shouldPrewarm(now, 0L))
    }

    @Test
    fun `刚确认有效时不应预热`() {
        assertFalse(JwxtAuthPolicy.shouldPrewarm(now, now - 1_000L))
        assertFalse(JwxtAuthPolicy.shouldPrewarm(now, now - 24 * 60_000L))
    }

    @Test
    fun `超过预热阈值应预热`() {
        assertTrue(JwxtAuthPolicy.shouldPrewarm(now, now - JwxtAuthPolicy.PREWARM_AGE_MS))
        assertTrue(JwxtAuthPolicy.shouldPrewarm(now, now - 60 * 60_000L))
    }

    @Test
    fun `登录后很短时间内失效视为节点异常`() {
        assertTrue(JwxtAuthPolicy.isNodeSuspicious(80_000L)) // 实测 210.36.22.60 ≈ 80-90s
        assertTrue(JwxtAuthPolicy.isNodeSuspicious(JwxtAuthPolicy.QUICK_EXPIRY_MS))
        assertFalse(JwxtAuthPolicy.isNodeSuspicious(JwxtAuthPolicy.QUICK_EXPIRY_MS + 1))
        assertFalse(JwxtAuthPolicy.isNodeSuspicious(0L))
        assertFalse(JwxtAuthPolicy.isNodeSuspicious(30 * 60_000L))
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
