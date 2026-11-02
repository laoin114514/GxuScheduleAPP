package com.cherry.wakeupschedule

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.cherry.wakeupschedule.service.AppInitializer
import com.cherry.wakeupschedule.widget.ScheduleWideWidgetProvider
import com.cherry.wakeupschedule.service.ThemeModeManager
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.widget.MinimalWidgetProvider
import com.cherry.wakeupschedule.widget.NextCourseWidgetProvider
import com.cherry.wakeupschedule.widget.ScheduleWidgetProvider
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import com.cherry.wakeupschedule.widget.WidgetMidnightReceiver
import java.lang.ref.WeakReference

class App : Application() {

    private var timeTickReceiver: BroadcastReceiver? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private val secondTickHandler = Handler(Looper.getMainLooper())
    private var secondTickRunnable: Runnable? = null
    @Volatile
    private var isScreenOn = true
    private val autoThemeHandler = Handler(Looper.getMainLooper())
    private val autoThemeRunnable = object : Runnable {
        override fun run() {
            try {
                val target = ThemeModeManager.resolveNightMode(this@App)
                if (target != AppCompatDelegate.getDefaultNightMode()) {
                    AppCompatDelegate.setDefaultNightMode(target)
                    Log.d("App", "Auto theme switched to mode=$target")
                }
            } catch (e: Exception) {
                Log.e("App", "Auto theme check failed", e)
            } finally {
                autoThemeHandler.postDelayed(this, AUTO_THEME_CHECK_INTERVAL_MS)
            }
        }
    }

    /** 当前前台（resumed）页面，供更新引导等全局弹窗选择宿主，避免弹在被销毁/不可见的页面上 */
    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    private val activityTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivityRef = WeakReference(activity)
            // 应用内提示卡挂在当前页面里（而非系统 Toast 窗口），随页面一起进退场
            AppToast.onActivityResumed(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            // 只有暂停的正是当前记录的页面才清空，避免切换页面时误清
            if (currentActivityRef?.get() === activity) currentActivityRef = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            // 摘掉提示容器但保留队列，等下一个页面 resume 时接管
            AppToast.onActivityDestroyed(activity)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(activityTracker)

        // 应用主题模式（浅色/深色/跟随系统）
        applyStoredThemeMode()

        android.util.Log.d("App", "Application onCreate called")

        // 教务账号 / CookieJar / 学期缓存 / 课程缓存的初始化全部是同步 Room 查询
        // （首次查询还会触发建库与迁移链），是冷启动最大的主线程成本。
        // 这里统一交给后台线程，与闪屏 400ms 展示窗口及页面启动本身重叠；
        // 需要这些缓存的入口（MainActivity、冷启动恢复的页面、小组件接收器）在读取前
        // 用 AppInitializer.awaitReady() 兜底，语义与"onCreate 里已同步加载完"一致。
        //
        // 小组件闹钟链的建立一并放到初始化线程：它们只碰 AlarmManager / AppWidgetManager 的
        // binder 调用，不需要主线程，省掉主线程 8~10 次 IPC（低端机上每次 ~0.1~0.3ms）；
        // 15/30 分钟级兜底闹钟提前几毫秒挂上没有行为差异。
        AppInitializer.start(this) { scheduleWidgetUpdateChains() }

        try {
            registerTimeTickReceiver()
        } catch (e: Exception) {
            Log.e("App", "Failed to register time tick receiver", e)
        }

        try {
            registerScreenStateReceiver()
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to register screen state receiver", e)
        }

        try {
            startSecondTick()
            Log.d("App", "Per-second widget tick started")
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to start per-second widget tick", e)
        }

        // 周期检测自定时间模式的深浅色自动切换
        startAutoThemeCheck()
    }

    /**
     * 建立小组件后台刷新链（30 分钟精确链 + 午夜链）。
     *
     * 从 App.onCreate 主线程挪到 [AppInitializer] 的初始化线程执行：
     * 这里只有 AlarmManager / AppWidgetManager 的 binder 调用（无 UI、无数据依赖），
     * 但每次启动会在主线程付 8~10 次 IPC；低端机上这部分会直接进入冷启动首帧耗时。
     * 时序上无差异：两条链的目标时刻都在几十分钟后，早挂/晚挂几毫秒不会被观察到。
     * 内部仍各自判断桌面是否有小组件实例（无实例用户 0 唤醒），行为不变。
     */
    private fun scheduleWidgetUpdateChains() {
        try {
            ScheduleWidgetUpdateService.scheduleNextUpdate(this)
            WidgetMidnightReceiver.scheduleMidnightUpdate(this)
            Log.d("App", "Widget update chains initialized")
        } catch (e: Exception) {
            Log.e("App", "Failed to initialize widget update chains", e)
        }
    }

