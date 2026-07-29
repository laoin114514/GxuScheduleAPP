package com.cherry.wakeupschedule.service

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
                // 重登录失败 → 清除凭证
                JwxtAccountManager.clear()
                destroyClient()
                Result.failure(LoginException("登录已过期，请重新绑定教务账号"))
            }
        } catch (e: LoginException) {
            JwxtAccountManager.clear()
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
