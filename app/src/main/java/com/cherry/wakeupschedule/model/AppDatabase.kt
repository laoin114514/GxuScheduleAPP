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

        /**
         * 应急恢复：关闭并删除本地数据库文件。
         *
         * 仅用于 [com.cherry.wakeupschedule.service.AppInitializer] 初始化失败后，
         * 用户在启动恢复弹窗里**主动选择**「重置本地数据」的场景（典型原因：库结构/identity
         * 校验失败、库文件损坏，且没有安全的自动修复手段）。
         *
         * 注意：
         * - 会清空本机全部课表、成绩、考试、教务账号与持久化 Cookie，用户需要重新绑定教务；
         * - 各 Manager 单例里还持有旧缓存，所以调用方重置后必须结束进程重启，
         *   不能只重建数据库实例（见 MainActivity 的恢复流程）。
         */
        @Synchronized
        fun resetForRecovery(context: Context) {
            try {
                INSTANCE?.close()
            } catch (t: Throwable) {
                android.util.Log.w("AppDatabase", "关闭数据库实例失败，继续删除库文件", t)
            }
            INSTANCE = null
            val deleted = context.applicationContext.deleteDatabase(DB_NAME)
            android.util.Log.w("AppDatabase", "应急重置本地数据库：deleteDatabase=$deleted")
        }
    }
}
