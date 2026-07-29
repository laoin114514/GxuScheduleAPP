package com.cherry.wakeupschedule.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cherry.wakeupschedule.model.dao.AccountDao
import com.cherry.wakeupschedule.model.dao.AccountSettingsDao
import com.cherry.wakeupschedule.model.dao.SemesterInfoDao

@Database(
    entities = [
        Course::class,
        AccountEntity::class,
        AccountProfileEntity::class,
        SemesterInfoEntity::class,
        AccountSettingsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun accountDao(): AccountDao
    abstract fun semesterInfoDao(): SemesterInfoDao
    abstract fun accountSettingsDao(): AccountSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schedule.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
