package com.cherry.wakeupschedule.ui.screen.schedule

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R
import com.google.android.material.color.MaterialColors

/**
 * 总课表列表适配器：一条 = 一门课程（同名课程已由 [CourseOverviewGroup.build] 归并）。
 *
 * 卡片只承载「课程名 / 理论课·实践课标签 / 教师 / 有课周次」——具体节次、教室、时间等
 * 上课信息统一收进详情弹层，列表保持可扫读。
 * 取色与周课表卡片、详情弹层保持一致（`color` 按课程名分配），点击整卡弹出详情。
 */
class CourseOverviewAdapter : RecyclerView.Adapter<CourseOverviewAdapter.GroupViewHolder>() {

    private val items = mutableListOf<CourseOverviewGroup>()
    private var colors: IntArray = IntArray(0)

    fun submit(groups: List<CourseOverviewGroup>, courseColors: IntArray) {
        items.clear()
        items.addAll(groups)
        colors = courseColors
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course_overview, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(items[position], colors)
    }

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val avatar: TextView = itemView.findViewById(R.id.iv_course_avatar)
        private val name: TextView = itemView.findViewById(R.id.tv_course_name)
        private val typeBadge: TextView = itemView.findViewById(R.id.tv_type_badge)
        private val teacher: TextView = itemView.findViewById(R.id.tv_course_teacher)
        private val weeks: TextView = itemView.findViewById(R.id.tv_course_weeks)

        /** 头像/标签背景 holder 级复用：只改颜色，不每次 bind 都新建 drawable（外观逐像素一致） */
        private val avatarBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        private val typeBadgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
        }

        init {
            avatar.background = avatarBg
            typeBadge.background = typeBadgeBg
        }

        fun bind(group: CourseOverviewGroup, courseColors: IntArray) {
            val ctx = itemView.context

            avatar.text = group.name.trim().take(1).ifEmpty { "课" }
            avatarBg.setColor(courseColor(group, courseColors))

            name.text = group.name
            teacher.text = group.teachers.ifBlank { "未知" }
            weeks.text = formatWeekRanges(group.weekBitmap)
            bindTypeBadge(group.isPractice)

            itemView.setOnClickListener {
                SchedulePageDetailDialog.show(
                    ctx, group.courses, courseColors, SchedulePageDetailDialog.Source.OVERVIEW
                )
            }
        }

        /** 理论课 / 实践课标签：实践课走 tertiary，理论课走 primary */
        private fun bindTypeBadge(isPractice: Boolean) {
            val color = MaterialColors.getColor(
                itemView,
                if (isPractice) com.google.android.material.R.attr.colorTertiary
                else com.google.android.material.R.attr.colorPrimary
            )
            typeBadge.text = if (isPractice) "实践课" else "理论课"
            typeBadge.setTextColor(color)
            typeBadgeBg.setColor(ColorUtils.setAlphaComponent(color, 0x26))
        }

        private fun courseColor(group: CourseOverviewGroup, courseColors: IntArray): Int {
            if (courseColors.isEmpty()) return 0
            val index = if (group.colorIndex > 0) (group.colorIndex - 1) % courseColors.size else 0
            return courseColors[index]
        }
    }
}
