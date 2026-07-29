package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.AccountEntity
import com.cherry.wakeupschedule.model.AppDatabase
import com.google.gson.Gson
import com.gxu.jwxt.model.StudentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 教务系统账号凭证 + 个人信息本地管理器（Room 存储 + 内存缓存）。
 */
object JwxtAccountManager {

    private val gson = Gson()
    private lateinit var dao: com.cherry.wakeupschedule.model.AccountDao

    @Volatile
    private var cached: AccountEntity? = null

    fun init(context: Context) {
        dao = AppDatabase.getInstance(context).accountDao()
        // 从 Room 加载缓存
        runBlocking(Dispatchers.IO) {
            cached = dao.getAccount() ?: AccountEntity()
        }
    }

    // ── 同步读（内存缓存，线程安全） ──

    fun isBound(): Boolean = cached?.isBound == true
    fun getUsername(): String = cached?.username ?: ""
    fun getPassword(): String = cached?.password ?: ""

    fun getProfile(): StudentProfile? {
        val json = cached?.profileJson ?: return null
        if (cached?.isBound != true) return null
        return try {
            gson.fromJson(json, StudentProfile::class.java)
        } catch (_: Exception) { null }
    }

    // ── 写操作 ──

    fun saveCredentials(username: String, password: String) {
        val entity = (cached ?: AccountEntity()).copy(
            username = username,
            password = password,
            isBound = true
        )
        cached = entity
        runBlocking(Dispatchers.IO) { dao.saveAccount(entity) }
    }

    fun saveProfile(profile: StudentProfile) {
        val json = gson.toJson(profile)
        val entity = (cached ?: AccountEntity()).copy(profileJson = json)
        cached = entity
        runBlocking(Dispatchers.IO) { dao.saveAccount(entity) }
    }

    fun clear() {
        cached = (cached ?: AccountEntity()).copy(
            username = "",
            password = "",
            isBound = false,
            profileJson = null
        )
        runBlocking(Dispatchers.IO) { dao.clearAccount() }
    }
}
