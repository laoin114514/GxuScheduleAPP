package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教务会话健康档案（本地持久化，只含会话元数据，不含密码/会话值本身）。
 *
 * <p>为什么需要它：正方教务的会话 TTL 是"滑动过期 + 按后端节点分档"的，
 * 而现有的 [RoomCookieJar] 只保存了 cookie 值，没有任何"上次确认有效是什么时候"的信息，
 * 客户端因此只能"用到才发现过期"。这里记录：</p>
 * <ul>
 *   <li>[JwxtSessionHealth.route]：当前会话所在后端节点的 `route` 常量；</li>
 *   <li>[JwxtSessionHealth.lastOkAt]：最近一次确认会话有效的本地时刻（预热/换节点的依据）；</li>
 *   <li>[JwxtSessionHealth.lastLoginAt]：最近一次完整登录时刻（快速失效判定）；</li>
 *   <li>[JwxtSessionHealth.lastFailKind]/[JwxtSessionHealth.lastFailAt]：最近失败类型与时刻（冷却用）；</li>
 *   <li>[JwxtSessionHealth.badRoutes]：运行时学习到的异常短 TTL 节点，登录时应避开。</li>
 * </ul>
 *
 * <p>存储用 SharedPreferences + JSON（不引入 Room 迁移）；解绑账号时随 cookie 一起清空。</p>
 */
object JwxtSessionHealthStore {

    private const val TAG = "JwxtHealth"
    private const val PREFS_NAME = "jwxt_session_health"
    private const val KEY_STATE = "state_v1"

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Application.onCreate 的后台初始化阶段调用（与 [RoomCookieJar.init] 同批）。 */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 读取当前档案；未初始化/损坏时返回空档案，不抛异常。 */
    fun load(): JwxtSessionHealth = synchronized(lock) { decode(readRawLocked()) }

    /** 确认会话有效（业务请求成功 / 探测 ALIVE）：刷新 lastOkAt 并清除失败标记。 */
    fun markOk(route: String?) = synchronized(lock) {
        updateLocked { current ->
            current.copy(
                route = route ?: current.route,
                lastOkAt = System.currentTimeMillis(),
                lastFailKind = JwxtAuthFailureKind.NONE,
                lastFailAt = 0L
            )
        }
    }

    /** 完整登录成功：记录 route、登录时刻，并视为刚确认有效。 */
    fun markLogin(route: String?) = synchronized(lock) {
        val now = System.currentTimeMillis()
        updateLocked { current ->
            current.copy(
                route = route ?: current.route,
                lastLoginAt = now,
                lastOkAt = now,
                lastFailKind = JwxtAuthFailureKind.NONE,
                lastFailAt = 0L
            )
        }
    }

    /** 记录一次失败（用于失败冷却与诊断）。 */
    fun markFailure(kind: JwxtAuthFailureKind) = synchronized(lock) {
        val now = System.currentTimeMillis()
        updateLocked { it.copy(lastFailKind = kind, lastFailAt = now) }
    }

    /** 记录一个运行时发现的异常短 TTL 节点，后续登录避开。 */
    fun markRouteBad(route: String?) {
        if (route.isNullOrBlank()) return
        synchronized(lock) {
            updateLocked { it.copy(badRoutes = it.badRoutes + route) }
        }
        Log.w(TAG, "记录异常节点 route=${route.take(8)}…，后续登录将避开")
    }

    /** 该 route 是否是异常节点（内置已知异常 + 运行时学习）。 */
    fun isBadRoute(route: String?): Boolean {
        if (route == null) return false
        return JwxtAuthPolicy.isKnownBadRoute(route) || route in load().badRoutes
    }

    /** 解绑账号/换账号时清空。 */
    fun clear() = synchronized(lock) {
        prefs?.edit()?.remove(KEY_STATE)?.apply()
    }

    // ── 内部 ─────────────────────────────────────

    private fun readRawLocked(): String? = prefs?.getString(KEY_STATE, null)

    private inline fun updateLocked(transform: (JwxtSessionHealth) -> JwxtSessionHealth) {
        val p = prefs ?: return
        val next = transform(decode(readRawLocked()))
        p.edit().putString(KEY_STATE, encode(next)).apply()
    }

    private fun encode(health: JwxtSessionHealth): String = JSONObject().apply {
        put("version", 1)
        put("route", health.route ?: JSONObject.NULL)
        put("lastOkAt", health.lastOkAt)
        put("lastLoginAt", health.lastLoginAt)
        put("lastFailKind", health.lastFailKind.name)
        put("lastFailAt", health.lastFailAt)
        put("badRoutes", JSONArray(health.badRoutes.toList()))
    }.toString()

    private fun decode(raw: String?): JwxtSessionHealth {
        if (raw.isNullOrBlank()) return JwxtSessionHealth()
        return try {
            val obj = JSONObject(raw)
            JwxtSessionHealth(
                route = if (obj.isNull("route")) null else obj.optString("route").takeIf { it.isNotBlank() },
                lastOkAt = obj.optLong("lastOkAt", 0L),
                lastLoginAt = obj.optLong("lastLoginAt", 0L),
                lastFailKind = runCatching {
                    JwxtAuthFailureKind.valueOf(obj.optString("lastFailKind", JwxtAuthFailureKind.NONE.name))
                }.getOrDefault(JwxtAuthFailureKind.NONE),
                lastFailAt = obj.optLong("lastFailAt", 0L),
                badRoutes = obj.optJSONArray("badRoutes").toStringSet()
            )
        } catch (e: Exception) {
            Log.w(TAG, "会话健康档案解析失败，按空档案处理", e)
            JwxtSessionHealth()
        }
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val result = LinkedHashSet<String>(length())
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }
}

/**
 * 会话健康档案的不可变快照。
 *
 * @param route 当前会话所在后端节点的 route 常量（对应 nginx 粘性路由）
 * @param lastOkAt 最近一次确认会话有效的本地时刻（毫秒；0 = 从未确认）
 * @param lastLoginAt 最近一次完整登录成功时刻（毫秒）
 * @param lastFailKind 最近一次失败类型
 * @param lastFailAt 最近一次失败时刻（毫秒；0 = 无）
 * @param badRoutes 运行时学习到的异常短 TTL 节点
 */
data class JwxtSessionHealth(
    val route: String? = null,
    val lastOkAt: Long = 0L,
    val lastLoginAt: Long = 0L,
    val lastFailKind: JwxtAuthFailureKind = JwxtAuthFailureKind.NONE,
    val lastFailAt: Long = 0L,
    val badRoutes: Set<String> = emptySet()
)
