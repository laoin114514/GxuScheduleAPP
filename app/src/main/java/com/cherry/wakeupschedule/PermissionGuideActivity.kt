package com.cherry.wakeupschedule

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cherry.wakeupschedule.service.BatteryOptimizationHelper
import com.cherry.wakeupschedule.ui.theme.ThemeManager

class PermissionGuideActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnNotification: Button
    private lateinit var tvExactAlarmStatus: TextView
    private lateinit var btnExactAlarm: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnBattery: Button
    private lateinit var tvAutostartStatus: TextView
    private lateinit var btnAutostart: Button
    private lateinit var tvInstructions: TextView

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_permission_guide)

        initViews()
        setupClickListeners()
        updatePermissionStatus()
        updateInstructions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        btnRefresh = findViewById(R.id.btn_refresh)
        tvNotificationStatus = findViewById(R.id.tv_notification_status)
        btnNotification = findViewById(R.id.btn_notification)
        tvExactAlarmStatus = findViewById(R.id.tv_exact_alarm_status)
        btnExactAlarm = findViewById(R.id.btn_exact_alarm)
        tvBatteryStatus = findViewById(R.id.tv_battery_status)
        btnBattery = findViewById(R.id.btn_battery)
        tvAutostartStatus = findViewById(R.id.tv_autostart_status)
        btnAutostart = findViewById(R.id.btn_autostart)
        tvInstructions = findViewById(R.id.tv_instructions)
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnRefresh.setOnClickListener {
            updatePermissionStatus()
        }

        btnNotification.setOnClickListener {
            requestNotificationPermission()
        }

        btnExactAlarm.setOnClickListener {
            requestExactAlarmPermission()
        }

        btnBattery.setOnClickListener {
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
        }

        btnAutostart.setOnClickListener {
            BatteryOptimizationHelper.openManufacturerPowerSettings(this)
        }
    }

    private fun updatePermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            tvNotificationStatus.text = if (hasPermission) "已授权 ✓" else "未授权"
            tvNotificationStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (hasPermission) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )
            btnNotification.text = if (hasPermission) "已授权" else "去授权"
            btnNotification.isEnabled = !hasPermission
        } else {
            tvNotificationStatus.text = "Android 13 以下无需授权"
            tvNotificationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            btnNotification.text = "无需授权"
            btnNotification.isEnabled = false
        }

        // 精确闹钟权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val hasExactAlarm = alarmManager.canScheduleExactAlarms()
            tvExactAlarmStatus.text = if (hasExactAlarm) "已授权 ✓" else "未授权"
            tvExactAlarmStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (hasExactAlarm) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )
            btnExactAlarm.text = if (hasExactAlarm) "已授权" else "去授权"
            btnExactAlarm.isEnabled = !hasExactAlarm
        } else {
            tvExactAlarmStatus.text = "Android 12 以下无需授权"
            tvExactAlarmStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            btnExactAlarm.text = "无需授权"
            btnExactAlarm.isEnabled = false
        }

        val batteryOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        tvBatteryStatus.text = if (batteryOptimized) "已关闭 ✓" else "未关闭"
        tvBatteryStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (batteryOptimized) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        btnBattery.text = if (batteryOptimized) "已关闭" else "去关闭"
        btnBattery.isEnabled = !batteryOptimized

        val hasAutostartIntent = BatteryOptimizationHelper.getAutoStartIntent(this) != null
        tvAutostartStatus.text = if (hasAutostartIntent) "可设置" else "请手动设置"
        tvAutostartStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasAutostartIntent) android.R.color.holo_green_dark else android.R.color.holo_orange_dark
            )
        )
        btnAutostart.text = if (hasAutostartIntent) "去设置" else "查看教程"
        btnAutostart.isEnabled = true
    }

    private fun updateInstructions() {
        val manufacturer = BatteryOptimizationHelper.getManufacturerName()
        val instructions = BatteryOptimizationHelper.getDetailedInstructions(this)

        // 构建闹钟状态汇总
        val summaryParts = mutableListOf<String>()

        // 通知权限
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        summaryParts.add("通知权限: ${if (hasNotification) "✓" else "✗"}")

        // 精确闹钟
        val hasExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true
        summaryParts.add("精确闹钟: ${if (hasExactAlarm) "✓" else "✗"}")

        // 电池优化
        val batteryOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        summaryParts.add("电池优化: ${if (batteryOptimized) "已关闭 ✓" else "未关闭 ✗"}")

        // 省电模式
        if (BatteryOptimizationHelper.isPowerSaveModeEnabled(this)) {
            summaryParts.add("省电模式: 已开启 ✗ (建议关闭)")
        } else {
            summaryParts.add("省电模式: 已关闭 ✓")
        }

        val summary = "闹钟状态检查:\n${summaryParts.joinToString("\n")}\n\n" +
                "${getManufacturerChineseName(manufacturer)} 系统设置教程：\n\n$instructions"

        tvInstructions.text = summary
    }

    private fun getManufacturerChineseName(manufacturer: String): String {
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> "小米/红米/POCO"
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "华为/荣耀"
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> "OPPO/Realme/一加"
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "Vivo/iQOO"
            manufacturer.contains("meizu") -> "魅族"
            manufacturer.contains("samsung") -> "三星"
            manufacturer.contains("lenovo") -> "联想"
            else -> "通用"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            updatePermissionStatus()
        }
    }
}
