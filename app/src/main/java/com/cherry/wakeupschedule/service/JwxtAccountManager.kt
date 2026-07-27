package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences

/**
 * 教务系统账号凭证本地管理器。
 * 将用户名、密码存储在 SharedPreferences 中。
 */
object JwxtAccountManager {

    private const val PREFS_NAME = "jwxt_account"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_IS_BOUND = "is_bound"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isBound(): Boolean = prefs.getBoolean(KEY_IS_BOUND, false)

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putBoolean(KEY_IS_BOUND, true)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .putBoolean(KEY_IS_BOUND, false)
            .apply()
    }
}
