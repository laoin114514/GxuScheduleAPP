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
import com.cherry.wakeupschedule.ui.feedback.AppToast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.TimeTableManager
import com.google.android.material.color.MaterialColors

/** 课程详情 Bottom Sheet Dialog，从原始 WeekPageFragment 提取 */
object SchedulePageDetailDialog {

    private var currentDialog: Dialog? = null

    /**
     * 详情弹层来源，决定版式：
     * - [Source.WEEK]：周课表点某个节次的卡片，保持原始版式（时间 / QQ群 / 教师 / 学分 / 教室 / 周次）；
     * - [Source.OVERVIEW]：总课表列表，隐藏时间行，改展示理论课·实践课标签与教务带回的课程信息。
     */
    enum class Source { WEEK, OVERVIEW }

    /**
     * 展示课程详情。
     *
     * [courses] 为同一门课的全部课程行：周课表传入单门；总课表传入同名课程的全部时段。
     * [displayWeek] 周课表当前打开的周次（1..显示总周数；0 = 未知）。仅周课表来源使用：
     * 只有这一周确实被调课时，才在详情里展示「调课原因」行。
     */
    fun show(
        context: Context,
        courses: List<Course>,
        courseColors: IntArray,
        source: Source,
        displayWeek: Int = 0
    ) {
        if (courses.isEmpty()) return
        // 防止连点打开多个详情弹窗（旧弹窗可能已随 Activity 销毁，安全关闭）
        dismissCurrent()
        val dialog = Dialog(context, R.style.BottomSheetDialog)
        currentDialog = dialog
        dialog.setOnDismissListener { currentDialog = null }
        val inflater = LayoutInflater.from(context)
        val sheetView = inflater.inflate(R.layout.dialog_course_detail, null)
        val density = context.resources.displayMetrics.density

        val topRadius = 24 * density
        val sheetBg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        // 弹层底色提升一级到 colorSurfaceContainer：暗色模式下与蒙版压暗的背景拉开亮度差
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

        val primary = courses.first()
        val courseColor = if (courseColors.isEmpty()) 0
            else courseColors[if (primary.color > 0) (primary.color - 1) % courseColors.size else 0]

        // ── 头部：课程色圆形头像（取课程名首字） ──
        sheetView.findViewById<TextView>(R.id.iv_detail_avatar)?.apply {
            text = primary.name.trim().take(1).ifEmpty { "课" }
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(courseColor)
            }
        }

        // ── 头部：课程类别徽章（组内第一个非空值，都没有则隐藏） ──
        val category = courses.firstOrNull { it.courseCategory.isNotBlank() }
            ?.courseCategory?.trim() ?: ""
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

        val isOverview = source == Source.OVERVIEW

