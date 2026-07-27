package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.gxu.jwxt.model.StudentProfile

/**
 * 教务系统账号凭证 + 个人信息本地管理器。
 */
object JwxtAccountManager {

    private const val PREFS_NAME = "jwxt_account"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_IS_BOUND = "is_bound"
    private const val KEY_PROFILE_JSON = "profile_json"
    private const val KEY_PROFILE_USERNAME = "profile_username"

    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isBound(): Boolean = prefs.getBoolean(KEY_IS_BOUND, false)
    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun saveCredentials(username: String, password: String) {
        // 账号变了就清掉旧缓存
        if (username != getUsername()) {
            clearProfileCache()
        }
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putBoolean(KEY_IS_BOUND, true)
            .apply()
    }

    fun clear() {
        clearProfileCache()
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .putBoolean(KEY_IS_BOUND, false)
            .apply()
    }

    // ── 个人信息缓存 ──

    fun getCachedProfile(): StudentProfile? {
        val json = prefs.getString(KEY_PROFILE_JSON, null) ?: return null
        val cachedUser = prefs.getString(KEY_PROFILE_USERNAME, "")
        if (cachedUser != getUsername()) return null // 账号变了，缓存失效
        return try {
            gson.fromJson(json, StudentProfile::class.java)
        } catch (_: Exception) { null }
    }

    fun saveProfileCache(profile: StudentProfile) {
        prefs.edit()
            .putString(KEY_PROFILE_JSON, gson.toJson(profile))
            .putString(KEY_PROFILE_USERNAME, getUsername())
            .apply()
    }

    private fun clearProfileCache() {
        prefs.edit()
            .remove(KEY_PROFILE_JSON)
            .remove(KEY_PROFILE_USERNAME)
            .apply()
    }
}
