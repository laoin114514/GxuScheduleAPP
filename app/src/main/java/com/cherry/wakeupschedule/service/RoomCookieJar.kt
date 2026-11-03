package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.model.CookieEntity
import com.cherry.wakeupschedule.model.CookieDao
import com.gxu.jwxt.ClearableCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 持久化 CookieJar：把教务系统会话 cookie 存进 Room，
 * App 重启后复用，避免每次冷启动都重新登录。
 *
 * 会话 cookie（无过期时间）以 expires_at = 0 保存，视为长期有效，
 * 直到解绑账号时 [clear] 清空；服务器 session 过期后重新登录产生的
 * 新 cookie 会按 (name, domain, path) 覆盖旧值。
 *
 * 实现 [ClearableCookieJar] 供 Java 客户端在登录触发教务验证码
 * 保护时抹除会话标识（清 Cookie + 换 UA）后重试。
 */
object RoomCookieJar : ClearableCookieJar {

    private lateinit var dao: CookieDao

    fun init(context: Context) {
        dao = AppDatabase.getInstance(context.applicationContext).cookieDao()
    }

    // ── CookieJar ──

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        runBlocking(Dispatchers.IO) {
            dao.deleteExpired(System.currentTimeMillis())
            dao.saveAll(cookies.map { it.toEntity() })
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return runBlocking(Dispatchers.IO) {
            dao.getAll()
                .filter { it.expiresAt == 0L || it.expiresAt >= now } // 会话 cookie 或未过期
                .mapNotNull { it.toOkHttpCookie() }
                .filter { it.matches(url) }
        }
    }

    /** 清空全部 cookie（解绑账号或登录触发验证码保护时） */
    override fun clear() {
        runBlocking(Dispatchers.IO) { dao.deleteAll() }
    }

    /**
     * 读取指定名称 cookie 的当前值（只读，供会话诊断使用）。
     *
     * 主要用于取 `route`：它标识当前会话被粘在哪个后端节点，是判断
     * "是否需要换节点、上次落在哪个节点"的唯一依据（不参与 cookie 回放逻辑）。
     * 初始化未完成或没有该 cookie 时返回 null。
     */
    fun cookieValue(name: String): String? {
        if (!this::dao.isInitialized) return null
        val now = System.currentTimeMillis()
        return runBlocking(Dispatchers.IO) {
            dao.getAll()
                .firstOrNull { it.name == name && (it.expiresAt == 0L || it.expiresAt >= now) }
                ?.value
        }
    }

    // ── 转换 ──

    private fun Cookie.toEntity() = CookieEntity(
        name = name,
        value = value,
        domain = domain,
        path = path,
        // 持久 cookie 记真实过期时间；会话 cookie 记 0（长期有效，解绑时清空）
        expiresAt = if (persistent) expiresAt else 0L,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly
    )

    private fun CookieEntity.toOkHttpCookie(): Cookie? = try {
        Cookie.Builder()
            .name(name)
            .value(value)
            // 会话 cookie 还原为 okhttp 的“不过期”语义（Long.MAX_VALUE）
            .expiresAt(if (expiresAt > 0L) expiresAt else Long.MAX_VALUE)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            }
            .path(path)
            .apply {
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    } catch (_: Exception) {
        // 脏数据（非法域/路径）跳过，不阻塞请求
        null
    }
}
