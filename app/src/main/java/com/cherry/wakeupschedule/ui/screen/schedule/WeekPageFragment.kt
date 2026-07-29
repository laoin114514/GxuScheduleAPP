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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.widget.GridBackgroundView
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
        val gridBg = view.findViewById<GridBackgroundView>(R.id.grid_bg)
        val timeAxis = view.findViewById<LinearLayout>(R.id.time_axis)
        val courseContainer = view.findViewById<FrameLayout>(R.id.course_container)
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

            // ── 配置 Canvas 网格背景 ──
            gridBg.rowCount = maxNodes
            gridBg.columnCount = 7
            // 获取主题中的 outline 颜色作为网格线颜色
            val typedValue = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOutline, typedValue, true
            )
            gridBg.gridColor = ColorUtils.setAlphaComponent(typedValue.data, 60)

            // ── 构建时间轴 ──
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
        } else {
            // 数据刷新时仅清除课程卡片，保留网格背景和时间轴
            courseContainer.removeAllViews()
        }

        if (weekCourses.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        // ── 等 layout 完成后计算 cell 宽度再放置卡片 ──
        courseContainer.post {
            val contentWidth = courseContainer.width
            if (contentWidth <= 0) return@post

            courseContainer.removeAllViews()

            val gapPx = 2.dpToPx()
            val cellWidth = contentWidth / 7f
            val textColor = Color.WHITE
            val strokeColor = 0x80FFFFFF.toInt()  // 50% white border

            for (course in weekCourses) {
                val colorIndex = if (course.color > 0) (course.color - 1) % courseColors.size else 0
                val baseColor = courseColors[colorIndex]
                val bgColor = ColorUtils.setAlphaComponent(baseColor, 128)

                val rowStart = (course.startTime - 1).coerceIn(0, maxNodes - 1)
                val span = (course.endTime - course.startTime + 1).coerceAtLeast(1)
                    .coerceAtMost(maxNodes - rowStart)
                val dayCol = (course.dayOfWeek - 1).coerceIn(0, 6)

                val cardW = (cellWidth - 2 * gapPx).toInt()
                val cardH = cellHeight * span - 2 * gapPx
                val leftMargin = (dayCol * cellWidth + gapPx).toInt()
                val topMargin = rowStart * cellHeight + gapPx

                val cardBg = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 14f
                    setStroke(2.dpToPx(), strokeColor)
                }

                // ── 轻量课程卡片：FrameLayout + TextView，取代 CardView ──
                val card = FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(cardW, cardH).apply {
                        setMargins(leftMargin, topMargin, 0, 0)
                    }
                    background = cardBg
                    setOnClickListener { showCourseDetail(course) }
                }

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
                card.addView(textView)
                courseContainer.addView(card)
            }
        }
    }

    // ── 课程详情 Bottom Sheet ──

    private fun showCourseDetail(course: Course) {
        val dialog = Dialog(requireContext(), R.style.BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.dialog_course_detail, null)

        val topRadius = 20.dpToPx().toFloat()
        val sheetBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

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

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(spacer)
        container.addView(sheetView)
        dialog.setContentView(container)

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
