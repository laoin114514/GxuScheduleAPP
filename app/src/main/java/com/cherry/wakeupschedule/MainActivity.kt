package com.cherry.wakeupschedule

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.NavHostFragment
import com.cherry.wakeupschedule.databinding.ActivityMainBinding
import com.cherry.wakeupschedule.service.ImportService
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

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

        // 自定义底部导航：点击 → 顶层页签式跳转（保留各页状态）；目的地变化 → 同步高亮
        binding.bottomNav.onItemSelected = { tabId ->
            navController.navigate(tabId) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.select(destination.id)
        }
        binding.bottomNav.select(R.id.nav_schedule)
    }

    override fun onResume() {
        super.onResume()
        handlePendingImport()
    }

    /**
     * 处理从 WebViewActivity 下载后保存到 pending_imports 的待导入文件。
     * 作为 WebViewActivity 直接导入失败时的兜底机制。
     */
    private fun handlePendingImport() {
        val prefs = getSharedPreferences("pending_imports", Context.MODE_PRIVATE)
        val pendingFile = prefs.getString("pending_file", null) ?: return
        // 清除记录，避免重复导入
        prefs.edit().remove("pending_file").apply()

        lifecycleScope.launch {
            try {
                val uri = try {
                    Uri.parse(pendingFile)
                } catch (e: Exception) {
                    Log.w("MainActivity", "无法解析 pending_file URI: $pendingFile", e)
                    return@launch
                }
                val importService = ImportService(this@MainActivity)
                val success = withContext(Dispatchers.IO) {
                    importService.importFromFile(uri)
                }
                if (success) {
                    Log.d("MainActivity", "pending_imports 兜底导入成功: $pendingFile")
                } else {
                    Log.w("MainActivity", "pending_imports 兜底导入失败: $pendingFile")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "pending_imports 兜底导入异常", e)
            }
        }
    }
}
