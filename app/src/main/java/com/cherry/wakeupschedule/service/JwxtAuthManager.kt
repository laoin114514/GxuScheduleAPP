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
 *
 * 使用示例：
 * ```kotlin
 * val result = JwxtAuthManager.doWithAuth { client ->
 *     client.profile().profile()
 * }
 * result.onSuccess { profile -> showProfile(profile) }
 *       .onFailure { e -> showError(e) }
 * ```
 */
object JwxtAuthManager {

    @Volatile
    private var client: JwxtClient? = null

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

    /**
     * 测试登录是否成功。
     */
    suspend fun testLogin(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val c = JwxtClient(username, password)
            c.login()
            destroyClient()
            this@JwxtAuthManager.client = c
            Result.success("登录成功")
        } catch (e: LoginException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(LoginException("登录失败: ${e.message}"))
        }
    }

    fun isBound(): Boolean = JwxtAccountManager.isBound()

    fun getBoundUsername(): String = JwxtAccountManager.getUsername()

    fun unbind() {
        JwxtAccountManager.clear()
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