        sheetView.findViewById<TextView>(R.id.tv_detail_name)?.text = primary.name
        sheetView.findViewById<TextView>(R.id.tv_detail_teacher)?.text =
            joinDistinct(courses) { it.teacher }.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_classroom)?.text =
            joinDistinct(courses) { it.classroom }.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_week)?.text =
            formatWeekRanges(courses.fold(0L) { acc, c -> acc or c.weekBitmap })
        sheetView.findViewById<TextView>(R.id.tv_detail_credit)?.text =
            courses.firstOrNull { it.credits.isNotBlank() }?.credits?.trim().orEmpty().ifBlank { "未设置" }
        // 时间行只有周课表详情展示；总课表详情看周次就够，整组（行 + 分隔线）隐藏
        sheetView.findViewById<View>(R.id.group_detail_time)?.visibility =
            if (isOverview) View.GONE else View.VISIBLE
        if (!isOverview) {
            sheetView.findViewById<TextView>(R.id.tv_detail_time)?.text =
                weekTimeText(context, primary)
        }

        // 类型标签与「课程信息」卡是总课表详情新增的内容，周课表保持原始版式
        if (isOverview) {
            bindTypeBadge(sheetView, courses.any { it.isPractice }, density)
            bindExtraInfo(sheetView, courses, density)
        } else {
            sheetView.findViewById<TextView>(R.id.tv_detail_type)?.visibility = View.GONE
            (sheetView.findViewById<View>(R.id.ll_detail_extra)?.parent as? View)?.visibility =
                View.GONE
        }

        // 「调课原因」行只有周课表详情展示，且仅当本次打开的这一周确实被调课时出现
        bindAdjustReason(sheetView, courses, displayWeek, isOverview)

        // 「调课」标签与来源无关：两种详情都展示。
        // 总课表详情的「课程信息」卡另有「调课周次」行精确说明具体哪几周被调课，
        // 避免把课程级标签误读成「整个学期的课都是调课来的」。
        bindAdjustBadge(sheetView, courses.any { it.isAdjusted })

        setupQqGroupJump(sheetView, courses.firstOrNull { it.qqGroup.isNotBlank() } ?: primary)

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
                AppToast.warn(ctx, "未检测到 QQ，无法跳转加群")
            }
        }
    }

    /**
     * 周课表详情的时间行文案（原始版式）：
     * 查时间表得 "08:00 - 09:40"，查不到退回 "第X-Y节"，前面拼星期。
     */
    private fun weekTimeText(context: Context, course: Course): String {
        val slots = TimeTableManager.getInstance(context).getTimeSlots()
        val startSlot = slots.find { it.node == course.startTime }
        val endSlot = slots.find { it.node == course.endTime }
        val timeText = if (startSlot != null && endSlot != null) {
            "${startSlot.startTime} - ${endSlot.endTime}"
        } else {
            "第${course.startTime}-${course.endTime}节"
        }
        return "${dayOfWeekLabel(course.dayOfWeek)} $timeText"
    }

    /** 头部「理论课 / 实践课」标签：实践课走 tertiary，理论课走 primary */
    private fun bindTypeBadge(view: View, isPractice: Boolean, density: Float) {
        val badge = view.findViewById<TextView>(R.id.tv_detail_type) ?: return
        val color = MaterialColors.getColor(
            view,
            if (isPractice) com.google.android.material.R.attr.colorTertiary
            else com.google.android.material.R.attr.colorPrimary
        )
        badge.text = if (isPractice) "实践课" else "理论课"
        badge.setTextColor(color)
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * density
            setColor(ColorUtils.setAlphaComponent(color, 38))
        }
    }

    /**
     * 「调课原因」行（仅周课表详情）。
     *
     * 显示条件：本次是从周课表某一周打开、且该课程行在**这一周**确实被调课（按
     * [Course.adjustWeeksBitmap] 判断），同时记录里带回了原因文案。这样同一门课只在
     * 被调课的那一节显示原因，正常周次不显示。总课表详情（OVERVIEW）一律隐藏该行。
     */
    private fun bindAdjustReason(
        view: View,
        courses: List<Course>,
        displayWeek: Int,
        isOverview: Boolean
    ) {
        val group = view.findViewById<View>(R.id.group_detail_adjust_reason) ?: return
        if (isOverview || displayWeek !in 1..64) {
            group.visibility = View.GONE
            return
        }
        val reason = courses
            .filter { it.isAdjustedInWeek(displayWeek) }
            .map { it.adjustReason.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("；")
        if (reason.isEmpty()) {
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_detail_adjust_reason)?.text = reason
    }

    /**
     * 头部「调课」标签：整教学班有教务调课记录时显示。
     *
     * 固定琥珀底 + 深棕字（与总课表列表 pill、周课表角标同一套颜色，浅深色主题通用）。
     * 该标签只表达「这门课有调课记录」，具体周次由总课表详情的「调课周次」行说明；
     * 不要新增网络请求去解析调课明细。
     */
    private fun bindAdjustBadge(view: View, hasAdjustment: Boolean) {
        val badge = view.findViewById<TextView>(R.id.tv_detail_adjust) ?: return
        if (!hasAdjustment) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        badge.setTextColor(ContextCompat.getColor(view.context, R.color.adjust_tag_text))
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(ContextCompat.getColor(view.context, R.color.adjust_tag_bg))
        }
    }

    /**
     * 填充「课程信息」卡：教务返回的补充字段逐行展示，值为空的整行不渲染；
     * 一行都没有时整张卡隐藏。行布局 item_course_detail_row.xml，行间补一条细分隔线。
     */
    private fun bindExtraInfo(view: View, courses: List<Course>, density: Float) {
        val container = view.findViewById<LinearLayout>(R.id.ll_detail_extra) ?: return
        container.removeAllViews()

        // 调课周次：总课表头部的「调课」pill 是课程级的，这里展开成具体周次（组内各行位图并集），
        // 精确回答「到底哪几周被调课」，避免误读成整学期调课；没有调课周次时不渲染该行。
        // 本函数只在总课表（OVERVIEW）来源调用，周课表详情保持原版式。
        val adjustWeeks = courses.fold(0L) { acc, c -> acc or c.adjustWeeksBitmap }
        val rows = listOf(
            view.context.getString(R.string.course_adjust_weeks) to
                if (adjustWeeks != 0L) formatWeekRanges(adjustWeeks) else "",
            "课程性质" to firstNonBlank(courses) { it.courseNature },
            "课程代码" to firstNonBlank(courses) { it.courseCode },
            "教学班" to firstNonBlank(courses) { it.teachingClass },
            "教学班组成" to firstNonBlank(courses) { it.classComposition },
            "选课人数" to formatEnrollment(
                firstNonBlank(courses) { it.enrolled },
                firstNonBlank(courses) { it.maxStudents }
            ),
            "总学时" to firstNonBlank(courses) { it.totalHours },
            "学时组成" to firstNonBlank(courses) { it.hourComposition },
            "考核方式" to firstNonBlank(courses) { it.assessmentMethod },
            "实践说明" to firstNonBlank(courses) { it.practiceDetail }
        ).filter { it.second.isNotEmpty() }

        if (rows.isEmpty()) {
            // 连带把承载它的卡片一起藏掉，避免留一张空卡
            (container.parent as? View)?.visibility = View.GONE
            return
        }
        (container.parent as? View)?.visibility = View.VISIBLE

        val dividerColor = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutlineVariant),
            0x59
        )
        rows.forEachIndexed { index, (label, value) ->
            val row = LayoutInflater.from(view.context)
                .inflate(R.layout.item_course_detail_row, container, false)
            row.findViewById<TextView>(R.id.tv_detail_row_label).text = label
            row.findViewById<TextView>(R.id.tv_detail_row_value).text = value
            container.addView(row)

            if (index != rows.lastIndex) {
                container.addView(View(view.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (0.5f * density).toInt()
                    ).apply { marginStart = (84 * density).toInt() }
                    setBackgroundColor(dividerColor)
                })
            }
        }
    }

    /** 组内第一个非空值（同名课程合并后，补充字段取第一个有值的） */
    private fun firstNonBlank(courses: List<Course>, selector: (Course) -> String): String =
        courses.map { selector(it).trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    /** 组内某字段的非空去重值，用「、」连接（教师 / 教室等） */
    private fun joinDistinct(courses: List<Course>, selector: (Course) -> String): String =
        courses.map { selector(it).trim() }.filter { it.isNotEmpty() }.distinct().joinToString("、")
}
