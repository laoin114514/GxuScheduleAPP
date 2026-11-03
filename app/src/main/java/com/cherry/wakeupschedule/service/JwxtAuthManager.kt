package com.cherry.wakeupschedule.service

import android.util.Log
import com.cherry.wakeupschedule.App
import com.gxu.jwxt.JwxtClient
import com.gxu.jwxt.SessionProbeResult
import com.gxu.jwxt.exceptions.CaptchaRequiredException
import com.gxu.jwxt.exceptions.KickedOutException
import com.gxu.jwxt.exceptions.LoginException
import com.gxu.jwxt.exceptions.SessionExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 登录流程的可上报阶段，用于绑定页展示分步进度。
 */
enum class JwxtLoginStep { LOGIN, PROFILE, SEMESTER, SCHEDULE }

/**
 * 登录态事件（由 [BaseActivity] 收集后用 AppToast 提示）。
 *
 * <p>只上报**用户操作触发**的自动恢复（`doWithAuth` 路径）；前台预热的静默重登录不上报，
 * 否则每次冷启动都弹提示会很吵。失败提示仍由各调用方按 `Result.failure` 展示。</p>
 */
enum class JwxtAuthNotice {
    /** 检测到教务会话过期，开始自动重登录。 */
    RECOVERING_EXPIRED,

    /** 检测到账号在其它设备登录（本会话被踢），开始自动重登录。 */
    RECOVERING_KICKED,

    /** 自动重登录完成（普通过期）。 */
    RECOVERED,

    /** 自动重登录完成（被踢恢复）。 */
    RECOVERED_KICKED,
}

/**
 * 教务系统认证管理器。
 *
 * <p>所有需要认证的接口调用都通过 [doWithAuth]，自动处理 session 过期重登录。</p>
 *
 * <h3>相对于旧实现的改动（依据 2026-11-03 真实账号实测，见 docs/登录状态失效规律实测.md）</h3>
 * <ol>
 *   <li><b>所有教务网络/认证操作串行化</b>（[authMutex]）：会话身份是单个
 *       JSESSIONID + route，并发登录会互相踢（同节点双登录实测互踢），并发探测会让
 *       loggedIn 状态错乱；旧实现 `client` 懒创建无锁、`relogin()` 也不同步。</li>
 *   <li><b>完整重登录前先抹掉浏览器身份</b>（`clearBrowserIdentity()`，连 route 一起清）：
 *       nginx 按 route 粘性路由，不清 route 直接重登录会被路由回原节点——App 一旦落在
 *       `210.36.22.60`（空闲 TTL ≈80–90 秒的异常节点）就永远逃不出去；顺带也让每次重登录
 *       重新随机选节点，打断"多设备同节点互踢 → 重登反踢 → ping-pong"的链条。</li>
 *   <li><b>三态探测 + 前台预热</b>：惰性探测从 `resumeSession()`（布尔）升级为
 *       `probeSession()`（有效/过期/网络异常三态）；App 回到前台时若距上次确认有效超过
 *       [JwxtAuthPolicy.PREWARM_AGE_MS]，就在后台先探测/静默重新登录，用户操作时不再
 *       现场等一次完整重登录（实测 Java 客户端一次重登录 ≈8 秒）。</li>
 *   <li><b>节点健康学习</b>：登录/确认有效后很短时间内就失效（[JwxtAuthPolicy.isNodeSuspicious]）
 *       的 route 记为异常节点，后续登录避开；登录仍落在异常节点则重掷最多
 *       [JwxtAuthPolicy.MAX_NODE_REROLL] 次。</li>
 *   <li><b>重试兜底</b>：旧实现 relogin 后第二次 action 再抛异常会逃出协程直接崩溃；
 *       现在统一兜底为 [Result.failure]，并按被踢/过期/网络/凭据分类给出可读文案。</li>
 *   <li><b>改密修复</b>：[updatePassword] 先清会话身份再登录。实测：会话仍有效时
 *       直接 login() 会被服务端当作已登录（登录页 302 回 initMenu），任意新密码都"验证通过"。</li>
 * </ol>
 */
