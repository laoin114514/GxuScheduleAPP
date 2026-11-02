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

    /**
     * 冷启动兜底：后台初始化（[AppInitializer]）还没完成时先等它就绪，
     * 否则会读到 null 缓存（未绑定/空账号）。初始化线程自身调用立即返回；就绪后只是一次 volatile 读。
     * 注意只加在读路径：写操作都发生在 UI 就绪后的用户操作里（MainActivity 已在 inflate 前 awaitReady）。
     */
    private fun awaitInit() {
        AppInitializer.awaitReady()
    }

    fun isBound(): Boolean {
        awaitInit()
        return cached?.isBound == true
    }

    fun getUsername(): String {
        awaitInit()
        return cached?.username ?: ""
    }

    fun getPassword(): String {
        awaitInit()
        return cached?.password ?: ""
    }

    fun getProfile(): StudentProfile? {
        awaitInit()
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

    fun updatePassword(password: String) {
        val account = checkNotNull(cached) { "教务账号未初始化" }
        check(account.isBound) { "教务账号未绑定" }
        cached = account.copy(password = password)
        runBlocking(Dispatchers.IO) { dao.updatePassword(password) }
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
