package com.cherry.wakeupschedule.model

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库版本号与全部迁移。
 *
 * 单独抽出来的原因：
 * 1. [LATEST_VERSION] 被 `@Database(version = ...)` 直接引用，版本号只有一个来源，
 *    不会再出现「实体改了、迁移写了、version 忘了 +1」这种静默漏更；
 * 2. [ALL] 是纯 JVM 可加载的对象，单元测试可以不开模拟器就校验整条链的连续性
 *    （见 `AppDatabaseMigrationsTest`）。之前这些 Migration 藏在 `AppDatabase.companion`
 *    里，测试一碰就会拉起 Android 运行时。
 *
 * ## 新增一版迁移的标准动作
 * 1. 改实体 → 2. 把 `LATEST_VERSION` +1 → 3. 在下面加 `MIGRATION_N_N+1` 并加进 [ALL]
 * → 4. 跑一次构建，`app/schemas/<新版本>.json` 会自动生成，**连同代码一起提交**
 * → 5. 在 `AppDatabaseMigrationTest` 里补一条 `runMigrationsAndValidate`。
 *
 * schema 快照是迁移链唯一的物证。少提交一个版本，那一步迁移就从此无法验证。
 */
object AppDatabaseMigrations {

    /** 当前 schema 版本。与 `@Database(version = ...)` 同源。 */
    const val LATEST_VERSION = 18

