package com.cherry.wakeupschedule.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AccountDao {

    @Query("SELECT * FROM account WHERE id = 1")
    suspend fun getAccount(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAccount(account: AccountEntity)

    @Query("UPDATE account SET profile_json = :profileJson WHERE id = 1")
    suspend fun updateProfile(profileJson: String)

    @Query("UPDATE account SET username = '', password = '', is_bound = 0, profile_json = NULL WHERE id = 1")
    suspend fun clearAccount()
}
