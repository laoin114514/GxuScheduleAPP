package com.cherry.wakeupschedule.service

import android.content.Context
import com.gxu.jwxt.model.StudentProfile

/**
 * 教务账号凭据管理（委托 AccountRepository）。
 * 保留原有接口兼容，内部改为多账号感知。
 */
object JwxtAccountManager {

    private lateinit var appContext: Context
    private val repo: AccountRepository by lazy { AccountRepository.getInstance(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isBound(): Boolean = repo.hasActiveAccount()

    suspend fun getUsername(): String {
        return repo.getActiveAccount()?.username ?: ""
    }

    suspend fun getPassword(): String {
        return repo.getActiveAccount()?.password ?: ""
    }

    suspend fun getActiveAccountId(): Long = repo.getActiveAccountId()

    suspend fun saveCredentials(username: String, password: String) {
        repo.bindAccount(username, password)
    }

    fun clear() {
        // 多账号下不应全局清理，改为 no-op
        // 使用 AccountRepository.unbindAccount() 替代
    }

    fun getCachedProfile(): StudentProfile? {
        val accountId = repo.getActiveAccountId()
        if (accountId <= 0) return null
        return kotlinx.coroutines.runBlocking {
            repo.getProfile(accountId)
        }
    }

    suspend fun saveProfileCache(profile: StudentProfile) {
        val accountId = repo.getActiveAccountId()
        if (accountId > 0) {
            repo.saveProfile(accountId, profile)
        }
    }
}
