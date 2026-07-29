package com.cherry.wakeupschedule.service

import com.gxu.jwxt.JwxtClient
import com.gxu.jwxt.exceptions.LoginException
import com.gxu.jwxt.exceptions.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object JwxtAuthManager {

    // accountId -> JwxtClient 缓存
    private val clients = ConcurrentHashMap<Long, JwxtClient>()

    private fun getOrCreateClient(accountId: Long, username: String, password: String): JwxtClient {
        return clients.getOrPut(accountId) {
            val c = JwxtClient(username, password)
            c.login()
            c
        }
    }

    fun destroyClient(accountId: Long) {
        clients.remove(accountId)
    }

    /**
     * 测试登录
     */
    suspend fun testLogin(username: String, password: String): Result<JwxtClient> =
        withContext(Dispatchers.IO) {
            try {
                val c = JwxtClient(username, password)
                c.login()
                Result.success(c)
            } catch (e: LoginException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(LoginException("登录失败: ${e.message}"))
            }
        }

    /**
     * 带自动 session 恢复的操作
     */
    suspend fun <T> doWithAuth(
        accountId: Long,
        username: String,
        password: String,
        action: (JwxtClient) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val c = getOrCreateClient(accountId, username, password)
            Result.success(action(c))
        } catch (e: SessionExpiredException) {
            try {
                val c = getOrCreateClient(accountId, username, password)
                c.relogin()
                Result.success(action(c))
            } catch (reloginError: LoginException) {
                destroyClient(accountId)
                Result.failure(LoginException("登录已过期，请重新绑定教务账号"))
            }
        } catch (e: LoginException) {
            destroyClient(accountId)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
