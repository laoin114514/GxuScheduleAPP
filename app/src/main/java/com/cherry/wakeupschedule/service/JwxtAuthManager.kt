package com.cherry.wakeupschedule.service

import com.cherry.wakeupschedule.App
import com.gxu.jwxt.JwxtClient
import com.gxu.jwxt.exceptions.LoginException
import com.gxu.jwxt.exceptions.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * 步骤 1：验证凭据 → 步骤 2：获取个人信息
     * 任一步骤失败则整体失败，成功则持久化到数据库。
     */
    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 步骤 1：验证凭据
            val c = JwxtClient(username, password)
            c.login()
            destroyClient()
            this@JwxtAuthManager.client = c

            // 步骤 2：获取个人信息
            try {
                val profile = c.profile().profile()
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
                // 获取当前学期的课表
                val curSem = SemesterManager.getCurrent()
                if (curSem != null) {
                    JwxtImportService.fetchAndSaveScheduleForSemester(App.instance, curSem)
                    App.instance.registerAllCourseNotifications()
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
            val c = JwxtClient(username, password)
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
        kotlinx.coroutines.runBlocking {
            SemesterManager.clear()
        }
        SemesterManager.setCurrentIndex(-1)
        // 清空课程显示
        CourseDataManager.getInstance(App.instance).switchSemester(0L)
        destroyClient()
    }

    private fun getOrCreateClient(): JwxtClient {
        return client ?: run {
            val c = JwxtClient(
                JwxtAccountManager.getUsername(),
                JwxtAccountManager.getPassword()
            )
            c.login()
            client = c
            c
        }
    }

    private fun destroyClient() {
        client = null
    }
}
