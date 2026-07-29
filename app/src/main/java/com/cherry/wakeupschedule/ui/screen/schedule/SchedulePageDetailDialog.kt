package com.cherry.wakeupschedule.ui.screen.schedule

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 课程详情 Bottom Sheet Dialog，从原始 WeekPageFragment 提取 */
object SchedulePageDetailDialog {

    fun show(context: Context, course: Course, courseColors: IntArray) {
        val dialog = Dialog(context, R.style.BottomSheetDialog)
        val inflater = LayoutInflater.from(context)
        val sheetView = inflater.inflate(R.layout.dialog_course_detail, null)
        val density = context.resources.displayMetrics.density

        val topRadius = 20 * density
        val sheetBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
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
        val weekText = "第${course.startWeek}-${course.endWeek}周" +
                when (course.weekType) { 1 -> " (单周)"; 2 -> " (双周)"; else -> "" }

        val sm = SettingsManager(context)
        val startDate = sm.getSemesterStartDate()
        var dateRangeText = ""
        if (startDate > 0L) {
            val cal = Calendar.getInstance().apply { timeInMillis = startDate }
            cal.add(Calendar.WEEK_OF_YEAR, course.startWeek - 1)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.add(Calendar.DAY_OF_MONTH, course.dayOfWeek - 1)
            val fmt = SimpleDateFormat("M/d", Locale.getDefault())
            val s = fmt.format(cal.time)
            cal.add(Calendar.WEEK_OF_YEAR, course.endWeek - course.startWeek)
            dateRangeText = "$s - ${fmt.format(cal.time)}"
        }

        val ci = if (course.color > 0) (course.color - 1) % courseColors.size else 0
        sheetView.findViewById<View>(R.id.color_indicator)?.setBackgroundColor(courseColors[ci])
        sheetView.findViewById<TextView>(R.id.tv_detail_name)?.text = course.name
        sheetView.findViewById<TextView>(R.id.tv_detail_teacher)?.text = course.teacher.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_classroom)?.text = course.classroom.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_time)?.text = "$dayText $timeText"
        sheetView.findViewById<TextView>(R.id.tv_detail_week)?.text = weekText
        if (dateRangeText.isNotEmpty()) {
            sheetView.findViewById<TextView>(R.id.tv_detail_date_range)?.text = dateRangeText
        }

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
}