    /**
     * 手写迁移覆盖到的最早版本。
     *
     * 比它更早（版本 1，来自上游 WakeUp 课表）没有迁移路径，由
     * [AppDatabase] 的 destructive fallback 兜底重建 —— 只对这唯一一个版本放宽，
     * 其余版本缺失迁移一律抛异常，绝不允许悄悄清库。
     */
    const val OLDEST_SUPPORTED_VERSION = 2

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS semesters (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    account_id INTEGER NOT NULL DEFAULT 1,
                    label TEXT NOT NULL,
                    academic_year TEXT NOT NULL,
                    term_name TEXT NOT NULL,
                    term_code TEXT NOT NULL,
                    enrollment_year TEXT NOT NULL,
                    sort_order INTEGER NOT NULL,
                    start_date INTEGER NOT NULL DEFAULT 0,
                    total_weeks INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 添加 week_bitmap 列（DEFAULT 不带 NOT NULL，兼容旧 SQLite）
            db.execSQL("ALTER TABLE courses ADD COLUMN week_bitmap INTEGER DEFAULT 0")
            // 2. 添加 course_category 列
            db.execSQL("ALTER TABLE courses ADD COLUMN course_category TEXT DEFAULT ''")
            // 3. 迁移旧数据：逐行读取旧字段计算位图
            val cursor = db.query("SELECT id, start_week, end_week, week_type FROM courses")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val startWeek = cursor.getInt(1)
                val endWeek = cursor.getInt(2)
                val weekType = cursor.getInt(3)
                var bitmap = 0L
                for (w in startWeek..endWeek) {
                    val include = when (weekType) {
                        1 -> w % 2 == 1
                        2 -> w % 2 == 0
                        else -> true
                    }
                    if (include && w in 1..64) {
                        bitmap = bitmap or (1L shl (w - 1))
                    }
                }
                db.execSQL("UPDATE courses SET week_bitmap = ? WHERE id = ?", arrayOf(bitmap, id))
            }
            cursor.close()
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // courses 表新增 semester_id 外键列，直接删表重建
            db.execSQL("DROP TABLE IF EXISTS courses")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS courses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    teacher TEXT NOT NULL,
                    classroom TEXT NOT NULL,
                    day_of_week INTEGER NOT NULL,
                    start_time INTEGER NOT NULL,
                    end_time INTEGER NOT NULL,
                    week_bitmap INTEGER DEFAULT 0,
                    course_category TEXT DEFAULT '',
                    alarm_enabled INTEGER DEFAULT 1,
                    alarm_minutes_before INTEGER DEFAULT 15,
                    color INTEGER DEFAULT 0,
                    cover_image_path TEXT DEFAULT '',
                    semester_id INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // courses 表新增学分 / QQ群 两列。
            // 必须带 NOT NULL 且给非空默认值：Room 对非空字段会生成 NOT NULL，
            // 迁移后的列需与其一致，否则启动时表结构校验失败闪退。
            db.execSQL("ALTER TABLE courses ADD COLUMN credits TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN qq_group TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 新增 cookies 表：持久化教务系统会话 cookie。
            // 表结构与 CookieEntity 完全一致（主键 name,domain,path）。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cookies` (
                    `name` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `domain` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `expires_at` INTEGER NOT NULL,
                    `secure` INTEGER NOT NULL,
                    `http_only` INTEGER NOT NULL,
                    `host_only` INTEGER NOT NULL,
                    PRIMARY KEY(`name`, `domain`, `path`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 新增 grades 表：外键关联 semesters.id，学期清理时级联删除成绩。
            // 建表 SQL 必须与 Room 依 GradeEntity 生成的完全一致，否则启动表结构校验失败。
            createGradesTable(db)
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 8 是早期未发布的成绩表实验版本（拼音列名，含 pscj/qmcj 等）。
            // 该结构从未提交到仓库，却可能残留在开发机的数据库里，
            // 导致版本号相同但 identity hash 不符而启动崩溃。
            // 这里统一重建为当前结构；此时该表必然为空，不需要保留数据。
            db.execSQL("DROP TABLE IF EXISTS `grades`")
            createGradesTable(db)
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 新增 exam_schedules 表：外键关联 semesters.id，学期清理时级联删除考试安排。
            // 建表 SQL 必须与 Room 依 ExamScheduleEntity 生成的完全一致，否则启动表结构校验失败。
            createExamSchedulesTable(db)
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 10 是早期未发布的考试表实验版本：表名 `exams`、多一个 scheduled 列、
            // 教学班列叫 teaching_class（当前结构是 exam_schedules / teaching_class_name）。
            // 该结构从未提交到仓库，却可能残留在开发机的数据库里：版本号同为 10 时
            // Room 不会走任何迁移，直接以 identity hash 校验失败崩在 Application.onCreate。
            // 这里统一重建；考试数据来自教务，重查即可，不需要保留。
            db.execSQL("DROP TABLE IF EXISTS `exam_schedules`")
            db.execSQL("DROP TABLE IF EXISTS `exams`")
            createExamSchedulesTable(db)
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // courses 表补充教务返回、总课表详情要展示的课程信息。
            // 非空字段必须带 NOT NULL 且给非空默认值：Room 依 Course 生成的结构是
            // NOT NULL + DEFAULT，迁移后的列需与其一致，否则启动时表结构校验失败闪退。
            db.execSQL("ALTER TABLE courses ADD COLUMN is_practice INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE courses ADD COLUMN course_code TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN course_nature TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN teaching_class TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN class_composition TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN total_hours TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN hour_composition TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN classroom_type TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN assessment_method TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN exam_form TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN practice_detail TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // courses 表补充选课人数（zzrl 已选 / xkrs 上限），总课表详情展示用。
            // 同样必须 NOT NULL + 非空默认值，与 Room 依 Course 生成的结构一致。
            db.execSQL("ALTER TABLE courses ADD COLUMN enrolled TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE courses ADD COLUMN max_students TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 13 把教务的 xkrs / zzrl 落反了：enrolled 存了容量(zzrl)、max_students 存了
            // 选课人数(xkrs)。这里把两列的值对调回来 —— SQLite 的 SET 表达式都用更新前的
            // 行值求值，一条 UPDATE 即可完成交换。
            db.execSQL("UPDATE courses SET enrolled = max_students, max_students = enrolled")
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v1 中间版迁移（曾用于开发/模拟器，未对外发布）：courses 表新增「调课」标志（教务个人课表条目 jxbsftkbj，
            // 且顶层 xskbsfxstkzt == "1" 时才显示），供周课表/总课表给课程名加【调】。
            // 非空字段必须带 NOT NULL 且给非空默认值：Room 依 Course 生成的结构是
            // NOT NULL + DEFAULT，迁移后的列需与其一致，否则启动时表结构校验失败闪退。
            db.execSQL("ALTER TABLE courses ADD COLUMN is_adjusted INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v2：调课标记精确到「具体课次/周次」，courses 表新增 adjust_weeks 位图列
            // （bit0=第1周，语义同 week_bitmap）。
            // 15 号 schema 存在两代：v1 发布版只有 is_adjusted；v2 开发中间版 14→15 已带两列。
            // 因此按列是否存在做幂等补列，两类旧库都能升级，且不会重复 ADD COLUMN。
            // 非空字段必须带 NOT NULL 且给非空默认值，与 Room 依 Course 生成的结构一致。
            if (!hasColumn(db, "courses", "adjust_weeks")) {
                db.execSQL("ALTER TABLE courses ADD COLUMN adjust_weeks INTEGER NOT NULL DEFAULT 0")
            }
            // v1 是按「教学班级」标志 jxbsftkbj 给整门课所有行打调课标（一次调课整学期误标，
            // 用户已反馈）。升级时清空该派生列，等下次导入按调课记录精确重算；
            // adjust_weeks 本身默认 0，不需要额外处理。
            db.execSQL("UPDATE courses SET is_adjusted = 0")
        }
    }

    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v3：周课表详情展示「调课原因」。原因来自调课记录（tkyy/tksm），按命中行的周次一起
            // 落库；旧数据默认空串，等下次刷新课表时按调课记录重新计算（空值详情页自动隐藏该行）。
            // 非空字段必须带 NOT NULL 且给非空默认值，与 Room 依 Course 生成的结构一致。
            db.execSQL("ALTER TABLE courses ADD COLUMN adjust_reason TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v4：**结构自愈**——把 `courses` 表按当前标准结构重建，旧数据逐行搬迁。
     *
     * 为什么需要这一步（2026-11-05 实测复现）：
     * - 3→4 / 4→5 是 2026-07 的早期迁移。当时为了兼容老 SQLite，`ADD COLUMN` / `CREATE TABLE`
     *   里没有写 `NOT NULL`，由此产生的库中 `week_bitmap` / `course_category` /
     *   `alarm_enabled` / `alarm_minutes_before` / `color` / `cover_image_path`
     *   六列是**可空**的，而当前实体与 schema 要求 `NOT NULL` + 默认值。
     * - Room 在升级结束时会用最终 schema 校验整表结构，不一致直接抛
     *   `IllegalStateException: Migration didn't properly handle: courses(...)` 闪退。
     *   实测：v0.1.5（DB3）官方包直跳 v0.5.9 必现；v0.2.12（DB7）/v0.3.18（DB14）正常。
     * - 已经发布过的迁移 SQL 是历史事实、不能改（改了对已经跑过它的库没有意义），
     *   所以在链尾加一步「按当前 schema 重建表」：所有历史路径（含漂移库）最终都收敛到
     *   同一结构，数据原样搬运，不丢数据、不需要清库。
     *
     * 只重建 `courses`：其余表（account / semesters / cookies / grades / exam_schedules）
     * 要么由 Room 建表、要么由后续迁移用与当前实体一致的 SQL 创建；旧库实测与当前 schema 一致。
     *
     * 幂等性：先 DROP 临时表再建，重复执行/中断后重跑都不会脏。
     */
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `courses_v18`")
            db.execSQL(coursesCreateSql("courses_v18"))
            db.execSQL(
                """
                INSERT INTO `courses_v18` (${COURSES_COLUMNS_SQL})
                SELECT ${COURSES_COPY_SELECT_SQL} FROM `courses`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `courses`")
            db.execSQL("ALTER TABLE `courses_v18` RENAME TO `courses`")
        }
    }

    /** `courses` 表的全部列名（与 [coursesCreateSql] 一一对应，顺序即复制顺序）。 */
    private val COURSES_COLUMNS = listOf(
        "id", "name", "teacher", "classroom", "day_of_week", "start_time", "end_time",
        "week_bitmap", "course_category", "credits", "qq_group", "is_practice", "is_adjusted",
        "adjust_weeks", "adjust_reason", "course_code", "course_nature", "teaching_class",
        "class_composition", "total_hours", "hour_composition", "classroom_type",
        "assessment_method", "exam_form", "practice_detail", "enrolled", "max_students",
        "alarm_enabled", "alarm_minutes_before", "color", "cover_image_path", "semester_id",
    )

    /** 复制列清单（`INSERT INTO ... (列)`）。 */
    private val COURSES_COLUMNS_SQL: String = COURSES_COLUMNS.joinToString(", ") { "`$it`" }

    /**
     * 复制数据时的 SELECT 表达式：对历史漂移列做 COALESCE 兜底。
     *
     * 漂移库的这些列可为 NULL（老迁移 ADD COLUMN 时只给了 DEFAULT 没给 NOT NULL，
     * 之后又被 Gson/代码写过 null 的极端情况），而新表是 NOT NULL；直接搬运会触发
     * `NOT NULL constraint failed`。COALESCE 把 NULL 收敛到实体默认值，等价于「迁移回填」。
     */
    private val COURSES_COPY_SELECT_SQL: String = COURSES_COLUMNS.joinToString(", ") { column ->
        val default = when (column) {
            "id" -> null
            "week_bitmap", "is_practice", "is_adjusted", "adjust_weeks", "color", "semester_id" -> "0"
            "alarm_enabled" -> "1"
            "alarm_minutes_before" -> "15"
            "day_of_week", "start_time", "end_time" -> "0"
            else -> "''"
        }
        if (default == null) "`$column`" else "COALESCE(`$column`, $default)"
    }

    /**
     * `courses` 表的标准建表 SQL（与 Room 依 [Course] 生成的完全一致；18 号 schema 快照为物证）。
     * 17→18 自愈迁移与将来的历史修复都复用它，保证「标准结构」只有一处定义。
     */
    private fun coursesCreateSql(tableName: String): String = """
        CREATE TABLE IF NOT EXISTS `$tableName` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `teacher` TEXT NOT NULL,
            `classroom` TEXT NOT NULL,
            `day_of_week` INTEGER NOT NULL,
            `start_time` INTEGER NOT NULL,
            `end_time` INTEGER NOT NULL,
            `week_bitmap` INTEGER NOT NULL DEFAULT 0,
            `course_category` TEXT NOT NULL DEFAULT '',
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
            `alarm_enabled` INTEGER NOT NULL DEFAULT 1,
            `alarm_minutes_before` INTEGER NOT NULL DEFAULT 15,
            `color` INTEGER NOT NULL DEFAULT 0,
            `cover_image_path` TEXT NOT NULL DEFAULT '',
            `semester_id` INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    /**
     * 全部迁移，按起始版本升序。顺序即执行顺序，[AppDatabaseMigrationsTest] 会强制校验。
     *
     * 注意：必须声明在所有 MIGRATION_* 之后 —— Kotlin 对象属性按书写顺序初始化，
     * 提前引用会拿到尚未赋值的 null。
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
    )

    /** 表里是否已有某列（15→16 幂等迁移用；PRAGMA table_info 在 Android SQLite 上稳定可用）。 */
    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    /** grades 表建表 + 索引，7→8 与 8→9 共用，保证两处结构永远一致。 */
    private fun createGradesTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `grades` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `semester_id` INTEGER NOT NULL,
                `course_name` TEXT NOT NULL,
                `class_id` TEXT NOT NULL,
                `student_id` TEXT NOT NULL,
                `score` TEXT NOT NULL,
                `percentage_score` TEXT NOT NULL,
                `grade_point` TEXT NOT NULL,
                `credit_grade_point` TEXT NOT NULL,
                `credits` TEXT NOT NULL,
                `course_nature` TEXT NOT NULL,
                `course_category` TEXT NOT NULL,
                `course_type` TEXT NOT NULL,
                `course_mark` TEXT NOT NULL,
                `assessment_method` TEXT NOT NULL,
                `exam_nature` TEXT NOT NULL,
                `teacher_name` TEXT NOT NULL,
                `school_year` TEXT NOT NULL,
                `term` TEXT NOT NULL,
                `score_voided` TEXT NOT NULL,
                `published_at` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`semester_id`) REFERENCES `semesters`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_grades_semester_id` ON `grades` (`semester_id`)")
    }

    /** exam_schedules 表建表 + 索引（列顺序与 ExamScheduleEntity 声明顺序一致）。 */
    private fun createExamSchedulesTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exam_schedules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `semester_id` INTEGER NOT NULL,
                `course_name` TEXT NOT NULL,
                `course_code` TEXT NOT NULL,
                `exam_name` TEXT NOT NULL,
                `exam_time` TEXT NOT NULL,
                `classroom` TEXT NOT NULL,
                `classroom_code` TEXT NOT NULL,
                `locations` TEXT NOT NULL,
                `assessment_method` TEXT NOT NULL,
                `teaching_class_name` TEXT NOT NULL,
                `teacher` TEXT NOT NULL,
                `credits` TEXT NOT NULL,
                `school_year` TEXT NOT NULL,
                `term` TEXT NOT NULL,
                `paper_id` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`semester_id`) REFERENCES `semesters`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_schedules_semester_id` ON `exam_schedules` (`semester_id`)")
    }
}
