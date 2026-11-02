package com.cherry.wakeupschedule

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.cherry.wakeupschedule.service.AppInitializer

class SplashActivity : BaseActivity() {

    /** 闪屏页 400ms 后就退场，安装引导留给主页面弹 */
    override val resumesPendingInstall: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Handler(Looper.getMainLooper()).postDelayed({
            // 这 400ms 与 App.onCreate 启动的后台初始化并行；到期时若还没初始化完，
            // 就在这里等它结束（页面本来就是空白过渡页，视觉不变），
            // 保证进入 MainActivity 时课表/学期/账号缓存已就绪，总耗时 = max(400ms, 初始化)。
            AppInitializer.awaitReady()
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, 400)
    }
}