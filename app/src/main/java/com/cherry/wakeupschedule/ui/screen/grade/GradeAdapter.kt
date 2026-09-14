package com.cherry.wakeupschedule.ui.screen.grade

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.GradeEntity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** 成绩列表适配器。不及格条目走红卡变体（左侧红条 + 红色成绩 + 需补考徽标）。 */
class GradeAdapter(
    private val onItemClick: (GradeEntity) -> Unit
) : RecyclerView.Adapter<GradeAdapter.GradeViewHolder>() {

    private val items = mutableListOf<GradeEntity>()

    fun submit(list: List<GradeEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GradeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_grade, parent, false)
        return GradeViewHolder(view)
    }

    override fun onBindViewHolder(holder: GradeViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class GradeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val card: MaterialCardView = itemView.findViewById(R.id.card_grade)
        private val failBar: View = itemView.findViewById(R.id.v_fail_bar)
        private val failIcon: ImageView = itemView.findViewById(R.id.iv_fail_icon)
        private val courseName: TextView = itemView.findViewById(R.id.tv_course_name)
        private val creditBadge: TextView = itemView.findViewById(R.id.tv_credit_badge)
        private val category: TextView = itemView.findViewById(R.id.tv_category)
        private val retake: TextView = itemView.findViewById(R.id.tv_retake)
        private val score: TextView = itemView.findViewById(R.id.tv_score)
        private val scoreCaption: TextView = itemView.findViewById(R.id.tv_score_caption)

        fun bind(grade: GradeEntity) {
            val failed = GradeStats.isFailed(grade)
            val errorColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorError)
            val outlineColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOutlineVariant)
            val primaryColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorPrimary)

            courseName.text = grade.courseName
            creditBadge.text = GradeStats.formatCreditBadge(grade)
            category.text = GradeStats.categoryLabel(grade)
            category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE

            score.text = grade.score.ifBlank { grade.percentageScore }.ifBlank { "—" }
            score.setTextColor(if (failed) errorColor else primaryColor)
            scoreCaption.text = if (failed) "不及格" else GradeStats.scoreCaption(grade)
            scoreCaption.setTextColor(
                if (failed) errorColor else MaterialColors.getColor(
                    itemView, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )

            // 红卡变体：左侧红条 + 红色描边 + 课程名前的警示图标 + 需补考徽标
            failBar.visibility = if (failed) View.VISIBLE else View.GONE
            failIcon.visibility = if (failed) View.VISIBLE else View.GONE
            retake.visibility = if (failed) View.VISIBLE else View.GONE
            card.strokeColor = if (failed) ColorUtils.setAlphaComponent(errorColor, 0x4D) else outlineColor

            itemView.setOnClickListener { onItemClick(grade) }
        }
    }
}