object JwxtAuthManager {

    private const val TAG = "JwxtAuth"

    /** 会话所在节点标识（nginx 粘性路由）。 */
    private const val COOKIE_ROUTE = "route"

    /**
     * 串行化所有教务网络/认证操作。
     *
     * <p>并发场景（两个页面同时首次请求、前台预热与用户刷新同时发生）下，若各自登录会
     * 在同节点互踢；串行化后等待方直接复用同一个会话/同一次登录结果。业务请求也走这把锁，
     * 因为 [JwxtSession] 的 loggedIn/csrfToken/限流时间戳不是为多线程并发设计的。</p>
     */
    private val authMutex = Mutex()

    /** 后台预热/静默恢复用的应用级作用域：不随页面销毁取消（进程级）。 */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 登录态事件流（用户可感知的恢复过程），由 BaseActivity 收集展示。 */
    private val _notices = MutableSharedFlow<JwxtAuthNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<JwxtAuthNotice> = _notices.asSharedFlow()

    @Volatile
    private var client: JwxtClient? = null

    // ════════════════════════════════════════════════════════════
    //  绑定 / 改密（用户主动、可见操作）
    // ════════════════════════════════════════════════════════════

    /**
     * 登录并获取个人信息（原子操作）。
     * 步骤 1：验证凭据 → 步骤 2：获取个人信息 → 步骤 3：初始化学期 → 步骤 4：导入课表
     * 任一前序步骤失败则整体失败，成功则持久化到数据库。
     *
     * @param onStep 每完成一个阶段回调一次（在 IO 线程触发，调用方自行切回主线程）
     */
    suspend fun login(
        username: String,
        password: String,
        onStep: (JwxtLoginStep) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        // 步骤 1：验证凭据。持锁执行，避免与前台预热/其它页面并发登录（同节点会互踢）。
        // 先清掉旧会话身份：登录页带着旧 cookie 时，服务端会把已登录会话 302 回 initMenu
        // （不校验密码）；旧 route 还会把这次登录钉在原来的后端节点上（实测确认）。
        val c = try {
            authMutex.withLock {
                val fresh = JwxtClient(username, password, RoomCookieJar)
                fresh.clearBrowserIdentity()
                fresh.login()
                client = fresh
                JwxtSessionHealthStore.markLogin(RoomCookieJar.cookieValue(COOKIE_ROUTE))
                fresh
            }
        } catch (e: LoginException) {
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            return@withContext Result.failure(LoginException("登录失败: ${e.message}"))
        }
        onStep(JwxtLoginStep.LOGIN)

        // 步骤 2~4 不持锁：其中课表导入会再走 doWithAuth，Mutex 不可重入，必须让出。
        try {
            // 步骤 2：获取个人信息
            val profile = c.profile().profile()
            onStep(JwxtLoginStep.PROFILE)
            // 两步都成功 → 持久化
            JwxtAccountManager.saveCredentials(username, password)
            JwxtAccountManager.saveProfile(profile)
            // 步骤 3：根据入学年份初始化学期 + 推断当前学期
            kotlinx.coroutines.runBlocking {
                SemesterManager.initialize(profile)
                val idx = SemesterManager.inferCurrentSemesterIndex(profile.grade ?: "")
                SemesterManager.setCurrentIndex(idx)
            }
            // 通知 CourseDataManager 切换到新学期的课程
            CourseDataManager.getInstance(App.instance)
                .switchSemester(SemesterManager.getCurrent()?.id ?: 0L)
            onStep(JwxtLoginStep.SEMESTER)
            // 步骤 4：获取当前学期的课表；导入失败不影响绑定成功，仅不上报 SCHEDULE
            val curSem = SemesterManager.getCurrent()
            val scheduleImported = curSem != null &&
                JwxtImportService.fetchAndSaveScheduleForSemester(App.instance, curSem).isSuccess
            if (scheduleImported) {
                onStep(JwxtLoginStep.SCHEDULE)
            }
            Result.success("登录成功")
        } catch (e: Exception) {
            // profile 获取失败 → 等同登录失败
            destroyClient()
            Result.failure(LoginException("获取个人信息失败: ${e.message}"))
        }
    }

