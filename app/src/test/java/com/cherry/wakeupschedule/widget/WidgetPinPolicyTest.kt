package com.cherry.wakeupschedule.widget

import com.cherry.wakeupschedule.widget.WidgetPinPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WidgetPinPolicy] 的核验语义测试（纯 JVM，无需模拟器）。
 *
 * 覆盖「我的 → 小组件」里一键添加的三种真实机型表现：
 * - 小米 / 红米静默添加：首轮核验就该判成功（否则页面不会刷成「已添加」）；
 * - vivo / iQOO 毫无反应：等满轮次后必须给出手动添加引导，而不是静默；
 * - 用户自己点了取消：只温和提示，不甩一整页排查弹窗。
 */
class WidgetPinPolicyTest {

    private fun evaluate(
        baselineCount: Int = 0,
        currentCount: Int = 0,
        attempts: Int = 1,
        leftForeground: Boolean = false,
        foregroundWithFocus: Boolean = true,
    ) = WidgetPinPolicy.evaluate(
        baselineCount = baselineCount,
        currentCount = currentCount,
        attempts = attempts,
        leftForeground = leftForeground,
        foregroundWithFocus = foregroundWithFocus,
    )

    @Test
    fun `组件数变多立刻判成功，不受轮次与焦点影响`() {
        // 小米 / 红米不弹确认框、直接添加：首次核验（attempts=1）就该出「已添加」
        assertEquals(Outcome.ADDED, evaluate(currentCount = 1, attempts = 1))

        // 同一组件再添加一个也算成功（计数是增量判定，不是等值判定）
        assertEquals(Outcome.ADDED, evaluate(baselineCount = 1, currentCount = 2, attempts = 1))

        // 华为 / 荣耀不回调，靠回前台的那次核验兜底；此时可能已经等了好几轮
        assertEquals(
            Outcome.ADDED,
            evaluate(
                currentCount = 1,
                attempts = WidgetPinPolicy.MAX_ATTEMPTS,
                leftForeground = true,
                foregroundWithFocus = true,
            ),
        )
    }

    @Test
    fun `轮次不足时不下结论，继续等桌面落地`() {
        for (attempts in 1 until WidgetPinPolicy.MIN_ATTEMPTS) {
            assertEquals(
                "attempts=$attempts 时应继续等待",
                Outcome.RETRY,
                evaluate(attempts = attempts),
            )
        }
    }

    @Test
    fun `系统确认框还压着本页时，即使轮次已够也继续等`() {
        assertEquals(
            Outcome.RETRY,
            evaluate(
                attempts = WidgetPinPolicy.MIN_ATTEMPTS,
                foregroundWithFocus = false,
            ),
        )
    }

    @Test
    fun `桌面从未响应时给手动添加引导`() {
        // vivo / iQOO：既不弹框也不添加，本页一直在前台 —— 老实现就是在这里「一点反馈都没有」
        assertEquals(
            Outcome.NO_RESPONSE,
            evaluate(
                attempts = WidgetPinPolicy.MIN_ATTEMPTS,
                leftForeground = false,
                foregroundWithFocus = true,
            ),
        )
    }

    @Test
    fun `系统界面出现过却没加上，按用户取消温和处理`() {
        assertEquals(
            Outcome.CANCELED,
            evaluate(
                attempts = WidgetPinPolicy.MIN_ATTEMPTS,
                leftForeground = true,
                foregroundWithFocus = true,
            ),
        )
    }

    @Test
    fun `轮次到上限后必须给结论，不再无限轮询`() {
        assertEquals(
            Outcome.CANCELED,
            evaluate(
                attempts = WidgetPinPolicy.MAX_ATTEMPTS,
                leftForeground = true,
                foregroundWithFocus = false,
            ),
        )
        assertEquals(
            Outcome.NO_RESPONSE,
            evaluate(
                attempts = WidgetPinPolicy.MAX_ATTEMPTS,
                leftForeground = false,
                foregroundWithFocus = false,
            ),
        )
    }
}
