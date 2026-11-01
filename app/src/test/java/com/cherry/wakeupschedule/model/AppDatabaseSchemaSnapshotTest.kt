package com.cherry.wakeupschedule.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验 Room 导出的 schema 快照与代码一致（纯 JVM）。
 *
 * `exportSchema = true` 之后，每次构建都会把当前结构写到
 * `app/schemas/<包名>.<库名>/<版本>.json`。那个目录是迁移链唯一的物证：
 * 少提交一版，对应那一步迁移就永远无法验证。
 *
 * 这里锁住两件容易被忽略的事：
 * 1. `LATEST_VERSION` 改了，快照却没重新生成/提交 —— 构建产物与仓库脱节；
 * 2. 新增了实体表，快照里却没有这张表 —— 说明构建没跑或文件没提交。
 */
class AppDatabaseSchemaSnapshotTest {

    private val schemaDirName = "com.cherry.wakeupschedule.model.AppDatabase"

    @Test
    fun `当前版本已导出 schema 快照且版本号与代码一致`() {
        val file = File(schemaRoot(), "$schemaDirName/${AppDatabaseMigrations.LATEST_VERSION}.json")

        assertTrue(
            "缺少 schema 快照：$file\n" +
                "改了实体/版本号之后必须重新构建一次并提交 app/schemas/ 下的新快照。",
            file.isFile,
        )

        val database = JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonObject("database")

        assertEquals(
            "快照里的 version 与 AppDatabaseMigrations.LATEST_VERSION 不一致",
            AppDatabaseMigrations.LATEST_VERSION,
            database.get("version").asInt,
        )
    }

    @Test
    fun `快照包含全部六张业务表`() {
        val file = File(schemaRoot(), "$schemaDirName/${AppDatabaseMigrations.LATEST_VERSION}.json")
        val entities = JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonObject("database")
            .getAsJsonArray("entities")
            .map { it.asJsonObject.get("tableName").asString }
            .toSet()

        // 与 @Database(entities = [...]) 一一对应；新增实体而忘了重新导出会被这里拦下
        val expected = setOf("courses", "account", "semesters", "cookies", "grades", "exam_schedules")
        assertEquals("schema 快照的表集合与实体清单不一致", expected, entities)
    }

    /**
     * 定位 `app/schemas` 目录。
     *
     * 单测的工作目录通常是模块目录（app/），但也可能被 IDE 配成项目根，
     * 所以向上最多找三层，两种布局都兼容。
     */
    private fun schemaRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val current = dir ?: return@repeat
            File(current, "schemas").takeIf { File(it, schemaDirName).isDirectory }?.let { return it }
            File(current, "app/schemas").takeIf { File(it, schemaDirName).isDirectory }?.let { return it }
            dir = current.parentFile
        }
        // 找不到就返回模块内路径，让断言给出「缺少快照」的清晰失败信息
        return File("schemas")
    }
}
