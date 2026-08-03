package com.cherry.wakeupschedule.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Course::class, AccountEntity::class, SemesterEntity::class, CookieEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun accountDao(): AccountDao
    abstract fun semesterDao(): SemesterDao
    abstract fun cookieDao(): CookieDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
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
                """.trimIndent())
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
                db.execSQL("""
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
                """.trimIndent())
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
                db.execSQL("""
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
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schedule.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
