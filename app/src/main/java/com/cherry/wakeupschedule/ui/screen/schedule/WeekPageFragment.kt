package com.cherry.wakeupschedule.ui.screen.schedule

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.AccountRepository
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class WeekPageFragment : Fragment() {

    private var weekNumber: Int = 1
    private var backdropBuilt = false

    companion object {
        private const val ARG_WEEK = "week"
        private const val TAG_GRID_CELL = "grid_cell"
        private const val TAG_COURSE_CARD = "course_card"

        fun newInstance(week: Int): WeekPageFragment {
            return WeekPageFragment().apply {
                arguments = Bundle().apply { putInt(ARG_WEEK, week) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        weekNumber = arguments?.getInt(ARG_WEEK, 1) ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_week_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo = AccountRepository.getInstance(requireContext())
        if (!repo.hasActiveAccount()) {
            view.findViewById<LinearLayout>(R.id.layout_empty)?.visibility = View.GONE
            return
        }

        val dataManager = CourseDataManager.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            dataManager.coursesFlow.collectLatest {
                buildSchedule(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        backdropBuilt = false
    }

    private fun buildSchedule(view: View) {
        val gridLayout = view.findViewById<GridLayout>(R.id.course_grid)
        val timeAxis = view.findViewById<LinearLayout>(R.id.time_axis)
        val emptyView = view.findViewById<LinearLayout>(R.id.layout_empty)

        val timeTableManager = TimeTableManager.getInstance(requireContext())
        val maxNodes = timeTableManager.getMaxNodes()
        val cellHeight = resources.getDimensionPixelSize(R.dimen.course_cell_height)
        val courseColors = ThemeManager.getCourseColors()
        val ctx = requireContext()

        val allCourses = CourseDataManager.getInstance(ctx).getAllCourses()
        val weekCourses = allCourses.filter { course ->
            val isInWeekRange = weekNumber in course.startWeek..course.endWeek
            val isWeekTypeMatch = when (course.weekType) {
                0 -> true
                1 -> weekNumber % 2 == 1
                2 -> weekNumber % 2 == 0
                else -> true
            }
            isInWeekRange && isWeekTypeMatch
        }

        if (!backdropBuilt) {
            backdropBuilt = true
            // 动态设置行数，匹配实际时间表节数
            gridLayout.rowCount = maxNodes
            timeAxis.removeAllViews()
            for (node in 1..maxNodes) {
                val timeSlot = timeTableManager.getTimeSlots().find { it.node == node }
                val timeView = layoutInflater.inflate(
                    R.layout.item_time_slot, timeAxis, false
                ) as LinearLayout
                timeView.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, cellHeight
                )
                timeView.findViewById<TextView>(R.id.tv_node).text = node.toString()
                val start = timeSlot?.startTime?.takeIf { it.isNotBlank() }
                val end = timeSlot?.endTime?.takeIf { it.isNotBlank() }
                timeView.findViewById<TextView>(R.id.tv_start_time).text = start ?: "--:--"
                timeView.findViewById<TextView>(R.id.tv_end_time).text = end ?: "--:--"
                timeAxis.addView(timeView)
            }

            for (row in 0 until maxNodes) {
                for (col in 0 until 7) {
                    val cell = View(ctx).apply {
                        layoutParams = GridLayout.LayoutParams().apply {
                            rowSpec = GridLayout.spec(row, 1f)
                            columnSpec = GridLayout.spec(col, 1f)
                            width = 0
                            height = cellHeight
                        }
                        setBackgroundResource(R.drawable.bg_grid_cell)
                        tag = TAG_GRID_CELL
                    }
                    gridLayout.addView(cell)
                }
            }
        } else {
            for (i in gridLayout.childCount - 1 downTo 0) {
                if (gridLayout.getChildAt(i).tag == TAG_COURSE_CARD) {
                    gridLayout.removeViewAt(i)
                }
            }
        }

        if (weekCourses.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        val textColor = Color.WHITE
        val strokeColor = 0x80FFFFFF.toInt()  // 50% 白色
        val marginPx = 2.dpToPx()

        for (course in weekCourses) {
            val colorIndex = if (course.color > 0) (course.color - 1) % courseColors.size else 0
            val baseColor = courseColors[colorIndex]
            // WakeUp: 50% alpha 背景 + 白色文字
            val bgColor = ColorUtils.setAlphaComponent(baseColor, 128)
            // 校验数据，防止越界崩溃
            val rowStart = (course.startTime - 1).coerceIn(0, maxNodes - 1)
            val span = (course.endTime - course.startTime + 1).coerceAtLeast(1)
                .coerceAtMost(maxNodes - rowStart)
            val dayCol = (course.dayOfWeek - 1).coerceIn(0, 6)
            val cardHeight = cellHeight * span

            val cardBg = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 14f
                setStroke(2.dpToPx(), strokeColor)
            }

            val cardView = CardView(ctx).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    rowSpec = GridLayout.spec(rowStart, span, 1f)
                    columnSpec = GridLayout.spec(dayCol, 1f)
                    width = 0
                    height = cardHeight
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                background = cardBg
                radius = 14f
                cardElevation = 0f
                setCardBackgroundColor(Color.TRANSPARENT)
                tag = TAG_COURSE_CARD
                setOnClickListener { showCourseDetail(course) }
            }

            // 单行信息拼接，自动换行占满卡片
            val parts = mutableListOf<String>()
            parts.add(course.name)
            if (course.classroom.isNotBlank()) parts.add(course.classroom)
            if (course.teacher.isNotBlank()) parts.add(course.teacher)
            val infoText = parts.joinToString("\n")

            val textView = TextView(ctx).apply {
                text = infoText
                textSize = 10f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            }
            cardView.addView(textView)
            gridLayout.addView(cardView)
        }
    }

    // ── 课程详情 Bottom Sheet ──

    private fun showCourseDetail(course: Course) {
        val dialog = Dialog(requireContext(), R.style.BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.dialog_course_detail, null)

        // 上方圆角背景
        val topRadius = 20.dpToPx().toFloat()
        val sheetBg = GradientDrawable().apply {
            setColor(Color.WHITE) // 会被 theme surface 覆盖，先设白色兜底
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        // 使用 theme surface 颜色
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

        // 获取时间段信息
        val timeTableManager = TimeTableManager.getInstance(requireContext())
        val startSlot = timeTableManager.getTimeSlots().find { it.node == course.startTime }
        val endSlot = timeTableManager.getTimeSlots().find { it.node == course.endTime }
        val timeText = if (startSlot != null && endSlot != null) {
            "${startSlot.startTime} - ${endSlot.endTime}"
        } else {
            "第${course.startTime}-${course.endTime}节"
        }

        val weekDays = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val dayText = weekDays.getOrElse(course.dayOfWeek) { "" }
        val weekText = "第${course.startWeek}-${course.endWeek}周" +
                when (course.weekType) {
                    1 -> " (单周)"
                    2 -> " (双周)"
                    else -> ""
                }

        val settingsManager = SettingsManager(requireContext())
        val startDate = settingsManager.getSemesterStartDate()
        var dateRangeText = ""
        if (startDate > 0L) {
            val cal = Calendar.getInstance().apply { timeInMillis = startDate }
            cal.add(Calendar.WEEK_OF_YEAR, course.startWeek - 1)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.add(Calendar.DAY_OF_MONTH, course.dayOfWeek - 1)
            val startFmt = SimpleDateFormat("M/d", Locale.getDefault())
            val startStr = startFmt.format(cal.time)
            cal.add(Calendar.WEEK_OF_YEAR, course.endWeek - course.startWeek)
            val endStr = startFmt.format(cal.time)
            dateRangeText = "$startStr - $endStr"
        }

        val courseColors = ThemeManager.getCourseColors()
        val colorIndex = if (course.color > 0) (course.color - 1) % courseColors.size else 0
        val courseColor = courseColors[colorIndex]

        sheetView.findViewById<View>(R.id.color_indicator)?.setBackgroundColor(courseColor)
        sheetView.findViewById<TextView>(R.id.tv_detail_name)?.text = course.name
        sheetView.findViewById<TextView>(R.id.tv_detail_teacher)?.text =
            if (course.teacher.isNotBlank()) course.teacher else "未知"
        sheetView.findViewById<TextView>(R.id.tv_detail_classroom)?.text =
            if (course.classroom.isNotBlank()) course.classroom else "未知"
        sheetView.findViewById<TextView>(R.id.tv_detail_time)?.text = "$dayText $timeText"
        sheetView.findViewById<TextView>(R.id.tv_detail_week)?.text = weekText
        if (dateRangeText.isNotEmpty()) {
            sheetView.findViewById<TextView>(R.id.tv_detail_date_range)?.text = dateRangeText
        }

        dialog.setContentView(sheetView)

        // 将 sheetView 包在 LinearLayout 中：上方透明可点击区域 + 下方内容
        // 确保弹窗占满下半屏且下方无漏缝
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 从原 parent 移除 sheetView
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        sheetView.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 上方：透明可点击区域，点击关闭
        val spacer = View(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(spacer)
        container.addView(sheetView)
        dialog.setContentView(container)

        // 窗口全屏，内容在下方
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
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

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}
