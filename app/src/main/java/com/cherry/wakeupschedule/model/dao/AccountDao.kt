package com.cherry.wakeupschedule.model.dao

import androidx.room.*
import com.cherry.wakeupschedule.model.AccountEntity
import com.cherry.wakeupschedule.model.AccountProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    // ── Account ──

    @Query("SELECT * FROM accounts ORDER BY last_active DESC")
    fun getAllAccountsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY last_active DESC")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccountById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE username = :username")
    suspend fun getAccountByUsername(username: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Query("UPDATE accounts SET last_active = :timestamp WHERE id = :id")
    suspend fun updateLastActive(id: Long, timestamp: Long)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int

    // ── Profile ──

    @Query("SELECT * FROM account_profiles WHERE account_id = :accountId")
    suspend fun getProfile(accountId: Long): AccountProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: AccountProfileEntity)

    @Query("DELETE FROM account_profiles WHERE account_id = :accountId")
    suspend fun deleteProfile(accountId: Long)
}
