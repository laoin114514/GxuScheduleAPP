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
import androidx.viewpager2.widget.ViewPager2
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.adapter.WeekPagerAdapter
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
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

    private var currentWeek = 1
    private var displayWeek = 1
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

        initViews(view)
        setupViewPager()
        setupObservers()
        updateDateTimeHeader()
        startCountdown()
    }

    private fun initViews(view: View) {
        viewPager = view.findViewById(R.id.view_pager)
        tvDate = view.findViewById(R.id.tv_date)
        tvWeekInfo = view.findViewById(R.id.tv_week_info)
        tvCountdown = view.findViewById(R.id.tv_countdown)

        view.findViewById<View>(R.id.btn_refresh).setOnClickListener {
            Toast.makeText(requireContext(), "刷新课表", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupViewPager() {
        currentWeek = calculateCurrentWeek()
        displayWeek = currentWeek
        val totalWeeks = settingsManager.getTotalWeeks()

        adapter = WeekPagerAdapter(this, totalWeeks)
        viewPager.adapter = adapter
        viewPager.setCurrentItem(currentWeek - 1, false)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                displayWeek = position + 1
                updateDateTimeHeader()
            }
        })
    }

    private fun setupObservers() {
        val viewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]
        viewModel.courses.observe(viewLifecycleOwner) { _ ->
            allCourses = CourseDataManager.getInstance(requireContext()).getAllCourses()
            // Fragments read directly from CourseDataManager, no need to update adapter
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
        val weekText = if (displayWeek == currentWeek) "第${displayWeek}周 (本周)"
        else "第${displayWeek}周"
        tvWeekInfo.text = weekText
        updateDateHeaderRow(displayWeek)
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

        // Update month label
        val monthLabel = root.findViewById<TextView>(R.id.tv_month_value)
        monthLabel?.text = "${cal.get(Calendar.MONTH) + 1}"

        dateViewIds.forEachIndexed { index, id ->
            val tv = root.findViewById<TextView>(id)
            tv.text = fmt.format(cal.time)

            if (displayWeek == currentWeek &&
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
                tv.setBackgroundResource(R.drawable.bg_date_selected)
            } else {
                tv.background = null
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
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
            .filter { it.dayOfWeek == adjustedDay && currentWeek in it.startWeek..it.endWeek }
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