    /**
     * 校验已绑定账号的新密码，并仅更新本地凭据。
     * 不读取个人信息、不初始化学期，也不会变更已有课表数据。
     *
     * <p>必须先 [JwxtClient.clearBrowserIdentity] 再登录：实测发现会话仍有效时直接调用
     * `login()` 不会真正校验密码（GET 登录页被 302 到 initMenu，POST 登录被服务端当作
     * 已登录直接重定向回 initMenu），任意新密码都会被当作"验证通过"，从而把错误密码
     * 写进本地，等会话过期后永久提示"登录已过期"。</p>
     */
    suspend fun updatePassword(password: String): Result<String> = withContext(Dispatchers.IO) {
        val username = JwxtAccountManager.getUsername()
        if (!JwxtAccountManager.isBound() || username.isBlank()) {
            return@withContext Result.failure(LoginException("请先绑定教务账号"))
        }
        try {
            authMutex.withLock {
                val fresh = JwxtClient(username, password, RoomCookieJar)
                fresh.clearBrowserIdentity()
                fresh.login()
                client = fresh
                JwxtSessionHealthStore.markLogin(RoomCookieJar.cookieValue(COOKIE_ROUTE))
                JwxtAccountManager.updatePassword(password)
            }
            Result.success("密码已更新")
        } catch (e: CaptchaRequiredException) {
            destroyClient()
            JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.CAPTCHA)
            Result.failure(e)
        } catch (e: LoginException) {
            // 验证过程中已清空 cookie（含 route），旧会话不可再复用：销毁客户端，下次请求重新探测/登录
            destroyClient()
            JwxtSessionHealthStore.markFailure(
                if (isNetworkFailure(e)) JwxtAuthFailureKind.NETWORK else JwxtAuthFailureKind.CREDENTIALS
            )
            Result.failure(e)
        } catch (e: Exception) {
            destroyClient()
            Result.failure(LoginException("登录失败: ${e.message}"))
        }
    }

    // ════════════════════════════════════════════════════════════
    //  业务调用入口
    // ════════════════════════════════════════════════════════════

    /**
     * 执行需要认证的操作，自动处理会话过期/被踢。
     *
     * <p>与旧实现的区别：</p>
     * <ul>
     *   <li>整个"取客户端 → 执行业务 → 恢复 → 重试"在 [authMutex] 内串行，避免并发双登录互踢；</li>
     *   <li>重试分支有兜底 catch：重登录后第二次 action 再抛任何异常都返回 [Result.failure]，
     *       不会逃出调用方的 `lifecycleScope.launch` 造成崩溃（旧实现在此处会崩）；</li>
     *   <li>会话失效后先清身份再重登录（随机换节点），可逃离异常短 TTL 节点、打断多设备互踢链。</li>
     * </ul>
     *
     * @param action 业务逻辑，接收已登录的 [JwxtClient]
     * @return [Result] 包装的结果
     */
    suspend fun <T> doWithAuth(action: (JwxtClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            val value = authMutex.withLock {
                val c = ensureClientLocked()
                try {
                    action(c)
                } catch (expired: SessionExpiredException) {
                    // 会话过期（或同节点被踢）→ 清身份完整重登录（随机换节点）→ 重试一次
                    val fresh = recoverSessionLocked(c, expired)
                    action(fresh)
                }
            }
            JwxtSessionHealthStore.markOk(RoomCookieJar.cookieValue(COOKIE_ROUTE))
            Result.success(value)
        } catch (e: Exception) {
            // 兜底：重登录失败 / 重试后仍失效 / 业务异常都不允许逃逸（旧实现会崩）
            if (e is LoginException || e is SessionExpiredException) {
                destroyClient()
                recordFailure(e)
            }
            Result.failure(toUserFacingError(e))
        }
    }

    // ════════════════════════════════════════════════════════════
    //  前台预热
    // ════════════════════════════════════════════════════════════

    /**
     * App 回到前台时调用（MainActivity/ScheduleFragment onResume）。
     *
     * <p>距上次确认会话有效超过 [JwxtAuthPolicy.PREWARM_AGE_MS] 时，在后台线程探测一次：
     * 有效则续期（滑动 TTL 被"touch"）；失效则静默完整重登录。这样用户点刷新时不用现场
     * 等约 8 秒的重登录，也不会在每次切 tab 时发无意义请求（阈值节流 + 进程内 client 复用）。</p>
     *
     * <p>非 suspend、不抛异常：调用方（主线程）无需关心结果；失败写入健康档案，
     * 凭据类失败进入 [JwxtAuthPolicy.FAIL_COOLDOWN_MS] 冷却，避免反复触发验证码保护。</p>
     */
    fun prewarmInBackground(reason: String) {
        backgroundScope.launch {
            try {
                prewarm(reason)
            } catch (t: Throwable) {
                Log.w(TAG, "后台预热失败（$reason）", t)
            }
        }
    }

    private suspend fun prewarm(reason: String) {
        if (!needsPrewarm(System.currentTimeMillis())) return
        authMutex.withLock {
            if (!needsPrewarm(System.currentTimeMillis())) return@withLock
            // 复用进程内客户端：它已经有 CookieJar 与登录状态，探测不会新建连接池
            val c = client ?: JwxtClient(
                JwxtAccountManager.getUsername(),
                JwxtAccountManager.getPassword(),
                RoomCookieJar
            ).also { client = it }

            val probe: SessionProbeResult = c.probeSession()
            when (probe) {
                SessionProbeResult.ALIVE -> {
                    JwxtSessionHealthStore.markOk(RoomCookieJar.cookieValue(COOKIE_ROUTE))
                    Log.d(TAG, "前台预热（$reason）：会话仍有效，已续期")
                }

                SessionProbeResult.NETWORK_ERROR -> {
                    JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.NETWORK)
                    Log.d(TAG, "前台预热（$reason）：网络不可用，保持现状")
                }

                SessionProbeResult.EXPIRED -> {
                    Log.d(TAG, "前台预热（$reason）：会话已失效，后台静默重新登录")
                    noteSessionExpiry()
                    try {
                        fullReloginLocked(c)
                        Log.d(TAG, "前台预热（$reason）：重新登录完成")
                    } catch (e: CaptchaRequiredException) {
                        destroyClient()
                        JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.CAPTCHA)
                        Log.w(TAG, "前台预热重登录触发验证码保护，进入冷却", e)
                    } catch (e: LoginException) {
                        destroyClient()
                        JwxtSessionHealthStore.markFailure(
                            if (isNetworkFailure(e)) JwxtAuthFailureKind.NETWORK else JwxtAuthFailureKind.CREDENTIALS
                        )
                        Log.w(TAG, "前台预热重登录失败", e)
                    }
                }
            }
        }
    }

    /** 是否需要预热：已绑定 + 距上次确认有效够久（按该节点学到的 TTL 上界收紧）+ 不在凭据失败冷却期。 */
    private fun needsPrewarm(now: Long): Boolean {
        if (!JwxtAccountManager.isBound()) return false
        val health = JwxtSessionHealthStore.load()
        val ttlUpper = health.route?.let { health.routeTtlUpperMs[it] }
        if (!JwxtAuthPolicy.shouldPrewarm(now, health.lastOkAt, ttlUpper)) return false
        return JwxtAuthPolicy.shouldAutoReloginAfterFailure(health.lastFailKind, health.lastFailAt, now)
    }

    // ════════════════════════════════════════════════════════════
    //  内部：客户端生命周期 / 恢复 / 节点切换
    // ════════════════════════════════════════════════════════════

    /** 取进程内客户端；没有或已掉登录态时先探测（三态），必要时完整登录。调用方须持有 [authMutex]。 */
    private fun ensureClientLocked(): JwxtClient {
        val existing = client
        if (existing != null && existing.isLoggedIn) return existing

        val c = existing ?: JwxtClient(
            JwxtAccountManager.getUsername(),
            JwxtAccountManager.getPassword(),
            RoomCookieJar
        )
        val probe: SessionProbeResult = c.probeSession()
        when (probe) {
            SessionProbeResult.ALIVE -> {
                // 进程内缓存的客户端状态未知（如刚被销毁后重建）：探测有效直接复用
                JwxtSessionHealthStore.markOk(RoomCookieJar.cookieValue(COOKIE_ROUTE))
            }

            SessionProbeResult.EXPIRED -> {
                // 已经持有 cookie 却探测失效：快速失效记为异常节点，否则学习该节点 TTL 上界
                noteSessionExpiry()
                JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.EXPIRED)
                fullReloginLocked(c)
            }

            SessionProbeResult.NETWORK_ERROR -> {
                JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.NETWORK)
                throw LoginException("网络异常，无法连接教务系统，请稍后重试")
            }
        }
        client = c
        return c
    }

    /**
     * 会话失效后的恢复：清掉旧会话身份 → 完整重登录（随机换节点）→ 返回新客户端。
     * 调用方须持有 [authMutex]。
     */
    private fun recoverSessionLocked(stale: JwxtClient, cause: SessionExpiredException): JwxtClient {
        // 并发场景：别的协程可能已经恢复过会话（client 已更换），先快速探测复用，避免重复登录 8 秒
        val current = client
        if (current != null && current !== stale && current.isLoggedIn &&
            current.probeSession() == SessionProbeResult.ALIVE
        ) {
            JwxtSessionHealthStore.markOk(RoomCookieJar.cookieValue(COOKIE_ROUTE))
            Log.d(TAG, "会话已被其它操作恢复，直接复用")
            return current
        }

        val kicked = cause is KickedOutException
        if (!kicked) {
            noteSessionExpiry()
        }
        JwxtSessionHealthStore.markFailure(
            if (kicked) JwxtAuthFailureKind.KICKED else JwxtAuthFailureKind.EXPIRED
        )
        if (kicked) {
            Log.d(TAG, "会话被其它设备踢下线，清身份重新登录以打断互踢链")
        }

        val target = current ?: stale
        // 用户操作触发的恢复才提示（RECOVERING → 完成后 RECOVERED）；前台预热保持静默
        emitNotice(if (kicked) JwxtAuthNotice.RECOVERING_KICKED else JwxtAuthNotice.RECOVERING_EXPIRED)
        fullReloginLocked(target)
        emitNotice(if (kicked) JwxtAuthNotice.RECOVERED_KICKED else JwxtAuthNotice.RECOVERED)
        return target
    }

    /**
     * 完整重登录：先 [JwxtClient.clearBrowserIdentity] 抹掉全部 cookie（JSESSIONID + route），
     * 让 nginx 重新随机分配节点，再登录。
     *
     * <p>这是本次实测最重要的修正：不清 route 时登录页请求会被粘回原节点，App 若落在
     * 异常短 TTL 节点就永远逃不出去；同时随机换节点把"两台设备同节点互踢 → 重登反踢"
     * 的确定性 ping-pong 变成小概率事件。</p>
     *
     * <p>登录成功但落在已知异常节点时重掷（最多 [JwxtAuthPolicy.MAX_NODE_REROLL] 次），
     * 避免用户先承受一次 8 秒重登、再在 90 秒后再次失效。调用方须持有 [authMutex]。</p>
     */
    private fun fullReloginLocked(c: JwxtClient) {
        var reroll = 0
        while (true) {
            c.clearBrowserIdentity()
            c.login()
            val route = RoomCookieJar.cookieValue(COOKIE_ROUTE)
            JwxtSessionHealthStore.markLogin(route)
            if (route == null ||
                !JwxtSessionHealthStore.isBadRoute(route) ||
                reroll >= JwxtAuthPolicy.MAX_NODE_REROLL
            ) {
                client = c
                return
            }
            reroll++
            Log.w(TAG, "登录落在已知异常节点（route=${route.take(8)}…），重新随机换节点（第 $reroll 次）")
        }
    }

    /**
     * 会话失效时更新节点健康（不改变恢复动作本身）：
     * <ul>
     *   <li>登录/确认有效后 10 分钟内就失效（[JwxtAuthPolicy.isNodeSuspicious]）→ 该节点 TTL 异常，
     *       记入 `badRoutes`，后续登录避开；</li>
     *   <li>其余情况（正常档节点）→ 把本次存活时长作为该节点 TTL 的**上界**，
     *       用于把下次预热阈值调早（[JwxtAuthPolicy.prewarmAgeFor]）。</li>
     * </ul>
     * 被踢/网络失败不算 TTL 证据，不应调用本方法。
     */
    private fun noteSessionExpiry() {
        val health = JwxtSessionHealthStore.load()
        val route = health.route ?: return
        val aliveSpan = System.currentTimeMillis() - health.lastOkAt
        if (aliveSpan <= 0L) return
        if (JwxtAuthPolicy.isNodeSuspicious(aliveSpan)) {
            Log.w(TAG, "节点在 ${aliveSpan / 1000}s 后就失效，标记为异常节点 route=${route.take(8)}…")
            JwxtSessionHealthStore.markRouteBad(route)
        } else if (aliveSpan < JwxtAuthPolicy.DEFAULT_NODE_TTL_MS) {
            // 只学习比默认 60min 更短的 TTL；更长的上界对预热阈值没有收紧作用
            JwxtSessionHealthStore.markRouteTtlUpper(route, aliveSpan)
            Log.d(TAG, "学习节点 TTL 上界：route=${route.take(8)}… ≤ ${aliveSpan / 60_000}min")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  对外简单接口
    // ════════════════════════════════════════════════════════════

    fun isBound(): Boolean = JwxtAccountManager.isBound()

    fun getBoundUsername(): String = JwxtAccountManager.getUsername()

    fun unbind() {
        JwxtAccountManager.clear()
        // 清除持久化会话与健康档案，避免残留 cookie / 节点记录影响下次绑定
        RoomCookieJar.clear()
        JwxtSessionHealthStore.clear()
        kotlinx.coroutines.runBlocking {
            SemesterManager.clear()
        }
        SemesterManager.setCurrentIndex(-1)
        // 清空课程显示
        CourseDataManager.getInstance(App.instance).switchSemester(0L)
        // 清空上次绩点计算结果：学期表重建后旧结果已失效，且换账号后不应展示上一个账号的绩点
        GpaResultStore(App.instance).clear()
        destroyClient()
    }

    // ════════════════════════════════════════════════════════════
    //  错误分类
    // ════════════════════════════════════════════════════════════

    /** 记录失败类型，供前台预热冷却与诊断使用。 */
    private fun recordFailure(e: Throwable) {
        when {
            e is CaptchaRequiredException -> JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.CAPTCHA)
            e is LoginException && isNetworkFailure(e) -> JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.NETWORK)
            e is LoginException -> JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.CREDENTIALS)
            e is KickedOutException -> JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.KICKED)
            e is SessionExpiredException -> JwxtSessionHealthStore.markFailure(JwxtAuthFailureKind.EXPIRED)
            else -> Unit
        }
    }

    /** 把底层异常转换成调用方可以直接展示的异常（保留原有 Captcha 类型供绑定页分支）。 */
    private fun toUserFacingError(e: Throwable): Throwable = when (e) {
        is CaptchaRequiredException -> e
        is KickedOutException -> LoginException("账号已在其它设备登录，自动重新登录失败，请稍后重试", e)
        is SessionExpiredException -> LoginException("登录状态恢复失败，请稍后重试", e)
        else -> e
    }

    /** 异常链里是否包含网络 I/O 异常（用于区分"网络不可用"和"凭据失效"）。 */
    private fun isNetworkFailure(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }

    private fun destroyClient() {
        client = null
    }

    /** 发一条登录态事件给 UI（非挂起、缓冲 8 条；无收集方时直接丢弃，不影响流程）。 */
    private fun emitNotice(notice: JwxtAuthNotice) {
        _notices.tryEmit(notice)
    }
}