    private fun registerTimeTickReceiver() {
        timeTickReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context ?: return
                // 桌面没有任何小组件实例时，这一分钟的刷新没有任何可见效果：
                // 4 次广播 + 后续 AppWidgetManager IPC 全是无用功，直接跳过（判断走内存缓存，成本接近零）
                if (!ScheduleWidgetUpdateService.hasAnyWidget(context)) return
                ScheduleWidgetProvider.triggerUpdate(context)
                ScheduleWideWidgetProvider.triggerUpdate(context)
                MinimalWidgetProvider.triggerUpdate(context)
                NextCourseWidgetProvider.triggerUpdate(context)
            }
        }
        registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK), RECEIVER_NOT_EXPORTED)
        Log.d("App", "TIME_TICK receiver registered dynamically for per-minute widget updates")
    }

    private fun registerScreenStateReceiver() {
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        stopSecondTick()
                        Log.d("App", "Screen off — widget tick paused")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        startSecondTick()
                        Log.d("App", "Screen on — widget tick resumed")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        Log.d("App", "Screen state receiver registered")
    }

    /**
     * 周期检测深浅色自动切换（自定时间模式到点自动切换主题）。
     * 仅当解析出的夜间模式与当前生效模式不同时才调用 setDefaultNightMode。
     */
    private fun startAutoThemeCheck() {
        try {
            ThemeModeManager.apply(this)
            autoThemeHandler.post(autoThemeRunnable)
            Log.d("App", "Auto theme check started")
        } catch (e: Exception) {
            Log.e("App", "Failed to start auto theme check", e)
        }
    }

    private fun startSecondTick() {
        if (!isScreenOn) return
        stopSecondTick()
        // 冷启动时课程/学期缓存还没就绪：等后台初始化完成后再开始 tick，
        // 避免还没数据就先刷一次小组件（也避免未就绪时在主线程等初始化）
        if (!AppInitializer.isReady) {
            AppInitializer.runOnMainWhenReady { startSecondTick() }
            return
        }
        secondTickRunnable = object : Runnable {
            override fun run() {
                if (!isScreenOn) return
                // 仅倒计时组件需要逐秒跳字；其余组件由分钟 tick / TIME_SET / 周期与切换闹钟覆盖。
                // 桌面没有该实例时，每秒广播 + IPC 都是无用功（低端机主线程 jank 来源），
                // 跳过更新但仍保留 1s 循环：用户随时可能在桌面上添加组件，循环在则下一秒自动恢复刷新。
                // isPlaced 是工作流 C 提供的内存缓存判断，成本接近零。
                if (MinimalWidgetProvider.isPlaced(this@App)) {
                    MinimalWidgetProvider.triggerUpdate(this@App)
                }
                secondTickHandler.postDelayed(this, 1000L)
            }
        }
        secondTickHandler.post(secondTickRunnable!!)
    }

    private fun stopSecondTick() {
        secondTickRunnable?.let { secondTickHandler.removeCallbacks(it) }
        secondTickRunnable = null
    }

    private fun applyStoredThemeMode() {
        // 统一由 ThemeModeManager 解析并应用深浅色/自动切换
        ThemeModeManager.apply(this)
    }

    companion object {
        /** 自定时间模式下的周期检测间隔（毫秒） */
        private const val AUTO_THEME_CHECK_INTERVAL_MS = 30_000L

        lateinit var instance: App
            private set

        /**
         * 当前前台页面；应用处于后台、或正处于页面切换间隙时返回 null。
         * 仅用于给全局弹窗（如更新安装引导）挑选可用宿主页面。
         */
        fun currentActivity(): Activity? {
            if (!::instance.isInitialized) return null
            val activity = instance.currentActivityRef?.get() ?: return null
            return activity.takeIf { !it.isFinishing && !it.isDestroyed }
        }
    }
}
