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
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager

class WeekPageFragment : Fragment() {

    private var weekNumber: Int = 1
    private var courses: List<Course> = emptyList()

    companion object {
        private const val ARG_WEEK = "week"
        private const val ARG_COURSES = "courses"

        fun newInstance(week: Int, courses: List<Course>): WeekPageFragment {
            return WeekPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_WEEK, week)
                    putSerializable(ARG_COURSES, ArrayList(courses))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        weekNumber = arguments?.getInt(ARG_WEEK, 1) ?: 1
        @Suppress("UNCHECKED_CAST")
        courses = (arguments?.getSerializable(ARG_COURSES) as? ArrayList<Course>) ?: emptyList()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_week_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildSchedule(view)
    }

    private fun buildSchedule(view: View) {
        val gridLayout = view.findViewById<GridLayout>(R.id.course_grid)
        val timeAxis = view.findViewById<LinearLayout>(R.id.time_axis)
        val emptyView = view.findViewById<LinearLayout>(R.id.layout_empty)

        timeAxis.removeAllViews()
        gridLayout.removeAllViews()

        val timeTableManager = TimeTableManager.getInstance(requireContext())
        val maxNodes = timeTableManager.getMaxNodes()
        val cellHeight = resources.getDimensionPixelSize(R.dimen.course_cell_height)
        val courseColors = ThemeManager.getCourseColors()

        val weekCourses = courses.filter { weekNumber in it.startWeek..it.endWeek }

        for (node in 1..maxNodes) {
            val timeSlot = timeTableManager.getTimeSlots().find { it.node == node }
            val timeView = layoutInflater.inflate(R.layout.item_time_slot, timeAxis, false) as LinearLayout
            timeView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cellHeight
            )
            timeView.findViewById<TextView>(R.id.tv_node).text = node.toString()
            timeView.findViewById<TextView>(R.id.tv_start_time).text = timeSlot?.startTime ?: "08:00"
            timeView.findViewById<TextView>(R.id.tv_end_time).text = timeSlot?.endTime ?: "08:45"
            timeAxis.addView(timeView)
        }

        for (row in 0 until maxNodes) {
            for (col in 0 until 7) {
                val cell = View(requireContext()).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        rowSpec = GridLayout.spec(row, 1f)
                        columnSpec = GridLayout.spec(col, 1f)
                        width = 0; height = cellHeight
                    }
                    setBackgroundResource(R.drawable.bg_grid_cell)
                }
                gridLayout.addView(cell)
            }
        }

        if (weekCourses.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        val palette = ThemeManager.currentPalette(requireContext())
        weekCourses.forEachIndexed { index, course ->
            val color = courseColors[index % courseColors.size]
            val cardView = CardView(requireContext()).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    rowSpec = GridLayout.spec(course.startTime - 1,
                        (course.endTime - course.startTime + 1).coerceAtLeast(1), 1f)
                    columnSpec = GridLayout.spec(course.dayOfWeek - 1, 1f)
                    width = 0; height = 0
                    setMargins(2, 2, 2, 2)
                }
                setCardBackgroundColor(color)
                radius = 8f
                cardElevation = 1f
            }

            val textLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4, 4, 4, 4)
                gravity = android.view.Gravity.CENTER
                addView(TextView(requireContext()).apply {
                    text = course.name; textSize = 10f
                    setTextColor(palette.onPrimaryContainer)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER
                })
            }

            cardView.addView(textLayout)
            gridLayout.addView(cardView)
        }
    }
}
