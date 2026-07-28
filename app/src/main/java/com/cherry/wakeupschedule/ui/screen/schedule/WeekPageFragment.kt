package com.cherry.wakeupschedule.ui.screen.schedule

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

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

        // ── 网格背景（仅首次） ──
        if (!backdropBuilt) {
            backdropBuilt = true
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
                timeView.findViewById<TextView>(R.id.tv_start_time).text =
                    timeSlot?.startTime ?: "08:00"
                timeView.findViewById<TextView>(R.id.tv_end_time).text =
                    timeSlot?.endTime ?: "08:45"
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

        // ── 课程卡片 ──
        val textColor = Color.WHITE
        val borderColor = Color.WHITE

        for (course in weekCourses) {
            val colorIndex = if (course.color > 0) (course.color - 1) % courseColors.size else 0
            val bgColor = courseColors[colorIndex]
            val span = (course.endTime - course.startTime + 1).coerceAtLeast(1)
            val cardHeight = cellHeight * span

            // 带白色描边的背景 drawable
            val cardBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 14f
                setStroke(2.dpToPx(), borderColor)
            }

            val cardView = CardView(ctx).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    rowSpec = GridLayout.spec(course.startTime - 1, span, 1f)
                    columnSpec = GridLayout.spec(course.dayOfWeek - 1, 1f)
                    width = 0
                    height = cardHeight
                    setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                }
                // 用 GradientDrawable 替代 CardView 默认背景
                background = cardBg
                // 保持 CardView 圆角与背景一致
                radius = 14f
                cardElevation = 0f
                // 防止 CardView 自带背景裁剪干扰
                setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                // 白色边框阴影效果：轻微 elevation + 白色 outline
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    outlineSpotShadowColor = borderColor
                }
                tag = TAG_COURSE_CARD

                setOnClickListener {
                    showCourseDetail(course)
                }
            }

            val textLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
                gravity = Gravity.CENTER
            }

            // 课程名称（粗体）
            textLayout.addView(TextView(ctx).apply {
                text = course.name
                textSize = 10f
                setTextColor(textColor)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            // 教室（有空间才显示）
            if (course.classroom.isNotBlank()) {
                textLayout.addView(TextView(ctx).apply {
                    text = course.classroom
                    textSize = 8f
                    setTextColor(textColor)
                    alpha = 0.85f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                })
            }

            // 教师（有空间才显示）
            if (course.teacher.isNotBlank()) {
                textLayout.addView(TextView(ctx).apply {
                    text = course.teacher
                    textSize = 8f
                    setTextColor(textColor)
                    alpha = 0.7f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                })
            }

            cardView.addView(textLayout)
            gridLayout.addView(cardView)
        }
    }

    // ── 课程详情 Bottom Sheet ──

    private fun showCourseDetail(course: Course) {
        val dialog = Dialog(requireContext(), R.style.BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.dialog_course_detail, null)

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

        // 学期开始日期 → 计算具体日期范围
        val settingsManager = SettingsManager(requireContext())
        val startDate = settingsManager.getSemesterStartDate()
        var dateRangeText = ""
        if (startDate > 0L) {
            val cal = Calendar.getInstance().apply { timeInMillis = startDate }
            cal.add(Calendar.WEEK_OF_YEAR, course.startWeek - 1)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.add(Calendar.DAY_OF_MONTH, course.dayOfWeek - 1)
            val startStr = "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            cal.add(Calendar.WEEK_OF_YEAR, course.endWeek - course.startWeek)
            val endStr = "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            dateRangeText = "$startStr - $endStr"
        }

        // 课程颜色
        val courseColors = ThemeManager.getCourseColors()
        val colorIndex = if (course.color > 0) (course.color - 1) % courseColors.size else 0
        val courseColor = courseColors[colorIndex]

        sheetView.findViewById<View>(R.id.color_indicator)
            ?.setBackgroundColor(courseColor)
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
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 底部滑入动画
            setWindowAnimations(R.style.BottomSheetAnimation)
            // 点击灰罩关闭
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setDimAmount(0.5f)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 点击外部区域关闭
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
        }
        // 点击灰罩区域关闭
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        dialog.show()
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}
