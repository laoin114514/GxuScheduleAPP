package com.cherry.wakeupschedule

import androidx.activity.result.contract.ActivityResultContracts
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.databinding.ActivityMainBinding
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.model.AppSettings
import com.cherry.wakeupschedule.model.BackupData
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.ImportService
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.util.DebugLogger
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import com.cherry.wakeupschedule.service.UpdateService
import com.google.gson.Gson
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: CourseViewModel
    private lateinit var settingsManager: SettingsManager
    private var displayWeek = 1  // 当前显示的周次
    private var currentWeek = 1  // 实际当前周（根据学期开始日期计算）
    private val dateFormat = SimpleDateFormat("yyyy/M/d", Locale.getDefault())
    private val weekFormat = SimpleDateFormat("EEE", Locale.getDefault())

    private val timeAxisViews = mutableListOf<LinearLayout>()

    private fun getCourseColors(): IntArray = settingsManager.getCourseColors()

    // 存储所有课程，用于检测冲突
    private var allCourses: List<Course> = emptyList()

    // 滑动相关变量
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val SWIPE_THRESHOLD = 80
    private val SWIPE_VELOCITY_THRESHOLD = 200

    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var countdownTickCount = 0

    // 视图状态："week"周视图，"day"日视图，"overview"课程全览
    private var currentViewState = "week"

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importFromFile(it) }
    }

    // 拖动相关
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var originalX = 0f
    private var originalY = 0f
    private val touchSlop = 8f

    override fun onCreate(savedInstanceState: Bundle?) {
        DebugLogger.init(this)
        // 在super.onCreate之前应用主题
        settingsManager = SettingsManager(this)
        applyTheme()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 请求通知权限（Android 13+）
        requestNotificationPermission()
        requestExactAlarmPermissionIfNeeded()

        currentWeek = calculateCurrentWeek()
        displayWeek = currentWeek

        val application = this.applicationContext as Application
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        viewModel = ViewModelProvider(this, factory)[CourseViewModel::class.java]

        setupViews()
        setupClickListeners()
        setupObservers()
        setupSwipeGesture()
        updateWeekDisplay()
        updateDateDisplay()
        // generateTimeAxis必须在applyBackgroundSettings之前执行，以便timeAxisViews被正确填充
        generateTimeAxis()
        applyBackgroundSettings()

        // 自动检查更新（不影响课表查看，每天最多一次）
        UpdateService(this).checkForUpdateSilently()
    }

    /**
     * 根据学期开始日期计算当前周
     */
    private fun calculateCurrentWeek(): Int {
        val semesterStartDate = settingsManager.getSemesterStartDate()
        if (semesterStartDate == 0L) {
            // 如果没有设置学期开始日期，使用默认值（假设当前是第一周）
            return 1
        }
        
        val now = System.currentTimeMillis()
        val diffMillis = now - semesterStartDate
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        val week = (diffDays / 7) + 1
        
        val totalWeeks = settingsManager.getTotalWeeks()
        return week.coerceIn(1, totalWeeks)
    }

    /**
     * 请求通知权限（Android 13+）
     */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = android.Manifest.permission.POST_NOTIFICATIONS
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, notificationPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(notificationPermission),
                    1001
                )
            }
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (alarmManager.canScheduleExactAlarms()) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun setupSwipeGesture() {
        // 为课程表容器设置触摸监听
        val courseContainer = binding.scrollView
        courseContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    // 不拦截移动事件，让 ScrollView 正常滚动
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = event.x - touchStartX
                    val diffY = event.y - touchStartY
                    val duration = event.eventTime - event.downTime

                    // 检查是否是快速水平滑动
                    if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY) * 2 &&
                        kotlin.math.abs(diffX) > SWIPE_THRESHOLD &&
                        duration < 300) {
                        if (diffX > 0) {
                            // 向右滑动 -> 上一周
                            switchToPreviousWeek()
                        } else {
                            // 向左滑动 -> 下一周
                            switchToNextWeek()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun switchToPreviousWeek() {
        if (displayWeek > 1) {
            animateWeekSwitch(isNext = false) {
                displayWeek--
                updateWeekDisplay()
                updateDateDisplay()
                viewModel.loadCoursesForWeek(displayWeek)
            }
        }
    }

    private fun switchToNextWeek() {
        if (displayWeek < settingsManager.getTotalWeeks()) {
            animateWeekSwitch(isNext = true) {
                displayWeek++
                updateWeekDisplay()
                updateDateDisplay()
                viewModel.loadCoursesForWeek(displayWeek)
            }
        }
    }

    private fun animateWeekSwitch(isNext: Boolean, onAnimationEnd: () -> Unit) {
        val scrollView = binding.scrollView

        // 滑出动画
        val slideOut = android.animation.ObjectAnimator.ofFloat(
            scrollView,
            "translationX",
            0f,
            if (isNext) -scrollView.width.toFloat() else scrollView.width.toFloat()
        )
        slideOut.duration = 150
        slideOut.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // 执行周切换
                onAnimationEnd()

                // 重置位置并滑入
                scrollView.translationX = if (isNext) scrollView.width.toFloat() else -scrollView.width.toFloat()

                val slideIn = android.animation.ObjectAnimator.ofFloat(
                    scrollView,
                    "translationX",
                    scrollView.translationX,
                    0f
                )
                slideIn.duration = 150
                slideIn.start()
            }
        })
        slideOut.start()
    }

    private fun setupDragListener() {
        val toggleBtn = binding.btnViewToggle
        val parent = binding.btnViewToggleParent

        toggleBtn.post {
            originalX = toggleBtn.x
            originalY = toggleBtn.y
        }

        toggleBtn.setOnTouchListener { _, event ->
            val parentWidth = parent.width
            val parentHeight = parent.height
            val btnWidth = toggleBtn.width
            val btnHeight = toggleBtn.height
            val maxX = parentWidth - btnWidth
            val maxY = parentHeight - btnHeight

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY

                    if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDragging = true
                        animateDragStart(toggleBtn)
                    }

                    if (isDragging) {
                        val newX = (originalX + dx).coerceIn(0f, maxX.toFloat())
                        val newY = (originalY + dy).coerceIn(0f, maxY.toFloat())
                        toggleBtn.x = newX
                        toggleBtn.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        animateDragEnd(toggleBtn, parentWidth, parentHeight, btnWidth, btnHeight)
                    } else {
                        toggleBtn.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateDragStart(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.85f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.85f)
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.7f)
        val elevation = ObjectAnimator.ofFloat(view, "elevation", 4f, 16f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha, elevation)
            duration = 120
            start()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun animateDragEnd(view: View, parentWidth: Int, parentHeight: Int, btnWidth: Int, btnHeight: Int) {
        val currentX = view.x
        val currentY = view.y
        val centerX = currentX + btnWidth / 2
        val halfParentWidth = parentWidth / 2

        val snapMargin = (20 * resources.displayMetrics.density).toInt()

        val targetX = if (centerX < halfParentWidth) {
            snapMargin.toFloat()
        } else {
            (parentWidth - btnWidth - snapMargin).toFloat()
        }
        val targetY = currentY.coerceIn(0f, (parentHeight - btnHeight).toFloat())

        val animScaleX = ObjectAnimator.ofFloat(view, "scaleX", view.scaleX, 1f)
        val animScaleY = ObjectAnimator.ofFloat(view, "scaleY", view.scaleY, 1f)
        val animAlpha = ObjectAnimator.ofFloat(view, "alpha", view.alpha, 1f)
        val animElevation = ObjectAnimator.ofFloat(view, "elevation", view.elevation, 4f)

        val animX = ObjectAnimator.ofFloat(view, "x", currentX, targetX)
        val animY = ObjectAnimator.ofFloat(view, "y", currentY, targetY)

        AnimatorSet().apply {
            playTogether(animScaleX, animScaleY, animAlpha, animElevation, animX, animY)
            duration = 300
            interpolator = OvershootInterpolator(1.2f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    originalX = targetX
                    originalY = targetY
                    // 保存悬浮球位置
                    settingsManager.setFloatButtonX(targetX)
                    settingsManager.setFloatButtonY(targetY)
                }
            })
            start()
        }
    }

    private fun updateWeekDisplay() {
        val calendar = Calendar.getInstance()
        val weekText = if (displayWeek == currentWeek) {
            "第${displayWeek}周 (本周)"
        } else {
            "第${displayWeek}周"
        }
        binding.tvWeekInfo.text = "$weekText  ${weekFormat.format(calendar.time)}"
    }

    private fun setupViews() {
        applyBackgroundSettings()
        binding.btnViewToggle.setOnClickListener {
            toggleViewMode()
        }
        setupDragListener()
        restoreViewMode()
        restoreFloatButtonPosition()
        setupBottomNav()
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_schedule
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_schedule -> {
                    showScheduleContent()
                    true
                }
                R.id.nav_settings -> {
                    showSettingsFragment()
                    true
                }
                else -> false
            }
        }
    }

    private fun showScheduleContent() {
        binding.contentMain.visibility = View.VISIBLE
        binding.btnViewToggleParent.visibility = View.VISIBLE
        binding.fragmentSettingsContainer.visibility = View.GONE
    }

    private fun showSettingsFragment() {
        binding.contentMain.visibility = View.GONE
        binding.btnViewToggleParent.visibility = View.GONE
        binding.fragmentSettingsContainer.visibility = View.VISIBLE

        if (supportFragmentManager.findFragmentById(R.id.fragment_settings_container) == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_settings_container, SettingsFragment())
                .commit()
        }
    }

    private fun toggleViewMode() {
        // 三种状态循环切换：周 -> 今 -> 总 -> 周
        when (currentViewState) {
            "week" -> {
                currentViewState = "day"
                settingsManager.setViewState(currentViewState)
                updateViewMode()
            }
            "day" -> {
                currentViewState = "overview"
                settingsManager.setViewState(currentViewState)
                updateViewMode()
            }
            "overview" -> {
                currentViewState = "week"
                settingsManager.setViewState(currentViewState)
                updateViewMode()
            }
        }
    }



    private fun updateViewMode() {
        when (currentViewState) {
            "week" -> {
                binding.scrollView.visibility = View.VISIBLE
                binding.scrollViewToday.visibility = View.GONE
                binding.layoutOverview.visibility = View.GONE
                binding.layoutHeaderWeek.visibility = View.VISIBLE
                binding.tvToggleLabel.text = "周"
            }
            "day" -> {
                binding.scrollView.visibility = View.GONE
                binding.scrollViewToday.visibility = View.VISIBLE
                binding.layoutOverview.visibility = View.GONE
                binding.layoutHeaderWeek.visibility = View.GONE
                binding.tvToggleLabel.text = "今"
                updateTodayView()
            }
            "overview" -> {
                binding.scrollView.visibility = View.GONE
                binding.scrollViewToday.visibility = View.GONE
                binding.layoutOverview.visibility = View.VISIBLE
                binding.layoutHeaderWeek.visibility = View.GONE
                binding.tvToggleLabel.text = "总"
                setupOverviewRecyclerView()
            }
        }
    }

    private fun restoreViewMode() {
        val savedState = settingsManager.getViewState()
        currentViewState = if (savedState == "overview") {
            "day"
        } else {
            savedState
        }
        updateViewMode()
    }

    private fun restoreFloatButtonPosition() {
        val savedX = settingsManager.getFloatButtonX()
        val savedY = settingsManager.getFloatButtonY()
        
        if (savedX >= 0f && savedY >= 0f) {
            // 有保存的位置，使用post确保视图已布局
            binding.btnViewToggle.post {
                binding.btnViewToggle.x = savedX
                binding.btnViewToggle.y = savedY
            }
        }
    }

    private fun setupOverviewRecyclerView() {
        val rvOverview = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_overview_courses)
        
        // 每次都重新创建 adapter 以确保显示最新数据
        val courses = CourseDataManager.getInstance(this).getAllCourses()
        val sortedCourses = courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startTime }))
        
        val adapter = com.cherry.wakeupschedule.adapter.CourseOverviewAdapter(this, sortedCourses, getCourseColors())
        rvOverview.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvOverview.adapter = adapter
    }

    private fun updateTodayView() {
        val container = findViewById<LinearLayout>(R.id.today_courses_container)
        val emptyView = findViewById<LinearLayout>(R.id.layout_empty_today)
        val tvCourseCount = findViewById<TextView>(R.id.tv_today_course_count)
        
        container.removeAllViews()

        val currentDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val adjustedDayOfWeek = if (currentDayOfWeek == Calendar.SUNDAY) 7 else currentDayOfWeek - 1

        val todayCourses = allCourses.filter { course ->
            course.dayOfWeek == adjustedDayOfWeek &&
            currentWeek >= course.startWeek &&
            currentWeek <= course.endWeek &&
            isCourseInCurrentWeekType(course, currentWeek)
        }.sortedBy { course -> getCourseStartMinutes(course) }

        tvCourseCount.text = "共${todayCourses.size}节课"

        if (todayCourses.isEmpty()) {
            container.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            container.visibility = View.VISIBLE
            emptyView.visibility = View.GONE

            todayCourses.forEach { course ->
                val courseView = createTodayCourseView(course)
                container.addView(courseView)
            }
        }
    }

    private fun createTodayCourseView(course: Course): View {
        val colorIndex = allCourses.indexOf(course) % getCourseColors().size
        val color = getCourseColors()[colorIndex]

        val view = LayoutInflater.from(this).inflate(R.layout.item_today_course, null)
        val colorIndicator = view.findViewById<View>(R.id.color_indicator)
        val tvName = view.findViewById<TextView>(R.id.tv_course_name)
        val tvTime = view.findViewById<TextView>(R.id.tv_course_time)
        val tvLocation = view.findViewById<TextView>(R.id.tv_course_location)

        colorIndicator.setBackgroundColor(color)

        tvName.text = course.name
        tvLocation.text = course.classroom.ifEmpty { "未设置地点" }

        val timeTableManager = TimeTableManager.getInstance(this)
        val timeSlots = timeTableManager.getTimeSlots()
        val startSlot = timeSlots.find { it.node == course.startTime }
        val endSlot = timeSlots.find { it.node == course.endTime }
        tvTime.text = if (startSlot != null && endSlot != null) {
            "第${course.startTime}-${course.endTime}节 ${startSlot.startTime}-${endSlot.endTime}"
        } else {
            "第${course.startTime}-${course.endTime}节"
        }

        view.setOnClickListener {
            showCourseDetail(course)
        }

        return view
    }

    private fun applyTheme() {
        val theme = settingsManager.getTheme()
        when (theme) {
            "light" -> setTheme(R.style.Theme_WakeupSchedule_Light)
            "dark" -> setTheme(R.style.Theme_WakeupSchedule_Dark)
            "frosted" -> setTheme(R.style.Theme_WakeupSchedule_Frosted)
            else -> setTheme(R.style.Theme_WakeupSchedule_Light)
        }
    }

    fun applyBackgroundSettings() {
        when (settingsManager.getBackgroundMode()) {
            SettingsManager.BackgroundType.IMAGE -> {
                val customBgPath = settingsManager.getCustomBackgroundPath()
                if (customBgPath.isNotEmpty() && File(customBgPath).exists()) {
                    try {
                        com.bumptech.glide.Glide.with(this@MainActivity)
                            .load(File(customBgPath))
                            .centerCrop()
                            .into(binding.ivBackground)
                        binding.ivBackground.setBackgroundColor(Color.TRANSPARENT)
                        setTextColorsForCustomBackground()
                    } catch (e: Exception) {
                        applyDefaultBackground()
                    }
                } else {
                    applyDefaultBackground()
                }
            }
            SettingsManager.BackgroundType.FROSTED,
            SettingsManager.BackgroundType.SOLID -> {
                binding.ivBackground.setImageResource(0)
                val theme = settingsManager.getCurrentBackgroundTheme()
                binding.ivBackground.setBackgroundColor(theme.color)
                if (theme.isLight) {
                    setLightModeTextColors()
                } else {
                    setDarkModeTextColors()
                }
            }
        }
    }

    private fun applyDefaultBackground() {
        binding.ivBackground.setImageResource(0)
        val theme = settingsManager.getCurrentBackgroundTheme()
        binding.ivBackground.setBackgroundColor(theme.color)
        if (theme.isLight) {
            setLightModeTextColors()
        } else {
            setDarkModeTextColors()
        }
    }

    private fun setDarkModeTextColors() {
        val textColor = Color.WHITE
        val subTextColor = Color.parseColor("#CCCCCC")

        binding.tvDate.setTextColor(textColor)
        binding.tvWeekInfo.setTextColor(subTextColor)
        binding.btnRefresh.setColorFilter(textColor)

        val weekdayViews = listOf(
            binding.tvWeekday1, binding.tvWeekday2, binding.tvWeekday3,
            binding.tvWeekday4, binding.tvWeekday5, binding.tvWeekday6, binding.tvWeekday7
        )
        weekdayViews.forEach { it.setTextColor(textColor) }

        val dateViews = listOf(
            binding.tvDate1, binding.tvDate2, binding.tvDate3,
            binding.tvDate4, binding.tvDate5, binding.tvDate6, binding.tvDate7
        )
        dateViews.forEach { it.setTextColor(textColor) }

        updateTimeAxisColors(textColor, subTextColor)
    }

    private fun setLightModeTextColors() {
        val textColor = Color.parseColor("#1A1A1A")
        val subTextColor = Color.parseColor("#555555")

        binding.tvDate.setTextColor(textColor)
        binding.tvWeekInfo.setTextColor(subTextColor)
        binding.btnRefresh.setColorFilter(textColor)

        val weekdayViews = listOf(
            binding.tvWeekday1, binding.tvWeekday2, binding.tvWeekday3,
            binding.tvWeekday4, binding.tvWeekday5, binding.tvWeekday6, binding.tvWeekday7
        )
        weekdayViews.forEach { it.setTextColor(textColor) }

        val dateViews = listOf(
            binding.tvDate1, binding.tvDate2, binding.tvDate3,
            binding.tvDate4, binding.tvDate5, binding.tvDate6, binding.tvDate7
        )
        dateViews.forEach { it.setTextColor(textColor) }

        updateTimeAxisColors(textColor, subTextColor)
    }

    private fun setTextColorsForSolidBackground(bgColor: Int) {
        val isLightBackground = isLightColor(bgColor)
        val textColor = if (isLightBackground) Color.BLACK else Color.WHITE
        val subTextColor = if (isLightBackground) Color.parseColor("#666666") else Color.parseColor("#CCCCCC")

        binding.tvDate.setTextColor(textColor)
        binding.tvWeekInfo.setTextColor(subTextColor)
        binding.btnRefresh.setColorFilter(textColor)

        // 设置星期表头颜色
        val weekdayViews = listOf(
            binding.tvWeekday1, binding.tvWeekday2, binding.tvWeekday3,
            binding.tvWeekday4, binding.tvWeekday5, binding.tvWeekday6, binding.tvWeekday7
        )
        weekdayViews.forEach { it.setTextColor(textColor) }

        // 设置日期数字颜色
        val dateViews = listOf(
            binding.tvDate1, binding.tvDate2, binding.tvDate3,
            binding.tvDate4, binding.tvDate5, binding.tvDate6, binding.tvDate7
        )
        dateViews.forEach { it.setTextColor(textColor) }

        // 设置时间轴颜色
        updateTimeAxisColors(textColor, subTextColor)
    }

    private fun updateTimeAxisColors(textColor: Int, subTextColor: Int) {
        timeAxisViews.forEach { timeView ->
            val tvNode = timeView.findViewById<TextView>(R.id.tv_node)
            val tvStartTime = timeView.findViewById<TextView>(R.id.tv_start_time)
            val tvEndTime = timeView.findViewById<TextView>(R.id.tv_end_time)
            tvNode.setTextColor(textColor)
            tvStartTime.setTextColor(subTextColor)
            tvEndTime.setTextColor(subTextColor)
        }
    }

    private fun setTextColorsForCustomBackground() {
        setDefaultTextColors()
    }

    private fun setDefaultTextColors() {
        val whiteColor = Color.WHITE
        val whiteSubColor = Color.parseColor("#CCFFFFFF")

        binding.tvDate.setTextColor(whiteColor)
        binding.tvWeekInfo.setTextColor(whiteColor)
        binding.btnRefresh.setColorFilter(whiteColor)

        // 设置星期表头颜色
        val weekdayViews = listOf(
            binding.tvWeekday1, binding.tvWeekday2, binding.tvWeekday3,
            binding.tvWeekday4, binding.tvWeekday5, binding.tvWeekday6, binding.tvWeekday7
        )
        weekdayViews.forEach { it.setTextColor(whiteColor) }

        // 设置日期数字颜色
        val dateViews = listOf(
            binding.tvDate1, binding.tvDate2, binding.tvDate3,
            binding.tvDate4, binding.tvDate5, binding.tvDate6, binding.tvDate7
        )
        dateViews.forEach { it.setTextColor(whiteColor) }

        // 设置时间轴颜色
        updateTimeAxisColors(whiteColor, whiteSubColor)
    }

    private fun isLightColor(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness < 0.5
    }

    private fun setupClickListeners() {
        // 刷新按钮
        binding.btnRefresh.setOnClickListener {
            refreshScheduleFromJwxt(showError = true)
        }

        // 周次显示点击 - 快速跳转到当前周
        binding.tvWeekInfo.setOnClickListener {
            if (displayWeek != currentWeek) {
                displayWeek = currentWeek
                updateWeekDisplay()
                updateDateDisplay()
                viewModel.loadCoursesForWeek(displayWeek)
                Toast.makeText(this, "已切换到本周 (第${currentWeek}周)", Toast.LENGTH_SHORT).show()
            }
        }

        // 日期选择
        val dateViews = listOf(
            binding.tvDate1, binding.tvDate2, binding.tvDate3,
            binding.tvDate4, binding.tvDate5, binding.tvDate6, binding.tvDate7
        )
        dateViews.forEachIndexed { _, textView ->
            textView.setOnClickListener {
                // 清除之前的选中状态
                dateViews.forEach { it.background = null }
                // 设置当前选中
                it.setBackgroundResource(R.drawable.bg_date_selected)
                // 可以在这里添加切换日期的逻辑
            }
        }
    }

    private fun showAddCourseDialog() {
        val intent = Intent(this, AddCourseActivity::class.java)
        startActivity(intent)
    }

    private fun showImportDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import, null)
        val dialog = AlertDialog.Builder(this, R.style.RoundedDialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.btn_import_school).setOnClickListener {
            val intent = Intent(this, SchoolImportActivity::class.java)
            startActivity(intent)
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.btn_import_excel).setOnClickListener {
            importFileLauncher.launch("*/*")
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.btn_import_file).setOnClickListener {
            importFileLauncher.launch("*/*")
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.btn_apply_adapter).setOnClickListener {
            val intent = Intent(this, ApplyAdapterActivity::class.java)
            startActivity(intent)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showExportDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_export, null)
        val dialog = AlertDialog.Builder(this, R.style.RoundedDialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.btn_export_backup).setOnClickListener {
            exportBackup()
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.btn_export_ics).setOnClickListener {
            exportIcs()
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.btn_share_app).setOnClickListener {
            shareApp()
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.btn_cancel_export).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun shareApp() {
        val websiteUrl = "https://yngu196.github.io/Schedule/"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("应用官网", websiteUrl)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已复制官网链接", Toast.LENGTH_SHORT).show()
    }

    private fun exportBackup() {
        // 导出课程备份 - 注意：必须获取所有课程，而不是被筛选过的课程！
        val courses = CourseDataManager.getInstance(this).getAllCourses()
        if (courses.isNullOrEmpty()) {
            Toast.makeText(this, "没有课程可导出", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // 生成备份文件名
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "schedule_backup_${dateFormat.format(Date())}.json"
            
            // 获取时间表信息
            val timeTableManager = TimeTableManager.getInstance(this)
            val timeSlots = timeTableManager.getTimeSlots().map {
                BackupData.TimeSlotData(
                    node = it.node,
                    startTime = it.startTime,
                    endTime = it.endTime
                )
            }
            val maxNodes = timeTableManager.getMaxNodes()
            
            // 创建备份数据，包含课程和配置
            val backupData = BackupData(
                version = 1,
                exportTime = System.currentTimeMillis(),
                courses = courses,
                settings = AppSettings(
                    currentSemester = settingsManager.getCurrentSemester(),
                    defaultWeek = settingsManager.getDefaultWeek(),
                    defaultAlarmMinutes = settingsManager.getDefaultAlarmMinutes(),
                    autoSwitchWeek = settingsManager.getAutoSwitchWeek(),
                    alarmEnabled = settingsManager.isAlarmEnabled(),
                    courseCardAlpha = settingsManager.getCourseCardAlpha(),
                    showNonCurrentWeekCourses = settingsManager.isShowNonCurrentWeekCourses(),
                    nonCurrentWeekAlpha = settingsManager.getNonCurrentWeekAlpha(),
                    fontSize = settingsManager.getFontSize(),
                    semesterStartDate = settingsManager.getSemesterStartDate(),
                    customSemesters = settingsManager.getCustomSemesters(),
                    courseColorThemeIndex = settingsManager.getCourseColorThemeIndex(),
                    backgroundThemeIndex = settingsManager.getBackgroundThemeIndex(),
                    backgroundType = settingsManager.getBackgroundTypeString(),
                    customBackgroundPath = settingsManager.getCustomBackgroundPath()
                ),
                timeSlots = timeSlots,
                maxNodes = maxNodes
            )
            
            // 转换备份数据为JSON
            val gson = Gson()
            val backupJson = gson.toJson(backupData)
            
            // 保存到应用私有目录（用于分享）
            val privateFile = File(getExternalFilesDir(null), fileName)
            privateFile.writeText(backupJson, StandardCharsets.UTF_8)
            
            // 保存到公共 Downloads 目录（用户容易找到）
            val publicFileUri = saveToPublicDownloads(fileName, backupJson)
            
            // 显示成功提示
            if (publicFileUri != null) {
                Toast.makeText(this, "备份已保存到下载文件夹: $fileName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "备份已保存到: ${privateFile.absolutePath}", Toast.LENGTH_LONG).show()
            }
            
            // 分享备份文件
            shareBackupFile(privateFile)
        } catch (e: Exception) {
            Log.e("MainActivity", "导出备份失败", e)
            Toast.makeText(this, "导出备份失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun saveToPublicDownloads(fileName: String, content: String): android.net.Uri? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                    }
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(it, contentValues, null, null)
                }
                uri
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                file.writeText(content, StandardCharsets.UTF_8)
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "保存到公共目录失败", e)
            null
        }
    }
    
    private fun shareBackupFile(file: File) {
        val fileUri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "课程表备份")
            putExtra(Intent.EXTRA_TEXT, "这是我的课程表备份文件，您可以在Schedule课程表App中导入恢复")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享备份文件"))
    }

    private fun exportIcs() {
        val courses = CourseDataManager.getInstance(this).getAllCourses()
        if (courses.isNullOrEmpty()) {
            Toast.makeText(this, "没有课程可导出", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val timeTableManager = TimeTableManager.getInstance(this)
            val timeSlots = timeTableManager.getTimeSlots()

            val semesterStartDate = settingsManager.getSemesterStartDate()
            if (semesterStartDate <= 0L) {
                Toast.makeText(this, "请先设置学期开始日期", Toast.LENGTH_LONG).show()
                return
            }

            val icsContent = generateIcsContent(courses, timeSlots, semesterStartDate)

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "schedule_${dateFormat.format(Date())}.ics"

            val privateFile = File(getExternalFilesDir(null), fileName)
            privateFile.writeText(icsContent, StandardCharsets.UTF_8)

            val publicFileUri = saveIcsToPublicDownloads(fileName, icsContent)

            if (publicFileUri != null) {
                Toast.makeText(this, "ICS文件已保存到下载文件夹: $fileName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "ICS文件已保存到: ${privateFile.absolutePath}", Toast.LENGTH_LONG).show()
            }

            shareIcsFile(privateFile)
        } catch (e: Exception) {
            Log.e("MainActivity", "导出ICS失败", e)
            Toast.makeText(this, "导出ICS失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateIcsContent(courses: List<Course>, timeSlots: List<TimeTableManager.TimeSlot>, semesterStartDate: Long): String {
        val sb = StringBuilder()
        val uidPrefix = "schedule_${System.currentTimeMillis()}"
        
        sb.append("BEGIN:VCALENDAR\n")
        sb.append("VERSION:2.0\n")
        sb.append("PRODID:-//Schedule//CN\n")
        sb.append("CALSCALE:GREGORIAN\n")
        sb.append("METHOD:PUBLISH\n")
        sb.append("X-WR-CALNAME:课程表\n")
        sb.append("X-WR-TIMEZONE:Asia/Shanghai\n")

        val semesterStartCalendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        semesterStartCalendar.timeInMillis = semesterStartDate
        semesterStartCalendar.set(Calendar.HOUR_OF_DAY, 0)
        semesterStartCalendar.set(Calendar.MINUTE, 0)
        semesterStartCalendar.set(Calendar.SECOND, 0)
        semesterStartCalendar.set(Calendar.MILLISECOND, 0)

        courses.forEach { course ->
            val startSlot = timeSlots.find { it.node == course.startTime }
            val endSlot = timeSlots.find { it.node == course.endTime }
            val startTime = startSlot?.startTime ?: "08:00"
            val endTime = endSlot?.endTime ?: "08:45"

            val startParts = startTime.split(":")
            val endParts = endTime.split(":")
            val startHour = startParts[0].toIntOrNull() ?: 8
            val startMin = startParts[1].toIntOrNull() ?: 0
            val endHour = endParts[0].toIntOrNull() ?: 8
            val endMin = endParts[1].toIntOrNull() ?: 45

            val courseCalendar = semesterStartCalendar.clone() as Calendar
            val daysToAdd = if (course.dayOfWeek == 7) 0 else course.dayOfWeek - 1
            courseCalendar.add(Calendar.DAY_OF_YEAR, daysToAdd)

            val startWeek = course.startWeek
            val endWeek = course.endWeek
            val untilCalendar = courseCalendar.clone() as Calendar
            untilCalendar.add(Calendar.WEEK_OF_YEAR, endWeek - startWeek)
            untilCalendar.set(Calendar.HOUR_OF_DAY, endHour)
            untilCalendar.set(Calendar.MINUTE, endMin)
            untilCalendar.set(Calendar.SECOND, 0)
            untilCalendar.set(Calendar.MILLISECOND, 0)

            for (week in startWeek..endWeek) {
                var shouldAddEvent = true
                if (course.weekType != 0) {
                    val isOddWeek = (week - startWeek + 1) % 2 == 1
                    if ((course.weekType == 1 && !isOddWeek) || (course.weekType == 2 && isOddWeek)) {
                        shouldAddEvent = false
                    }
                }

                if (shouldAddEvent) {
                    val eventStart = courseCalendar.clone() as Calendar
                    eventStart.set(Calendar.HOUR_OF_DAY, startHour)
                    eventStart.set(Calendar.MINUTE, startMin)
                    eventStart.set(Calendar.SECOND, 0)
                    eventStart.set(Calendar.MILLISECOND, 0)

                    val eventEnd = courseCalendar.clone() as Calendar
                    eventEnd.set(Calendar.HOUR_OF_DAY, endHour)
                    eventEnd.set(Calendar.MINUTE, endMin)
                    eventEnd.set(Calendar.SECOND, 0)
                    eventEnd.set(Calendar.MILLISECOND, 0)

                    sb.append("BEGIN:VEVENT\n")
                    sb.append("UID:${uidPrefix}_${course.id}_${week}@schedule\n")
                    sb.append("DTSTAMP:${formatIcsDate(eventStart)}\n")
                    sb.append("DTSTART:${formatIcsDate(eventStart)}\n")
                    sb.append("DTEND:${formatIcsDate(eventEnd)}\n")
                    sb.append("SUMMARY:${escapeIcsString(course.name)}\n")
                    if (course.teacher.isNotEmpty()) {
                        sb.append("DESCRIPTION:${escapeIcsString("教师: ${course.teacher}")}\n")
                    }
                    if (course.classroom.isNotEmpty()) {
                        sb.append("LOCATION:${escapeIcsString(course.classroom)}\n")
                    }
                    if (course.alarmEnabled) {
                        sb.append("BEGIN:VALARM\n")
                        sb.append("TRIGGER:-PT${course.alarmMinutesBefore}M\n")
                        sb.append("ACTION:DISPLAY\n")
                        sb.append("DESCRIPTION:课程提醒\n")
                        sb.append("END:VALARM\n")
                    }
                    sb.append("END:VEVENT\n")
                }

                courseCalendar.add(Calendar.WEEK_OF_YEAR, 1)
            }
        }

        sb.append("END:VCALENDAR\n")
        return sb.toString()
    }

    private fun formatIcsDate(calendar: Calendar): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(calendar.time)
    }

    private fun escapeIcsString(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }

    private fun saveIcsToPublicDownloads(fileName: String, content: String): android.net.Uri? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/calendar")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                    }
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(it, contentValues, null, null)
                }
                uri
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                file.writeText(content, StandardCharsets.UTF_8)
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "保存ICS到公共目录失败", e)
            null
        }
    }

    private fun shareIcsFile(file: File) {
        val fileUri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "课程表")
            putExtra(Intent.EXTRA_TEXT, "这是我的课程表ICS文件，可导入到日历应用中")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享课程表"))
    }

    private fun setupObservers() {
        viewModel.courses.observe(this) { _ ->
            allCourses = CourseDataManager.getInstance(this).getAllCourses()
            if (allCourses.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.courseGrid.visibility = View.GONE
            } else {
                binding.layoutEmpty.visibility = View.GONE
                binding.courseGrid.visibility = View.VISIBLE
                displayCourses(allCourses)
                syncTimeAxisHeight()
            }
            if (currentViewState == "day") {
                updateTodayView()
            }
        }
    }

    private fun updateDateDisplay() {
        val calendar = Calendar.getInstance()
        binding.tvDate.text = dateFormat.format(calendar.time)

        val dateViews = listOf(
            binding.tvDate1, binding.tvDate2, binding.tvDate3,
            binding.tvDate4, binding.tvDate5, binding.tvDate6, binding.tvDate7
        )

        val semesterStartDate = settingsManager.getSemesterStartDate()
        val weekCalendar = Calendar.getInstance()

        if (semesterStartDate > 0L) {
            weekCalendar.timeInMillis = semesterStartDate
            weekCalendar.add(Calendar.WEEK_OF_YEAR, displayWeek - 1)
        }
        weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

        val currentDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val adjustedDayOfWeek = if (currentDayOfWeek == Calendar.SUNDAY) 7 else currentDayOfWeek - 1

        dateViews.forEachIndexed { index, textView ->
            textView.text = weekCalendar.get(Calendar.DAY_OF_MONTH).toString()
            if (displayWeek == currentWeek && index + 1 == adjustedDayOfWeek) {
                textView.setBackgroundResource(R.drawable.bg_date_selected)
            } else {
                textView.background = null
            }
            weekCalendar.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun generateTimeAxis() {
        val timeAxis = binding.timeAxis
        timeAxis.removeAllViews()
        timeAxisViews.clear()

        val timeTableManager = TimeTableManager.getInstance(this)
        val maxNodes = timeTableManager.getMaxNodes()

        // 获取课程单元格的高度
        val cellHeight = resources.getDimensionPixelSize(R.dimen.course_cell_height)

        // 为每个节次创建时间轴项
        for (node in 1..maxNodes) {
            // 尝试获取自定义时间槽，否则使用默认值
            val timeSlot = timeTableManager.getTimeSlots().find { it.node == node }
                ?: TimeTableManager.getTimeSlot(node) ?: TimeTableManager.TimeSlot(node, "08:00", "08:45")

            val timeView = LayoutInflater.from(this)
                .inflate(R.layout.item_time_slot, timeAxis, false) as LinearLayout

            // 设置时间轴项的高度，与课程单元格高度一致
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cellHeight)
            timeView.layoutParams = params

            val tvNode = timeView.findViewById<TextView>(R.id.tv_node)
            val tvStartTime = timeView.findViewById<TextView>(R.id.tv_start_time)
            val tvEndTime = timeView.findViewById<TextView>(R.id.tv_end_time)

            tvNode.text = timeSlot.node.toString()
            // 确保时间格式正确
            val startTime = timeSlot.startTime
            val endTime = timeSlot.endTime
            tvStartTime.text = if (startTime.contains(":")) startTime else "00:00"
            tvEndTime.text = if (endTime.contains(":")) endTime else "00:00"

            timeAxis.addView(timeView)
            timeAxisViews.add(timeView)
        }
    }

    private fun syncTimeAxisHeight() {
        if (timeAxisViews.isEmpty()) return
        binding.courseGrid.post {
            if (timeAxisViews.isEmpty()) return@post
            val firstCell = binding.courseGrid.getChildAt(0) ?: return@post
            val actualRowHeight = firstCell.height
            if (actualRowHeight <= 0) return@post
            val targetHeight = maxOf(actualRowHeight, (70 * resources.displayMetrics.density).toInt())
            if (kotlin.math.abs(targetHeight - resources.getDimensionPixelSize(R.dimen.course_cell_height)) < 4) return@post
            timeAxisViews.forEach { timeView ->
                val lp = timeView.layoutParams
                lp.height = targetHeight
                timeView.layoutParams = lp
            }
        }
    }
    
    /**
     * 检查课程在当前显示周是否应该显示
     * 支持单双周判断
     */
    private fun isCourseActiveInWeek(course: Course, week: Int): Boolean {
        // 基础周次范围检查
        if (week < course.startWeek || week > course.endWeek) {
            return false
        }
        
        // 单双周判断
        val isWeekTypeMatch = when (course.weekType) {
            0 -> true // 每周
            1 -> week % 2 == 1 // 单周
            2 -> week % 2 == 0 // 双周
            else -> true
        }
        
        return isWeekTypeMatch
    }

    /**
     * 获取课程在当前周的透明度
     * 非本周课程透明度降低
     */
    private fun getCourseAlpha(course: Course): Float {
        val isActive = isCourseActiveInWeek(course, displayWeek)
        val baseAlpha = settingsManager.getCourseCardAlpha()

        return if (isActive) {
            baseAlpha  // 本周课程使用设置的透明度
        } else {
            // 非本周课程
            if (settingsManager.isShowNonCurrentWeekCourses()) {
                baseAlpha * settingsManager.getNonCurrentWeekAlpha()  // 应用非本周透明度
            } else {
                0f  // 不显示非本周课程
            }
        }
    }

    private fun displayCourses(courses: List<Course>) {
        val gridLayout = binding.courseGrid
        gridLayout.removeAllViews()

        try {
            val timeTableManager = TimeTableManager.getInstance(this)
            val maxNodes = timeTableManager.getMaxNodes()

            // 动态设置GridLayout的行数
            gridLayout.rowCount = maxNodes
            
            // 创建7列 x maxNodes行的网格背景
            for (row in 0 until maxNodes) {
                for (col in 0 until 7) {
                    val cellView = View(this)
                    val params = GridLayout.LayoutParams().apply {
                        rowSpec = GridLayout.spec(row, 1f)
                        columnSpec = GridLayout.spec(col, 1f)
                        width = 0
                        height = resources.getDimensionPixelSize(R.dimen.course_cell_height)
                    }
                    cellView.layoutParams = params
                    cellView.setBackgroundResource(R.drawable.bg_grid_cell)
                    gridLayout.addView(cellView)
                }
            }

            // 过滤掉超出范围的课程
            val validCourses = courses.filter {
                course ->
                course.startTime <= maxNodes
            }

            // 改进的冲突检测：按位置分组，并且只对在当前显示周(displayWeek)有重叠的课程才标记为冲突
            // 同时对于非本周课程，即使在同一位置，也不应该标记为冲突（除非它们在同一周范围）
            val courseGroups = mutableMapOf<Pair<Int, Int>, MutableList<Pair<Course, Int>>>()

            validCourses.forEachIndexed { index, course ->
                val key = Pair(course.dayOfWeek - 1, course.startTime - 1)
                if (!courseGroups.containsKey(key)) {
                    courseGroups[key] = mutableListOf()
                }
                courseGroups[key]?.add(Pair(course, index))
            }

            val highlightCourse = findCurrentOrNextCourse()

            // 添加课程卡片
            validCourses.forEachIndexed { index, course ->
                val color = if (course.color != 0) course.color else getCourseColors()[index % getCourseColors().size]
                val key = Pair(course.dayOfWeek - 1, course.startTime - 1)
                val allCoursesAtPosition = courseGroups[key] ?: listOf()
                
                // 改进的冲突检测：只有在当前显示周都有课的课程才算冲突
                val conflictingCoursesAtDisplayWeek = allCoursesAtPosition.filter { (c, _) ->
                    isCourseActiveInWeek(c, displayWeek)
                }
                val hasConflict = conflictingCoursesAtDisplayWeek.size > 1
                
                val alpha = getCourseAlpha(course)
                val isHighlight = course == highlightCourse

                addCourseCard(course, color, hasConflict, alpha, allCoursesAtPosition, isHighlight)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error displaying courses", e)
        }
    }

    private fun findCurrentOrNextCourse(): Course? {
        val currentDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val adjustedDayOfWeek = if (currentDayOfWeek == Calendar.SUNDAY) 7 else currentDayOfWeek - 1

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val todayCourses = allCourses.filter { course ->
            course.dayOfWeek == adjustedDayOfWeek &&
            currentWeek >= course.startWeek &&
            currentWeek <= course.endWeek &&
            isCourseInCurrentWeekType(course, currentWeek)
        }.sortedBy { course -> getCourseStartMinutes(course) }

        for (course in todayCourses) {
            val startMinutes = getCourseStartMinutes(course)
            val endMinutes = getCourseEndMinutes(course)

            if (currentMinutes in startMinutes..endMinutes) {
                return course
            } else if (currentMinutes < startMinutes && startMinutes - currentMinutes <= 30) {
                return course
            }
        }
        return null
    }

    private fun addCourseCard(
        course: Course,
        color: Int,
        hasConflict: Boolean,
        alpha: Float,
        conflictCourses: List<Pair<Course, Int>>,
        isHighlight: Boolean = false
    ) {
        try {
            // 如果透明度为0，不显示该课程
            if (alpha <= 0f) {
                return
            }

            // 获取当前最大节数，确保课程在有效范围内
            val timeTableManager = TimeTableManager.getInstance(this)
            val maxNodes = timeTableManager.getMaxNodes()

            // 检查课程时间是否在有效范围内
            if (course.startTime > maxNodes) {
                return  // 课程开始时间超出当前最大节数，不显示
            }

            val containerView = LayoutInflater.from(this)
                .inflate(R.layout.item_course_card, binding.courseGrid, false) as FrameLayout

            val cardView = containerView.findViewById<CardView>(R.id.card_course)
            val tvName = containerView.findViewById<TextView>(R.id.tv_course_name)
            val tvLocation = containerView.findViewById<TextView>(R.id.tv_course_location)
            val ivConflict = containerView.findViewById<ImageView>(R.id.iv_conflict_indicator)

            // 设置卡片背景色
            cardView.setCardBackgroundColor(color)

            // 根据当前周设置透明度
            cardView.alpha = alpha

            // 高亮样式
            if (isHighlight) {
                cardView.cardElevation = resources.getDimension(R.dimen.card_elevation_highlight)
                cardView.setCardBackgroundColor(Color.argb(
                    Color.alpha(color),
                    Math.min(Color.red(color) + 30, 255),
                    Math.min(Color.green(color) + 30, 255),
                    Math.min(Color.blue(color) + 30, 255)
                ))
            }

            // 设置课程信息
            tvName.text = course.name
            tvLocation.text = "@${course.classroom}"

            // 显示冲突指示器
            if (hasConflict) {
                ivConflict.visibility = View.VISIBLE
            } else {
                ivConflict.visibility = View.GONE
            }

            // 计算位置，确保不超出网格范围
            val row = course.startTime - 1
            val col = course.dayOfWeek - 1
            
            // 计算课程跨度，确保三节课对应三个格子
            val calculatedSpan = course.endTime - course.startTime + 1
            // 确保rowSpan不会导致超出网格范围
            val maxPossibleSpan = maxNodes - row
            val rowSpan = if (maxPossibleSpan > 0) min(calculatedSpan, maxPossibleSpan) else 1

            // 计算课程卡片的实际高度
            val cellHeight = resources.getDimensionPixelSize(R.dimen.course_cell_height)
            val cardHeight = cellHeight * rowSpan

            val params = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(row, rowSpan, 1f)
                columnSpec = GridLayout.spec(col, 1f)
                width = 0
                height = cardHeight
                setMargins(2, 2, 2, 2)
            }
            containerView.layoutParams = params

            // 点击事件 - 显示课程详情
            containerView.setOnClickListener {
                if (hasConflict && conflictCourses.size > 1) {
                    showConflictCourseDetail(conflictCourses.map { it.first })
                } else {
                    showCourseDetail(course)
                }
            }

            // 长按事件
            containerView.setOnLongClickListener {
                showDeleteDialog(course)
                true
            }

            binding.courseGrid.addView(containerView)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error adding course card", e)
        }
    }

    private fun showCourseDetail(course: Course) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_course_detail, null)
        val dialog = AlertDialog.Builder(this, R.style.RoundedDialog)
            .setView(dialogView)
            .create()

        // 设置课程信息
        dialogView.findViewById<TextView>(R.id.tv_course_name).text = course.name
        // 显示周次信息，添加单双周标注
        val weekInfo = when (course.weekType) {
            1 -> "第${course.startWeek}-${course.endWeek}周 (单周)"
            2 -> "第${course.startWeek}-${course.endWeek}周 (双周)"
            else -> "第${course.startWeek}-${course.endWeek}周"
        }
        dialogView.findViewById<TextView>(R.id.tv_week_info).text = weekInfo

        val weekDays = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val timeTableManager = TimeTableManager.getInstance(this)
        val timeSlots = timeTableManager.getTimeSlots()
        val timeSlot = timeSlots.find { it.node == course.startTime }
        val endTimeSlot = timeSlots.find { it.node == course.endTime }
        val timeStr = if (timeSlot != null && endTimeSlot != null) {
            "${weekDays[course.dayOfWeek]} 第${course.startTime}-${course.endTime}节 ${timeSlot.startTime}-${endTimeSlot.endTime}"
        } else {
            "${weekDays[course.dayOfWeek]} 第${course.startTime}-${course.endTime}节"
        }
        dialogView.findViewById<TextView>(R.id.tv_time_info).text = timeStr
        dialogView.findViewById<TextView>(R.id.tv_teacher).text = course.teacher.ifEmpty { "未设置" }
        dialogView.findViewById<TextView>(R.id.tv_location).text = course.classroom.ifEmpty { "未设置" }

        // 隐藏冲突区域
        dialogView.findViewById<LinearLayout>(R.id.layout_conflict_switch).visibility = View.GONE

        // 关闭按钮
        dialogView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener {
            dialog.dismiss()
        }

        // 删除按钮
        dialogView.findViewById<ImageButton>(R.id.btn_delete).setOnClickListener {
            showDeleteDialog(course)
            dialog.dismiss()
        }

        // 编辑按钮
        dialogView.findViewById<ImageButton>(R.id.btn_edit).setOnClickListener {
            editCourse(course)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showConflictCourseDetail(conflictCourses: List<Course>) {
        // 显示冲突课程列表，让用户选择查看哪一门
        val courseNames = conflictCourses.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("该时间段有多门课程")
            .setItems(courseNames) { _, which ->
                showCourseDetail(conflictCourses[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editCourse(course: Course) {
        val intent = Intent(this, AddCourseActivity::class.java)
        intent.putExtra("course", course as java.io.Serializable)
        startActivity(intent)
    }

    private fun showMoreOptionsDialog() {
        val options = arrayOf("设置")
        AlertDialog.Builder(this)
            .setTitle("更多选项")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(this, SettingsActivity::class.java)
                        startActivity(intent)
                    }
                }
            }
            .show()
    }

    private fun showDeleteDialog(course: Course) {
        AlertDialog.Builder(this)
            .setTitle("删除课程")
            .setMessage("确定要删除 ${course.name} 吗？")
            .setPositiveButton("删除所有周次") { _, _ ->
                viewModel.deleteCourse(course)
                Toast.makeText(this, "课程删除成功", Toast.LENGTH_SHORT).show()
                // 更新桌面小组件
                updateWidget()
            }
            .setNegativeButton("仅删除本周") { _, _ ->
                // 仅删除本周 - 实际上需要修改周次范围
                if (course.startWeek == displayWeek && course.endWeek == displayWeek) {
                    viewModel.deleteCourse(course)
                } else if (displayWeek == course.startWeek) {
                    // 调整开始周
                    val updatedCourse = course.copy(startWeek = course.startWeek + 1)
                    viewModel.updateCourse(updatedCourse)
                } else if (displayWeek == course.endWeek) {
                    // 调整结束周
                    val updatedCourse = course.copy(endWeek = course.endWeek - 1)
                    viewModel.updateCourse(updatedCourse)
                } else if (displayWeek > course.startWeek && displayWeek < course.endWeek) {
                    // 在中间，需要拆分成两个课程
                    // 简化处理：先删除原课程，添加两个新课程
                    viewModel.deleteCourse(course)
                    val course1 = course.copy(endWeek = displayWeek - 1)
                    val course2 = course.copy(startWeek = displayWeek + 1)
                    viewModel.addCourse(course1)
                    viewModel.addCourse(course2)
                    // addCourse 已在 ViewModel 内部调用 registerAllCourseNotifications，此处无需重复
                }
                Toast.makeText(this, "已删除本周课程", Toast.LENGTH_SHORT).show()
                // 更新桌面小组件
                updateWidget()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 用户手动打开应用时，取消自动关闭定时器
        com.cherry.wakeupschedule.service.AutoStartReceiver.cancelShutdown(this)
        // 自动从教务系统刷新课表（失败不提示）
        refreshScheduleFromJwxt(showError = false)
        // 从课程总览返回时，确保显示正确的视图
        // 如果之前是在day视图，返回后显示"今"，下次点击回到"周"
        // 为了更好的用户体验，我们恢复到之前的状态，悬浮球文字也要对应显示
        val savedState = settingsManager.getViewState()
        currentViewState = if (savedState == "overview") {
            "day"
        } else {
            savedState
        }
        updateViewMode()
        
        // 重新计算当前周
        currentWeek = calculateCurrentWeek()
        // 同步默认周，避免其他模块仍使用旧周次
        settingsManager.setDefaultWeek(currentWeek)
        // 同步显示周与当前周
        displayWeek = currentWeek
        // generateTimeAxis必须在applyBackgroundSettings之前执行
        generateTimeAxis()
        // 重新应用背景设置
        applyBackgroundSettings()
        updateWeekDisplay()
        viewModel.loadCoursesForWeek(displayWeek)
        // 如果已经有课程数据，重新显示以适配新的时间轴
        allCourses.let {
            if (it.isNotEmpty()) {
                displayCourses(it)
                syncTimeAxisHeight()
            }
        }
        // 更新日期显示
        updateDateDisplay()

        // 为所有课程设置闹钟
        setupAllCoursesAlarms()

        // 检查是否有待导入的文件
        checkPendingImportFile()

        startCountdown()
    }

    override fun onPause() {
        super.onPause()
        stopCountdown()
    }

    private fun setupAllCoursesAlarms() {
        (application as App).registerAllCourseNotifications()
    }

    private fun checkPendingImportFile() {
        val prefs = getSharedPreferences("pending_imports", Context.MODE_PRIVATE)
        val pendingFilePath = prefs.getString("pending_file", null)

        if (!pendingFilePath.isNullOrEmpty()) {
            val file = File(pendingFilePath)
            if (file.exists()) {
                // 有文件待导入
                AlertDialog.Builder(this)
                    .setTitle("发现课程表文件")
                    .setMessage("检测到从教务系统下载的课程表文件，是否立即导入？")
                    .setPositiveButton("立即导入") { _, _ ->
                        importPendingFile(file)
                    }
                    .setNegativeButton("稍后手动导入") { _, _ ->
                        // 清除待导入标记
                        prefs.edit().remove("pending_file").apply()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                // 文件不存在，清除标记
                prefs.edit().remove("pending_file").apply()
            }
        }
    }

    private fun importPendingFile(file: File) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val importService = ImportService(this@MainActivity)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    file
                )
                val success = importService.importFromFile(uri)

                // 清除待导入标记
                val prefs = getSharedPreferences("pending_imports", Context.MODE_PRIVATE)
                prefs.edit().remove("pending_file").apply()

                if (success) {
                    Toast.makeText(this@MainActivity, "课程表导入成功！", Toast.LENGTH_LONG).show()
                    // 删除已导入的文件
                    file.delete()
                    // 更新小组件
                    updateWidget()
                } else {
                    Toast.makeText(this@MainActivity, "导入失败，请检查文件格式", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "导入错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importFromFile(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val importService = ImportService(this@MainActivity)
                val success = importService.importFromFile(uri)
                if (success) {
                    // 更新小组件
                    updateWidget()
                } else {
                    // 如果 ImportService 失败，尝试直接解析 JSON
                    tryParseAsJson(uri)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tryParseAsJson(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val content = inputStream.bufferedReader().use { it.readText() }
                if (content.trim().startsWith("[")) {
                    // 旧格式：纯课程数组
                    importFromJson(content)
                } else if (content.trim().startsWith("{")) {
                    // 新格式：包含版本和配置的备份
                    importFromBackupData(content)
                } else {
                    Toast.makeText(this, "不支持的文件格式", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromBackupData(json: String) {
        try {
            val gson = Gson()
            val backupData = gson.fromJson(json, BackupData::class.java)
            
            // 检查是否有现有课程
            val hasExistingCourses = CourseDataManager.getInstance(this).getAllCourses().isNotEmpty()
            
            if (backupData.courses.isNotEmpty()) {
                if (hasExistingCourses) {
                    // 有现有课程，询问用户
                    AlertDialog.Builder(this)
                        .setTitle("导入选项")
                        .setMessage("检测到现有课程。您想要：\n\n1. 保留现有课程并追加新课程\n2. 清空现有课程后导入")
                        .setPositiveButton("追加导入") { _, _ ->
                            doImportCourses(backupData, append = true)
                        }
                        .setNegativeButton("清空导入") { _, _ ->
                            doImportCourses(backupData, append = false)
                        }
                        .setNeutralButton("取消", null)
                        .show()
                } else {
                    // 没有现有课程，直接导入
                    doImportCourses(backupData, append = false)
                }
            } else {
                // 没有课程数据，直接询问配置
                showImportSettingsDialog(backupData.settings, backupData.timeSlots, backupData.maxNodes)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "解析备份失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun doImportCourses(backupData: BackupData, append: Boolean) {
        if (!append) {
            viewModel.clearAllCourses()
        }
        viewModel.addCourses(backupData.courses)
        Toast.makeText(this, "成功导入 ${backupData.courses.size} 门课程", Toast.LENGTH_LONG).show()
        
        // 询问是否导入配置和时间表
        showImportSettingsDialog(backupData.settings, backupData.timeSlots, backupData.maxNodes)
        
        // 更新小组件
        updateWidget()
    }

    private fun showImportSettingsDialog(
        settings: AppSettings,
        timeSlots: List<BackupData.TimeSlotData>?,
        maxNodes: Int?
    ) {
        val hasTimeTableData = timeSlots != null && timeSlots.isNotEmpty() && maxNodes != null
        val message = if (hasTimeTableData) {
            "是否导入备份中的配置信息？\n\n包括：学期、主题、背景、卡片透明度等设置\n\n以及：时间表配置（节数和上课时间）"
        } else {
            "是否导入备份中的配置信息？\n\n包括：学期、主题、背景、卡片透明度等设置"
        }
        
        AlertDialog.Builder(this)
            .setTitle("导入配置")
            .setMessage(message)
            .setPositiveButton("导入") { _, _ ->
                applySettings(settings)
                if (hasTimeTableData && timeSlots != null && maxNodes != null) {
                    applyTimeTable(timeSlots, maxNodes)
                    Toast.makeText(this, "配置和时间表导入成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "配置导入成功", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("跳过") { _, _ ->
                Toast.makeText(this, "已跳过配置导入", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun applySettings(settings: AppSettings) {
        settingsManager.setCurrentSemester(settings.currentSemester)
        settingsManager.setDefaultWeek(settings.defaultWeek)
        settingsManager.setDefaultAlarmMinutes(settings.defaultAlarmMinutes)
        settingsManager.setAutoSwitchWeek(settings.autoSwitchWeek)
        settingsManager.setAlarmEnabled(settings.alarmEnabled)
        settingsManager.setCourseCardAlpha(settings.courseCardAlpha)
        settingsManager.setShowNonCurrentWeekCourses(settings.showNonCurrentWeekCourses)
        settingsManager.setNonCurrentWeekAlpha(settings.nonCurrentWeekAlpha)
        settingsManager.setFontSize(settings.fontSize)
        settingsManager.setSemesterStartDate(settings.semesterStartDate)
        settingsManager.saveCustomSemesters(settings.customSemesters)
        settingsManager.setCourseColorThemeIndex(settings.courseColorThemeIndex)
        settingsManager.setBackgroundThemeIndex(settings.backgroundThemeIndex)
        settingsManager.setBackgroundTypeString(settings.backgroundType)
        settingsManager.setCustomBackgroundPath(settings.customBackgroundPath)
    }

    private fun applyTimeTable(timeSlots: List<BackupData.TimeSlotData>, maxNodes: Int) {
        val timeTableManager = TimeTableManager.getInstance(this)
        // 转换时间槽数据
        val convertedTimeSlots = timeSlots.map {
            TimeTableManager.TimeSlot(
                node = it.node,
                startTime = it.startTime,
                endTime = it.endTime
            )
        }
        timeTableManager.saveTimeSlots(convertedTimeSlots)
        timeTableManager.setMaxNodes(maxNodes)
        // 重新生成时间轴
        generateTimeAxis()
        // 重新显示课程
        viewModel.loadCoursesForWeek(displayWeek)
    }

    private fun importFromJson(json: String) {
        try {
            val courses = com.cherry.wakeupschedule.service.ImportService.parseCoursesFromJson(json)
            if (courses.isNotEmpty()) {
                viewModel.addCourses(courses)
                Toast.makeText(this, "成功导入 ${courses.size} 门课程", Toast.LENGTH_LONG).show()
                // 更新小组件
                updateWidget()
            } else {
                Toast.makeText(this, "未找到有效课程数据", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 从教务系统刷新课表。
     * @param showError 失败时是否弹 Toast 提示用户
     */
    private fun refreshScheduleFromJwxt(showError: Boolean = false) {
        if (!com.cherry.wakeupschedule.service.JwxtAuthManager.isBound()) return

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = com.cherry.wakeupschedule.service.JwxtAuthManager.doWithAuth { client ->
                val selectedSemester = settingsManager.getCurrentSemester()
                val (year, termCode) =
                    com.cherry.wakeupschedule.service.JwxtImportService.getYearTermForSemester(selectedSemester)
                val term = com.gxu.jwxt.model.Term.fromCode(termCode)
                    ?: com.gxu.jwxt.model.Term.SPRING
                client.schedule().personal(year, term)
            }

            result.onSuccess { response ->
                val (courses, semesterStart) =
                    com.cherry.wakeupschedule.service.JwxtImportService.convertScheduleResponse(response)

                if (semesterStart != null && semesterStart > 0
                    && settingsManager.getSemesterStartDate() == 0L) {
                    settingsManager.setSemesterStartDate(semesterStart)
                }

                com.cherry.wakeupschedule.service.CourseDataManager.getInstance(this@MainActivity)
                    .replaceAllCourses(courses)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    currentWeek = calculateCurrentWeek()
                    displayWeek = currentWeek
                    allCourses = courses
                    viewModel.loadCoursesForWeek(displayWeek)
                    updateWeekDisplay()
                    updateDateDisplay()
                    updateWidget()
                    (application as App).registerAllCourseNotifications()
                }
            }.onFailure { e ->
                if (showError) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity, "刷新失败: ${e.message}", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * 更新桌面小组件
     */
    private fun updateWidget() {
        ScheduleWidgetUpdateService.triggerUpdate(this)
    }

    private fun startCountdown() {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownTickCount = 0
        countdownRunnable = object : Runnable {
            override fun run() {
                updateCountdown()
                countdownTickCount++
                countdownHandler.postDelayed(this, 1000)
            }
        }
        countdownRunnable?.let { countdownHandler.post(it) }
        updateCountdown()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun updateCountdown() {
        val currentDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val adjustedDayOfWeek = if (currentDayOfWeek == Calendar.SUNDAY) 7 else currentDayOfWeek - 1

        val now = Calendar.getInstance()
        val currentSeconds = now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val todayCourses = allCourses.filter { course ->
            course.dayOfWeek == adjustedDayOfWeek &&
            currentWeek >= course.startWeek &&
            currentWeek <= course.endWeek &&
            isCourseInCurrentWeekType(course, currentWeek)
        }.sortedBy { course -> getCourseStartMinutes(course) }

        var nextCourse: Course? = null
        var currentCourse: Course? = null

        for (course in todayCourses) {
            val startMinutes = getCourseStartMinutes(course)
            val endMinutes = getCourseEndMinutes(course)

            if (currentMinutes in startMinutes..endMinutes) {
                currentCourse = course
                break
            } else if (currentMinutes < startMinutes) {
                nextCourse = course
                break
            }
        }

        when {
            currentCourse != null -> {
                val endSeconds = getCourseEndMinutes(currentCourse) * 60
                val remainingSeconds = (endSeconds - currentSeconds).coerceAtLeast(0)
                binding.tvCountdown.visibility = View.VISIBLE
                binding.tvCountdown.text = getString(R.string.countdown_class_end, formatDurationSeconds(remainingSeconds))
            }
            nextCourse != null -> {
                val startSeconds = getCourseStartMinutes(nextCourse) * 60
                val remainingSeconds = (startSeconds - currentSeconds).coerceAtLeast(0)
                binding.tvCountdown.visibility = View.VISIBLE
                binding.tvCountdown.text = getString(R.string.countdown_format, formatDurationSeconds(remainingSeconds))
            }
            todayCourses.isNotEmpty() -> {
                binding.tvCountdown.visibility = View.VISIBLE
                binding.tvCountdown.text = getString(R.string.countdown_no_more_classes)
            }
            else -> {
                binding.tvCountdown.visibility = View.GONE
            }
        }

        if (countdownTickCount % 30 == 0) {
            if (currentViewState == "week" && allCourses.isNotEmpty()) {
                displayCourses(allCourses)
                syncTimeAxisHeight()
            }
            updateDateDisplay()
        }
    }

    private fun getCourseStartMinutes(course: Course): Int {
        return try {
            val timeTableManager = TimeTableManager.getInstance(this)
            val timeSlots = timeTableManager.getTimeSlots()
            val startSlot = timeSlots.find { it.node == course.startTime }
            if (startSlot != null) {
                val parts = startSlot.startTime.split(":")
                if (parts.size == 2) {
                    parts[0].toInt() * 60 + parts[1].toInt()
                } else {
                    (8 + course.startTime) * 60
                }
            } else {
                (8 + course.startTime) * 60
            }
        } catch (e: Exception) {
            (8 + course.startTime) * 60
        }
    }

    private fun getCourseEndMinutes(course: Course): Int {
        return try {
            val timeTableManager = TimeTableManager.getInstance(this)
            val timeSlots = timeTableManager.getTimeSlots()
            val endSlot = timeSlots.find { it.node == course.endTime }
            if (endSlot != null) {
                val parts = endSlot.endTime.split(":")
                if (parts.size == 2) {
                    parts[0].toInt() * 60 + parts[1].toInt()
                } else {
                    (8 + course.endTime) * 60 + 45
                }
            } else {
                (8 + course.endTime) * 60 + 45
            }
        } catch (e: Exception) {
            (8 + course.endTime) * 60 + 45
        }
    }

    private fun formatDurationSeconds(totalSeconds: Int): String {
        return when {
            totalSeconds >= 3600 -> {
                val hours = totalSeconds / 3600
                val mins = (totalSeconds % 3600) / 60
                val secs = totalSeconds % 60
                when {
                    mins > 0 && secs > 0 -> "${hours}小时${mins}分${secs}秒"
                    mins > 0 -> "${hours}小时${mins}分钟"
                    else -> "${hours}小时"
                }
            }
            totalSeconds >= 60 -> {
                val mins = totalSeconds / 60
                val secs = totalSeconds % 60
                if (secs > 0) "${mins}分${secs}秒" else "${mins}分钟"
            }
            else -> "${totalSeconds}秒"
        }
    }

    private fun isCourseInCurrentWeekType(course: Course, week: Int): Boolean {
        return when (course.weekType) {
            0 -> true
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    }
