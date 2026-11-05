package com.cherry.wakeupschedule.model

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真正执行迁移 SQL 的仪器化测试（需要模拟器/真机，因为要用 Android 自带的 SQLite）。
 *
 * 为什么非要有它：
 * - `AppDatabaseMigrationsTest`（JVM）只用 `startVersion/endVersion` 校验「版本号 + 迁移链连续」，
 *   迁移 SQL 写错也照样全绿；
 * - `AppDatabaseSchemaSnapshotTest`（JVM）只校验 schema 快照是否存在、版本号与表名，
 *   不执行任何 SQL；
 * - 用户升级时 Room 会先跑迁移、再按实体结构逐列校验，任何一列的类型 / NOT NULL / 默认值
 *   漂移都会抛 `IllegalStateException: Migration didn't properly handle: courses(...)` 直接闪退。
 *
 * 本类用 [MigrationTestHelper] 把 `app/schemas/` 里的 schema 快照打进 androidTest assets，
 * 逐段执行 14→15→16→17→18 的迁移并用「迁移后的库结构 == 目标版本快照」做严格校验，
 * 同时检查迁移前插入的真实数据行没有被丢掉、新加列的回填默认值正确；
 * 另外单独覆盖 17→18「结构自愈」迁移：把 courses 表还原成 2026-07 早期迁移产生的
 * 「可空列」形态（v0.1.x 老库），验证自愈后结构与 schema 一致、旧行数据与默认值都正确。
 *
 * 运行方式（需要已连接的模拟器或真机）：
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * # 只跑本类（改迁移时节约时间）：
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.cherry.wakeupschedule.model.AppDatabaseMigrationTest
 * ```
 * CI 里由 `.github/workflows/android-ci.yml` 的 androidTest job 在 API 35 模拟器上执行。
 *
 * 维护约定：新增一版迁移（登记进 [AppDatabaseMigrations.ALL]）后，在这里补一条
 * `runMigrationsAndValidate(TEST_DB, <新版本>, true, MIGRATION_N_N1)`，并断言数据未丢。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    /**
     * Room 2.6.1 推荐的构造器：schema 目录由 `@Database` 类的全限定名推导，
     * 即 `app/schemas/com.cherry.wakeupschedule.model.AppDatabase/<version>.json`
     * （`app/build.gradle` 已把 `app/schemas` 加进 androidTest assets）。
     *
     * 注意：旧构造器 `MigrationTestHelper(Instrumentation, String, Factory)` 在 2.6.1 已废弃，
     * 且无法覆盖 AutoMigration；这里用 (Instrumentation, Class) 重载。
     */
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration14To18_keepsExistingRowAndBackfillsDefaults() {
        // 1. 按 v14 快照建库，并插入一行「升级前就存在」的真实课程数据。
        helper.createDatabase(TEST_DB_14_TO_18, 14).apply {
            insertV14Course()
            close()
        }

        // 2. 逐段迁移：每一段都用目标版本的 schema 快照严格校验（validateDroppedTables=true），
        //    这样结构漂移在「哪一段」就能定位，而不是只在最终版报一个笼统的异常。
        helper.runMigrationsAndValidate(
            TEST_DB_14_TO_18,
            15,
            true,
            migration(from = 14, to = 15),
        ).apply {
            assertEquals("14→15 迁移不应丢数据行", 1, courseCount())
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB_14_TO_18,
            16,
            true,
            migration(from = 15, to = 16),
        ).apply {
            assertEquals("15→16 迁移不应丢数据行", 1, courseCount())
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB_14_TO_18,
            17,
            true,
            migration(from = 16, to = 17),
        ).apply {
            assertEquals("16→17 迁移不应丢数据行", 1, courseCount())
            close()
        }
        val db = helper.runMigrationsAndValidate(
            TEST_DB_14_TO_18,
            18,
            true,
            migration(from = 17, to = 18),
        )

        // 3. 旧数据仍在、且 15/16/17 新加的列在旧行上拿到了正确默认值；
        //    17→18 会重建表，这里同时验证重建没有丢数据、没有改列值。
        db.query(
            """
            SELECT name, teacher, week_bitmap, course_category,
                   is_adjusted, adjust_weeks, adjust_reason, semester_id
            FROM courses
            """.trimIndent()
        ).use { cursor ->
            assertTrue("14→18 全链迁移后旧数据行必须仍然存在", cursor.moveToFirst())
            assertEquals("迁移不应复制出多余的数据行", 1, cursor.count)

            assertEquals("课程名丢了，迁移可能重建了表却没搬数据", "高等数学", cursor.getString(0))
            assertEquals("教师丢了", "张老师", cursor.getString(1))
            assertEquals("week_bitmap 位图被改写", WEEK_BITMAP_1_TO_16, cursor.getLong(2))
            assertEquals("course_category 丢了", "必修", cursor.getString(3))

            assertEquals("15 新增的 is_adjusted 默认值应为 0", 0, cursor.getInt(4))
            assertEquals("16 新增的 adjust_weeks 默认值应为 0", 0L, cursor.getLong(5))
            assertEquals("17 新增的 adjust_reason 默认值应为空串", "", cursor.getString(6))
            assertEquals("semester_id 外键列被改写", 1L, cursor.getLong(7))
        }
        db.close()
    }

    @Test
    fun migration16To18_keepsExistingRowAndBackfillsEmptyAdjustReason() {
        // v16 是 v0.5.7 发布版；v0.5.9 升级时这一段是现网用户真实会走到的路径。
        helper.createDatabase(TEST_DB_16_TO_18, 16).apply {
            // 旧库里的行可能已经被调课标记过：迁移只能补 adjust_reason，不能动已有标记。
            execSQL(
                """
                INSERT INTO courses (
                    name, teacher, classroom, day_of_week, start_time, end_time,
                    week_bitmap, course_category, is_adjusted, adjust_weeks
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "线性代数", "李老师", "B202", 3, 3, 4,
                    WEEK_BITMAP_1_TO_16, "选修", 1, ADJUST_WEEKS_2_AND_3,
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB_16_TO_18,
            17,
            true,
            migration(from = 16, to = 17),
        ).close()
        val db = helper.runMigrationsAndValidate(
            TEST_DB_16_TO_18,
            18,
            true,
            migration(from = 17, to = 18),
        )

        db.query(
            "SELECT name, is_adjusted, adjust_weeks, adjust_reason FROM courses"
        ).use { cursor ->
            assertTrue("16→18 迁移后旧数据行必须仍然存在", cursor.moveToFirst())
            assertEquals("迁移不应复制出多余的数据行", 1, cursor.count)

            assertEquals("课程名丢了，17/18 迁移可能重建了表", "线性代数", cursor.getString(0))
            assertEquals("旧行的调课标记 is_adjusted 被改写", 1, cursor.getInt(1))
            assertEquals("旧行的调课周次位图 adjust_weeks 被改写", ADJUST_WEEKS_2_AND_3, cursor.getLong(2))
            // ALTER TABLE ADD COLUMN ... NOT NULL DEFAULT '' 会让旧行读出空串，
            // 详情页据此自动隐藏「调课原因」行，等下次刷新课表再按调课记录重算。
            assertEquals("旧行 adjust_reason 应回填为空串", "", cursor.getString(3))
        }
        db.close()
    }

    @Test
    fun migration17To18_healsLegacyNullableCoursesTable() {
        // 回归 2026-07 早期迁移留下的结构漂移：3→4 / 4→5 当时没有写 NOT NULL，
        // 老库（v0.1.x 创建）的 courses 有 6 列可空。直接升级到新版本时 Room 会抛
        // `Migration didn't properly handle: courses(...)` 闪退（真实复现过 v0.1.5 → 0.5.9）。
        // 这里把 17 号库的 courses 还原成「可空列 + 含 NULL 的数据行」，验证 17→18 自愈：
        // 1) 结构与 18 号 schema 一致（runMigrationsAndValidate 会严格校验）；
        // 2) 旧行保留、id 不变；
        // 3) 可空列里的 NULL 被收敛成实体默认值（COALESCE 回填）。
        helper.createDatabase(TEST_DB_17_LEGACY_TO_18, 17).apply {
            execSQL("DROP TABLE `courses`")
            execSQL(
                """
                CREATE TABLE `courses` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `teacher` TEXT NOT NULL,
                    `classroom` TEXT NOT NULL,
                    `day_of_week` INTEGER NOT NULL,
                    `start_time` INTEGER NOT NULL,
                    `end_time` INTEGER NOT NULL,
                    `week_bitmap` INTEGER DEFAULT 0,
                    `course_category` TEXT DEFAULT '',
                    `credits` TEXT NOT NULL DEFAULT '',
                    `qq_group` TEXT NOT NULL DEFAULT '',
                    `is_practice` INTEGER NOT NULL DEFAULT 0,
                    `is_adjusted` INTEGER NOT NULL DEFAULT 0,
                    `adjust_weeks` INTEGER NOT NULL DEFAULT 0,
                    `adjust_reason` TEXT NOT NULL DEFAULT '',
                    `course_code` TEXT NOT NULL DEFAULT '',
                    `course_nature` TEXT NOT NULL DEFAULT '',
                    `teaching_class` TEXT NOT NULL DEFAULT '',
                    `class_composition` TEXT NOT NULL DEFAULT '',
                    `total_hours` TEXT NOT NULL DEFAULT '',
                    `hour_composition` TEXT NOT NULL DEFAULT '',
                    `classroom_type` TEXT NOT NULL DEFAULT '',
                    `assessment_method` TEXT NOT NULL DEFAULT '',
                    `exam_form` TEXT NOT NULL DEFAULT '',
                    `practice_detail` TEXT NOT NULL DEFAULT '',
                    `enrolled` TEXT NOT NULL DEFAULT '',
                    `max_students` TEXT NOT NULL DEFAULT '',
                    `alarm_enabled` INTEGER DEFAULT 1,
                    `alarm_minutes_before` INTEGER DEFAULT 15,
                    `color` INTEGER DEFAULT 0,
                    `cover_image_path` TEXT DEFAULT '',
                    `semester_id` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            // 故意把 6 个漂移列写成 NULL，模拟最脏的老库数据
            execSQL(
                """
                INSERT INTO courses (
                    id, name, teacher, classroom, day_of_week, start_time, end_time,
                    week_bitmap, course_category, alarm_enabled, alarm_minutes_before,
                    color, cover_image_path, semester_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, ?)
                """.trimIndent(),
                arrayOf<Any>(7L, "旧课程", "赵老师", "A101", 2, 3, 4, 2L),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_17_LEGACY_TO_18,
            18,
            true,
            migration(from = 17, to = 18),
        )

        // 自愈后 6 列必须变成 NOT NULL（与 schema 一致）；runMigrationsAndValidate 已校验结构，
        // 这里再显式查一次 PRAGMA，让失败信息直接指向列级原因。
        val notNullByColumn = mutableMapOf<String, Boolean>()
        db.query("PRAGMA table_info(`courses`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val notNullIndex = cursor.getColumnIndex("notnull")
            while (cursor.moveToNext()) {
                notNullByColumn[cursor.getString(nameIndex)] = cursor.getInt(notNullIndex) != 0
            }
        }
        listOf(
            "week_bitmap", "course_category", "alarm_enabled",
            "alarm_minutes_before", "color", "cover_image_path",
        ).forEach { column ->
            assertEquals("17→18 自愈后 $column 必须恢复为 NOT NULL", true, notNullByColumn[column])
        }

        db.query(
            """
            SELECT id, name, week_bitmap, course_category, alarm_enabled,
                   alarm_minutes_before, color, cover_image_path, semester_id
            FROM courses
            """.trimIndent()
        ).use { cursor ->
            assertTrue("17→18 自愈后旧数据行必须仍然存在", cursor.moveToFirst())
            assertEquals("迁移不应复制出多余的数据行", 1, cursor.count)
            assertEquals("主键 id 被改写", 7L, cursor.getLong(0))
            assertEquals("课程名丢了", "旧课程", cursor.getString(1))
            assertEquals("week_bitmap 的 NULL 应回填为 0", 0L, cursor.getLong(2))
            assertEquals("course_category 的 NULL 应回填为空串", "", cursor.getString(3))
            assertEquals("alarm_enabled 的 NULL 应回填为 1", 1, cursor.getInt(4))
            assertEquals("alarm_minutes_before 的 NULL 应回填为 15", 15, cursor.getInt(5))
            assertEquals("color 的 NULL 应回填为 0", 0, cursor.getInt(6))
            assertEquals("cover_image_path 的 NULL 应回填为空串", "", cursor.getString(7))
            assertEquals("semester_id 不应被改写", 2L, cursor.getLong(8))
        }
        db.close()
    }

    @Test
    fun migration15To16_isIdempotentForV2SchemaWithAdjustWeeks() {
        // 「15 号 schema 有两代」：v1 发布版只有 is_adjusted，v2 开发中间版的 14→15
        // 已经带上了 adjust_weeks。这里模拟 v2 形态的 15 号库，验证幂等分支
        // （hasColumn 已存在就不重复 ADD COLUMN）不会把开发机上的库升级搞崩。
        helper.createDatabase(TEST_DB_15_V2_TO_16, 15).apply {
            execSQL("ALTER TABLE courses ADD COLUMN adjust_weeks INTEGER NOT NULL DEFAULT 0")
            execSQL(
                """
                INSERT INTO courses (
                    name, teacher, classroom, day_of_week, start_time, end_time,
                    week_bitmap, course_category, is_adjusted, adjust_weeks
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "大学物理", "王老师", "C303", 5, 5, 6,
                    WEEK_BITMAP_1_TO_16, "必修", 1, ADJUST_WEEKS_2_AND_3,
                ),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_15_V2_TO_16,
            16,
            true,
            migration(from = 15, to = 16),
        )

        db.query(
            "SELECT name, is_adjusted, adjust_weeks FROM courses"
        ).use { cursor ->
            assertTrue("幂等分支迁移后旧数据行必须仍然存在", cursor.moveToFirst())
            assertEquals("迁移不应复制出多余的数据行", 1, cursor.count)

            assertEquals("课程名丢了", "大学物理", cursor.getString(0))
            // v1 的 is_adjusted 是按「整门课」打的，命中率太高（用户已反馈误标），
            // 迁移会清空这个派生列，等下次导入按调课记录精确重算。
            assertEquals("15→16 应清空旧库的 is_adjusted 派生标记", 0, cursor.getInt(1))
            assertEquals("adjust_weeks 位图不应被清空", ADJUST_WEEKS_2_AND_3, cursor.getLong(2))
        }
        db.close()
    }

    /** 从 `AppDatabaseMigrations.ALL` 取出指定的一段迁移（MIGRATION_* 变量本身是 private）。 */
    private fun migration(from: Int, to: Int): Migration =
        AppDatabaseMigrations.ALL.single {
            it.startVersion == from && it.endVersion == to
        }

    /** 当前 courses 行数；迁移前后对比用，确认不是「建了空表」或「重复搬数据」。 */
    private fun SupportSQLiteDatabase.courseCount(): Int =
        query("SELECT COUNT(*) FROM courses").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }

    /**
     * 按 v14 的列插入一行真实课程数据（14 版没有 is_adjusted / adjust_weeks / adjust_reason）。
     *
     * 用绑定参数而不是字符串拼接：课程名里将来若带单引号也不会把 SQL 弄坏。
     */
    private fun SupportSQLiteDatabase.insertV14Course() {
        execSQL(
            """
            INSERT INTO courses (
                name, teacher, classroom, day_of_week, start_time, end_time,
                week_bitmap, course_category, credits, qq_group, is_practice,
                course_code, course_nature, teaching_class, class_composition,
                total_hours, hour_composition, classroom_type, assessment_method,
                exam_form, practice_detail, enrolled, max_students, alarm_enabled,
                alarm_minutes_before, color, cover_image_path, semester_id
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            arrayOf<Any>(
                "高等数学", "张老师", "A101", 1, 1, 2,
                WEEK_BITMAP_1_TO_16, "必修", "3.0", "123456789", 0,
                "MATH01", "学类", "教学班-01", "机械2301", "48", "理论:48",
                "普通教室", "考试", "闭卷", "", "60", "100", 1,
                15, 3, "", 1L,
            ),
        )
    }

    private companion object {
        /**
         * 迁移测试用的库文件名。MigrationTestHelper 会在被测 App 的 `databases/` 下
         * 建库/删库；每个用例用独立文件名，互不影响。
         */
        const val TEST_DB_14_TO_18 = "migration-test-14-18"
        const val TEST_DB_16_TO_18 = "migration-test-16-18"
        const val TEST_DB_15_V2_TO_16 = "migration-test-15-v2-16"
        const val TEST_DB_17_LEGACY_TO_18 = "migration-test-17-legacy-18"

        /** 第 1-16 周的周次位图（bit0 = 第 1 周）。 */
        val WEEK_BITMAP_1_TO_16: Long = Course.bitmapFromRange(1, 16)

        /** 只调了第 2、3 周的 adjust_weeks 位图（bit1 + bit2 = 6）。 */
        const val ADJUST_WEEKS_2_AND_3: Long = 6L
    }
}
