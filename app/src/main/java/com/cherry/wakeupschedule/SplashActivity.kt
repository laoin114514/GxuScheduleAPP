package com.cherry.wakeupschedule

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.cherry.wakeupschedule.service.AppInitializer
import com.cherry.wakeupschedule.ui.motion.Motion

/**
 * 闪屏页：品牌图标弹性放大 + 名称/副标题依次上浮，随后淡入主页面。
 *
 * 与系统 Splash（Android 12+）的衔接：注册 [android.window.SplashScreen] 退场监听，
 * 等系统大图标开始退场时才播品牌入场，并与大图标的缩放缓出交叉淡化——
 * 避免「系统大图标消失 → 空白页 → 自定义小图标出现」的三段跳，也避免入场动画被系统
 * Splash 盖住白播。
 *
 * 时长与启动初始化并行：动画播完时若 [AppInitializer] 还没就绪，才在主线程等它
 * （页面本来就在过渡，视觉不变），总耗时 = max(动画时长, 初始化)。
 *
 * 动效全部走 [Motion] 令牌：系统关闭动画时直接落终态，不阻塞跳转。
 */
class SplashActivity : BaseActivity() {

    /** 闪屏页退场后安装引导留给主页面弹 */
    override val resumesPendingInstall: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    /** 品牌入场序列是否已启动（系统 Splash 退场回调可能只来一次） */
    private var sequenceStarted = false

    private val navigateRunnable = Runnable { goMain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installSystemSplashExit()
            // 兜底：极少数 ROM 不回调退场监听时，到点仍然进主页（内容此时为静态可见，不会黑屏）
            handler.postDelayed(navigateRunnable, FALLBACK_NAVIGATE_MS)
        } else {
            startSplashSequence()
        }
    }

    /**
     * 注册系统 Splash 退场动画：系统大图标缩放缓出的同时，启动品牌入场动画，
     * 两者在 200ms 内交叉淡化。
     */
    private fun installSystemSplashExit() {
        try {
            getSplashScreen().setOnExitAnimationListener { splashView ->
                // 系统大图标开始退场的这一刻才真正"露出"自定义闪屏，此时播入场才看得见
                startSplashSequence()
                if (Motion.enabled()) {
                    splashView.animate()
                        .alpha(0f)
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .setDuration(220)
                        .setInterpolator(Motion.standard)
                        .withEndAction { splashView.remove() }
                        .start()
                } else {
                    splashView.remove()
                }
            }
        } catch (t: Throwable) {
            // 个别 ROM 的系统 Splash API 行为不一致：退回默认退出方式，不影响启动流程
            Log.w(TAG, "系统 Splash 退场动画不可用", t)
        }
    }

    /** 启动品牌入场并安排跳转（系统 Splash 退场时 / 无系统 Splash 时调用） */
    private fun startSplashSequence() {
        if (sequenceStarted || isFinishing || isDestroyed) return
        sequenceStarted = true
        playEntrance()
        // 以真正开始播放入场为起点计时，避免自定义动画还没播完就被切走
        handler.removeCallbacks(navigateRunnable)
        handler.postDelayed(navigateRunnable, SPLASH_DURATION_MS)
    }

    /** 品牌入场：图标回弹放大 → 名称上浮 → 副标题上浮，总时长约 400ms */
    private fun playEntrance() {
        val logo = findViewById<View>(R.id.iv_splash_logo)
        val name = findViewById<View>(R.id.tv_splash_name)
        val tagline = findViewById<View>(R.id.tv_splash_tagline)

        if (!Motion.enabled()) {
            listOf(logo, name, tagline).forEach {
                it.alpha = 1f
                it.translationY = 0f
                it.scaleX = 1f
                it.scaleY = 1f
            }
            return
        }

        logo.alpha = 0f
        logo.scaleX = 0.82f
        logo.scaleY = 0.82f
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420)
            .setInterpolator(Motion.spring)
            .start()

        // 名称与副标题错峰上浮：这里需要精确控制与图标的视觉节奏，不用批量 stagger
        name.alpha = 0f
        name.translationY = Motion.dp(name, 10f)
        name.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(90)
            .setDuration(Motion.ENTRANCE)
            .setInterpolator(Motion.standard)
            .start()

        tagline.alpha = 0f
        tagline.translationY = Motion.dp(tagline, 8f)
        tagline.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(170)
            .setDuration(Motion.ENTRANCE)
            .setInterpolator(Motion.standard)
            .start()
    }

    private fun goMain() {
        if (navigated || isFinishing || isDestroyed) return
        navigated = true
        // 动画期间后台初始化一直并行；到这里通常已就绪，未就绪则等它结束，
        // 保证进入 MainActivity 时课表/学期/账号缓存已可用
        AppInitializer.awaitReady()
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.splash_content_in, R.anim.splash_content_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SplashActivity"

        /** 入场动画开始后再停留的时长（动画约 400ms 收束，留 60ms 让品牌画面"停一下"） */
        const val SPLASH_DURATION_MS = 460L

        /** 系统 Splash 退场监听一直未回调时的兜底跳转时间 */
        const val FALLBACK_NAVIGATE_MS = 900L
    }
}
