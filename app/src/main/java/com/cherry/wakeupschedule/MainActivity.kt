package com.cherry.wakeupschedule

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.cherry.wakeupschedule.databinding.ActivityMainBinding
import com.cherry.wakeupschedule.service.AccountRepository
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.ImportService
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager.init(this)
        ThemeManager.applyToTheme(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)

        // 处理绑定后的初始化
        val initAccountId = intent.getLongExtra("init_account_id", -1L)
        if (initAccountId > 0) {
            val accountRepo = AccountRepository.getInstance(this)
            lifecycleScope.launch {
                // 切换 CourseDataManager 到目标账号
                CourseDataManager.getInstance(this@MainActivity).switchAccount(initAccountId)
                // 加载账号设置到缓存
                SettingsManager(this@MainActivity).loadAccountSettings(initAccountId)
                // 触发初始化流程
                val viewModel = ViewModelProvider(this@MainActivity)[CourseViewModel::class.java]
                viewModel.startInitFlow(initAccountId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handlePendingImport()
        // 每次 resume 确保活跃账号课程已加载
        val accountRepo = AccountRepository.getInstance(this)
        if (accountRepo.hasActiveAccount()) {
            val activeId = accountRepo.getActiveAccountId()
            CourseDataManager.getInstance(this).switchAccount(activeId)
        }
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
