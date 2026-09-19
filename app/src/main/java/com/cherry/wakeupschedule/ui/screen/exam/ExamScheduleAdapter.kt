package com.cherry.wakeupschedule.ui.screen.exam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.ExamScheduleEntity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * 考试安排列表适配器。
 *
 * 状态徽标文案与颜色都来自 [ExamScheduleStats]：进行中额外点亮左侧竖条与主色描边，
 * 已结束整体压暗。列表没有点击行为（本页没有详情层）。
 *
 * [submit] 需要调用方传入统一的 [now]：状态与排序必须用同一个「现在」，
 * 否则列表顺序与徽标可能自相矛盾。
 */
class ExamScheduleAdapter : RecyclerView.Adapter<ExamScheduleAdapter.ExamViewHolder>() {

    private val items = mutableListOf<ExamScheduleEntity>()
    private var now: Long = 0L

    fun submit(list: List<ExamScheduleEntity>, now: Long) {
        items.clear()
        items.addAll(list)
        this.now = now
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExamViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exam_schedule, parent, false)
        return ExamViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExamViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ExamViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val card: MaterialCardView = itemView.findViewById(R.id.card_exam)
        private val statusBar: View = itemView.findViewById(R.id.v_status_bar)
        private val courseName: TextView = itemView.findViewById(R.id.tv_course_name)
        private val examName: TextView = itemView.findViewById(R.id.tv_exam_name)
        private val examTime: TextView = itemView.findViewById(R.id.tv_exam_time)
        private val classroom: TextView = itemView.findViewById(R.id.tv_classroom)
        private val statusBadge: TextView = itemView.findViewById(R.id.tv_status_badge)

        fun bind(exam: ExamScheduleEntity) {
            val status = ExamScheduleStats.status(exam, now)
            val primaryColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorPrimary)
            val tertiaryColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorTertiary)
            val onSurfaceVariant = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val outlineColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOutline)
            val outlineVariant = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOutlineVariant)

            courseName.text = exam.courseName
            examName.text = exam.examName
            examName.visibility = if (exam.examName.isBlank()) View.GONE else View.VISIBLE
            examTime.text = exam.examTime.ifBlank { "时间待定" }
            // 场地只用教务的 cdmc，为空显示「地点待定」（不回退 jxdd）
            classroom.text = ExamScheduleStats.locationLabel(exam)

            statusBadge.text = status.label
            statusBadge.setTextColor(
                when (status) {
                    ExamStatus.NOT_STARTED -> primaryColor
                    ExamStatus.ONGOING -> tertiaryColor
                    ExamStatus.FINISHED -> onSurfaceVariant
                    ExamStatus.UNKNOWN -> outlineColor
                }
            )

            // 进行中：左侧主色竖条 + 主色描边；已结束：整体压暗，视觉上让位给未开始/进行中
            val ongoing = status == ExamStatus.ONGOING
            statusBar.visibility = if (ongoing) View.VISIBLE else View.GONE
            card.strokeColor = if (ongoing) {
                ColorUtils.setAlphaComponent(primaryColor, 0x4D)
            } else {
                outlineVariant
            }
            itemView.alpha = if (status == ExamStatus.FINISHED) 0.72f else 1f
        }
    }
}
