package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 电池优化辅助工具
 * 用于处理各种厂商系统的电池优化问题，确保闹钟能正常工作
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"

    /**
     * 各厂商电源管理页面Intent列表
     */
    private val MANUFACTURER_POWER_MANAGER_INTENTS = listOf(
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent().setClassName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Intent().setClassName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        Intent().setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        Intent().setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        Intent().setClassName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        Intent().setClassName("com.samsung.android.sm", "com.samsung.android.sm.ui.settings.SettingsActivity"),
        Intent().setClassName("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"),
        Intent().setClassName("com.anchu.dianxin", "com.anchu.dianxin.DianXinMainActivity"),
        Intent().setClassName("com.tencent.android.qqpimsecure", "com.tencent.aa.ui.MainActivity"),
    )

    /**
     * 检查是否已忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求忽略电池优化
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) {
            Log.d(TAG, "Already ignoring battery optimizations")
            return true
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption", e)
        }

        return openBatteryOptimizationSettings(context)
    }

    /**
     * 打开电池优化设置页面
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
        }

        openManufacturerPowerSettings(context)
        return true
    }

    /**
     * 打开厂商特定的电源管理设置页面
     */
    fun openManufacturerPowerSettings(context: Context): Boolean {
        for (intent in MANUFACTURER_POWER_MANAGER_INTENTS) {
            if (isIntentAvailable(context, intent)) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.d(TAG, "Opened manufacturer power manager: ${intent.component}")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open manufacturer power manager", e)
                }
            }
        }

        Log.d(TAG, "No manufacturer power manager found, opening general settings")
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open general settings", e)
        }

        return false
    }

    /**
     * 检查是否开启省电模式
     * 超级省电模式会严重影响后台应用
     */
    fun isPowerSaveModeEnabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }

    /**
     * 获取设备厂商名称
     */
    fun getManufacturerName(): String {
        return Build.MANUFACTURER.lowercase()
    }

    /**
     * 检查是否为中国厂商
     */
    fun isChineseManufacturer(): Boolean {
        val manufacturer = getManufacturerName()
        return manufacturer in listOf(
            "xiaomi", "redmi", "poco",
            "huawei", "honor",
            "oppo", "realme", "oneplus",
            "vivo", "iqoo",
            "meizu",
            "lenovo", "motorola",
            "zte", "nubia",
            "samsung"
        )
    }

    /**
     * 检查是否需要特殊处理
     */
    fun needsSpecialHandling(): Boolean {
        val manufacturer = getManufacturerName()
        return manufacturer in listOf(
            "xiaomi", "redmi", "poco",
            "huawei", "honor",
            "oppo", "realme", "oneplus",
            "vivo", "iqoo",
            "meizu"
        )
    }

    /**
     * 检查Intent是否可用
     */
    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取厂商自启动设置Intent
     */
    fun getAutoStartIntent(context: Context): Intent? {
        for (intent in MANUFACTURER_POWER_MANAGER_INTENTS) {
            if (isIntentAvailable(context, intent)) {
                return intent
            }
        }
        return null
    }

    /**
     * 获取详细的设置说明
     */
    fun getDetailedInstructions(@Suppress("UNUSED_PARAMETER") context: Context): String {
        val manufacturer = getManufacturerName()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                """
                小米/红米/POCO 系统设置步骤：
                1. 设置 → 应用设置 → 应用管理 → 找到「西大课栈」
                2. 点击「省电策略」→ 选择「无限制」
                3. 返回 → 设置 → 应用设置 → 应用管理 → 授权管理 → 后台弹出界面 → 允许
                4. 设置 → 应用设置 → 应用管理 → 授权管理 → 后台启动 → 允许
                """.trimIndent()
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                """
                华为/荣耀 系统设置步骤：
                1. 设置 → 应用 → 应用管理 → 找到「西大课栈」
                2. 点击「电池」→「启动管理」→ 关闭「自动管理」
                3. 手动开启「允许自启动」「允许后台活动」「允许关联启动」
                4. 设置 → 电池 → 更多电池管理 → 关闭「严格限制后台」
                """.trimIndent()
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                """
                OPPO/Realme/一加 系统设置步骤：
                1. 设置 → 应用管理 → 找到「西大课栈」
                2. 点击「电池使用」→ 选择「允许后台活动」
                3. 返回 → 设置 → 电池 → 更多设置 → 关闭「后台冻结」「检测异常」
                4. 设置 → 应用管理 → 找到「西大课栈」→「权限」→ 允许「自启动」
                """.trimIndent()
            }
            manufacturer.contains("vivo") -> {
                """
                Vivo 系统设置步骤：
                1. 设置 → 电池 → 高耗电提醒 → 关闭
                2. 设置 → 更多设置 → 应用程序 → 找到「西大课栈」
                3. 点击「后台管理」→ 选择「允许后台运行」
                4. i管家 → 手机管理 → 启动管理 → 找到「西大课栈」→ 开启
                """.trimIndent()
            }
            manufacturer.contains("meizu") -> {
                """
                魅族 系统设置步骤：
                1. 设置 → 电池 → 找到「西大课栈」
                2. 选择「无限制」模式
                3. 设置 → 应用管理 → 找到「西大课栈」→ 权限 → 开启「自启动」
                """.trimIndent()
            }
            else -> {
                """
                通用设置步骤：
                1. 设置 → 应用管理 → 找到「西大课栈」
                2. 点击「电池」→ 选择「不受限制」或「允许后台活动」
                3. 开启应用的自启动权限
                4. 如果有省电管家，将「西大课栈」加入白名单
                """.trimIndent()
            }
        }
    }
}
