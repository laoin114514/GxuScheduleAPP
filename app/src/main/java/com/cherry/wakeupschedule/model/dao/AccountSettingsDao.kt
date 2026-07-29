package com.cherry.wakeupschedule.model.dao

import androidx.room.*
import com.cherry.wakeupschedule.model.AccountSettingsEntity

@Dao
interface AccountSettingsDao {

    @Query("SELECT * FROM account_settings WHERE account_id = :accountId")
    suspend fun getSettings(accountId: Long): AccountSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: AccountSettingsEntity)

    @Query("DELETE FROM account_settings WHERE account_id = :accountId")
    suspend fun deleteSettings(accountId: Long)
}
