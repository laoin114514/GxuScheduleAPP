package com.cherry.wakeupschedule

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.cherry.wakeupschedule.util.DebugLogger
import com.cherry.wakeupschedule.service.AlarmService
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.CourseReminderWorker
import com.cherry.wakeupschedule.service.NotificationHelper
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.ThemeModeManager
import com.cherry.wakeupschedule.widget.MinimalWidgetProvider
import com.cherry.wakeupschedule.widget.ScheduleWidgetProvider
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import com.cherry.wakeupschedule.widget.WidgetMidnightReceiver

class App : Application() {

    var alarmService: AlarmService? = null
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

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 应用主题模式（浅色/深色/跟随系统）
        applyStoredThemeMode()

        android.util.Log.d("App", "Application onCreate called")

        // 初始化调试日志（在 Application 层，确保所有组件都能使用）
        DebugLogger.init(this)

        // 初始化教务账号管理器
        com.cherry.wakeupschedule.service.JwxtAccountManager.init(this)

        // 初始化学期管理器（内部自动恢复当前学期索引）
        com.cherry.wakeupschedule.service.SemesterManager.init(this)

        try {
            NotificationHelper(this).createNotificationChannels()
            android.util.Log.d("App", "Notification channels created")
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to create notification channels", e)
        }

        try {
            CourseDataManager.getInstance(this)
            android.util.Log.d("App", "CourseDataManager initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to initialize CourseDataManager", e)
        }

        try {
            alarmService = AlarmService(this)
            android.util.Log.d("App", "AlarmService initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to initialize AlarmService", e)
        }

        try {
            ScheduleWidgetUpdateService.scheduleNextUpdate(this)
            WidgetMidnightReceiver.scheduleMidnightUpdate(this)
            android.util.Log.d("App", "Widget update chains initialized")
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to initialize widget update chains", e)
        }

        try {
            registerTimeTickReceiver()
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to register time tick receiver", e)
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

        try {
            registerAllCourseNotifications()
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to restore course alarms on app init", e)
        }
    }

    private fun registerTimeTickReceiver() {
        timeTickReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context ?: return
                ScheduleWidgetProvider.triggerUpdate(context)
                MinimalWidgetProvider.triggerUpdate(context)
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
        secondTickRunnable = object : Runnable {
            override fun run() {
                if (!isScreenOn) return
                MinimalWidgetProvider.triggerUpdate(this@App)
                ScheduleWidgetProvider.triggerUpdate(this@App)
                secondTickHandler.postDelayed(this, 1000L)
            }
        }
        secondTickHandler.post(secondTickRunnable!!)
    }

    private fun stopSecondTick() {
        secondTickRunnable?.let { secondTickHandler.removeCallbacks(it) }
        secondTickRunnable = null
    }

    fun registerAllCourseNotifications() {
        if (SettingsManager(this).isAlarmEnabled()) {
            alarmService?.registerAllCourseNotifications()
            // 启动前台服务，确保闹钟在国产 ROM 上不被杀
            try {
                com.cherry.wakeupschedule.service.CourseReminderForegroundService.start(this)
            } catch (e: Exception) {
                Log.e("App", "Failed to start foreground service", e)
            }
            // 检查精确闹钟权限
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                    if (!am.canScheduleExactAlarms()) {
                        Log.w("App", "未授权精确闹钟权限，课前通知可能无法准时触发")
                    }
                } catch (e: Exception) {
                    Log.e("App", "检查精确闹钟权限失败", e)
                }
            }
            Log.d("App", "All course notifications have been re-registered")
        }
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
    }
}
