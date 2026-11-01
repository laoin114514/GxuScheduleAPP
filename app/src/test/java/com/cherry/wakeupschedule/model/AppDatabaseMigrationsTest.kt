package com.cherry.wakeupschedule.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库迁移链的结构性校验（纯 JVM，无需模拟器）。
 *
 * 这里不执行迁移 SQL（那需要真 SQLite），只锁住最容易出、也最致命的两个错误：
 * 1. 改了实体、`LATEST_VERSION` 忘了 +1 —— 用户库停在旧版本，Room 校验失败直接闪退；
 * 2. 加了迁移、忘了登记进 [AppDatabaseMigrations.ALL] —— 链断在中间，
 *    曾经会触发 destructive fallback 静默清库。
 *
 * 真正的「迁移后结构与新 schema 一致」由 `AppDatabaseMigrationTest`（androidTest）
 * 配合 `app/schemas/` 快照验证。
 */
class AppDatabaseMigrationsTest {

    private val migrations = AppDatabaseMigrations.ALL

    @Test
    fun `迁移链非空且没有未初始化的空元素`() {
        // ALL 必须声明在所有 MIGRATION_* 之后，否则 Kotlin 初始化顺序会让它塞满 null
        assertTrue("迁移链为空", migrations.isNotEmpty())
        migrations.forEachIndexed { index, migration ->
            assertNotNull("ALL[$index] 为 null，检查 ALL 的声明位置", migration)
        }
    }

    @Test
    fun `迁移链从最早支持版本起、到当前版本止`() {
        assertEquals(
            "迁移链必须从 OLDEST_SUPPORTED_VERSION 开始",
            AppDatabaseMigrations.OLDEST_SUPPORTED_VERSION,
            migrations.first().startVersion,
        )
        assertEquals(
            "最后一步迁移的终点必须等于 LATEST_VERSION，否则升级后会缺列闪退",
            AppDatabaseMigrations.LATEST_VERSION,
            migrations.last().endVersion,
        )
    }

    @Test
    fun `每一步迁移都跨一个版本且首尾相接`() {
        migrations.forEach { migration ->
            assertEquals(
                "迁移 ${migration.startVersion}→${migration.endVersion} 必须只跨一个版本",
                migration.startVersion + 1,
                migration.endVersion,
            )
        }

        for (i in 0 until migrations.size - 1) {
            val current = migrations[i]
            val next = migrations[i + 1]
            assertEquals(
                "迁移链在 ${current.endVersion} 与 ${next.startVersion} 之间断开",
                current.endVersion,
                next.startVersion,
            )
        }
    }

    @Test
    fun `迁移链按起始版本升序且无重复`() {
        val startVersions = migrations.map { it.startVersion }

        assertEquals("迁移不能按起始版本重复登记", startVersions.distinct().size, startVersions.size)
        assertEquals(
            "迁移链应按起始版本升序书写，便于人工核对",
            startVersions.sorted(),
            startVersions,
        )
    }

    @Test
    fun `迁移链覆盖的版本数等于版本跨度`() {
        val covered = migrations.last().endVersion - migrations.first().startVersion
        assertEquals(
            "迁移条数应与版本跨度一致，中间不允许有洞",
            covered,
            migrations.size,
        )
    }
}
