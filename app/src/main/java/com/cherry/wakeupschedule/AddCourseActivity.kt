package com.cherry.wakeupschedule

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.component.StyledDialog
import android.graphics.drawable.GradientDrawable

class AddCourseActivity : AppCompatActivity() {

    private lateinit var viewModel: CourseViewModel
    private lateinit var settingsManager: SettingsManager

    private lateinit var etCourseName: EditText
    private lateinit var etTeacher: EditText
    private lateinit var etLocation: EditText
    private lateinit var spinnerWeekDay: Spinner
    private lateinit var spinnerStartTime: Spinner
    private lateinit var spinnerEndTime: Spinner
    private lateinit var spinnerStartWeek: Spinner
    private lateinit var spinnerEndWeek: Spinner
    private lateinit var spinnerWeekType: Spinner
    private lateinit var btnCancel: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var colorIndicator: View
    private lateinit var btnSelectColor: TextView

    private var isEditMode = false
    private var existingCourse: Course? = null
    private var selectedColor: Int = 0xFF6200EE.toInt()

    private val courseColors: IntArray
        get() = ThemeManager.getCourseColors()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_add_course)
            android.util.Log.d("AddCourseActivity", "布局设置完成")

            settingsManager = SettingsManager(this)
            
            val application = this.applicationContext as android.app.Application
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
            viewModel = ViewModelProvider(this, factory)[CourseViewModel::class.java]
            android.util.Log.d("AddCourseActivity", "ViewModel初始化完成")

            initViews()
            setupSpinners()
            setupClickListeners()
            // 设置默认颜色为当前主题的第一个颜色
            selectedColor = courseColors.first()
            setupColorPicker()
            android.util.Log.d("AddCourseActivity", "视图初始化完成")

            existingCourse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra("course", Course::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra("course") as? Course
            }
            if (existingCourse != null) {
                isEditMode = true
                populateCourseData(existingCourse!!)
                android.util.Log.d("AddCourseActivity", "编辑模式")
            } else {
                android.util.Log.d("AddCourseActivity", "添加模式")
            }
        } catch (e: Exception) {
            android.util.Log.e("AddCourseActivity", "初始化失败", e)
            e.printStackTrace()
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun populateCourseData(course: Course) {
        etCourseName.setText(course.name)
        etTeacher.setText(course.teacher)
        etLocation.setText(course.classroom)
        spinnerWeekDay.setSelection(course.dayOfWeek - 1)
        spinnerStartTime.setSelection(course.startTime - 1)
        spinnerEndTime.setSelection(course.endTime - 1)
        spinnerStartWeek.setSelection(course.startWeek - 1)
        spinnerEndWeek.setSelection(course.endWeek - 1)
        spinnerWeekType.setSelection(course.weekType)
        selectedColor = if (course.color != 0) course.color else courseColors[(course.id % courseColors.size).toInt()]
        updateColorIndicator()
    }

    private fun updateColorIndicator() {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f
            setColor(selectedColor)
        }
        colorIndicator.background = drawable
    }

    private fun setupColorPicker() {
        updateColorIndicator()
        btnSelectColor.setOnClickListener {
            showColorPickerDialog()
        }
        colorIndicator.setOnClickListener {
            showColorPickerDialog()
        }
    }

    private fun showColorPickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val gridLayout = dialogView.findViewById<GridLayout>(R.id.gl_colors)

        val dialog = StyledDialog.Builder(this)
            .view(dialogView)
            .show()

        val itemSize = (resources.displayMetrics.widthPixels - 80) / 4
        val spacing = 16

        for (color in courseColors) {
            val colorView = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(itemSize, itemSize).apply {
                    setMargins(spacing, spacing, spacing, spacing)
                }
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(color)
                    if (color == selectedColor) {
                        setStroke(4, Color.BLACK)
                    }
                }
                background = drawable
                setOnClickListener {
                    selectedColor = color
                    updateColorIndicator()
                    dialog.dismiss()
                }
            }
            gridLayout.addView(colorView)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initViews() {
        try {
            etCourseName = findViewById(R.id.et_course_name)
            etTeacher = findViewById(R.id.et_teacher)
            etLocation = findViewById(R.id.et_location)
            spinnerWeekDay = findViewById(R.id.spinner_week_day)
            spinnerStartTime = findViewById(R.id.spinner_start_time)
            spinnerEndTime = findViewById(R.id.spinner_end_time)
            spinnerStartWeek = findViewById(R.id.spinner_start_week)
            spinnerEndWeek = findViewById(R.id.spinner_end_week)
            spinnerWeekType = findViewById(R.id.spinner_week_type)
            btnCancel = findViewById(R.id.btn_cancel)
            btnSave = findViewById(R.id.btn_save)
            btnBack = findViewById(R.id.btn_back)
            colorIndicator = findViewById(R.id.color_indicator)
            btnSelectColor = findViewById(R.id.btn_select_color)
        } catch (e: Exception) {
            android.util.Log.e("AddCourseActivity", "初始化视图失败", e)
            throw e
        }
    }

    private fun setupSpinners() {
        val weekDays = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        spinnerWeekDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weekDays)

        val timeSlots = (1..12).map { "第${it}节" }.toTypedArray()
        spinnerStartTime.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, timeSlots)
        spinnerEndTime.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, timeSlots)
        spinnerEndTime.setSelection(1)

        val weeks = (1..20).map { "第${it}周" }.toTypedArray()
        spinnerStartWeek.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weeks)
        spinnerEndWeek.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weeks)
        spinnerEndWeek.setSelection(19)

        val weekTypes = arrayOf("每周", "单周", "双周")
        spinnerWeekType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weekTypes)
        spinnerWeekType.setSelection(0)
    }

    private fun setupClickListeners() {
        btnCancel.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveCourse() }
        btnBack.setOnClickListener { finish() }
    }

    private fun saveCourse() {
        val courseName = etCourseName.text.toString().trim()
        val teacher = etTeacher.text.toString().trim()
        val location = etLocation.text.toString().trim()

        if (courseName.isEmpty()) {
            Toast.makeText(this, "请输入课程名称", Toast.LENGTH_SHORT).show()
            return
        }

        val weekDay = spinnerWeekDay.selectedItemPosition + 1
        val startTime = spinnerStartTime.selectedItemPosition + 1
        val endTime = spinnerEndTime.selectedItemPosition + 1
        val startWeek = spinnerStartWeek.selectedItemPosition + 1
        val endWeek = spinnerEndWeek.selectedItemPosition + 1
        val weekType = spinnerWeekType.selectedItemPosition

        if (endTime < startTime) {
            Toast.makeText(this, "结束节次不能早于开始节次", Toast.LENGTH_SHORT).show()
            return
        }

        if (endWeek < startWeek) {
            Toast.makeText(this, "结束周不能早于开始周", Toast.LENGTH_SHORT).show()
            return
        }

        val existingCourseExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("course", Course::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("course") as? Course
        }

        val course = if (existingCourseExtra != null) {
            existingCourseExtra.copy(
                name = courseName,
                teacher = teacher,
                classroom = location,
                dayOfWeek = weekDay,
                startTime = startTime,
                endTime = endTime,
                startWeek = startWeek,
                endWeek = endWeek,
                weekType = weekType,
                color = selectedColor
            )
        } else {
            Course(
                name = courseName,
                teacher = teacher,
                classroom = location,
                dayOfWeek = weekDay,
                startTime = startTime,
                endTime = endTime,
                startWeek = startWeek,
                endWeek = endWeek,
                weekType = weekType,
                color = selectedColor
            )
        }

        if (existingCourseExtra != null) {
            // 先取消旧课程的闹钟与已展示的通知，避免改名/改信息后旧通知残留
            App.instance.alarmService?.cancelCourseAlarm(existingCourseExtra)
            viewModel.updateCourse(course)
            Toast.makeText(this, "课程更新成功", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.addCourse(course)
            Toast.makeText(this, "课程添加成功", Toast.LENGTH_SHORT).show()
        }
        // updateCourse / addCourse 已在 ViewModel 内部同步调用 registerAllCourseNotifications，
        // 此处不再重复调用，避免 DB 写入竞态导致新课程遗漏
        ScheduleWidgetUpdateService.triggerUpdate(this)
        finish()
    }
}