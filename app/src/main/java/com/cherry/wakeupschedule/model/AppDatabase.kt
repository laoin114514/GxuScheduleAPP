package com.cherry.wakeupschedule.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用数据库。
 *
 * 版本号与迁移链已抽到 [AppDatabaseMigrations]，此处只留装配：
 * - `version` 单一来源，不会与迁移声明对不上；
 * - schema 已开启导出（`app/schemas/`），每个版本的建表结构都有快照可比对。
 */
@Database(
    entities = [
        Course::class,
        AccountEntity::class,
        SemesterEntity::class,
        CookieEntity::class,
        GradeEntity::class,
        ExamScheduleEntity::class,
    ],
    version = AppDatabaseMigrations.LATEST_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun accountDao(): AccountDao
    abstract fun semesterDao(): SemesterDao
    abstract fun cookieDao(): CookieDao
    abstract fun gradeDao(): GradeDao
    abstract fun examScheduleDao(): ExamScheduleDao

    companion object {
        private const val DB_NAME = "schedule.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(*AppDatabaseMigrations.ALL)
                    // 只对上游遗留的版本 1 放宽：它没有任何迁移路径，重建是唯一出路。
                    // 此前是无参 fallbackToDestructiveMigration()，等于把「少写一步迁移」
                    // 也一并降级成静默清库 —— 用户导入的课表、成绩会毫无提示地消失。
                    // 现在其余版本一旦缺迁移会直接抛异常暴露出来。
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
