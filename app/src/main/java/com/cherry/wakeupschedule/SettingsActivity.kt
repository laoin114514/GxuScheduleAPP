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
import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.BuildConfig
import com.cherry.wakeupschedule.model.Course
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.cherry.wakeupschedule.service.ImportService
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import com.cherry.wakeupschedule.ui.component.SelectOption
import com.cherry.wakeupschedule.ui.component.SelectionDialog
import com.cherry.wakeupschedule.ui.component.StyledDialog
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
    private lateinit var btnThemeMode: TextView
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

    override fun onResume() {
        super.onResume()
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
        btnColorTheme.visibility = android.view.View.GONE
        btnThemeMode = findViewById(R.id.btn_theme_mode)
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
            StyledDialog.Builder(this)
                .title("确认清除数据")
                .message("确定要清除所有课程数据吗？此操作不可恢复。")
                .positiveButton("确定清除") {
                    viewModel.clearAllCourses()
                    Toast.makeText(this, "所有课程数据已清除", Toast.LENGTH_SHORT).show()
                }
                .negativeButton("取消")
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

        btnThemeMode.setOnClickListener {
            showThemeModeDialog()
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

        StyledDialog.Builder(this)
            .title("时间表设置")
            .message(message.toString())
            .items(arrayOf("编辑时间段", "设置每天节数", "重置为默认")) { which ->
                when (which) {
                    0 -> startActivity(Intent(this, TimeTableEditActivity::class.java))
                    1 -> showMaxNodesDialog()
                    2 -> {
                        StyledDialog.Builder(this)
                            .title("确认重置")
                            .message("确定要重置为默认时间表吗？")
                            .positiveButton("确定") {
                                timeTableManager.resetToDefault()
                                App.instance.registerAllCourseNotifications()
                                Toast.makeText(this, "已重置为默认时间表", Toast.LENGTH_SHORT).show()
                            }
                            .negativeButton("取消")
                            .show()
                    }
                }
            }
            .negativeButton("关闭")
            .show()
    }

    private fun showTimeSlotsEditor() {
        val timeSlots = timeTableManager.getTimeSlots().sortedBy { it.node }
        val slotStrings = timeSlots.map { "第${it.node}节: ${it.startTime}-${it.endTime}" }.toTypedArray()

        StyledDialog.Builder(this)
            .title("编辑时间段 (点击编辑)")
            .items(slotStrings) { which ->
                showEditTimeSlotDialog(timeSlots[which])
            }
            .positiveButton("添加新节次") {
                showAddTimeSlotDialog()
            }
            .negativeButton("关闭")
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

        StyledDialog.Builder(this)
            .title("编辑第${timeSlot.node}节时间段")
            .view(view)
            .positiveButton("保存") {
                val node = etNode.text.toString().toIntOrNull()
                val startTime = etStartTime.text.toString().trim()
                val endTime = etEndTime.text.toString().trim()

                if (node == null || node <= 0) {
                    Toast.makeText(this, "节次必须为正整数", Toast.LENGTH_SHORT).show()
                    return@positiveButton
                }

                if (!isValidTimeFormat(startTime) || !isValidTimeFormat(endTime)) {
                    Toast.makeText(this, "时间格式不正确，请使用 HH:MM 格式", Toast.LENGTH_SHORT).show()
                    return@positiveButton
                }

                if (node != timeSlot.node) {
                    timeTableManager.removeTimeSlot(timeSlot.node)
                }
                timeTableManager.updateTimeSlot(node, startTime, endTime)
                App.instance.registerAllCourseNotifications()
                Toast.makeText(this, "第${node}节时间段已更新", Toast.LENGTH_SHORT).show()

                showTimeSlotsEditor()
            }
            .negativeButton("删除") {
                StyledDialog.Builder(this)
                    .title("确认删除")
                    .message("确定要删除第${timeSlot.node}节吗？")
                    .positiveButton("删除") {
                        timeTableManager.removeTimeSlot(timeSlot.node)
                        App.instance.registerAllCourseNotifications()
                        Toast.makeText(this, "第${timeSlot.node}节已删除", Toast.LENGTH_SHORT).show()
                    }
                    .negativeButton("取消")
                    .show()
            }
            .neutralButton("取消")
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

        val maxNode = timeTableManager.getTimeSlots().maxOfOrNull { it.node } ?: 0
        val nextNode = maxNode + 1
        etNode.setText(nextNode.toString())

        val defaultTimeSlot = TimeTableManager.getTimeSlot(nextNode)
        etStartTime.setText(defaultTimeSlot?.startTime ?: "08:00")
        etEndTime.setText(defaultTimeSlot?.endTime ?: "08:45")

        StyledDialog.Builder(this)
            .title("添加新节次")
            .view(view)
            .positiveButton("添加") {
                val node = etNode.text.toString().toIntOrNull()
                val startTime = etStartTime.text.toString().trim()
                val endTime = etEndTime.text.toString().trim()

                if (node == null || node <= 0) {
                    Toast.makeText(this, "节次必须为正整数", Toast.LENGTH_SHORT).show()
                    return@positiveButton
                }

                if (!isValidTimeFormat(startTime) || !isValidTimeFormat(endTime)) {
                    Toast.makeText(this, "时间格式不正确，请使用 HH:MM 格式", Toast.LENGTH_SHORT).show()
                    return@positiveButton
                }

                timeTableManager.addTimeSlot(node, startTime, endTime)
                App.instance.registerAllCourseNotifications()
                Toast.makeText(this, "第${node}节时间段已添加", Toast.LENGTH_SHORT).show()

                showTimeSlotsEditor()
            }
            .negativeButton("取消")
            .show()
    }

    private fun showMaxNodesDialog() {
        val currentMax = timeTableManager.getMaxNodes()
        val options = (4..16).map { SelectOption(label = "$it 节") }
        val currentIndex = (4..16).indexOf(currentMax).coerceAtLeast(0)

        SelectionDialog.show(
            context = this,
            title = "设置每天节数",
            options = options,
            selectedIndex = currentIndex,
            onSelected = { index ->
                val maxNodes = index + 4
                timeTableManager.setMaxNodes(maxNodes)

                // 如果新的节数大于当前时间段数量，自动添加缺失的时间段
                val currentSlots = timeTableManager.getTimeSlots()
                val maxNodeInSlots = currentSlots.maxOfOrNull { it.node } ?: 0
                if (maxNodes > maxNodeInSlots) {
                    for (node in (maxNodeInSlots + 1)..maxNodes) {
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
            }
        )
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

        // 更新主题模式显示
        val themeModeLabel = when (settingsManager.getThemeMode()) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }
        btnThemeMode.text = "主题模式 - $themeModeLabel"

        // 更新开关状态（不触发监听器提示）
        isUpdatingSwitchState = true
        switchUpdateRemind.isChecked = settingsManager.isUpdateRemindEnabled()
        switchHideHolidayCourses.isChecked = settingsManager.isHideHolidayCourses()
        isUpdatingSwitchState = false
    }
    
    private fun showSemesterDialog() {
        val semesters = SemesterManager.getAll()
        if (semesters.isEmpty()) {
            Toast.makeText(this, "请先绑定教务账号", Toast.LENGTH_SHORT).show()
            return
        }

        val currentIndex = settingsManager.getCurrentSemesterIndex()
        val options = semesters.map { s ->
            SelectOption(
                label = "${s.label}  (${s.academicYear}学年 ${s.termName})",
                subtitle = if (s.sortOrder == currentIndex) "← 当前" else null
            )
        }

        SelectionDialog.show(
            context = this,
            title = "选择当前学期",
            options = options,
            selectedIndex = currentIndex.coerceAtLeast(0),
            onSelected = { index ->
                settingsManager.setCurrentSemesterIndex(index)
                // 切换学期后重新加载对应课表
                CourseDataManager.getInstance(this).switchSemester(semesters[index].id)
                updateSettingsDisplay()
                Toast.makeText(this, "已切换至: ${semesters[index].label}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showWeekDialog() {
        val options = listOf(
            SelectOption(label = "设置学期开始日期（自动计算当前周）"),
            SelectOption(label = "直接设置当前周")
        )

        SelectionDialog.show(
            context = this,
            title = "周次设置",
            options = options,
            selectedIndex = 0,
            onSelected = { index ->
                when (index) {
                    0 -> showSemesterStartDatePicker()
                    1 -> showCurrentWeekPicker()
                }
            }
        )
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
        val weeks = (1..20).map { SelectOption(label = "第${it}周") }
        val currentWeek = settingsManager.getDefaultWeek()

        SelectionDialog.show(
            context = this,
            title = "选择默认显示周",
            options = weeks,
            selectedIndex = currentWeek - 1,
            onSelected = { index ->
                settingsManager.setDefaultWeek(index + 1)
                Toast.makeText(this, "默认显示周已设置为第${index + 1}周", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showCurrentWeekPicker() {
        val weeks = (1..20).map { SelectOption(label = "第${it}周") }

        val semesterStartDate = settingsManager.getSemesterStartDate()
        val currentWeek = if (semesterStartDate > 0) {
            val now = System.currentTimeMillis()
            val diffMillis = now - semesterStartDate
            val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
            (diffDays / 7) + 1
        } else {
            settingsManager.getDefaultWeek()
        }

        SelectionDialog.show(
            context = this,
            title = "设置当前周（将调整学期开始日期）",
            options = weeks,
            selectedIndex = currentWeek.coerceIn(1, 20) - 1,
            onSelected = { index ->
                val selectedWeek = index + 1
                val daysToSubtract = (selectedWeek - 1) * 7L
                val startDate = System.currentTimeMillis() - (daysToSubtract * 24 * 60 * 60 * 1000)

                settingsManager.setSemesterStartDate(startDate)
                settingsManager.setDefaultWeek(selectedWeek)

                Toast.makeText(this, "当前周已设置为第${selectedWeek}周", Toast.LENGTH_SHORT).show()
                updateSettingsDisplay()
            }
        )
    }
    
    private fun showAlarmDialog() {
        val alarmOptions = (0..4).map { i ->
            val minutes = when (i) { 0 -> 5; 1 -> 10; 2 -> 15; 3 -> 20; 4 -> 30; else -> 15 }
            SelectOption(label = "提前${minutes}分钟")
        }
        val currentMinutes = settingsManager.getDefaultAlarmMinutes()
        val currentIndex = when (currentMinutes) {
            5 -> 0; 10 -> 1; 15 -> 2; 20 -> 3; 30 -> 4
            else -> 2
        }

        SelectionDialog.show(
            context = this,
            title = "选择默认闹钟提醒时间",
            options = alarmOptions,
            selectedIndex = currentIndex,
            onSelected = { index ->
                val minutes = when (index) {
                    0 -> 5; 1 -> 10; 2 -> 15; 3 -> 20; 4 -> 30
                    else -> 15
                }
                settingsManager.setDefaultAlarmMinutes(minutes)
                updateSettingsDisplay()
                Toast.makeText(this, "闹钟设置已更新", Toast.LENGTH_SHORT).show()
            }
        )
    }
    
    private fun showBackgroundDialog() {
        StyledDialog.Builder(this)
            .title("选择应用背景")
            .items(arrayOf("颜色背景", "图片背景")) { which ->
                when (which) {
                    0 -> showBackgroundThemePicker()
                    1 -> imagePickerLauncher.launch("image/*")
                }
            }
            .negativeButton("取消")
            .show()
    }

    private fun showBackgroundThemePicker() {
        val options = listOf(SelectOption(label = "默认"))

        SelectionDialog.show(
            context = this,
            title = "选择背景颜色",
            options = options,
            selectedIndex = 0,
            onSelected = { _ ->
                applyBackgroundSettings()
                updateSettingsDisplay()
                Toast.makeText(this, "已设置为默认背景", Toast.LENGTH_SHORT).show()
            }
        )
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

        val bitmap = BitmapFactory.decodeFile(imagePath)
        imageView.setImageBitmap(bitmap)

        StyledDialog.Builder(this)
            .title("背景预览")
            .view(dialogView)
            .positiveButton("应用") {
                applyBackgroundSettings()
                Toast.makeText(this, "背景设置已更新", Toast.LENGTH_SHORT).show()
            }
            .negativeButton("重新选择") {
                File(imagePath).delete()
                settingsManager.setCustomBackgroundPath("")
                imagePickerLauncher.launch("image/*")
            }
            .neutralButton("取消") {
                File(imagePath).delete()
                settingsManager.setCustomBackgroundPath("")
            }
            .show()
    }

    private fun showSolidColorPicker() {
        val colorNames = arrayOf(
            "白色", "浅灰", "浅蓝", "浅绿", "浅黄",
            "浅粉", "浅紫", "米色", "天蓝", "薄荷绿"
        )

        StyledDialog.Builder(this)
            .title("选择背景颜色")
            .items(colorNames) { which ->
                settingsManager.setCustomBackgroundPath("")
                applyBackgroundSettings()
                Toast.makeText(this, "已设置为${colorNames[which]}背景", Toast.LENGTH_SHORT).show()
            }
            .negativeButton("取消")
            .show()
    }
    
    private fun showAppearanceSettingsDialog() {
        val options = arrayOf(
            "课程卡片透明度 (${(settingsManager.getCourseCardAlpha() * 100).toInt()}%)",
            "显示非本周课程 (${if (settingsManager.isShowNonCurrentWeekCourses()) "开启" else "关闭"})",
            "非本周课程透明度 (${(settingsManager.getNonCurrentWeekAlpha() * 100).toInt()}%)"
        )

        StyledDialog.Builder(this)
            .title("外观设置")
            .items(options) { which ->
                when (which) {
                    0 -> showCourseCardAlphaDialog()
                    1 -> toggleShowNonCurrentWeekCourses()
                    2 -> showNonCurrentWeekAlphaDialog()
                }
            }
            .negativeButton("关闭")
            .show()
    }

    private fun showCourseCardAlphaDialog() {
        val options = (2..10).map { SelectOption(label = "${it * 10}%") }
        val currentAlpha = settingsManager.getCourseCardAlpha()
        val currentIndex = ((currentAlpha * 100).toInt() / 10 - 2).coerceIn(0, options.size - 1)

        SelectionDialog.show(
            context = this,
            title = "课程卡片透明度",
            options = options,
            selectedIndex = currentIndex,
            onSelected = { index ->
                val alpha = (index + 2) * 0.1f
                settingsManager.setCourseCardAlpha(alpha)
                Toast.makeText(this, "课程卡片透明度已设置为 ${(alpha * 100).toInt()}%", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun toggleShowNonCurrentWeekCourses() {
        val current = settingsManager.isShowNonCurrentWeekCourses()
        settingsManager.setShowNonCurrentWeekCourses(!current)
        Toast.makeText(this, "非本周课程显示已${if (!current) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
    }

    private fun showNonCurrentWeekAlphaDialog() {
        val options = (1..8).map { SelectOption(label = "${it * 10}%") }
        val currentAlpha = settingsManager.getNonCurrentWeekAlpha()
        val currentIndex = ((currentAlpha * 100).toInt() / 10 - 1).coerceIn(0, options.size - 1)

        SelectionDialog.show(
            context = this,
            title = "非本周课程透明度",
            options = options,
            selectedIndex = currentIndex,
            onSelected = { index ->
                val alpha = (index + 1) * 0.1f
                settingsManager.setNonCurrentWeekAlpha(alpha)
                Toast.makeText(this, "非本周课程透明度已设置为 ${(alpha * 100).toInt()}%", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showThemeModeDialog() {
        val modes = listOf("浅色", "深色", "跟随系统")
        val modeKeys = listOf("light", "dark", "system")
        val currentMode = settingsManager.getThemeMode()
        val currentIndex = modeKeys.indexOf(currentMode).coerceAtLeast(0)

        val options = modes.map { SelectOption(label = it) }
        SelectionDialog.show(
            context = this,
            title = "主题模式",
            options = options,
            selectedIndex = currentIndex,
            onSelected = { index ->
                val newMode = modeKeys[index]
                settingsManager.setThemeMode(newMode)
                applyThemeMode(newMode)
                updateSettingsDisplay()
                Toast.makeText(this, "已切换至: ${modes[index]}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }
    
    private fun showAlarmSettingsDialog() {
        StyledDialog.Builder(this)
            .title("课前提醒设置")
            .items(arrayOf("开启课前提醒", "关闭课前提醒", "电池优化设置")) { which ->
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
            .positiveButton("确定")
            .negativeButton("取消")
            .show()
    }

    private fun showBatteryOptimizationDialog() {
        val status = if (com.cherry.wakeupschedule.service.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this))
            "已关闭电池优化 ✓" else "未关闭电池优化（可能影响提醒）"

        StyledDialog.Builder(this)
            .title("电池优化设置")
            .message("为了确保课前提醒稳定推送，建议关闭电池优化。\n\n当前状态：$status")
            .items(arrayOf("请求关闭电池优化", "查看详细设置教程", "打开系统设置")) { which ->
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
            .positiveButton("确定")
            .show()
    }

    private fun showBatteryOptimizationInstructions() {
        val instructions = com.cherry.wakeupschedule.service.BatteryOptimizationHelper.getDetailedInstructions(this)

        StyledDialog.Builder(this)
            .title("设置教程")
            .message(instructions)
            .positiveButton("打开设置") {
                com.cherry.wakeupschedule.service.BatteryOptimizationHelper.openManufacturerPowerSettings(this)
            }
            .negativeButton("关闭")
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

        StyledDialog.Builder(this)
            .title("关于应用")
            .message(message)
            .positiveButton("确定")
            .show()
    }

    private fun showFeedbackDialog() {
        StyledDialog.Builder(this)
            .title("选择反馈方式")
            .items(arrayOf("GitHub Issue（功能需求、Bug反馈）", "发送邮件（其他反馈或联系开发者）")) { which ->
                when (which) {
                    0 -> openGitHubIssues()
                    1 -> sendFeedbackEmail()
                }
            }
            .negativeButton("取消")
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
                        val weekList = Course.bitmapToWeekList(course.weekBitmap)
                        csvContent.append("${weekList.firstOrNull() ?: 1},${weekList.lastOrNull() ?: 16}\n")
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