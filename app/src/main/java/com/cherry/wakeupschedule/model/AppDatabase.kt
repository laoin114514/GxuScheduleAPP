package com.cherry.wakeupschedule.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Course::class, AccountEntity::class, SemesterEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun accountDao(): AccountDao
    abstract fun semesterDao(): SemesterDao

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
                // 1. 添加 week_bitmap 列
                db.execSQL("ALTER TABLE courses ADD COLUMN week_bitmap INTEGER NOT NULL DEFAULT 0")
                // 2. 添加 course_category 列
                db.execSQL("ALTER TABLE courses ADD COLUMN course_category TEXT NOT NULL DEFAULT ''")
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schedule.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
