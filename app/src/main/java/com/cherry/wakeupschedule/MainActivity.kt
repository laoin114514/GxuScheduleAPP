package com.cherry.wakeupschedule

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navOptions
import androidx.navigation.fragment.NavHostFragment
import com.cherry.wakeupschedule.ui.theme.PageBackground
import com.cherry.wakeupschedule.databinding.ActivityMainBinding
import com.cherry.wakeupschedule.service.UpdateService
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.viewmodel.CourseViewModel

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize theme manager and apply current palette overlay
        // MUST be called before setContentView() so all ?attr/ references resolve correctly
        ThemeManager.init(this)
        ThemeManager.applyToTheme(this)

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

    override fun onResume() {
        super.onResume()
        // 主题切换等场景重建后，Fragment 恢复时序不定，此处兜底校准导航栏高亮
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        navHostFragment.navController.currentDestination?.let {
            binding.bottomNav.select(it.id)
        }
    }
}
