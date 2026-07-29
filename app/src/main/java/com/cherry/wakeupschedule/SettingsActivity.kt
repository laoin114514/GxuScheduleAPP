package com.cherry.wakeupschedule

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.BuildConfig
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
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

class SettingsActivity : AppCompatActivity() {
    
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
                val importService = ImportService(this@SettingsActivity)
                importService.importFromFile(selectedUri)
            }
        }
    }

    // 图片选择器 - 用于选择背景图片
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            saveAndProcessBackgroundImage(selectedUri)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        viewModel = ViewModelProvider(this)[CourseViewModel::class.java]
        settingsManager = SettingsManager(this)
        timeTableManager = TimeTableManager.getInstance(this)

        initViews()
        setupClickListeners()
        updateSettingsDisplay()
    }

    private fun initViews() {
        tvCurrentSemester = findViewById(R.id.tv_current_semester)
        tvDefaultWeek = findViewById(R.id.tv_default_week)
        tvDefaultAlarm = findViewById(R.id.tv_default_alarm)
        btnImportSchedule = findViewById(R.id.btn_import_schedule)
        btnExportSchedule = findViewById(R.id.btn_export_schedule)
        btnClearData = findViewById(R.id.btn_clear_data)
        btnModifySemester = findViewById(R.id.btn_modify_semester)
        btnModifyWeek = findViewById(R.id.btn_modify_week)
        btnModifyAlarm = findViewById(R.id.btn_modify_alarm)
        btnSchoolImport = findViewById(R.id.btn_school_import)
        btnBackgroundSettings = findViewById(R.id.btn_background_settings)
        btnAlarmSettings = findViewById(R.id.btn_alarm_settings)
        btnAbout = findViewById(R.id.btn_about)
        btnTimeTableSettings = findViewById(R.id.btn_time_table_settings)
        btnAppearanceSettings = findViewById(R.id.btn_appearance_settings)
        btnColorTheme = findViewById(R.id.btn_color_theme)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        btnPermissionGuide = findViewById(R.id.btn_permission_guide)
        btnFeedback = findViewById(R.id.btn_feedback)
        switchUpdateRemind = findViewById<Switch>(R.id.switch_update_remind)
        switchHideHolidayCourses = findViewById<Switch>(R.id.switch_hide_holiday_courses)

        // 初始化更新服务
        updateService = com.cherry.wakeupschedule.service.UpdateService(this)
    }
    
    private fun setupClickListeners() {
        // 更新提醒开关监听器
        switchUpdateRemind.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
                if (isUpdatingSwitchState) {
                    return
                }
                settingsManager.setUpdateRemindEnabled(isChecked)
                Toast.makeText(
                    this@SettingsActivity,
                    if (isChecked) "已开启更新提醒" else "已关闭更新提醒",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        // 节假日隐藏课程开关监听器
        switchHideHolidayCourses.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
                if (isUpdatingSwitchState) {
                    return
                }
                settingsManager.setHideHolidayCourses(isChecked)
                Toast.makeText(
                    this@SettingsActivity,
                    if (isChecked) "已开启节假日隐藏课程" else "已关闭节假日隐藏课程",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
        
        btnSchoolImport.setOnClickListener {
            // 打开教务系统导入界面
            val intent = Intent(this, SchoolImportActivity::class.java)
            startActivity(intent)
        }
        
        btnImportSchedule.setOnClickListener {
            // Android 10+ 使用分区存储，不需要权限检查
            filePickerLauncher.launch("*/*")
        }
        
        btnExportSchedule.setOnClickListener {
            // 导出课程表为CSV
            exportToCsv()
        }
        
        btnClearData.setOnClickListener {
            // 显示二次确认对话框
            AlertDialog.Builder(this)
                .setTitle("确认清除数据")
                .setMessage("确定要清除所有课程数据吗？此操作不可恢复。")
                .setPositiveButton("确定清除") { _, _ ->
                    viewModel.clearAllCourses()
                    Toast.makeText(this, "所有课程数据已清除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        
        btnModifySemester.setOnClickListener {
            showSemesterDialog()
        }
        
        btnModifyWeek.setOnClickListener {
            showWeekDialog()
        }
        
        btnModifyAlarm.setOnClickListener {
            showAlarmDialog()
        }

        btnBackgroundSettings.setOnClickListener {
            showBackgroundDialog()
        }

        btnAlarmSettings.setOnClickListener {
            showAlarmSettingsDialog()
        }

        btnAppearanceSettings.setOnClickListener {
            showAppearanceSettingsDialog()
        }

        btnColorTheme.setOnClickListener {
            val names = com.cherry.wakeupschedule.ui.theme.ThemeManager.paletteNames().toTypedArray()
            val currentIndex = com.cherry.wakeupschedule.ui.theme.ThemeManager.getPaletteIndex(this)
            AlertDialog.Builder(this)
                .setTitle("选择主题色板")
                .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                    com.cherry.wakeupschedule.ui.theme.ThemeManager.setPaletteIndex(which, this)
                    recreate()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        btnCheckUpdate.setOnClickListener {
            updateService.manualUpdate()
        }

        btnPermissionGuide.setOnClickListener {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }

        btnFeedback.setOnClickListener {
            showFeedbackDialog()
        }

        btnTimeTableSettings.setOnClickListener {
            // 直接跳转到新的时间表编辑界面
            startActivity(Intent(this, TimeTableEditActivity::class.java))
        }
    }

    private fun showTimeTableSettingsDialog() {
        val currentMaxNodes = timeTableManager.getMaxNodes()
        val timeSlots = timeTableManager.getTimeSlots()

        val message = StringBuilder()
        message.appendLine("当前每天 ${currentMaxNodes} 节课")
        message.appendLine()
        message.appendLine("时间段列表：")
        timeSlots.take(currentMaxNodes).forEach { slot ->
            message.appendLine("第${slot.node}节: ${slot.startTime}-${slot.endTime}")
        }

        AlertDialog.Builder(this)
            .setTitle("时间表设置")
            .setMessage(message.toString())
            .setItems(arrayOf("编辑时间段", "设置每天节数", "重置为默认")) { _, which ->
                when (which) {
                    0 -> {
                        // 跳转到新的时间表编辑界面
                        startActivity(Intent(this, TimeTableEditActivity::class.java))
                    }
                    1 -> showMaxNodesDialog()
                    2 -> {
                        AlertDialog.Builder(this)
                            .setTitle("确认重置")
                            .setMessage("确定要重置为默认时间表吗？")
                            .setPositiveButton("确定") { _, _ ->
                        timeTableManager.resetToDefault()
                        App.instance.registerAllCourseNotifications()
                        Toast.makeText(this, "已重置为默认时间表", Toast.LENGTH_SHORT).show()
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

        AlertDialog.Builder(this)
            .setTitle("编辑时间段 (点击编辑)")
            .setItems(slotStrings) { _, which ->
                showEditTimeSlotDialog(timeSlots[which])
            }
            .setPositiveButton("添加新节次") { _, _ ->
                showAddTimeSlotDialog()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showEditTimeSlotDialog(timeSlot: TimeTableManager.TimeSlot) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_time_slot, null)
        val etNode = view.findViewById<android.widget.EditText>(R.id.et_node)
        val etStartTime = view.findViewById<android.widget.EditText>(R.id.et_start_time)
        val etEndTime = view.findViewById<android.widget.EditText>(R.id.et_end_time)

        etNode.setText(timeSlot.node.toString())
        etStartTime.setText(timeSlot.startTime)
        etEndTime.setText(timeSlot.endTime)

        AlertDialog.Builder(this)
            .setTitle("编辑第${timeSlot.node}节时间段")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val node = etNode.text.toString().toIntOrNull()
                val startTime = etStartTime.text.toString().trim()
                val endTime = etEndTime.text.toString().trim()

                if (node == null || node <= 0) {
                    Toast.makeText(this, "节次必须为正整数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 验证时间格式
                if (!isValidTimeFormat(startTime) || !isValidTimeFormat(endTime)) {
                    Toast.makeText(this, "时间格式不正确，请使用 HH:MM 格式", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 如果节次改变了，先删除旧的，再添加新的
                if (node != timeSlot.node) {
                    timeTableManager.removeTimeSlot(timeSlot.node)
                }
                timeTableManager.updateTimeSlot(node, startTime, endTime)
                App.instance.registerAllCourseNotifications()
                Toast.makeText(this, "第${node}节时间段已更新", Toast.LENGTH_SHORT).show()

                // 重新打开编辑器显示更新后的列表
                showTimeSlotsEditor()
            }
            .setNegativeButton("删除") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除第${timeSlot.node}节吗？")
                    .setPositiveButton("删除") { _, _ ->
                    timeTableManager.removeTimeSlot(timeSlot.node)
                    App.instance.registerAllCourseNotifications()
                    Toast.makeText(this, "第${timeSlot.node}节已删除", Toast.LENGTH_SHORT).show()
                }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun isValidTimeFormat(time: String): Boolean {
        val regex = Regex("^([0-1]?[0-9]|2[0-3]):([0-5][0-9])$")
        return regex.matches(time)
    }

    private fun showAddTimeSlotDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_time_slot, null)
        val etNode = view.findViewById<android.widget.EditText>(R.id.et_node)
        val etStartTime = view.findViewById<android.widget.EditText>(R.id.et_start_time)
        val etEndTime = view.findViewById<android.widget.EditText>(R.id.et_end_time)

        // 自动填充下一个节次
        val maxNode = timeTableManager.getTimeSlots().maxOfOrNull { it.node } ?: 0
        val nextNode = maxNode + 1
        etNode.setText(nextNode.toString())

        // 根据节次自动填充默认时间
        val defaultTimeSlot = TimeTableManager.getTimeSlot(nextNode)
        etStartTime.setText(defaultTimeSlot?.startTime ?: "08:00")
        etEndTime.setText(defaultTimeSlot?.endTime ?: "08:45")

        AlertDialog.Builder(this)
            .setTitle("添加新节次")
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val node = etNode.text.toString().toIntOrNull()
                val startTime = etStartTime.text.toString().trim()
                val endTime = etEndTime.text.toString().trim()

                if (node == null || node <= 0) {
                    Toast.makeText(this, "节次必须为正整数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 验证时间格式
                if (!isValidTimeFormat(startTime) || !isValidTimeFormat(endTime)) {
                    Toast.makeText(this, "时间格式不正确，请使用 HH:MM 格式", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                timeTableManager.addTimeSlot(node, startTime, endTime)
            App.instance.registerAllCourseNotifications()
            Toast.makeText(this, "第${node}节时间段已添加", Toast.LENGTH_SHORT).show()

                // 重新打开编辑器显示更新后的列表
                showTimeSlotsEditor()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMaxNodesDialog() {
        val currentMax = timeTableManager.getMaxNodes()
        val nodes = (4..16).map { "$it 节" }.toTypedArray()
        val currentIndex = (4..16).indexOf(currentMax).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("设置每天节数")
            .setSingleChoiceItems(nodes, currentIndex) { dialog, which ->
                val maxNodes = which + 4
                timeTableManager.setMaxNodes(maxNodes)

                // 如果新的节数大于当前时间段数量，自动添加缺失的时间段
                val currentSlots = timeTableManager.getTimeSlots()
                val maxNodeInSlots = currentSlots.maxOfOrNull { it.node } ?: 0
                if (maxNodes > maxNodeInSlots) {
                    for (node in (maxNodeInSlots + 1)..maxNodes) {
                        // 根据节次生成默认时间
                        val defaultTimeSlot = TimeTableManager.getTimeSlot(node)
                        timeTableManager.addTimeSlot(
                            node,
                            defaultTimeSlot?.startTime ?: "08:00",
                            defaultTimeSlot?.endTime ?: "08:45"
                        )
                    }
                }

                Toast.makeText(this, "每天节数已设置为 $maxNodes 节", Toast.LENGTH_SHORT).show()
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

        // 更新外观设置状态显示
        val backgroundText = if (settingsManager.getCustomBackgroundPath().isNotEmpty()) "图片背景" else ""
        if (backgroundText.isNotEmpty()) {
            btnBackgroundSettings.text = "背景设置 - $backgroundText"
        } else {
            btnBackgroundSettings.text = "背景设置"
        }
        btnAlarmSettings.text = "课前提醒 - ${if (settingsManager.isAlarmEnabled()) "开启" else "关闭"}"
        
        // 更新开关状态（不触发监听器提示）
        isUpdatingSwitchState = true
        switchUpdateRemind.isChecked = settingsManager.isUpdateRemindEnabled()
        switchHideHolidayCourses.isChecked = settingsManager.isHideHolidayCourses()
        isUpdatingSwitchState = false
    }
    
    private fun showSemesterDialog() {
        val semesters = settingsManager.getCustomSemesters().toMutableList()

        AlertDialog.Builder(this)
            .setTitle("选择学期")
            .setItems(semesters.toTypedArray()) { _, which ->
                settingsManager.setCurrentSemester(semesters[which])
                updateSettingsDisplay()
                Toast.makeText(this, "学期设置已更新", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("新增学期") { _, _ ->
                showAddSemesterDialog()
            }
            .setNeutralButton("管理学期") { _, _ ->
                showManageSemestersDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddSemesterDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val editText = view.findViewById<android.widget.EditText>(R.id.et_input)
        editText.hint = "例如: 2024-2025学年 第一学期"

        AlertDialog.Builder(this)
            .setTitle("新增学期")
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val semesterName = editText.text.toString().trim()
                if (semesterName.isNotEmpty()) {
                    settingsManager.addCustomSemester(semesterName)
                    settingsManager.setCurrentSemester(semesterName)
                    updateSettingsDisplay()
                    Toast.makeText(this, "已添加并选中: $semesterName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "学期名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManageSemestersDialog() {
        val semesters = settingsManager.getCustomSemesters().toMutableList()
        val currentSemester = settingsManager.getCurrentSemester()

        if (semesters.isEmpty()) {
            Toast.makeText(this, "没有可管理的学期", Toast.LENGTH_SHORT).show()
            return
        }

        // 标记当前选中的学期
        val displaySemesters = semesters.map {
            if (it == currentSemester) "$it (当前)" else it
        }.toMutableList()

        // 创建ListView
        val listView = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displaySemesters)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("管理学期 (长按删除)")
            .setView(listView)
            .setPositiveButton("新增") { _, _ ->
                showAddSemesterDialog()
            }
            .setNegativeButton("关闭", null)
            .create()

        // 点击切换学期
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedSemester = semesters[position]
            settingsManager.setCurrentSemester(selectedSemester)
            updateSettingsDisplay()
            Toast.makeText(this, "已切换到: $selectedSemester", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // 长按删除学期
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val semesterToDelete = semesters[position]

            // 不能删除当前选中的学期
            if (semesterToDelete == currentSemester) {
                Toast.makeText(this, "不能删除当前正在使用的学期", Toast.LENGTH_SHORT).show()
                return@setOnItemLongClickListener true
            }

            AlertDialog.Builder(this)
                .setTitle("删除学期")
                .setMessage("确定要删除学期 \"$semesterToDelete\" 吗？\n注意：该学期下的所有课程数据也会被删除！")
                .setPositiveButton("删除") { _, _ ->
                    // 从列表中移除
                    semesters.removeAt(position)
                    displaySemesters.removeAt(position)

                    // 保存更新后的学期列表
                    settingsManager.saveCustomSemesters(semesters)

                    // 删除该学期的课程数据
                    deleteCoursesForSemester(semesterToDelete)

                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "已删除: $semesterToDelete", Toast.LENGTH_SHORT).show()

                    // 如果列表为空，关闭对话框
                    if (semesters.isEmpty()) {
                        dialog.dismiss()
                    }
                }
                .setNegativeButton("取消", null)
                .show()

            true
        }

        dialog.show()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun deleteCoursesForSemester(semester: String) {
        // 获取该学期的所有课程并删除
        val courseDataManager = com.cherry.wakeupschedule.service.CourseDataManager.getInstance(this)
        @Suppress("UNUSED_VARIABLE")
        val allCourses = courseDataManager.getAllCourses()

        // 删除与该学期相关的课程
        // 注意：这里假设课程数据中没有直接存储学期信息
        // 如果需要按学期删除，需要在Course模型中添加学期字段

        // 更新小组件
        ScheduleWidgetUpdateService.triggerUpdate(this)
    }
    
    private fun showWeekDialog() {
        val options = arrayOf("设置学期开始日期（自动计算当前周）", "设置当前周")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("周次设置")
            .setItems(options) { _, which ->
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
        if (currentStartDate > 0) {
            calendar.timeInMillis = currentStartDate
        }

        android.app.DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedCalendar = Calendar.getInstance()
                selectedCalendar.set(year, month, dayOfMonth, 0, 0, 0)
                selectedCalendar.set(Calendar.MILLISECOND, 0)

                settingsManager.setSemesterStartDate(selectedCalendar.timeInMillis)

                // 计算当前周
                val now = System.currentTimeMillis()
                val diffMillis = now - selectedCalendar.timeInMillis
                val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
                val currentWeek = (diffDays / 7) + 1

                Toast.makeText(this, "学期开始日期已设置，当前为第${currentWeek.coerceIn(1, 20)}周", Toast.LENGTH_LONG).show()
                updateSettingsDisplay()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showDefaultWeekPicker() {
        val weeks = (1..20).map { "第${it}周" }.toTypedArray()
        val currentWeek = settingsManager.getDefaultWeek()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择默认显示周")
            .setSingleChoiceItems(weeks, currentWeek - 1) { dialog, which ->
                settingsManager.setDefaultWeek(which + 1)
                Toast.makeText(this, "默认显示周已设置为第${which + 1}周", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCurrentWeekPicker() {
        val weeks = (1..20).map { "第${it}周" }.toTypedArray()

        // 计算当前周
        val semesterStartDate = settingsManager.getSemesterStartDate()
        val currentWeek = if (semesterStartDate > 0) {
            val now = System.currentTimeMillis()
            val diffMillis = now - semesterStartDate
            val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
            (diffDays / 7) + 1
        } else {
            settingsManager.getDefaultWeek()
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置当前周（将调整学期开始日期）")
            .setSingleChoiceItems(weeks, currentWeek.coerceIn(1, 20) - 1) { dialog, which ->
                val selectedWeek = which + 1

                // 根据选择的周次反推学期开始日期
                val now = System.currentTimeMillis()
                val daysToSubtract = (selectedWeek - 1) * 7L
                val startDate = now - (daysToSubtract * 24 * 60 * 60 * 1000)

                settingsManager.setSemesterStartDate(startDate)
                settingsManager.setDefaultWeek(selectedWeek)

                Toast.makeText(this, "当前周已设置为第${selectedWeek}周", Toast.LENGTH_SHORT).show()
                updateSettingsDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showAlarmDialog() {
        val alarmTimes = arrayOf("提前5分钟", "提前10分钟", "提前15分钟", "提前20分钟", "提前30分钟")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择默认闹钟提醒时间")
            .setItems(alarmTimes) { _, which ->
                val minutes = when (which) {
                    0 -> 5
                    1 -> 10
                    2 -> 15
                    3 -> 20
                    4 -> 30
                    else -> 15
                }
                settingsManager.setDefaultAlarmMinutes(minutes)
                updateSettingsDisplay()
                Toast.makeText(this, "闹钟设置已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showBackgroundDialog() {
        val options = arrayOf("颜色背景", "图片背景")

        AlertDialog.Builder(this)
            .setTitle("选择应用背景")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBackgroundThemePicker()
                    1 -> imagePickerLauncher.launch("image/*")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBackgroundThemePicker() {
        val themeNames = arrayOf("默认")
        val currentIndex = 0

        AlertDialog.Builder(this)
            .setTitle("选择背景颜色")
            .setSingleChoiceItems(themeNames, currentIndex) { dialog, _ ->
                applyBackgroundSettings()
                updateSettingsDisplay()
                Toast.makeText(this, "已设置为默认背景", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveAndProcessBackgroundImage(uri: Uri) {
        try {
            // 读取并处理图片
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            
            if (bitmap == null) {
                Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show()
                return
            }

            // 保存处理后的图片
            val fileName = "custom_bg_${System.currentTimeMillis()}.jpg"
            val file = File(filesDir, fileName)
            
            FileOutputStream(file).use { output ->
                // 压缩保存，质量80%
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            }

            // 保存路径并设置背景类型
            settingsManager.setCustomBackgroundPath(file.absolutePath)

            // 显示预览
            showBackgroundPreview(file.absolutePath)

        } catch (e: Exception) {
            Toast.makeText(this, "保存背景图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBackgroundPreview(imagePath: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_background_preview, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.iv_preview)

        // 加载图片
        val bitmap = BitmapFactory.decodeFile(imagePath)
        imageView.setImageBitmap(bitmap)

        AlertDialog.Builder(this)
            .setTitle("背景预览")
            .setView(dialogView)
            .setPositiveButton("应用") { _, _ ->
                applyBackgroundSettings()
                Toast.makeText(this, "背景设置已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("重新选择") { _, _ ->
                // 删除已保存的图片
                File(imagePath).delete()
                settingsManager.setCustomBackgroundPath("")
                imagePickerLauncher.launch("image/*")
            }
            .setNeutralButton("取消") { _, _ ->
                // 删除已保存的图片
                File(imagePath).delete()
                settingsManager.setCustomBackgroundPath("")
            }
            .setCancelable(false)
            .show()
    }

    private fun showSolidColorPicker() {
        val colors = arrayOf(
            "白色" to android.graphics.Color.WHITE,
            "浅灰" to android.graphics.Color.parseColor("#F5F5F5"),
            "浅蓝" to android.graphics.Color.parseColor("#E3F2FD"),
            "浅绿" to android.graphics.Color.parseColor("#E8F5E9"),
            "浅黄" to android.graphics.Color.parseColor("#FFFDE7"),
            "浅粉" to android.graphics.Color.parseColor("#FCE4EC"),
            "浅紫" to android.graphics.Color.parseColor("#F3E5F5"),
            "米色" to android.graphics.Color.parseColor("#FFF8E1"),
            "天蓝" to android.graphics.Color.parseColor("#E0F7FA"),
            "薄荷绿" to android.graphics.Color.parseColor("#E0F2F1")
        )

        val colorNames = colors.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择背景颜色")
            .setItems(colorNames) { _, which ->
                settingsManager.setCustomBackgroundPath("")
                applyBackgroundSettings()
                Toast.makeText(this, "已设置为${colors[which].first}背景", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showAppearanceSettingsDialog() {
        val options = arrayOf(
            "课程卡片透明度 (${(settingsManager.getCourseCardAlpha() * 100).toInt()}%)",
            "显示非本周课程 (${if (settingsManager.isShowNonCurrentWeekCourses()) "开启" else "关闭"})",
            "非本周课程透明度 (${(settingsManager.getNonCurrentWeekAlpha() * 100).toInt()}%)"
        )

        AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
            .setTitle("课程卡片透明度")
            .setSingleChoiceItems(alphas, currentIndex) { dialog, which ->
                val alpha = (which + 2) * 0.1f
                settingsManager.setCourseCardAlpha(alpha)
                Toast.makeText(this, "课程卡片透明度已设置为 ${(alpha * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleShowNonCurrentWeekCourses() {
        val current = settingsManager.isShowNonCurrentWeekCourses()
        settingsManager.setShowNonCurrentWeekCourses(!current)
        Toast.makeText(this, "非本周课程显示已${if (!current) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
    }

    private fun showNonCurrentWeekAlphaDialog() {
        val alphas = (10..80 step 10).map { "$it%" }.toTypedArray()
        val currentAlpha = settingsManager.getNonCurrentWeekAlpha()
        val currentIndex = ((currentAlpha * 100).toInt() / 10 - 1).coerceIn(0, alphas.size - 1)

        AlertDialog.Builder(this)
            .setTitle("非本周课程透明度")
            .setSingleChoiceItems(alphas, currentIndex) { dialog, which ->
                val alpha = (which + 1) * 0.1f
                settingsManager.setNonCurrentWeekAlpha(alpha)
                Toast.makeText(this, "非本周课程透明度已设置为 ${(alpha * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showAlarmSettingsDialog() {
        val alarmOptions = arrayOf("开启课前提醒", "关闭课前提醒", "电池优化设置")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("课前提醒设置")
            .setItems(alarmOptions) { _, which ->
                when (which) {
                    0 -> {
                        settingsManager.setAlarmEnabled(true)
                        applyAlarmSettings()
                        Toast.makeText(this, "课前提醒已开启", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        settingsManager.setAlarmEnabled(false)
                        applyAlarmSettings()
                        Toast.makeText(this, "课前提醒已关闭", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showBatteryOptimizationDialog()
                }
            }
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatteryOptimizationDialog() {
        val options = arrayOf("请求关闭电池优化", "查看详细设置教程", "打开系统设置")

        AlertDialog.Builder(this)
            .setTitle("电池优化设置")
            .setMessage("为了确保课前提醒稳定推送，建议关闭电池优化。\n\n当前状态：${
                if (com.cherry.wakeupschedule.service.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this))
                    "已关闭电池优化 ✓"
                else
                    "未关闭电池优化（可能影响提醒）"
            }")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (!com.cherry.wakeupschedule.service.BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)) {
                            Toast.makeText(this, "请求失败，请手动设置", Toast.LENGTH_LONG).show()
                        }
                    }
                    1 -> showBatteryOptimizationInstructions()
                    2 -> com.cherry.wakeupschedule.service.BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
                }
            }
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showBatteryOptimizationInstructions() {
        val instructions = com.cherry.wakeupschedule.service.BatteryOptimizationHelper.getDetailedInstructions(this)

        AlertDialog.Builder(this)
            .setTitle("设置教程")
            .setMessage(instructions)
            .setPositiveButton("打开设置") { _, _ ->
                com.cherry.wakeupschedule.service.BatteryOptimizationHelper.openManufacturerPowerSettings(this)
            }
            .setNegativeButton("关闭", null)
            .show()
    }
    
    private fun showAboutDialog() {
        val message = """
            西大课栈

            版本: ${BuildConfig.VERSION_NAME}

            功能: 自行摸索

            感谢使用！

            反馈: 2908451607@qq.com
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("关于应用")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showFeedbackDialog() {
        val options = arrayOf("GitHub Issue（功能需求、Bug反馈）", "发送邮件（其他反馈或联系开发者）")

        AlertDialog.Builder(this)
            .setTitle("选择反馈方式")
            .setItems(options) { _, which ->
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
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/laoin114514/GxuScheduleAPP/issues/new"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接，请检查网络连接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFeedbackEmail() {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("2908451607@qq.com"))
                putExtra(Intent.EXTRA_SUBJECT, "西大课栈 应用反馈")
                putExtra(Intent.EXTRA_TEXT, """
                    应用版本: ${BuildConfig.VERSION_NAME}
                    设备信息: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
                    Android版本: ${android.os.Build.VERSION.RELEASE}

                    ---

                    请在此处描述您的反馈或建议：
                """.trimIndent())
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "发送邮件"))
            } else {
                Toast.makeText(this, "未找到邮件应用", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开邮件应用: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun applyBackgroundSettings() {
        // 这里可以实现背景切换逻辑
        updateSettingsDisplay()
    }
    
    private fun applyFontSizeSettings() {
        // 字体大小功能已移除
        updateSettingsDisplay()
    }
    
    private fun applyAlarmSettings() {
        val alarmEnabled = settingsManager.isAlarmEnabled()
        try {
            val app = com.cherry.wakeupschedule.App.instance
            if (alarmEnabled) {
                app.registerAllCourseNotifications()
            } else {
                app.alarmService?.cancelAllReminders()
            }
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Failed to apply alarm settings", e)
        }
        updateSettingsDisplay()
    }
    
    private fun exportToCsv() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val courses = viewModel.getAllCourses()
                
                // 收集课程数据
                courses.collect { courseList ->
                    if (courseList.isEmpty()) {
                        Toast.makeText(this@SettingsActivity, "没有课程数据可以导出", Toast.LENGTH_SHORT).show()
                        return@collect
                    }
                    
                    // 创建CSV内容
                    val csvContent = StringBuilder()
                    csvContent.append("课程名称,教师姓名,上课地点,星期(1-7),开始节次,结束节次,开始周,结束周\n")
                    
                    courseList.forEach { course ->
                        csvContent.append("${course.name},${course.teacher},${course.classroom},")
                        csvContent.append("${course.dayOfWeek},${course.startTime},${course.endTime},")
                        csvContent.append("${course.startWeek},${course.endWeek}\n")
                    }
                    
                    // 保存到文件并分享
                    saveAndShareCsvFile(csvContent.toString(), courseList.size)
                    
                    // 取消收集，避免重复导出
                    return@collect
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun saveAndShareCsvFile(csvContent: String, courseCount: Int) {
        try {
            // 创建文件名
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "课程表_${timeStamp}.csv"
            
            // Android 10+ 使用应用专属目录
            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            
            // 写入文件
            FileOutputStream(file).use { fos ->
                fos.write(csvContent.toByteArray(Charsets.UTF_8))
            }
            
            // 创建分享Intent
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
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
            
            Toast.makeText(this, "已导出 ${courseCount} 门课程到 ${file.name}", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e("SettingsActivity", "保存CSV文件失败", e)
            
            // 回退到文本分享
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, "课程表导出")
            intent.putExtra(Intent.EXTRA_TEXT, csvContent)
            
            try {
                startActivity(Intent.createChooser(intent, "导出课程表"))
                Toast.makeText(this, "使用文本方式导出", Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "导出失败: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}