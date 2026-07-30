package com.cherry.wakeupschedule.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.TimeTableManager

/**
 * 课程全览适配器
 * 按课程名称分组，合并同一课程的不同时间，直接列表显示
 */
class CourseOverviewAdapter(
    private val context: Context,
    private val courses: List<Course>,
    private val courseColors: IntArray
) : RecyclerView.Adapter<CourseOverviewAdapter.ItemViewHolder>() {

    // 合并后的课程数据
    private data class MergedCourse(
        val name: String,
        val teacher: String,
        val classroom: String,
        val weekBitmap: Long,
        val color: Int,
        val timeSlots: List<TimeSlot>
    ) {
        data class TimeSlot(
            val dayOfWeek: Int,
            val startTime: Int,
            val endTime: Int
        )
    }

    private val mergedCourses: List<MergedCourse>

    private val dayNames = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val timeTableManager = TimeTableManager.getInstance(context)
    private val timeSlots = timeTableManager.getTimeSlots()

    init {
        mergedCourses = mergeCourses(courses).sortedBy { it.name }
    }

    /**
     * 合并逻辑：相同课程名称、老师、地点的合并
     */
    private fun mergeCourses(courses: List<Course>): List<MergedCourse> {
        val map = mutableMapOf<String, MergedCourse>()
        
        courses.forEach { course ->
            val key = "${course.name}|${course.teacher}|${course.classroom}"
            val color = if (course.color != 0) course.color else courseColors[(course.id % courseColors.size).toInt()]
            
            if (map.containsKey(key)) {
                val existing = map[key]!!
                // 添加新的时间slot
                val newTimeSlots = existing.timeSlots.toMutableList()
                newTimeSlots.add(
                    MergedCourse.TimeSlot(
                        course.dayOfWeek,
                        course.startTime,
                        course.endTime
                    )
                )
                map[key] = existing.copy(
                    weekBitmap = existing.weekBitmap or course.weekBitmap,
                    timeSlots = newTimeSlots.sortedWith(compareBy({ it.dayOfWeek }, { it.startTime }))
                )
            } else {
                map[key] = MergedCourse(
                    name = course.name,
                    teacher = course.teacher,
                    classroom = course.classroom,
                    weekBitmap = course.weekBitmap,
                    color = color,
                    timeSlots = listOf(
                        MergedCourse.TimeSlot(
                            course.dayOfWeek,
                            course.startTime,
                            course.endTime
                        )
                    )
                )
            }
        }
        
        return map.values.toList()
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val leftBar: View = view.findViewById(R.id.color_indicator_container)
        val tvCourseName: TextView = view.findViewById(R.id.tv_course_name)
        val tvWeekTypeBadge: TextView = view.findViewById(R.id.tv_week_type_badge)
        val tvCourseTeacher: TextView = view.findViewById(R.id.tv_course_teacher)
        val tvCourseLocation: TextView = view.findViewById(R.id.tv_course_location)
        val tvCourseWeeks: TextView = view.findViewById(R.id.tv_course_weeks)
        val llTimeList: LinearLayout = view.findViewById(R.id.ll_time_list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_course_overview, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        bindCourseItem(holder, mergedCourses[position])
    }

    private fun bindCourseItem(holder: ItemViewHolder, course: MergedCourse) {
        // 左侧颜色条
        holder.leftBar.background.setTint(course.color)

        // 课程名称
        holder.tvCourseName.text = course.name

        // 教师
        holder.tvCourseTeacher.text = if (course.teacher.isNotBlank()) course.teacher else "未设置教师"

        // 地点
        holder.tvCourseLocation.text = if (course.classroom.isNotBlank()) course.classroom else "未设置地点"

        // 周次
        val weekList = Course.bitmapToWeekList(course.weekBitmap)
        holder.tvCourseWeeks.text = if (weekList.isNotEmpty()) {
            "第${weekList.first()}-${weekList.last()}周"
        } else {
            "周次未设置"
        }

        val allOdd = weekList.isNotEmpty() && weekList.all { it % 2 == 1 }
        val allEven = weekList.isNotEmpty() && weekList.all { it % 2 == 0 }
        val isContinuous = weekList.isNotEmpty() &&
            weekList.zipWithNext().all { (a, b) -> b - a == 1 }

        when {
            !isContinuous && weekList.isNotEmpty() -> {
                holder.tvWeekTypeBadge.text = "分散"
                holder.tvWeekTypeBadge.visibility = View.VISIBLE
            }
            allOdd -> {
                holder.tvWeekTypeBadge.text = "单周"
                holder.tvWeekTypeBadge.visibility = View.VISIBLE
            }
            allEven -> {
                holder.tvWeekTypeBadge.text = "双周"
                holder.tvWeekTypeBadge.visibility = View.VISIBLE
            }
            else -> {
                holder.tvWeekTypeBadge.visibility = View.GONE
            }
        }

        // 时间列表
        holder.llTimeList.removeAllViews()
        course.timeSlots.forEach { slot ->
            val timeView = LayoutInflater.from(context).inflate(R.layout.item_course_time, holder.llTimeList, false)
            val tvDay = timeView.findViewById<TextView>(R.id.tv_day)
            val tvTimeRange = timeView.findViewById<TextView>(R.id.tv_time_range)
            
            tvDay.text = dayNames[slot.dayOfWeek]
            
            val startSlot = timeSlots.find { it.node == slot.startTime }
            val endSlot = timeSlots.find { it.node == slot.endTime }
            val timeStr = if (startSlot != null && endSlot != null) {
                "第${slot.startTime}-${slot.endTime}节 ${startSlot.startTime}-${endSlot.endTime}"
            } else {
                "第${slot.startTime}-${slot.endTime}节"
            }
            tvTimeRange.text = timeStr
            
            holder.llTimeList.addView(timeView)
        }
    }

    override fun getItemCount(): Int = mergedCourses.size
}
