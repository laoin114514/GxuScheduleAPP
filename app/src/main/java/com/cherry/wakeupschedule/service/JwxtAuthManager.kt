package com.cherry.wakeupschedule.service

import com.cherry.wakeupschedule.App
import com.gxu.jwxt.JwxtClient
import com.gxu.jwxt.exceptions.LoginException
import com.gxu.jwxt.exceptions.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 登录流程的可上报阶段，用于绑定页展示分步进度。
 */
enum class JwxtLoginStep { LOGIN, PROFILE, SEMESTER, SCHEDULE }

/**
 * 教务系统认证管理器。
 *
 * 所有需要认证的接口调用都通过 [doWithAuth]，自动处理 session 过期重登录。
 */
object JwxtAuthManager {

    @Volatile
    private var client: JwxtClient? = null

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
        try {
            // 步骤 1：验证凭据
            val c = JwxtClient(username, password, RoomCookieJar)
            c.login()
            destroyClient()
            this@JwxtAuthManager.client = c
            onStep(JwxtLoginStep.LOGIN)

            // 步骤 2：获取个人信息
            try {
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
                    App.instance.registerAllCourseNotifications()
                    onStep(JwxtLoginStep.SCHEDULE)
                }
                Result.success("登录成功")
            } catch (e: Exception) {
                // profile 获取失败 → 等同登录失败
                destroyClient()
                Result.failure(LoginException("获取个人信息失败: ${e.message}"))
            }
        } catch (e: LoginException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(LoginException("登录失败: ${e.message}"))
        }
    }

    /**
     * 校验已绑定账号的新密码，并仅更新本地凭据。
     * 不读取个人信息、不初始化学期，也不会变更已有课表数据。
     */
    suspend fun updatePassword(password: String): Result<String> = withContext(Dispatchers.IO) {
        val username = JwxtAccountManager.getUsername()
        if (!JwxtAccountManager.isBound() || username.isBlank()) {
            return@withContext Result.failure(LoginException("请先绑定教务账号"))
        }

        try {
            val c = JwxtClient(username, password, RoomCookieJar)
            c.login()
            destroyClient()
            client = c
            JwxtAccountManager.updatePassword(password)
            Result.success("密码已更新")
        } catch (e: LoginException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(LoginException("登录失败: ${e.message}"))
        }
    }

    /**
     * 执行需要认证的操作，自动处理 session 过期。
     *
     * @param action 业务逻辑，接收已登录的 [JwxtClient]
     * @return [Result] 包装的结果
     */
    suspend fun <T> doWithAuth(action: (JwxtClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            val c = getOrCreateClient()
            Result.success(action(c))
        } catch (e: SessionExpiredException) {
            try {
                // session 过期 → 重登录 → 重试
                getOrCreateClient().relogin()
                Result.success(action(getOrCreateClient()))
            } catch (reloginError: LoginException) {
                // 重登录失败（可能是网络问题或凭据失效），不清除本地凭据
                destroyClient()
                Result.failure(LoginException("登录已过期，请稍后重试"))
            }
        } catch (e: LoginException) {
            // 网络错误等导致的 LoginException，不清除本地凭据
            destroyClient()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isBound(): Boolean = JwxtAccountManager.isBound()

    fun getBoundUsername(): String = JwxtAccountManager.getUsername()

    fun unbind() {
        JwxtAccountManager.clear()
        // 清除持久化会话，避免残留 cookie 影响下次绑定
        RoomCookieJar.clear()
        kotlinx.coroutines.runBlocking {
            SemesterManager.clear()
        }
        SemesterManager.setCurrentIndex(-1)
        // 清空课程显示
        CourseDataManager.getInstance(App.instance).switchSemester(0L)
        destroyClient()
    }

    /**
     * 获取（或创建）带持久化 cookie 的客户端。
     *
     * 懒认证：先用 Room 中保存的 cookie 探测会话是否有效，
     * 有效则免登录直接复用；无效（无 cookie/已过期）才走完整登录并刷新 cookie。
     */
    private fun getOrCreateClient(): JwxtClient {
        return client ?: run {
            val c = JwxtClient(
                JwxtAccountManager.getUsername(),
                JwxtAccountManager.getPassword(),
                RoomCookieJar
            )
            // 探测持久化会话；失败则完整登录（登录成功后 cookie 自动入库）
            if (!c.resumeSession()) {
                c.login()
            }
            client = c
            c
        }
    }

    private fun destroyClient() {
        client = null
    }
}
