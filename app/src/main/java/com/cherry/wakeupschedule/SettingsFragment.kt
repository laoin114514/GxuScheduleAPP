package com.cherry.wakeupschedule

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.cherry.wakeupschedule.BuildConfig
import com.cherry.wakeupschedule.service.ImportService
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private lateinit var viewModel: CourseViewModel
    private lateinit var settingsManager: SettingsManager
    private lateinit var tvCurrentSemester: TextView
    private lateinit var tvDefaultWeek: TextView
    private lateinit var tvDefaultAlarm: TextView
    private lateinit var btnImportSchedule: TextView
    private lateinit var btnExportSchedule: TextView
    private lateinit var btnClearData: TextView
    private lateinit var btnModifySemester: TextView
    private lateinit var btnModifyWeek: TextView
    private lateinit var btnModifyAlarm: TextView
    private lateinit var btnSchoolImport: TextView
    private lateinit var btnBackgroundSettings: TextView
    private lateinit var btnAlarmSettings: TextView
    private lateinit var btnAbout: TextView
    private lateinit var btnTimeTableSettings: TextView
    private lateinit var btnAppearanceSettings: TextView
    private lateinit var btnColorTheme: TextView
    private lateinit var btnCheckUpdate: TextView
    private lateinit var btnPermissionGuide: TextView
    private lateinit var btnFeedback: TextView
    private lateinit var switchUpdateRemind: Switch
    private lateinit var switchHideHolidayCourses: Switch
    private lateinit var timeTableManager: TimeTableManager
    private lateinit var updateService: com.cherry.wakeupschedule.service.UpdateService
    private var isUpdatingSwitchState = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            CoroutineScope(Dispatchers.Main).launch {
                val importService = ImportService(requireContext())
                importService.importFromFile(selectedUri)
            }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            saveAndProcessBackgroundImage(selectedUri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]
        settingsManager = SettingsManager(requireContext())
        timeTableManager = TimeTableManager.getInstance(requireContext())

        initViews(view)
        setupClickListeners()
        updateSettingsDisplay()
    }

    private fun initViews(view: View) {
        tvCurrentSemester = view.findViewById(R.id.tv_current_semester)
        tvDefaultWeek = view.findViewById(R.id.tv_default_week)
        tvDefaultAlarm = view.findViewById(R.id.tv_default_alarm)
        btnImportSchedule = view.findViewById(R.id.btn_import_schedule)
        btnExportSchedule = view.findViewById(R.id.btn_export_schedule)
        btnClearData = view.findViewById(R.id.btn_clear_data)
        btnModifySemester = view.findViewById(R.id.btn_modify_semester)
        btnModifyWeek = view.findViewById(R.id.btn_modify_week)
        btnModifyAlarm = view.findViewById(R.id.btn_modify_alarm)
        btnSchoolImport = view.findViewById(R.id.btn_school_import)
        btnBackgroundSettings = view.findViewById(R.id.btn_background_settings)
        btnAlarmSettings = view.findViewById(R.id.btn_alarm_settings)
        btnAbout = view.findViewById(R.id.btn_about)
        btnTimeTableSettings = view.findViewById(R.id.btn_time_table_settings)
        btnAppearanceSettings = view.findViewById(R.id.btn_appearance_settings)
        btnColorTheme = view.findViewById(R.id.btn_color_theme)
        btnCheckUpdate = view.findViewById(R.id.btn_check_update)
        btnPermissionGuide = view.findViewById(R.id.btn_permission_guide)
        btnFeedback = view.findViewById(R.id.btn_feedback)
        switchUpdateRemind = view.findViewById<Switch>(R.id.switch_update_remind)
        switchHideHolidayCourses = view.findViewById<Switch>(R.id.switch_hide_holiday_courses)

        updateService = com.cherry.wakeupschedule.service.UpdateService(requireContext())
    }

    private fun setupClickListeners() {
        switchUpdateRemind.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
                if (isUpdatingSwitchState) return
                settingsManager.setUpdateRemindEnabled(isChecked)
                Toast.makeText(requireContext(),
                    if (isChecked) "已开启更新提醒" else "已关闭更新提醒",
                    Toast.LENGTH_SHORT).show()
            }
        })

        switchHideHolidayCourses.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
                if (isUpdatingSwitchState) return
                settingsManager.setHideHolidayCourses(isChecked)
                Toast.makeText(requireContext(),
                    if (isChecked) "已开启节假日隐藏课程" else "已关闭节假日隐藏课程",
                    Toast.LENGTH_SHORT).show()
            }
        })

        btnSchoolImport.setOnClickListener {
            startActivity(Intent(requireContext(), SchoolImportActivity::class.java))
        }

        btnImportSchedule.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        btnExportSchedule.setOnClickListener {
            exportToCsv()
        }

        btnClearData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("确认清除数据")
                .setMessage("确定要清除所有课程数据吗？此操作不可恢复。")
                .setPositiveButton("确定清除") { _, _ ->
                    viewModel.clearAllCourses()
                    Toast.makeText(requireContext(), "所有课程数据已清除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnModifySemester.setOnClickListener { showSemesterDialog() }
        btnModifyWeek.setOnClickListener { showWeekDialog() }
        btnModifyAlarm.setOnClickListener { showAlarmDialog() }
        btnBackgroundSettings.setOnClickListener { showBackgroundDialog() }
        btnAlarmSettings.setOnClickListener { showAlarmSettingsDialog() }
        btnAppearanceSettings.setOnClickListener { showAppearanceSettingsDialog() }
        btnColorTheme.setOnClickListener {
            startActivity(Intent(requireContext(), ColorThemePickerActivity::class.java))
        }
        btnAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
        btnCheckUpdate.setOnClickListener { updateService.manualUpdate() }
        btnPermissionGuide.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionGuideActivity::class.java))
        }
        btnFeedback.setOnClickListener { showFeedbackDialog() }
        btnTimeTableSettings.setOnClickListener {
            startActivity(Intent(requireContext(), TimeTableEditActivity::class.java))
        }
    }

    // ─── Dialog methods (same as SettingsActivity, using requireContext()) ───

    private fun showSemesterDialog() {
        val semesters = settingsManager.getCustomSemesters().toMutableList()
        AlertDialog.Builder(requireContext())
            .setTitle("选择学期")
            .setItems(semesters.toTypedArray()) { _, which ->
                settingsManager.setCurrentSemester(semesters[which])
                updateSettingsDisplay()
                Toast.makeText(requireContext(), "学期设置已更新", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("新增学期") { _, _ -> showAddSemesterDialog() }
            .setNeutralButton("管理学期") { _, _ -> showManageSemestersDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddSemesterDialog() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_text, null)
        val editText = view.findViewById<android.widget.EditText>(R.id.et_input)
        editText.hint = "例如: 2024-2025学年 第一学期"
        AlertDialog.Builder(requireContext())
            .setTitle("新增学期")
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    settingsManager.addCustomSemester(name)
                    settingsManager.setCurrentSemester(name)
                    updateSettingsDisplay()
                    Toast.makeText(requireContext(), "已添加并选中: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "学期名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManageSemestersDialog() {
        val semesters = settingsManager.getCustomSemesters().toMutableList()
        val currentSemester = settingsManager.getCurrentSemester()
        if (semesters.isEmpty()) {
            Toast.makeText(requireContext(), "没有可管理的学期", Toast.LENGTH_SHORT).show()
            return
        }
        val displaySemesters = semesters.map {
            if (it == currentSemester) "$it (当前)" else it
        }.toMutableList()
        val listView = ListView(requireContext())
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, displaySemesters)
        listView.adapter = adapter
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("管理学期 (长按删除)")
            .setView(listView)
            .setPositiveButton("新增") { _, _ -> showAddSemesterDialog() }
            .setNegativeButton("关闭", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            settingsManager.setCurrentSemester(semesters[position])
            updateSettingsDisplay()
            Toast.makeText(requireContext(), "已切换到: ${semesters[position]}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val toDelete = semesters[position]
            if (toDelete == currentSemester) {
                Toast.makeText(requireContext(), "不能删除当前正在使用的学期", Toast.LENGTH_SHORT).show()
                return@setOnItemLongClickListener true
            }
            AlertDialog.Builder(requireContext())
                .setTitle("删除学期")
                .setMessage("确定要删除学期 \"$toDelete\" 吗？\n注意：该学期下的所有课程数据也会被删除！")
                .setPositiveButton("删除") { _, _ ->
                    semesters.removeAt(position)
                    displaySemesters.removeAt(position)
                    settingsManager.saveCustomSemesters(semesters)
                    deleteCoursesForSemester(toDelete)
                    adapter.notifyDataSetChanged()
                    Toast.makeText(requireContext(), "已删除: $toDelete", Toast.LENGTH_SHORT).show()
                    if (semesters.isEmpty()) dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        dialog.show()
    }

    private fun showWeekDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("周次设置")
            .setItems(arrayOf("设置学期开始日期（自动计算当前周）", "设置当前周")) { _, which ->
                when (which) {
                    0 -> showSemesterStartDatePicker()
                    1 -> showCurrentWeekPicker()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSemesterStartDatePicker() {
        val calendar = Calendar.getInstance()
        val currentStartDate = settingsManager.getSemesterStartDate()
        if (currentStartDate > 0) calendar.timeInMillis = currentStartDate
        android.app.DatePickerDialog(requireContext(),
            { _, year, month, dayOfMonth ->
                val sel = Calendar.getInstance()
                sel.set(year, month, dayOfMonth, 0, 0, 0)
                sel.set(Calendar.MILLISECOND, 0)
                settingsManager.setSemesterStartDate(sel.timeInMillis)
                val now = System.currentTimeMillis()
                val diffDays = ((now - sel.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                val currentWeek = (diffDays / 7) + 1
                Toast.makeText(requireContext(),
                    "学期开始日期已设置，当前为第${currentWeek.coerceIn(1, 20)}周",
                    Toast.LENGTH_LONG).show()
                updateSettingsDisplay()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showCurrentWeekPicker() {
        val weeks = (1..20).map { "第${it}周" }.toTypedArray()
        val semesterStartDate = settingsManager.getSemesterStartDate()
        val currentWeek = if (semesterStartDate > 0) {
            val diffDays = ((System.currentTimeMillis() - semesterStartDate) / (1000 * 60 * 60 * 24)).toInt()
            (diffDays / 7) + 1
        } else {
            settingsManager.getDefaultWeek()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("设置当前周（将调整学期开始日期）")
            .setSingleChoiceItems(weeks, currentWeek.coerceIn(1, 20) - 1) { dialog, which ->
                val selectedWeek = which + 1
                val daysToSubtract = (selectedWeek - 1) * 7L
                val startDate = System.currentTimeMillis() - (daysToSubtract * 24 * 60 * 60 * 1000)
                settingsManager.setSemesterStartDate(startDate)
                settingsManager.setDefaultWeek(selectedWeek)
                Toast.makeText(requireContext(), "当前周已设置为第${selectedWeek}周", Toast.LENGTH_SHORT).show()
                updateSettingsDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAlarmDialog() {
        val alarmTimes = arrayOf("提前5分钟", "提前10分钟", "提前15分钟", "提前20分钟", "提前30分钟")
        AlertDialog.Builder(requireContext())
            .setTitle("选择默认闹钟提醒时间")
            .setItems(alarmTimes) { _, which ->
                val minutes = when (which) {
                    0 -> 5; 1 -> 10; 2 -> 15; 3 -> 20; 4 -> 30; else -> 15
                }
                settingsManager.setDefaultAlarmMinutes(minutes)
                updateSettingsDisplay()
                Toast.makeText(requireContext(), "闹钟设置已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBackgroundDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("选择应用背景")
            .setItems(arrayOf("颜色背景", "图片背景")) { _, which ->
                when (which) {
                    0 -> showBackgroundThemePicker()
                    1 -> imagePickerLauncher.launch("image/*")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBackgroundThemePicker() {
        val currentIndex = settingsManager.getBackgroundThemeIndex()
        val themeNames = settingsManager.backgroundThemes.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("选择背景颜色")
            .setSingleChoiceItems(themeNames, currentIndex) { dialog, which ->
                settingsManager.setBackgroundThemeIndex(which)
                updateSettingsDisplay()
                Toast.makeText(requireContext(),
                    "已设置为: ${settingsManager.backgroundThemes[which].name}",
                    Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveAndProcessBackgroundImage(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "无法读取图片", Toast.LENGTH_SHORT).show()
                return
            }
            val fileName = "custom_bg_${System.currentTimeMillis()}.jpg"
            val file = File(requireContext().filesDir, fileName)
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            }
            settingsManager.setCustomBackgroundPath(file.absolutePath)
            settingsManager.setBackgroundTypeString("custom")
            showBackgroundPreview(file.absolutePath)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "保存背景图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBackgroundPreview(imagePath: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_background_preview, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.iv_preview)
        imageView.setImageBitmap(BitmapFactory.decodeFile(imagePath))
        AlertDialog.Builder(requireContext())
            .setTitle("背景预览")
            .setView(dialogView)
            .setPositiveButton("应用") { _, _ ->
                Toast.makeText(requireContext(), "背景设置已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("重新选择") { _, _ ->
                File(imagePath).delete()
                settingsManager.setCustomBackgroundPath("")
                imagePickerLauncher.launch("image/*")
            }
            .setNeutralButton("取消") { _, _ ->
                File(imagePath).delete()
                settingsManager.setCustomBackgroundPath("")
                settingsManager.setBackgroundThemeIndex(0)
            }
            .setCancelable(false)
            .show()
    }

    private fun showAppearanceSettingsDialog() {
        val options = arrayOf(
            "课程卡片透明度 (${(settingsManager.getCourseCardAlpha() * 100).toInt()}%)",
            "显示非本周课程 (${if (settingsManager.isShowNonCurrentWeekCourses()) "开启" else "关闭"})",
            "非本周课程透明度 (${(settingsManager.getNonCurrentWeekAlpha() * 100).toInt()}%)"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("外观设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCourseCardAlphaDialog()
                    1 -> toggleShowNonCurrentWeekCourses()
                    2 -> showNonCurrentWeekAlphaDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showCourseCardAlphaDialog() {
        val alphas = (20..100 step 10).map { "$it%" }.toTypedArray()
        val currentAlpha = settingsManager.getCourseCardAlpha()
        val currentIndex = ((currentAlpha * 100).toInt() / 10 - 2).coerceIn(0, alphas.size - 1)
        AlertDialog.Builder(requireContext())
            .setTitle("课程卡片透明度")
            .setSingleChoiceItems(alphas, currentIndex) { dialog, which ->
                settingsManager.setCourseCardAlpha((which + 2) * 0.1f)
                Toast.makeText(requireContext(),
                    "课程卡片透明度已设置为 ${(which + 2) * 10}%", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleShowNonCurrentWeekCourses() {
        val current = settingsManager.isShowNonCurrentWeekCourses()
        settingsManager.setShowNonCurrentWeekCourses(!current)
        Toast.makeText(requireContext(),
            "非本周课程显示已${if (!current) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
    }

    private fun showNonCurrentWeekAlphaDialog() {
        val alphas = (10..80 step 10).map { "$it%" }.toTypedArray()
        val currentAlpha = settingsManager.getNonCurrentWeekAlpha()
        val currentIndex = ((currentAlpha * 100).toInt() / 10 - 1).coerceIn(0, alphas.size - 1)
        AlertDialog.Builder(requireContext())
            .setTitle("非本周课程透明度")
            .setSingleChoiceItems(alphas, currentIndex) { dialog, which ->
                settingsManager.setNonCurrentWeekAlpha((which + 1) * 0.1f)
                Toast.makeText(requireContext(),
                    "非本周课程透明度已设置为 ${(which + 1) * 10}%", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAlarmSettingsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("课前提醒设置")
            .setItems(arrayOf("开启课前提醒", "关闭课前提醒", "电池优化设置")) { _, which ->
                when (which) {
                    0 -> {
                        settingsManager.setAlarmEnabled(true)
                        applyAlarmSettings()
                        Toast.makeText(requireContext(), "课前提醒已开启", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        settingsManager.setAlarmEnabled(false)
                        applyAlarmSettings()
                        Toast.makeText(requireContext(), "课前提醒已关闭", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showBatteryOptimizationDialog()
                }
            }
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatteryOptimizationDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("电池优化设置")
            .setMessage("为了确保课前提醒稳定推送，建议关闭电池优化。\n\n当前状态：${
                if (com.cherry.wakeupschedule.service.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx))
                    "已关闭电池优化 ✓" else "未关闭电池优化（可能影响提醒）"
            }")
            .setItems(arrayOf("请求关闭电池优化", "查看详细设置教程", "打开系统设置")) { _, which ->
                when (which) {
                    0 -> {
                        if (!com.cherry.wakeupschedule.service.BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(requireActivity())) {
                            Toast.makeText(ctx, "请求失败，请手动设置", Toast.LENGTH_LONG).show()
                        }
                    }
                    1 -> showBatteryOptimizationInstructions()
                    2 -> com.cherry.wakeupschedule.service.BatteryOptimizationHelper.openBatteryOptimizationSettings(requireActivity())
                }
            }
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showBatteryOptimizationInstructions() {
        val instructions = com.cherry.wakeupschedule.service.BatteryOptimizationHelper.getDetailedInstructions(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("设置教程")
            .setMessage(instructions)
            .setPositiveButton("打开设置") { _, _ ->
                com.cherry.wakeupschedule.service.BatteryOptimizationHelper.openManufacturerPowerSettings(requireActivity())
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showFeedbackDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("选择反馈方式")
            .setItems(arrayOf("GitHub Issue（功能需求、Bug反馈）", "发送邮件（其他反馈或联系开发者）")) { _, which ->
                when (which) {
                    0 -> openGitHubIssues()
                    1 -> sendFeedbackEmail()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openGitHubIssues() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/Yngu196/Schedule/issues/new")))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开链接，请检查网络连接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFeedbackEmail() {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("Yngu196@qq.com"))
                putExtra(Intent.EXTRA_SUBJECT, "Schedule 应用反馈")
                putExtra(Intent.EXTRA_TEXT, """
                    应用版本: ${BuildConfig.VERSION_NAME}
                    设备信息: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
                    Android版本: ${android.os.Build.VERSION.RELEASE}

                    ---

                    请在此处描述您的反馈或建议：
                """.trimIndent())
            }
            if (intent.resolveActivity(requireActivity().packageManager) != null) {
                startActivity(Intent.createChooser(intent, "发送邮件"))
            } else {
                Toast.makeText(requireContext(), "未找到邮件应用", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开邮件应用: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTimeTableSettingsDialog() {
        val currentMaxNodes = timeTableManager.getMaxNodes()
        val timeSlots = timeTableManager.getTimeSlots()
        val message = buildString {
            appendLine("当前每天 ${currentMaxNodes} 节课")
            appendLine()
            appendLine("时间段列表：")
            timeSlots.take(currentMaxNodes).forEach { slot ->
                appendLine("第${slot.node}节: ${slot.startTime}-${slot.endTime}")
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("时间表设置")
            .setMessage(message)
            .setItems(arrayOf("编辑时间段", "设置每天节数", "重置为默认")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(requireContext(), TimeTableEditActivity::class.java))
                    1 -> showMaxNodesDialog()
                    2 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("确认重置")
                            .setMessage("确定要重置为默认时间表吗？")
                            .setPositiveButton("确定") { _, _ ->
                                timeTableManager.resetToDefault()
                                App.instance.registerAllCourseNotifications()
                                Toast.makeText(requireContext(), "已重置为默认时间表", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showTimeSlotsEditor() {
        val timeSlots = timeTableManager.getTimeSlots().sortedBy { it.node }
        val slotStrings = timeSlots.map { "第${it.node}节: ${it.startTime}-${it.endTime}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("编辑时间段 (点击编辑)")
            .setItems(slotStrings) { _, which -> showEditTimeSlotDialog(timeSlots[which]) }
            .setPositiveButton("添加新节次") { _, _ -> showAddTimeSlotDialog() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showEditTimeSlotDialog(timeSlot: TimeTableManager.TimeSlot) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_time_slot, null)
        val etNode = view.findViewById<android.widget.EditText>(R.id.et_node)
        val etStartTime = view.findViewById<android.widget.EditText>(R.id.et_start_time)
        val etEndTime = view.findViewById<android.widget.EditText>(R.id.et_end_time)
        etNode.setText(timeSlot.node.toString())
        etStartTime.setText(timeSlot.startTime)
        etEndTime.setText(timeSlot.endTime)
        AlertDialog.Builder(requireContext())
            .setTitle("编辑第${timeSlot.node}节时间段")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val node = etNode.text.toString().toIntOrNull()
                val startTime = etStartTime.text.toString().trim()
                val endTime = etEndTime.text.toString().trim()
                if (node == null || node <= 0) {
                    Toast.makeText(requireContext(), "节次必须为正整数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!isValidTimeFormat(startTime) || !isValidTimeFormat(endTime)) {
                    Toast.makeText(requireContext(), "时间格式不正确，请使用 HH:MM 格式", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (node != timeSlot.node) timeTableManager.removeTimeSlot(timeSlot.node)
                timeTableManager.updateTimeSlot(node, startTime, endTime)
                App.instance.registerAllCourseNotifications()
                Toast.makeText(requireContext(), "第${node}节时间段已更新", Toast.LENGTH_SHORT).show()
                showTimeSlotsEditor()
            }
            .setNegativeButton("删除") { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle("确认删除")
                    .setMessage("确定要删除第${timeSlot.node}节吗？")
                    .setPositiveButton("删除") { _, _ ->
                        timeTableManager.removeTimeSlot(timeSlot.node)
                        App.instance.registerAllCourseNotifications()
                        Toast.makeText(requireContext(), "第${timeSlot.node}节已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun isValidTimeFormat(time: String): Boolean {
        return Regex("^([0-1]?[0-9]|2[0-3]):([0-5][0-9])$").matches(time)
    }

    private fun showAddTimeSlotDialog() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_time_slot, null)
        val etNode = view.findViewById<android.widget.EditText>(R.id.et_node)
        val etStartTime = view.findViewById<android.widget.EditText>(R.id.et_start_time)
        val etEndTime = view.findViewById<android.widget.EditText>(R.id.et_end_time)
        val nextNode = (timeTableManager.getTimeSlots().maxOfOrNull { it.node } ?: 0) + 1
        etNode.setText(nextNode.toString())
        val defaultSlot = TimeTableManager.getTimeSlot(nextNode)
        etStartTime.setText(defaultSlot?.startTime ?: "08:00")
        etEndTime.setText(defaultSlot?.endTime ?: "08:45")
        AlertDialog.Builder(requireContext())
            .setTitle("添加新节次")
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val node = etNode.text.toString().toIntOrNull()
                val startTime = etStartTime.text.toString().trim()
                val endTime = etEndTime.text.toString().trim()
                if (node == null || node <= 0) {
                    Toast.makeText(requireContext(), "节次必须为正整数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!isValidTimeFormat(startTime) || !isValidTimeFormat(endTime)) {
                    Toast.makeText(requireContext(), "时间格式不正确，请使用 HH:MM 格式", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                timeTableManager.addTimeSlot(node, startTime, endTime)
                App.instance.registerAllCourseNotifications()
                Toast.makeText(requireContext(), "第${node}节时间段已添加", Toast.LENGTH_SHORT).show()
                showTimeSlotsEditor()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMaxNodesDialog() {
        val currentMax = timeTableManager.getMaxNodes()
        val nodes = (4..16).map { "$it 节" }.toTypedArray()
        val currentIndex = (4..16).indexOf(currentMax).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("设置每天节数")
            .setSingleChoiceItems(nodes, currentIndex) { dialog, which ->
                val maxNodes = which + 4
                timeTableManager.setMaxNodes(maxNodes)
                val currentSlots = timeTableManager.getTimeSlots()
                val maxNodeInSlots = currentSlots.maxOfOrNull { it.node } ?: 0
                if (maxNodes > maxNodeInSlots) {
                    for (node in (maxNodeInSlots + 1)..maxNodes) {
                        val defaultSlot = TimeTableManager.getTimeSlot(node)
                        timeTableManager.addTimeSlot(node,
                            defaultSlot?.startTime ?: "08:00",
                            defaultSlot?.endTime ?: "08:45")
                    }
                }
                Toast.makeText(requireContext(), "每天节数已设置为 $maxNodes 节", Toast.LENGTH_SHORT).show()
                App.instance.registerAllCourseNotifications()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateSettingsDisplay() {
        tvCurrentSemester.text = settingsManager.getCurrentSemester()
        tvDefaultWeek.text = "第${settingsManager.getDefaultWeek()}周"
        tvDefaultAlarm.text = "提前${settingsManager.getDefaultAlarmMinutes()}分钟"
        val backgroundText = when (settingsManager.getBackgroundMode()) {
            SettingsManager.BackgroundType.IMAGE -> "图片背景"
            else -> settingsManager.getCurrentBackgroundTheme().name
        }
        btnBackgroundSettings.text = "背景设置 - $backgroundText"
        btnAlarmSettings.text = "课前提醒 - ${if (settingsManager.isAlarmEnabled()) "开启" else "关闭"}"
        isUpdatingSwitchState = true
        switchUpdateRemind.isChecked = settingsManager.isUpdateRemindEnabled()
        switchHideHolidayCourses.isChecked = settingsManager.isHideHolidayCourses()
        isUpdatingSwitchState = false
    }

    private fun deleteCoursesForSemester(semester: String) {
        val courseDataManager = com.cherry.wakeupschedule.service.CourseDataManager.getInstance(requireContext())
        courseDataManager.getAllCourses()
        ScheduleWidgetUpdateService.triggerUpdate(requireContext())
    }

    private fun applyAlarmSettings() {
        try {
            val app = App.instance
            if (settingsManager.isAlarmEnabled()) {
                app.registerAllCourseNotifications()
            } else {
                app.alarmService?.cancelAllReminders()
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Failed to apply alarm settings", e)
        }
        updateSettingsDisplay()
    }

    private fun exportToCsv() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val courses = viewModel.getAllCourses()
                courses.collect { courseList ->
                    if (courseList.isEmpty()) {
                        Toast.makeText(requireContext(), "没有课程数据可以导出", Toast.LENGTH_SHORT).show()
                        return@collect
                    }
                    val csvContent = buildString {
                        append("课程名称,教师姓名,上课地点,星期(1-7),开始节次,结束节次,开始周,结束周\n")
                        courseList.forEach { course ->
                            append("${course.name},${course.teacher},${course.classroom},")
                            append("${course.dayOfWeek},${course.startTime},${course.endTime},")
                            append("${course.startWeek},${course.endWeek}\n")
                        }
                    }
                    saveAndShareCsvFile(csvContent, courseList.size)
                    return@collect
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndShareCsvFile(csvContent: String, courseCount: Int) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "课程表_${timeStamp}.csv"
            val downloadsDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { fos ->
                fos.write(csvContent.toByteArray(Charsets.UTF_8))
            }
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "课程表导出")
                putExtra(Intent.EXTRA_TEXT, "导出了 ${courseCount} 门课程")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "导出课程表"))
            Toast.makeText(requireContext(), "已导出 ${courseCount} 门课程到 ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SettingsFragment", "保存CSV文件失败", e)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, "课程表导出")
            intent.putExtra(Intent.EXTRA_TEXT, csvContent)
            try {
                startActivity(Intent.createChooser(intent, "导出课程表"))
                Toast.makeText(requireContext(), "使用文本方式导出", Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "导出失败: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
