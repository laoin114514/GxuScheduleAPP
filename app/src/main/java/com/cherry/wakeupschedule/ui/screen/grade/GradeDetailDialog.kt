package com.cherry.wakeupschedule.ui.screen.grade

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.GradeEntity
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.gxu.jwxt.model.GradeDetail

/**
 * 成绩详情弹窗：平时/期末拆分、总评与期末强制达标线。
 *
 * 详情接口返回 HTML 且字段随教务版本变化，解析结果可能不完整，
 * 因此这里既能展示结构化字段，也能在拿不到拆分时退回原始明细行。
 *
 * 学分/绩点/教师/发布时间不在详情接口里（该接口只回分项成绩），
 * 取成绩列表条目 [GradeEntity] 上已有的字段补在分项下方。
 */
class GradeDetailDialog private constructor(
    context: Context,
    private val grade: GradeEntity
) : Dialog(context, R.style.RoundedDialog) {

    private val primaryColor: Int by lazy { ThemeManager.currentPalette(context).primary }
    private val density = context.resources.displayMetrics.density

    private lateinit var content: LinearLayout
    private lateinit var loading: CircularProgressIndicator
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_grade_detail)

        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (context.resources.displayMetrics.widthPixels * 0.86).toInt()
            setLayout(minOf(width, dp(380)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        findViewById<TextView>(R.id.tv_title).text = grade.courseName
        findViewById<View>(R.id.v_accent).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(primaryColor)
        }
        findViewById<TextView>(R.id.btn_close).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(primaryColor)
            }
            setOnClickListener { dismiss() }
        }

        content = findViewById(R.id.ll_content)
        loading = findViewById(R.id.pb_loading)
        message = findViewById(R.id.tv_message)
        loading.isIndeterminate = true
        loading.setIndicatorColor(primaryColor)
    }

    /** 填充解析结果 */
    fun bind(detail: GradeDetail) {
        loading.visibility = View.GONE
        message.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()

        detail.courseName?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.tv_title).text = it
        }

        val hasBreakdown = detail.usualScore != null || detail.usualRatio != null ||
            detail.finalScore != null || detail.finalRatio != null

        detail.totalScore?.let { addRow("总评成绩", null, it, highlight = true) }
        if (hasBreakdown) {
            addRow("平时成绩", detail.usualRatio?.let { "$it%" }, detail.usualScore)
            addRow("期末成绩", detail.finalRatio?.let { "$it%" }, detail.finalScore)
        }
        detail.passLine?.takeIf { it.isNotBlank() }?.let {
            addRow("期末强制达标线", null, "$it 分")
        }

        // 没有解析出任何结构化成绩时，退回原始明细行
        if (detail.totalScore == null && !hasBreakdown) {
            detail.rows.forEach { addRow(it.label, null, it.value) }
        }

        // 学分/绩点/教师/发布时间来自成绩列表条目，详情接口不返回
        val info = listOf(
            "学分" to GradeStats.formatCreditValue(grade),
            "绩点" to grade.gradePoint.trim().ifBlank { null },
            "教师" to grade.teacherName.trim().ifBlank { null },
            "成绩发布时间" to grade.publishedAt.trim().ifBlank { null }
        ).filter { !it.second.isNullOrBlank() }

        if (info.isNotEmpty()) {
            if (content.childCount > 0) content.addView(buildDivider())
            info.forEach { addRow(it.first, null, it.second) }
        }

        if (content.childCount == 0) showMessage("该课程没有更多成绩明细")
    }

    /** 拉取失败 */
    fun showError(text: String) = showMessage(text)

    private fun showMessage(text: String) {
        loading.visibility = View.GONE
        content.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.text = text
    }

    /**
     * 一行明细：左侧名称（可带比例小字），右侧数值。
     * [highlight] 为 true 时数值用主题色加粗（总评）。
     */
    private fun addRow(label: String, ratio: String?, value: String?, highlight: Boolean = false) {
        if (value.isNullOrBlank() && ratio == null) return

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(10), 0, dp(10))
        }

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(context).apply {
            text = label
            setTextSizeRes(R.dimen.text_body)
            setTextColor(resolveAttr(com.google.android.material.R.attr.colorOnSurface))
            if (highlight) setTypeface(null, Typeface.BOLD)
        })
        if (ratio != null) {
            left.addView(TextView(context).apply {
                text = ratio
                setTextSizeRes(R.dimen.text_caption)
                setTextColor(resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            })
        }
        row.addView(left)

        row.addView(TextView(context).apply {
            text = value ?: ""
            setTextSizeRes(if (highlight) R.dimen.text_headline else R.dimen.text_subtitle)
            setTextColor(
                if (highlight) primaryColor
                else resolveAttr(com.google.android.material.R.attr.colorOnSurface)
            )
            if (highlight) setTypeface(null, Typeface.BOLD)
        })

        content.addView(row)
    }

    /** 成绩分项与课程信息之间的细分隔线 */
    private fun buildDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
        setBackgroundColor(
            ColorUtils.setAlphaComponent(
                resolveAttr(com.google.android.material.R.attr.colorOutlineVariant), 0x66
            )
        )
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()

    /**
     * 立即关闭，不播放退出动画（与 SelectionDialog 一致：内容与遮罩同帧消失更干脆）。
     */
    override fun dismiss() {
        try {
            super.dismiss()
        } catch (_: IllegalArgumentException) {
            // Activity 已销毁，窗口已移除，忽略
        }
    }

    companion object {
        private var activeDialog: GradeDetailDialog? = null

        /** 显示并立即进入 loading 态，返回实例供调用方回填数据 */
        fun show(context: Context, grade: GradeEntity): GradeDetailDialog {
            val dialog = GradeDetailDialog(context, grade)
            activeDialog?.dismiss()
            activeDialog = dialog
            dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
            dialog.show()
            return dialog
        }
    }
}
