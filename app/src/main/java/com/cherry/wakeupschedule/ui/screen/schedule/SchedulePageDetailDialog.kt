package com.cherry.wakeupschedule.ui.screen.schedule

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.TimeTableManager

/** 课程详情 Bottom Sheet Dialog，从原始 WeekPageFragment 提取 */
object SchedulePageDetailDialog {

    private var currentDialog: Dialog? = null

    fun show(context: Context, course: Course, courseColors: IntArray) {
        // 防止连点打开多个详情弹窗（旧弹窗可能已随 Activity 销毁，安全关闭）
        dismissCurrent()
        val dialog = Dialog(context, R.style.BottomSheetDialog)
        currentDialog = dialog
        dialog.setOnDismissListener { currentDialog = null }
        val inflater = LayoutInflater.from(context)
        val sheetView = inflater.inflate(R.layout.dialog_course_detail, null)
        val density = context.resources.displayMetrics.density

        val topRadius = 20 * density
        val sheetBg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        // 弹层底色提升一级到 colorSurfaceContainer：暗色模式下与蒙版压暗的背景拉开亮度差，
        // 再配 1dp outlineVariant 描边勾出边界，避免弹层与背景糊成一片。
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOutlineVariant, typedValue, true
        )
        sheetBg.setStroke((1 * density).toInt(), typedValue.data)
        sheetView.background = sheetBg

        val ttm = TimeTableManager.getInstance(context)
        val startSlot = ttm.getTimeSlots().find { it.node == course.startTime }
        val endSlot = ttm.getTimeSlots().find { it.node == course.endTime }
        val timeText = if (startSlot != null && endSlot != null) {
            "${startSlot.startTime} - ${endSlot.endTime}"
        } else {
            "第${course.startTime}-${course.endTime}节"
        }
        val weekDays = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val dayText = weekDays.getOrElse(course.dayOfWeek) { "" }

        // 课程主色：与课表卡片取色逻辑保持一致
        val ci = if (course.color > 0) (course.color - 1) % courseColors.size else 0
        val courseColor = courseColors[ci]

        // ── 头部：课程色圆形头像（取课程名首字） ──
        sheetView.findViewById<TextView>(R.id.iv_detail_avatar)?.apply {
            text = course.name.trim().take(1).ifEmpty { "课" }
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(courseColor)
            }
        }

        // ── 头部：课程类别徽章（非空才显示） ──
        val category = course.courseCategory.trim()
        sheetView.findViewById<TextView>(R.id.tv_detail_category)?.apply {
            if (category.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = category
                setTextColor(courseColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * density
                    setColor(ColorUtils.setAlphaComponent(courseColor, 38))
                }
            }
        }

        sheetView.findViewById<TextView>(R.id.tv_detail_name)?.text = course.name
        sheetView.findViewById<TextView>(R.id.tv_detail_teacher)?.text = course.teacher.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_classroom)?.text = course.classroom.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_time)?.text = "$dayText $timeText"
        sheetView.findViewById<TextView>(R.id.tv_detail_week)?.text = formatWeeks(course.weekBitmap)
        sheetView.findViewById<TextView>(R.id.tv_detail_credit)?.text = course.credits.trim().ifBlank { "未设置" }
        setupQqGroupJump(sheetView, course)

        dialog.setContentView(sheetView)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnClickListener { dialog.dismiss() }
        })
        container.addView(sheetView)
        dialog.setContentView(container)

        // 内容超高时限制高度并允许内部滚动（小屏手机友好）
        try {
            val display = context.resources.displayMetrics
            val scroll = sheetView.findViewById<ScrollView>(R.id.scroll_detail)
            val inner = scroll.getChildAt(0)
            inner.measure(
                View.MeasureSpec.makeMeasureSpec(display.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val desired = inner.measuredHeight
            val maxH = (display.heightPixels * 0.72f).toInt()
            scroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (desired > 0) desired.coerceAtMost(maxH) else maxH
            )
        } catch (_: Exception) { }

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
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

    /**
     * 安全关闭当前弹窗。
     *
     * 承载弹窗的 Activity 销毁/重建后，旧弹窗的 DecorView 已脱离 WindowManager，
     * 直接 dismiss() 会抛 "not attached to window manager" 崩溃（本单例持有旧引用，
     * 属于 Activity 生命周期管理不到的对象）。这里先查 isShowing，再 try-catch 兜底。
     */
    private fun dismissCurrent() {
        val d = currentDialog ?: return
        if (!d.isShowing) {
            currentDialog = null
            return
        }
        try {
            d.dismiss()
        } catch (_: Exception) {
            // 窗口已解绑（Activity 已销毁/重建），忽略并清引用
            currentDialog = null
        }
    }

    /**
     * 设置 QQ 群行：有群号时显示跳转箭头，点击拉起 QQ 加群；
     * 无群号时退化为纯展示。
     *
     * 群号取文本中第一段连续数字（≥5 位），兼容 "12345678" / "群号：12345678" 等格式。
     * 跳转使用 QQ 官方 mqqapi scheme，未安装 QQ 时捕获异常并提示。
     */
    private fun setupQqGroupJump(view: View, course: Course) {
        val row = view.findViewById<View>(R.id.row_detail_qq) ?: return
        val tv = view.findViewById<TextView>(R.id.tv_detail_qq)
        val arrow = view.findViewById<View>(R.id.iv_detail_qq_arrow)

        val raw = course.qqGroup.trim()
        val groupId = Regex("\\d{5,}").find(raw)?.value
        tv?.text = raw.ifBlank { "未设置" }

        if (groupId == null) {
            arrow?.visibility = View.GONE
            row.isClickable = false
            row.setOnClickListener(null)
            return
        }

        arrow?.visibility = View.VISIBLE
        row.isClickable = true
        row.setOnClickListener {
            val ctx = view.context
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "mqqapi://card/show_pslcard?src_type=internal&version=1" +
                            "&uin=$groupId&card_type=group&source=external"
                    )
                )
                if (ctx is Activity) {
                    ctx.startActivity(intent)
                } else {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "未检测到 QQ，无法跳转加群", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 把周次位图格式化为原始信息：连续周合并为区间，其余逐周列出。
     * 例：第1-16周 / 第1-5、7-10、13周 / 第1、3、5周
     */
    private fun formatWeeks(bitmap: Long): String {
        val list = Course.bitmapToWeekList(bitmap)
        if (list.isEmpty()) return "周次未设置"
        val parts = mutableListOf<String>()
        var start = list[0]
        var prev = list[0]
        for (i in 1 until list.size) {
            if (list[i] == prev + 1) {
                prev = list[i]
                continue
            }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = list[i]
            prev = list[i]
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return "第" + parts.joinToString("、") + "周"
    }
}
