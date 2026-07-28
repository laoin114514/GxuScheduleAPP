package com.cherry.wakeupschedule.ui.screen.schedule

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtImportService
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
    private var isFirstInit = true

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

        // 自动从教务刷新（静默），仅首次
        if (isFirstInit) {
            isFirstInit = false
            refreshScheduleFromJwxt(showError = false)
        }
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

        adapter = WeekPagerAdapter(this, totalWeeks)
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
        val weekText = if (displayWk == currentWk) "第${displayWk}周 (本周)"
        else "第${displayWk}周"
        tvWeekInfo.text = weekText
        updateDateHeaderRow(displayWk)
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

        val monthLabel = root.findViewById<TextView>(R.id.tv_month_value)
        monthLabel?.text = "${cal.get(Calendar.MONTH) + 1}"

        dateViewIds.forEachIndexed { _, id ->
            val tv = root.findViewById<TextView>(id)
            tv.text = fmt.format(cal.time)

            if (getDisplayWeek() == getCurrentWeek() &&
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
                tv.setBackgroundResource(R.drawable.bg_date_selected)
            } else {
                tv.background = null
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun refreshScheduleFromJwxt(showError: Boolean) {
        if (!JwxtAuthManager.isBound()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val result = JwxtAuthManager.doWithAuth { client ->
                val selectedSemester = settingsManager.getCurrentSemester()
                val (year, termCode) = JwxtImportService.getYearTermForSemester(selectedSemester)
                val term = com.gxu.jwxt.model.Term.fromCode(termCode)
                    ?: com.gxu.jwxt.model.Term.SPRING
                client.schedule().personal(year, term)
            }

            withContext(Dispatchers.Main) {
                result.onSuccess { response ->
                    val (courses, semesterStart) = JwxtImportService.convertScheduleResponse(response)

                    if (semesterStart != null && semesterStart > 0
                        && settingsManager.getSemesterStartDate() == 0L) {
                        settingsManager.setSemesterStartDate(semesterStart)
                    }

                    CourseDataManager.getInstance(requireContext()).replaceAllCourses(courses)

                    // 手动刷新时跳回当前周，自动刷新时保持用户浏览的周次
                    val currentWk = calculateCurrentWeek()
                    courseViewModel.currentWeek = currentWk
                    if (showError) {
                        setDisplayWeek(currentWk)
                        viewPager.setCurrentItem(currentWk - 1, false)
                    }
                    allCourses = courses
                    updateDateTimeHeader()

                    (requireActivity().application as App).registerAllCourseNotifications()

                    if (showError) {
                        Toast.makeText(requireContext(),
                            "成功导入 ${courses.size} 门课程", Toast.LENGTH_SHORT).show()
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
            .filter { it.dayOfWeek == adjustedDay && getCurrentWeek() in it.startWeek..it.endWeek }
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
