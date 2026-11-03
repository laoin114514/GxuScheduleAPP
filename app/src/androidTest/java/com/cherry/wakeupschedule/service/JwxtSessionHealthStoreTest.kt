package com.cherry.wakeupschedule.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [JwxtSessionHealthStore] 的仪器化测试（不联网、不涉及真实账号）。
 *
 * 覆盖：持久化往返、旧格式（v1 `badRoutes` 字符串数组）兼容、异常节点 7 天衰减、
 * TTL 上界学习（只变小）与"被实际存活推翻后清除"。
 */
@RunWith(AndroidJUnit4::class)
class JwxtSessionHealthStoreTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val prefs
        get() = context.getSharedPreferences("jwxt_session_health", Context.MODE_PRIVATE)

    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Before
    fun setUp() {
        JwxtSessionHealthStore.init(context)
        JwxtSessionHealthStore.clear()
    }

    @After
    fun tearDown() {
        JwxtSessionHealthStore.clear()
    }

    private fun seedRaw(json: String) {
        assertTrue(prefs.edit().putString("state_v1", json).commit())
    }

    private fun now() = System.currentTimeMillis()

    @Test
    fun loginAndMarkOk_roundTrip() {
        JwxtSessionHealthStore.markLogin("route-a")
        var health = JwxtSessionHealthStore.load()
        assertEquals("route-a", health.route)
        assertTrue(health.lastLoginAt > 0L)
        assertTrue(health.lastOkAt > 0L)
        assertEquals(JwxtAuthFailureKind.NONE, health.lastFailKind)
        assertTrue(health.badRoutes.isEmpty())

        JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.EXPIRED)
        health = JwxtSessionHealthStore.load()
        assertEquals(JwxtAuthFailureKind.EXPIRED, health.lastFailKind)

        JwxtSessionHealthStore.markOk("route-a")
        health = JwxtSessionHealthStore.load()
        assertEquals(JwxtAuthFailureKind.NONE, health.lastFailKind)
        assertEquals(0L, health.lastFailAt)
    }

    @Test
    fun legacyBadRoutesArray_isTreatedAsExpired() {
        // v1 格式：badRoutes 是字符串数组，没有时间戳 → 升级后视为已过期（会重新参与随机落点）
        seedRaw(
            """{"version":1,"route":"route-a","lastOkAt":0,"lastLoginAt":0,
               "lastFailKind":"NONE","lastFailAt":0,"badRoutes":["route-legacy"]}"""
                .trimIndent()
        )
        assertFalse(JwxtSessionHealthStore.isBadRoute("route-legacy"))
        assertTrue(JwxtSessionHealthStore.load().badRoutes.isEmpty())
    }

    @Test
    fun learnedBadRoute_decaysAfterSevenDays() {
        val current = now()
        seedRaw(
            """{"version":2,"route":"r","lastOkAt":0,"lastLoginAt":0,
               "lastFailKind":"NONE","lastFailAt":0,
               "badRoutes":{"fresh-route":$current,"stale-route":${current - 8 * day}},
               "routeTtlUpperMs":{}}"""
                .trimIndent()
        )
        assertTrue(JwxtSessionHealthStore.isBadRoute("fresh-route"))
        assertFalse(JwxtSessionHealthStore.isBadRoute("stale-route"))
    }

    @Test
    fun markRouteBad_keepsFirstTimestamp() {
        JwxtSessionHealthStore.markRouteBad("route-x")
        val first = JwxtSessionHealthStore.load().badRoutes.getValue("route-x")
        Thread.sleep(30)
        JwxtSessionHealthStore.markRouteBad("route-x")
        assertEquals(first, JwxtSessionHealthStore.load().badRoutes.getValue("route-x"))
        assertTrue(JwxtSessionHealthStore.isBadRoute("route-x"))
    }

    @Test
    fun ttlUpper_onlyShrinks() {
        JwxtSessionHealthStore.markRouteTtlUpper("route-b", 30 * minute)
        JwxtSessionHealthStore.markRouteTtlUpper("route-b", 40 * minute)
        assertEquals(30 * minute, JwxtSessionHealthStore.load().routeTtlUpperMs["route-b"])
        // 更小的观测值才会覆盖
        JwxtSessionHealthStore.markRouteTtlUpper("route-b", 20 * minute)
        assertEquals(20 * minute, JwxtSessionHealthStore.load().routeTtlUpperMs["route-b"])
    }

    @Test
    fun ttlUpper_clearedWhenActualSurvivalContradictsIt() {
        val current = now()
        // 之前学习到 "该节点 TTL ≤ 30min"，但本次安静 40min 后业务仍成功（markOk）→ 估计错误，应清除
        seedRaw(
            """{"version":2,"route":"route-c","lastOkAt":${current - 40 * minute},
               "lastLoginAt":${current - 40 * minute},"lastFailKind":"NONE","lastFailAt":0,
               "badRoutes":{},"routeTtlUpperMs":{"route-c":${30 * minute}}}"""
                .trimIndent()
        )
        JwxtSessionHealthStore.markOk("route-c")
        assertNull(JwxtSessionHealthStore.load().routeTtlUpperMs["route-c"])
    }
}
