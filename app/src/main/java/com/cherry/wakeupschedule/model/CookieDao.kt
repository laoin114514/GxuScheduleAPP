package com.cherry.wakeupschedule.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CookieDao {

    @Query("SELECT * FROM cookies")
    suspend fun getAll(): List<CookieEntity>

    /** 批量写入，同名同域同路径自动覆盖旧值 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(cookies: List<CookieEntity>)

    @Query("DELETE FROM cookies WHERE expires_at > 0 AND expires_at < :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM cookies")
    suspend fun deleteAll()
}
