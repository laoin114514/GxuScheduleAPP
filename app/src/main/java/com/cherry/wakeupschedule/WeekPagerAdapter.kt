package com.cherry.wakeupschedule

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.model.Course

/**
 * ViewPager2 适配器，用于左右滑动切换周次
 */
class WeekPagerAdapter(
    private val onCourseClick: (Course) -> Unit,
    private val onCourseLongClick: (Course) -> Unit
) : RecyclerView.Adapter<WeekPagerAdapter.WeekViewHolder>() {

    private var courses: List<Course> = emptyList()
    private var currentDisplayWeek: Int = 1
    private var actualCurrentWeek: Int = 1

    // 课程颜色数组
    private val courseColors = intArrayOf(
        android.graphics.Color.parseColor("#E57373"),
        android.graphics.Color.parseColor("#F06292"),
        android.graphics.Color.parseColor("#BA68C8"),
        android.graphics.Color.parseColor("#9575CD"),
        android.graphics.Color.parseColor("#7986CB"),
        android.graphics.Color.parseColor("#64B5F6"),
        android.graphics.Color.parseColor("#4FC3F7"),
        android.graphics.Color.parseColor("#4DD0E1"),
        android.graphics.Color.parseColor("#4DB6AC"),
        android.graphics.Color.parseColor("#81C784"),
        android.graphics.Color.parseColor("#AED581"),
        android.graphics.Color.parseColor("#DCE775"),
        android.graphics.Color.parseColor("#FFF176"),
        android.graphics.Color.parseColor("#FFD54F"),
        android.graphics.Color.parseColor("#FFB74D"),
        android.graphics.Color.parseColor("#FF8A65")
    )

    fun setData(courses: List<Course>, displayWeek: Int, currentWeek: Int) {
        this.courses = courses
        this.currentDisplayWeek = displayWeek
        this.actualCurrentWeek = currentWeek
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_week_page, parent, false)
        return WeekViewHolder(view)
    }

    override fun onBindViewHolder(holder: WeekViewHolder, position: Int) {
        val week = position + 1
        holder.bind(week, courses, currentDisplayWeek, actualCurrentWeek)
    }

    override fun getItemCount(): Int = 20  // 最多20周

    inner class WeekViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val gridLayout: GridLayout = itemView.findViewById(R.id.week_grid)

        fun bind(week: Int, allCourses: List<Course>, displayWeek: Int, @Suppress("UNUSED_PARAMETER") currentWeek: Int) {
            gridLayout.removeAllViews()

            // 筛选出本周的课程
            val weekCourses = allCourses.filter { course ->
                course.isActiveInWeek(week)
            }

            // 创建网格背景
            for (row in 0 until 12) {
                for (col in 0 until 7) {
                    val cellView = View(itemView.context)
                    val params = GridLayout.LayoutParams().apply {
                        rowSpec = GridLayout.spec(row, 1f)
                        columnSpec = GridLayout.spec(col, 1f)
                        width = 0
                        height = itemView.context.resources.getDimensionPixelSize(R.dimen.course_cell_height)
                    }
                    cellView.layoutParams = params
                    cellView.setBackgroundResource(R.drawable.bg_grid_cell)
                    gridLayout.addView(cellView)
                }
            }

            // 添加课程卡片
            weekCourses.forEachIndexed { index, course ->
                addCourseCard(gridLayout, course, index, week == displayWeek)
            }
        }

        private fun addCourseCard(gridLayout: GridLayout, course: Course, index: Int, isCurrentWeek: Boolean) {
            val context = gridLayout.context
            val cardView = CardView(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    rowSpec = GridLayout.spec(course.startTime - 1, course.endTime - course.startTime + 1, 1f)
                    columnSpec = GridLayout.spec(course.dayOfWeek - 1, 1f)
                    width = 0
                    height = 0
                    setMargins(2, 2, 2, 2)
                }
                setCardBackgroundColor(courseColors[index % courseColors.size])
                radius = 6f
                cardElevation = context.resources.getDimension(R.dimen.card_elevation_normal)

                // 如果不是当前显示的周，降低透明度
                alpha = if (isCurrentWeek) 1.0f else 0.3f

                setOnClickListener { onCourseClick(course) }
                setOnLongClickListener {
                    onCourseLongClick(course)
                    true
                }
            }

            // 添加文字
            val textLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(4, 4, 4, 4)
                gravity = android.view.Gravity.CENTER

                addView(TextView(context).apply {
                    text = course.name
                    textSize = 10f
                    setTextColor(android.graphics.Color.WHITE)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER
                })

                addView(TextView(context).apply {
                    text = "@${course.classroom}"
                    textSize = 8f
                    setTextColor(android.graphics.Color.WHITE)
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
