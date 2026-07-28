package com.cherry.wakeupschedule.ui.screen.schedule

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WeekPageFragment : Fragment() {

    private var weekNumber: Int = 1

    /** 网格背景（时间轴 + 空白格子）是否已构建，避免重复创建静态结构 */
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

        // 监听课程数据变化，自动重建（仅重建课程卡片，网格背景缓存）
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
        val palette = ThemeManager.currentPalette(requireContext())

        val allCourses = CourseDataManager.getInstance(requireContext()).getAllCourses()
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

        // ── 网格背景：仅首次构建，后续 rebuild 跳过 ──
        if (!backdropBuilt) {
            backdropBuilt = true

            // 时间轴
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

            // 空白网格单元格（仅背景，无内容变化）
            for (row in 0 until maxNodes) {
                for (col in 0 until 7) {
                    val cell = View(requireContext()).apply {
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
            // ── 增量更新：仅移除课程卡片，保留网格背景 ──
            for (i in gridLayout.childCount - 1 downTo 0) {
                if (gridLayout.getChildAt(i).tag == TAG_COURSE_CARD) {
                    gridLayout.removeViewAt(i)
                }
            }
        }

        // ── 空状态 ──
        if (weekCourses.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        // ── 课程卡片（每次重建） ──
        for (course in weekCourses) {
            val colorIndex = if (course.color > 0) (course.color - 1) % courseColors.size else 0
            val color = courseColors[colorIndex]
            val span = (course.endTime - course.startTime + 1).coerceAtLeast(1)
            val cardHeight = cellHeight * span

            val cardView = CardView(requireContext()).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    rowSpec = GridLayout.spec(course.startTime - 1, span, 1f)
                    columnSpec = GridLayout.spec(course.dayOfWeek - 1, 1f)
                    width = 0
                    height = cardHeight
                    setMargins(2, 2, 2, 2)
                }
                setCardBackgroundColor(color)
                radius = 8f
                cardElevation = 1f
                tag = TAG_COURSE_CARD
            }

            val textLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4, 4, 4, 4)
                gravity = android.view.Gravity.CENTER
                addView(TextView(requireContext()).apply {
                    text = course.name
                    textSize = 10f
                    setTextColor(palette.onPrimaryContainer)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER
                })
            }

            cardView.addView(textLayout)
            gridLayout.addView(cardView)
        }
    }
}
