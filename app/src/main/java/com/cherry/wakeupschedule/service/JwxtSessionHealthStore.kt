package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
 *   <li>[JwxtSessionHealth.badRoutes]：运行时学习到的异常短 TTL 节点（route → 首次标记时刻），
 *       登录时应避开；超过 [JwxtAuthPolicy.BAD_ROUTE_TTL_MS] 自动失效，避免误标永久化；</li>
 *   <li>[JwxtSessionHealth.routeTtlUpperMs]：各节点已观测到的 TTL 上界（route → 毫秒），
 *       用于按节点收紧前台预热阈值；被"实际存活超过该上界"推翻时会自动清除。</li>
 * </ul>
 *
 * <p>存储用 SharedPreferences + JSON（不引入 Room 迁移）；解绑账号时随 cookie 一起清空。</p>
 *
 * <p>数据格式兼容：早期版本的 `badRoutes` 是纯字符串数组（无时间戳，无法判断是否该衰减），
 * 解码时直接丢弃（内置已知异常节点不受影响）；当前版本写入对象形式 `{route: 标记时刻}`。</p>
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
        val now = System.currentTimeMillis()
        updateLocked { current ->
            val confirmedRoute = route ?: current.route
            // 本次确认有效时的"静默时长"：若已超过我们为它学到的 TTL 上界，说明估计错了，清除。
            val idleGap = if (current.lastOkAt > 0L) now - current.lastOkAt else 0L
            val ttlEstimates = if (confirmedRoute != null && idleGap > 0L) {
                val upper = current.routeTtlUpperMs[confirmedRoute]
                if (upper != null && idleGap >= upper) {
                    Log.d(
                        TAG,
                        "节点 ${confirmedRoute.take(8)}… 的 TTL 上界估计（${upper / 60_000}min）" +
                            "被实际存活 ${idleGap / 60_000}min 推翻，清除该估计"
                    )
                    current.routeTtlUpperMs - confirmedRoute
                } else {
                    current.routeTtlUpperMs
                }
            } else {
                current.routeTtlUpperMs
            }
            current.copy(
                route = confirmedRoute,
                lastOkAt = now,
                lastFailKind = JwxtAuthFailureKind.NONE,
                lastFailAt = 0L,
                routeTtlUpperMs = ttlEstimates
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

    /**
     * 记录一个节点"很快失效"：route → 首次标记时刻（保留 [JwxtAuthPolicy.BAD_ROUTE_TTL_MS]）。
     * 重复标记不会刷新时间，避免"一直落在坏节点"把避让时间无限延长。
     */
    fun markRouteBad(route: String?) {
        if (route.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            updateLocked { current ->
                val alive = current.badRoutes.filterValues { JwxtAuthPolicy.isLearnedBadRoute(it, now) }
                current.copy(badRoutes = alive + (route to (alive[route] ?: now)))
            }
        }
        Log.w(TAG, "记录异常节点 route=${route.take(8)}…，后续登录将避开（7 天后自动失效）")
    }

    /**
     * 记录某节点的 TTL 上界（只保留更小的估计：更小的上界意味着更早预热）。
     * 仅用于把预热阈值调早，不影响正常节点（默认阈值已经是保守的 25 分钟）。
     */
    fun markRouteTtlUpper(route: String?, ttlUpperMs: Long) {
        if (route.isNullOrBlank() || ttlUpperMs <= 0L) return
        synchronized(lock) {
            updateLocked { current ->
                val prev = current.routeTtlUpperMs[route]
                if (prev != null && prev <= ttlUpperMs) {
                    current
                } else {
                    current.copy(routeTtlUpperMs = current.routeTtlUpperMs + (route to ttlUpperMs))
                }
            }
        }
    }

    /** 该 route 当前是否应避开（内置已知异常节点 + 未过期的运行时学习结果）。 */
    fun isBadRoute(route: String?): Boolean {
        if (route == null) return false
        if (JwxtAuthPolicy.isKnownBadRoute(route)) return true
        val since = load().badRoutes[route] ?: return false
        return JwxtAuthPolicy.isLearnedBadRoute(since, System.currentTimeMillis())
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
        put("version", 2)
        put("route", health.route ?: JSONObject.NULL)
        put("lastOkAt", health.lastOkAt)
        put("lastLoginAt", health.lastLoginAt)
        put("lastFailKind", health.lastFailKind.name)
        put("lastFailAt", health.lastFailAt)
        put("badRoutes", JSONObject(health.badRoutes))
        put("routeTtlUpperMs", JSONObject(health.routeTtlUpperMs))
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
                badRoutes = obj.opt("badRoutes").toBadRouteMap(),
                routeTtlUpperMs = obj.optJSONObject("routeTtlUpperMs").toLongMap()
            )
        } catch (e: Exception) {
            Log.w(TAG, "会话健康档案解析失败，按空档案处理", e)
            JwxtSessionHealth()
        }
    }

    /**
     * 旧版 `badRoutes` 是 `string[]`（无时间戳，无法判断是否该衰减），直接丢弃；
     * 内置已知异常节点（[JwxtAuthPolicy.KNOWN_BAD_ROUTES]）不受影响。新版为 `{route: 标记时刻}`。
     */
    private fun Any?.toBadRouteMap(): Map<String, Long> = when (this) {
        is JSONObject -> toLongMap()
        else -> emptyMap()
    }

    private fun JSONObject?.toLongMap(): Map<String, Long> {
        if (this == null) return emptyMap()
        val result = LinkedHashMap<String, Long>(length())
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = optLong(key, 0L)
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
 * @param badRoutes 运行时学习到的异常短 TTL 节点（route → 首次标记时刻；超 7 天自动失效）
 * @param routeTtlUpperMs 各节点已观测到的 TTL 上界（route → 毫秒；用于收紧预热阈值）
 */
data class JwxtSessionHealth(
    val route: String? = null,
    val lastOkAt: Long = 0L,
    val lastLoginAt: Long = 0L,
    val lastFailKind: JwxtAuthFailureKind = JwxtAuthFailureKind.NONE,
    val lastFailAt: Long = 0L,
    val badRoutes: Map<String, Long> = emptyMap(),
    val routeTtlUpperMs: Map<String, Long> = emptyMap()
)
