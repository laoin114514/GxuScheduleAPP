package com.cherry.wakeupschedule

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navOptions
import androidx.navigation.fragment.NavHostFragment
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.service.AppInitializer
import com.cherry.wakeupschedule.service.CrashLogger
import com.cherry.wakeupschedule.ui.theme.PageBackground
import com.cherry.wakeupschedule.databinding.ActivityMainBinding
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtSessionHealthStore
import com.cherry.wakeupschedule.service.UpdateService
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.viewmodel.CourseViewModel

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 本地数据库初始化失败、正在显示恢复弹窗：此时没有内容视图，生命周期里要跳过常规逻辑 */
    private var localDataRecoveryMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize theme manager and apply current palette overlay
        // MUST be called before setContentView() so all ?attr/ references resolve correctly
        ThemeManager.init(this)
        ThemeManager.applyToTheme(this)

        // 下面的 setContentView 会立即创建 NavHostFragment 并执行各页面的 onViewCreated，
        // 那里开始读课表/学期/账号缓存。从桌面小组件直接点开本页面（不经过 Splash）时，
        // App.onCreate 启动的后台初始化可能还没结束，这里兜底等它完成：
        // 已就绪时只是一次 volatile 读，不引入任何多余主线程工作。
        AppInitializer.awaitReady()

        // 后台初始化失败（典型原因：本地数据库 schema/identity 校验失败、库文件损坏）：
        // 不能再带着一个不可用的数据库继续 setContentView —— 下面创建 CourseViewModel 时会
        // 立刻第一次访问数据库并再次抛异常，表现成「打开闪退」或「一刷新课表就闪退」，
        // 而且用户没有任何自救手段。这里先给出明确说明 + 「重置本地数据」恢复路径。
        AppInitializer.initFailure()?.let { failure ->
            showLocalDataRecoveryDialog(failure)
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // 课表视图模式（周课表 / 总课表）：Activity 级 ViewModel，切 tab / 重建 Activity 都不丢；
        // 不做持久化，每次进入应用都从周课表开始
        val courseViewModel = ViewModelProvider(this)[CourseViewModel::class.java]

        // 自定义底部导航：点击 → 顶层页签式跳转（保留各页状态）；目的地变化 → 同步高亮。
        // 「课表」页签再次点击 = 在周课表 / 总课表之间切换（角标只是指示器，不接收点击）。
        // 注意：必须用 (id, null, navOptions) 三参形式走 int 重载。Navigation 2.8 新增的
        // 类型安全重载 navigate(route: T, NavOptions) 与 (Int, NavOptions) 调用形状完全匹配，
        // 两参写法会把资源 id 当路由对象，抛 "Destination with route Int" 崩溃
        binding.bottomNav.onItemSelected = { tabId ->
            if (tabId == R.id.nav_schedule &&
                navController.currentDestination?.id == R.id.nav_schedule
            ) {
                courseViewModel.toggleOverviewMode()
            } else {
                navController.navigate(tabId, null, navOptions {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                })
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.select(destination.id)
        }
        // 主题切换等场景会重建 Activity，NavController 恢复的目的地不一定是首页签，
        // 必须以当前目的地为准（首启动时 currentDestination 为空，回落到课表）
        binding.bottomNav.select(navController.currentDestination?.id ?: R.id.nav_schedule)

        // 底部导航底色与页面渐变底端同色：换配色时整条导航跟着变色，避免出现一条蓝灰色带
        binding.bottomNav.background =
            android.graphics.drawable.ColorDrawable(PageBackground.bottomColor(this))

        // 角标外观随模式回灌（切换动作由上面的「课表」页签点击触发）
        courseViewModel.overviewMode.observe(this) { binding.bottomNav.setScheduleOverview(it) }

        // 每日更新检查：今日已检查则直接返回，有新版本时静默弹窗提示
        UpdateService(this).checkForUpdateSilently()
    }

    // ── 本地数据库初始化失败的启动恢复 ─────────────────────────────

    /**
     * 本地数据库初始化失败的启动恢复弹窗。
     *
     * 给三条路：重置本地数据（应急恢复，会清空本机数据）、分享诊断日志（便于用户反馈）、退出。
     * 重置后必须重启进程：各 Manager 单例里缓存了旧数据库/学期/账号状态，
     * 单纯重建 DB 实例会让 App 继续使用旧缓存，等于没修。
     */
    private fun showLocalDataRecoveryDialog(failure: Throwable) {
        localDataRecoveryMode = true
        val reason = buildString {
            append(failure.javaClass.simpleName)
            failure.message?.takeIf { it.isNotBlank() }?.let { message ->
                append("：").append(if (message.length > 140) message.take(140) + "…" else message)
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.db_recovery_title)
            .setMessage(getString(R.string.db_recovery_message, reason))
            .setCancelable(false)
            .setPositiveButton(R.string.db_recovery_reset) { _, _ -> resetLocalDataAndRestart() }
            .setNeutralButton(R.string.db_recovery_share) { _, _ -> shareLocalDataDiagnostics(failure) }
            .setNegativeButton(R.string.db_recovery_exit) { _, _ -> finishAffinity() }
            .show()
    }

    /**
     * 分享「本地数据初始化失败」的诊断信息（系统分享面板，纯文本，不需要 FileProvider）。
     *
     * 应用走自有更新渠道，用户报「闪退」时往往拿不到 logcat；这里把设备/版本信息、初始化异常栈、
     * 以及上一次崩溃落盘内容拼成文本，用户直接转发给开发者即可定位。
     * 只在恢复弹窗里出现，不影响正常页面。
     */
    private fun shareLocalDataDiagnostics(failure: Throwable) {
        val text = buildString {
            appendLine("【西大课栈 · 本地数据初始化失败诊断】")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "Android：" + android.os.Build.VERSION.RELEASE +
                    " (API " + android.os.Build.VERSION.SDK_INT + ")"
            )
            appendLine("机型：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("ABI：${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine(
                "时间：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(java.util.Date())
            )
            appendLine()
            appendLine("—— 初始化异常 ——")
            appendLine(Log.getStackTraceString(failure))
            CrashLogger.readLastCrash(this@MainActivity)?.let { crash ->
                appendLine()
                appendLine("—— 最近一次崩溃落盘 ——")
                appendLine(crash.take(4000))
            }
        }
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "西大课栈诊断日志")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.db_recovery_share)))
        } catch (t: Throwable) {
            Log.e("MainActivity", "分享诊断日志失败", t)
            com.cherry.wakeupschedule.ui.feedback.AppToast.error(
                this,
                getString(R.string.db_recovery_share_failed),
            )
        }
    }

    private fun resetLocalDataAndRestart() {
        try {
            AppDatabase.resetForRecovery(this)
            // 会话健康档案（路由/TTL）与旧库强相关，一并清掉，避免下次绑定沿用坏节点结论
            JwxtSessionHealthStore.clear()
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "重置本地数据失败", t)
        }
        // 先起一个新任务再结束进程：进程退出后系统会用空白状态重新拉起，
        // 保证所有 Manager 单例/学期缓存都被真正重建。
        // 不用 AlarmManager 延时拉起：Android 14+ 后台 Activity 启动限制下并不可靠
        // （实测 reset 后闹钟已登记但页面不会自动打开），用户会停在「应用已退出」状态。
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (launchIntent != null) startActivity(launchIntent)
        } catch (t: Throwable) {
            Log.e("MainActivity", "重置后重启失败，用户可手动重新打开应用", t)
        }
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    override fun onResume() {
        super.onResume()
        // 恢复弹窗分支没有 setContentView，binding 未初始化；等用户选完按钮再继续
        if (localDataRecoveryMode) return
        // 教务会话前台预热：距上次确认有效超过阈值时，在后台探测/静默重登录，
        // 避免用户点刷新时才发现会话过期（实测一次完整重登录约 8 秒）。
        // 阈值节流（默认 25 分钟）+ 进程内客户端复用，正常切前后台不会产生多余请求。
        JwxtAuthManager.prewarmInBackground("main-resume")
        // 主题切换等场景重建后，Fragment 恢复时序不定，此处兜底校准导航栏高亮
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        navHostFragment.navController.currentDestination?.let {
            binding.bottomNav.select(it.id)
        }
    }
}
