package com.cherry.wakeupschedule.ui.screen.schedule

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtImportService
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.adapter.WeekPagerAdapter
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tvDate: TextView
    private lateinit var tvWeekInfo: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: WeekPagerAdapter
    private lateinit var courseViewModel: CourseViewModel

    private var allCourses: List<Course> = emptyList()

    private val dateFormat = SimpleDateFormat("yyyy/M/d", Locale.getDefault())
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())
        courseViewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]

        initViews(view)
        setupViewPager()
        setupObservers()
        updateDateTimeHeader()
        startCountdown()
    }

    override fun onResume() {
        super.onResume()
        // 不在 onResume 触发教务刷新，避免每次切tab都重建课表
        // 手动刷新按钮 + 首次启动已覆盖数据更新场景
    }

    private fun initViews(view: View) {
        viewPager = view.findViewById(R.id.view_pager)
        tvDate = view.findViewById(R.id.tv_date)
        tvWeekInfo = view.findViewById(R.id.tv_week_info)
        tvCountdown = view.findViewById(R.id.tv_countdown)

        view.findViewById<View>(R.id.btn_refresh).setOnClickListener {
            refreshScheduleFromJwxt(showError = true)
        }

        view.findViewById<View>(R.id.btn_menu).setOnClickListener {
            showMenuSheet()
        }
    }

    /**
     * 获取当前周数（便捷访问 ViewModel 或计算）
     */
    private fun getCurrentWeek(): Int {
        if (courseViewModel.currentWeek == 0) {
            courseViewModel.currentWeek = calculateCurrentWeek()
        }
        return courseViewModel.currentWeek
    }

    /**
     * 获取当前显示的周数。
     * ViewModel 作用域为 Activity，tab 切换保持位置，进程死亡后重建回到当前周。
     */
    private fun getDisplayWeek(): Int {
        if (courseViewModel.displayWeek == 0) {
            courseViewModel.displayWeek = calculateCurrentWeek()
        }
        return courseViewModel.displayWeek
    }

    private fun setDisplayWeek(week: Int) {
        courseViewModel.displayWeek = week
    }

    private fun setupViewPager() {
        val currentWk = calculateCurrentWeek()
        courseViewModel.currentWeek = currentWk

        // ViewModel 未初始化（新鲜启动）→ 显示当前周
        // ViewModel 已有值（tab 切换归来）→ 保持位置
        val displayWk = getDisplayWeek()
        val totalWeeks = settingsManager.getTotalWeeks()

        adapter = WeekPagerAdapter(totalWeeks)
        // 仅预加载相邻1页（3页总量），减少tab切换时的初始构建压力
        viewPager.offscreenPageLimit = 1

        // 延迟到下一帧设置 adapter + 当前页，让tab切换动画先完成
        viewPager.post {
            viewPager.adapter = adapter
            viewPager.setCurrentItem(displayWk - 1, false)
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setDisplayWeek(position + 1)
                updateDateTimeHeader()
            }
        })
    }

    private fun setupObservers() {
        val viewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]
        viewModel.courses.observe(viewLifecycleOwner) { _ ->
            allCourses = CourseDataManager.getInstance(requireContext()).getAllCourses()
            adapter.updateData(allCourses)
        }
    }

    private fun calculateCurrentWeek(): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return 1
        val diffDays = ((System.currentTimeMillis() - startDate) / 86400000L).toInt()
        return (diffDays / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks())
    }

    private fun updateDateTimeHeader() {
        val cal = Calendar.getInstance()
        tvDate.text = dateFormat.format(cal.time)
        val displayWk = getDisplayWeek()
        val currentWk = getCurrentWeek()

        val rangeStatus = getSemesterRangeStatus()
        val weekText = when {
            rangeStatus < 0 -> "第${displayWk}周 该学期未开始"
            rangeStatus > 0 -> "第${displayWk}周 该学期已结束"
            displayWk == currentWk -> "第${displayWk}周 (本周)"
            else -> "第${displayWk}周"
        }
        tvWeekInfo.text = weekText
        updateDateHeaderRow(displayWk)
    }

    /** 返回 -1=未开始, 0=进行中, 1=已结束 */
    private fun getSemesterRangeStatus(): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return 0 // 未设置学期日期，不判定
        val totalWeeks = settingsManager.getTotalWeeks()
        val now = System.currentTimeMillis()
        val endDate = startDate + totalWeeks * 7L * 86400000L
        return when {
            now < startDate -> -1
            now > endDate -> 1
            else -> 0
        }
    }

    private fun updateDateHeaderRow(week: Int) {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return

        val cal = Calendar.getInstance().apply {
            timeInMillis = startDate
            add(Calendar.WEEK_OF_YEAR, week - 1)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }

        val dateViewIds = intArrayOf(
            R.id.tv_date_1, R.id.tv_date_2, R.id.tv_date_3,
            R.id.tv_date_4, R.id.tv_date_5, R.id.tv_date_6, R.id.tv_date_7
        )
        val today = Calendar.getInstance()
        val fmt = SimpleDateFormat("M/d", Locale.getDefault())

        val root = view ?: return

        val yearLabel = root.findViewById<TextView>(R.id.tv_year_value)
        yearLabel?.text = "${cal.get(Calendar.YEAR)}"

        dateViewIds.forEachIndexed { _, id ->
            val tv = root.findViewById<TextView>(id)
            tv.text = fmt.format(cal.time)

            if (getDisplayWeek() == getCurrentWeek() &&
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
                tv.setBackgroundResource(R.drawable.bg_date_selected)
                val typedValue = android.util.TypedValue()
                root.context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurface, typedValue, true
                )
                tv.setTextColor(typedValue.data)
            } else {
                tv.background = null
                val typedValue = android.util.TypedValue()
                root.context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true
                )
                tv.setTextColor(typedValue.data)
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun refreshScheduleFromJwxt(showError: Boolean) {
        if (!JwxtAuthManager.isBound()) return
        val curSem = SemesterManager.getCurrent() ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val result = JwxtImportService.fetchAndSaveScheduleForSemester(requireContext(), curSem)
            withContext(Dispatchers.Main) {
                result.onSuccess { count ->
                    courseViewModel.currentWeek = calculateCurrentWeek()
                    allCourses = CourseDataManager.getInstance(requireContext()).getAllCourses()
                    adapter.updateData(allCourses)
                    updateDateTimeHeader()
                    (requireActivity().application as App).registerAllCourseNotifications()
                    if (showError) {
                        Toast.makeText(requireContext(),
                            "成功导入 ${count} 门课程", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { e ->
                    if (showError) {
                        Toast.makeText(requireContext(),
                            "刷新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ── 课表菜单 Bottom Sheet ─────────────────────────────

    /**
     * 显示课表菜单底部弹窗，包含周数滑块和学期选择。
     * 参考 SchedulePageDetailDialog 的 BottomSheet 模式。
     */
    private fun showMenuSheet() {
        val ctx = requireContext()
        val dialog = Dialog(ctx, R.style.BottomSheetDialog)
        val inflater = LayoutInflater.from(ctx)
        val sheetView = inflater.inflate(R.layout.bottom_sheet_schedule_menu, null)
        val density = ctx.resources.displayMetrics.density

        // 顶部圆角背景
        val topRadius = 20 * density
        val sheetBg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        ctx.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

        val totalWeeks = settingsManager.getTotalWeeks()
        val currentDisplayWeek = getDisplayWeek()

        // ── 周数滑块 ──
        val tvWeekLabel = sheetView.findViewById<TextView>(R.id.tv_week_label)
        val sbWeek = sheetView.findViewById<SeekBar>(R.id.sb_week)

        tvWeekLabel.text = "第 $currentDisplayWeek 周"
        sbWeek.max = totalWeeks - 1
        sbWeek.progress = currentDisplayWeek - 1

        sbWeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val week = progress + 1
                tvWeekLabel.text = "第 $week 周"
                if (fromUser) {
                    setDisplayWeek(week)
                    viewPager.setCurrentItem(progress, false)
                    updateDateTimeHeader()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ── 学期选择（横向滑动） ──
        val llSemesterList = sheetView.findViewById<LinearLayout>(R.id.ll_semester_list)
        val semesters = SemesterManager.getAll()
        val currentSemesterIndex = settingsManager.getCurrentSemesterIndex()

        // 色块颜色跟随主题（浅色/深色自适应）
        val blockColor = android.util.TypedValue().also { tv ->
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceVariant, tv, true
            )
        }.data

        val blockSize = (52 * density).toInt()
        val itemMarginEnd = (14 * density).toInt()
        val blockRadius = (14 * density).toFloat()
        val labelTextSize = 13f

        semesters.forEachIndexed { index, sem ->
            val isSelected = index == currentSemesterIndex

            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = itemMarginEnd }
                isClickable = true
                isFocusable = true
            }

            // 圆角色块
            val block = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(blockSize, blockSize).apply {
                    bottomMargin = (8 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = blockRadius
                    setColor(blockColor)
                    if (isSelected) {
                        ctx.theme.resolveAttribute(
                            com.google.android.material.R.attr.colorPrimary, typedValue, true
                        )
                        setStroke((2.5f * density).toInt(), typedValue.data)
                    }
                }
            }
            item.addView(block)

            // 标签文字（只显示前三个字，如"大一上"）
            val label = TextView(ctx).apply {
                text = sem.label.take(3)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelTextSize)
                gravity = Gravity.CENTER
                ctx.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurface, typedValue, true
                )
                setTextColor(typedValue.data)
            }
            item.addView(label)

            // 选中标记
            if (isSelected) {
                val dot = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (6 * density).toInt(), (6 * density).toInt()
                    ).apply { topMargin = (4 * density).toInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        ctx.theme.resolveAttribute(
                            com.google.android.material.R.attr.colorPrimary, typedValue, true
                        )
                        setColor(typedValue.data)
                    }
                }
                item.addView(dot)
            }

            item.setOnClickListener {
                if (isSelected) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                // 切换学期
                settingsManager.setCurrentSemesterIndex(index)
                CourseDataManager.getInstance(ctx).switchSemester(sem.id)

                // 若该学期日期信息为空，从教务获取课表
                if (sem.startDate == 0L || sem.totalWeeks == 0) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = JwxtImportService.fetchAndSaveScheduleForSemester(ctx, sem)
                        withContext(Dispatchers.Main) {
                            result.onSuccess { _ ->
                                // 更新 ViewPager 总页数
                                adapter = WeekPagerAdapter(settingsManager.getTotalWeeks())
                                viewPager.adapter = adapter
                                adapter.updateData(CourseDataManager.getInstance(ctx).getAllCourses())
                            }.onFailure { e ->
                                Toast.makeText(ctx, "获取课表失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                // 调到第一周
                courseViewModel.currentWeek = calculateCurrentWeek()
                setDisplayWeek(1)
                viewPager.setCurrentItem(0, false)
                updateDateTimeHeader()
                dialog.dismiss()
            }

            llSemesterList.addView(item)
        }

        // ── 组装容器 ──
        dialog.setContentView(sheetView)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnClickListener { dialog.dismiss() }
        })
        container.addView(sheetView)
        dialog.setContentView(container)

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.BottomSheetAnimation)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setDimAmount(0.5f)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun startCountdown() {
        countdownRunnable = object : Runnable {
            override fun run() {
                updateCountdown()
                countdownHandler.postDelayed(this, 1000)
            }
        }
        countdownHandler.post(countdownRunnable!!)
    }

    private fun updateCountdown() {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val adjustedDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
        val currentSeconds = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)

        val todayCourses = allCourses
            .filter { it.dayOfWeek == adjustedDay && it.isActiveInWeek(getCurrentWeek()) }
            .sortedBy { getCourseStartMinutes(it) }

        val currentCourse = todayCourses.find {
            val start = getCourseStartMinutes(it)
            val end = getCourseEndMinutes(it)
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) in start..end
        }
        val nextCourse = todayCourses.find {
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) < getCourseStartMinutes(it)
        }

        when {
            currentCourse != null -> {
                val endSeconds = getCourseEndMinutes(currentCourse) * 60
                val remaining = (endSeconds - currentSeconds).coerceAtLeast(0)
                tvCountdown.visibility = View.VISIBLE
                tvCountdown.text = "距下课: ${formatDuration(remaining)}"
            }
            nextCourse != null -> {
                val startSeconds = getCourseStartMinutes(nextCourse) * 60
                val remaining = (startSeconds - currentSeconds).coerceAtLeast(0)
                tvCountdown.visibility = View.VISIBLE
                tvCountdown.text = "下节课: ${formatDuration(remaining)}"
            }
            todayCourses.isNotEmpty() -> {
                tvCountdown.visibility = View.VISIBLE
                tvCountdown.text = "今日课程已结束"
            }
            else -> tvCountdown.visibility = View.GONE
        }
    }

    private fun getCourseStartMinutes(course: Course): Int {
        val slots = TimeTableManager.getInstance(requireContext()).getTimeSlots()
        val slot = slots.find { it.node == course.startTime }
        return if (slot != null) {
            val parts = slot.startTime.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } else (8 + course.startTime) * 60
    }

    private fun getCourseEndMinutes(course: Course): Int {
        val slots = TimeTableManager.getInstance(requireContext()).getTimeSlots()
        val slot = slots.find { it.node == course.endTime }
        return if (slot != null) {
            val parts = slot.endTime.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } else (8 + course.endTime) * 60 + 45
    }

    private fun formatDuration(totalSeconds: Int): String {
        return when {
            totalSeconds >= 3600 -> "${totalSeconds / 3600}小时${(totalSeconds % 3600) / 60}分钟"
            totalSeconds >= 60 -> "${totalSeconds / 60}分${totalSeconds % 60}秒"
            else -> "${totalSeconds}秒"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
    }
}
