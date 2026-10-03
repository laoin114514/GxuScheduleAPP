package com.cherry.wakeupschedule.ui.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.screen.schedule.SchedulePageDetailDialog
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.widget.GridBackgroundView
import com.cherry.wakeupschedule.ui.widget.OverlapBadgeView
import com.cherry.wakeupschedule.ui.widget.VerticalScrollView
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ViewPager2 适配器。
 * 完全模仿原始 app：RecyclerView.Adapter + 代码构建 View（网格零 XML inflation）+ 每页直接渲染。
 *
 * 每页结构：日期栏（item_date_header.xml）+ 周网格。日期栏放在页内是为了让它随页面一起横向滚动，
 * 这样左右滑周时日期栏与课表天然 1:1 跟手，不需要任何滚动同步代码。
 */
class WeekPagerAdapter(
    private val totalWeeks: Int,
    initialCellHeightDp: Int
) : RecyclerView.Adapter<WeekPagerAdapter.WeekViewHolder>() {

    /** 当前课程格子高度（设计 dp），随「我的 → 外观 → 课表」的设置变化 */
    var cellHeightDp: Int = initialCellHeightDp
        private set

    /** 课程格子是否显示教室 / 教师（课程名恒显示），随「课表外观 → 课程内容」的设置变化 */
    var showClassroom: Boolean = true
        private set
    var showTeacher: Boolean = true
        private set

    /** 课程格子边框颜色（ARGB），随「课表外观 → 边框颜色」的设置变化 */
    var borderColor: Int = DEFAULT_BORDER_COLOR
        private set

    /** 边框是否跟随课程颜色：开启后每格描边 = 该课不透明课程色，自定义色暂不生效 */
    var borderFollowCourse: Boolean = false
        private set

    /** 课表底部是否留白：开启后滚动内容末尾追加约 15% 屏高的空白 */
    var bottomBlankEnabled: Boolean = false
        private set

    /** 「高亮当日」：开启后在日期蓝框之外，为当日整列追加淡色底（课表整体 → 高亮当日） */
    var highlightToday: Boolean = false
        private set

    private var allCourses: List<Course> = emptyList()

    /** 学期开始日期（epoch ms；0 = 未设置 → 日期栏保留占位文案） */
    private var semesterStartDate: Long = 0L

    /** 当前周，用于日期栏「今天」高亮（0 = 不高亮） */
    private var currentWeek: Int = 0

    fun updateData(courses: List<Course>) {
        allCourses = courses
        notifyDataSetChanged()
    }

    /**
     * 更新日期栏所需的学期上下文。
     * 日期栏在每页内部，值变了只需重绑日期栏，不必重建课程卡片（走 payload 分支）。
     */
    fun setWeekContext(semesterStartDate: Long, currentWeek: Int) {
        if (this.semesterStartDate == semesterStartDate && this.currentWeek == currentWeek) return
        this.semesterStartDate = semesterStartDate
        this.currentWeek = currentWeek
        notifyItemRangeChanged(0, totalWeeks, PAYLOAD_WEEK_CONTEXT)
    }

    /**
     * 更新格子高度并重排课表；值未变时直接返回，避免每次切 tab 白刷一遍。
     * 时间轴行高缓存在 ViewHolder 里，所以必须让 bind 重跑（见 WeekViewHolder 的重建条件）。
     */
    fun setCellHeightDp(dp: Int) {
        if (dp == cellHeightDp) return
        cellHeightDp = dp
        notifyDataSetChanged()
    }

    /**
     * 更新课程格子的显示内容（教室 / 教师）；值未变时直接返回，避免每次切 tab 白刷一遍。
     * 文字是 bind 时拼进同一个 TextView 的，所以改开关同样要重绑。
     */
    fun setContentVisibility(showClassroom: Boolean, showTeacher: Boolean) {
        if (showClassroom == this.showClassroom && showTeacher == this.showTeacher) return
        this.showClassroom = showClassroom
        this.showTeacher = showTeacher
        notifyDataSetChanged()
    }

    /** 更新格子边框颜色；值未变时直接返回，避免每次切 tab 白刷一遍 */
    fun setBorderColor(color: Int) {
        if (color == borderColor) return
        borderColor = color
        notifyDataSetChanged()
    }

    /** 更新「边框跟随课程颜色」；值未变时直接返回 */
    fun setBorderFollowCourse(follow: Boolean) {
        if (follow == borderFollowCourse) return
        borderFollowCourse = follow
        notifyDataSetChanged()
    }

    /** 更新「课表底部留白」；值未变时直接返回 */
    fun setBottomBlank(enabled: Boolean) {
        if (enabled == bottomBlankEnabled) return
        bottomBlankEnabled = enabled
        notifyDataSetChanged()
    }

    /** 更新「高亮当日」；值未变时直接返回 */
    fun setHighlightToday(enabled: Boolean) {
        if (enabled == highlightToday) return
        highlightToday = enabled
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = totalWeeks

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekViewHolder {
        return WeekViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: WeekViewHolder, position: Int) {
        val week = position + 1
        holder.bind(week, allCourses, cellHeightDp, semesterStartDate, currentWeek,
            showClassroom, showTeacher, borderColor, borderFollowCourse, bottomBlankEnabled,
            highlightToday)
    }

    /** 只有日期栏上下文变化时走这条轻量分支，避免整页重建课程卡片 */
    override fun onBindViewHolder(
        holder: WeekViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_WEEK_CONTEXT)) {
            holder.bindDateHeader(position + 1, semesterStartDate, currentWeek)
            // 周上下文变化可能改变「今天在哪列/是否本周」，列淡底跟着日期栏一起轻量刷新
            holder.bindTodayColumn(highlightToday, 0f)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    class WeekViewHolder(context: Context) : RecyclerView.ViewHolder(
        buildPageRoot(context)
    ) {
        // ── 缓存的 view 引用 ──
        private val dateHeader: ViewGroup
        private val scrollView: VerticalScrollView
        private val gridBg: GridBackgroundView
        private val timeAxis: LinearLayout
        private val courseContainer: FrameLayout
        private val emptyView: LinearLayout
        private val bottomSpacer: View

        // ── 日期栏 ──
        private val dateViews: Array<TextView>
        private val yearView: TextView

        /** 「高亮当日」的当日列淡底：holder 级复用，attach/detach 由 bindTodayColumn 管理 */
        private val todayColumnView = View(itemView.context)

        /** 今天在本页的列下标（bindDateHeader 算出；-1 = 学期未设置或今天不在本页） */
        private var todayColumnIndex = -1

        /** 当日列最近一次使用的列宽 px（payload 轻量刷新时复用，避免重算） */
        private var lastCellWidthPx = 0f

        /** 日期栏渲染复用实例，避免每次 bind 重新分配 Calendar / Formatter */
        private val headerCal = Calendar.getInstance()
        private val headerDateFormat = SimpleDateFormat("M/d", Locale.getDefault())

        private var axisBuilt = false
        private var builtNodes = 0

        /** 上次构建时间轴所用的格子高度 px；高度变了必须重建，否则行高停在旧值 */
        private var builtCellHeight = 0
        private val courseColors: IntArray get() = ThemeManager.getCourseColors()

        /** 当前显示的重叠课程弹窗，防止连点叠加 */
        private var overlapPickerDialog: android.app.Dialog? = null

        init {
            val root = itemView as LinearLayout
            // 页面结构：日期栏 + 周网格。日期栏在页内，因此和网格共用同一次横向滚动
            dateHeader = root.getChildAt(0) as ViewGroup
            scrollView = root.getChildAt(1) as VerticalScrollView
            // 滚动内容：网格行 + 可选的底部留白（见 bind）
            val scrollContent = scrollView.getChildAt(0) as LinearLayout
            val contentLayout = scrollContent.getChildAt(0) as LinearLayout
            bottomSpacer = scrollContent.getChildAt(1)
            timeAxis = contentLayout.getChildAt(0) as LinearLayout
            val contentArea = contentLayout.getChildAt(1) as FrameLayout
            gridBg = contentArea.getChildAt(0) as GridBackgroundView
            courseContainer = contentArea.getChildAt(1) as FrameLayout
            emptyView = contentArea.getChildAt(2) as LinearLayout

            dateViews = arrayOf(
                R.id.tv_date_1, R.id.tv_date_2, R.id.tv_date_3,
                R.id.tv_date_4, R.id.tv_date_5, R.id.tv_date_6, R.id.tv_date_7
            ).map { dateHeader.findViewById<TextView>(it) }.toTypedArray()
            yearView = dateHeader.findViewById(R.id.tv_year_value)
        }

        /**
         * 渲染本页日期栏（本页 = 第 week 周）。
         * 学期开始日期未设置（0）时保留布局里的占位文案，与改造前一致。
         * 「今天」仍用蓝框标出；命中时记下 [todayColumnIndex]，
         * 供 [bindTodayColumn] 在「高亮当日」开启时画整列淡底。
         */
        fun bindDateHeader(week: Int, semesterStartDate: Long, currentWeek: Int) {
            todayColumnIndex = -1
            if (semesterStartDate == 0L) return

            headerCal.timeInMillis = semesterStartDate
            headerCal.add(Calendar.WEEK_OF_YEAR, week - 1)
            headerCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            yearView.text = headerCal.get(Calendar.YEAR).toString()

            val today = Calendar.getInstance()
            // 今天方块底色是 colorPrimary（浅色主题为深色、深色主题为亮色），
            // 字体用 colorOnPrimary 与之反色：浅色主题白字、深色主题深字
            val todayTextColor = themeColor(com.google.android.material.R.attr.colorOnPrimary)
            val normalTextColor =
                themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            val isCurrentWeek = week == currentWeek

            dateViews.forEachIndexed { index, tv ->
                tv.text = headerDateFormat.format(headerCal.time)
                val isToday = isCurrentWeek &&
                        headerCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                        headerCal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                if (isToday) {
                    tv.setBackgroundResource(R.drawable.bg_date_selected)
                    tv.setTextColor(todayTextColor)
                    todayColumnIndex = index
                } else {
                    tv.background = null
                    tv.setTextColor(normalTextColor)
                }
                headerCal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        /**
         * 「高亮当日」的当日列淡底：周微缩地图小组件同款画法——主色 10% 淡底、8dp 圆角，
         * 作为 courseContainer 第一个孩子垫在课程卡之下（半透明卡片与 2dp 间隙处会透出）。
         * 依赖 [bindDateHeader] 算出的 [todayColumnIndex]，须在其后调用；空课表页不画。
         * [cellWidthPx] 传 0 表示复用上次的列宽（payload 轻量刷新路径）。
         */
        fun bindTodayColumn(highlightToday: Boolean, cellWidthPx: Float) {
            if (cellWidthPx > 0f) lastCellWidthPx = cellWidthPx
            val show = highlightToday && todayColumnIndex >= 0 &&
                    emptyView.visibility != View.VISIBLE && lastCellWidthPx > 0f
            if (!show) {
                (todayColumnView.parent as? ViewGroup)?.removeView(todayColumnView)
                return
            }
            if (todayColumnView.parent == null) courseContainer.addView(todayColumnView, 0)
            val density = itemView.context.resources.displayMetrics.density
            todayColumnView.background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(
                    ColorUtils.setAlphaComponent(
                        themeColor(com.google.android.material.R.attr.colorPrimary), 26
                    )
                )
            }
            todayColumnView.layoutParams = FrameLayout.LayoutParams(
                lastCellWidthPx.toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { marginStart = (todayColumnIndex * lastCellWidthPx).toInt() }
        }

        /** 取主题色（浅色/深色自适应） */
        private fun themeColor(attr: Int): Int {
            val typedValue = android.util.TypedValue()
            itemView.context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }

        fun bind(
            week: Int,
            allCourses: List<Course>,
            cellHeightDp: Int,
            semesterStartDate: Long,
            currentWeek: Int,
            showClassroom: Boolean,
            showTeacher: Boolean,
            borderColor: Int,
            borderFollowCourse: Boolean,
            bottomBlankEnabled: Boolean,
            highlightToday: Boolean
        ) {
            // 日期栏先渲染：下面「暂无课程」会提前 return，不能漏掉日期栏；
            // 且当日列淡底依赖它算出的 todayColumnIndex
            bindDateHeader(week, semesterStartDate, currentWeek)

            val ctx = itemView.context
            val maxNodes = TimeTableManager.getInstance(ctx).getMaxNodes()
            val density = ctx.resources.displayMetrics.density
            // 按 dp × 当前密度换算：与「字体大小」档位（改写 densityDpi）保持一致，格子与文字同步缩放
            val cellHeight = (cellHeightDp * density).roundToInt()

            // ── 网格背景（只配置一次） ──
            if (!axisBuilt || builtNodes != maxNodes) {
                gridBg.rowCount = maxNodes
                gridBg.columnCount = 7
                gridBg.gridColor = android.graphics.Color.TRANSPARENT
            }

            // ── 时间轴（只构建一次，或节点数/格子高度变化时重建） ──
            if (!axisBuilt || builtNodes != maxNodes || builtCellHeight != cellHeight) {
                timeAxis.removeAllViews()
                val slots = TimeTableManager.getInstance(ctx).getTimeSlots()
                for (node in 1..maxNodes) {
                    val slot = slots.find { it.node == node }
                    val timeView = buildTimeSlotView(ctx, node, slot?.startTime, slot?.endTime, cellHeight)
                    timeAxis.addView(timeView)
                }
                axisBuilt = true
                builtNodes = maxNodes
                builtCellHeight = cellHeight
            }

            // ── 筛选本周课程 ──
            // 实践课没有固定星期/节次，放不进网格（只在总课表列出）
            val weekCourses = allCourses.filter { it.isActiveInWeek(week) && it.hasFixedTime() }

            // ── 空状态 ──
            if (weekCourses.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                courseContainer.removeAllViews()
                return
            }
            emptyView.visibility = View.GONE

            // ── 同步计算 cell 宽度 ──
            val timeAxisWidth = (32 * density).toInt()
            val contentWidth = ctx.resources.displayMetrics.widthPixels - timeAxisWidth
            if (contentWidth <= 0) return

            // ── 「课表整体 → 底部留白」：网格之下追加 BOTTOM_BLANK_RATIO(15%) 屏高的空白，
            //    让底部课程能上滑到屏幕中部查看；空白不带时间轴与行线，与页面背景无缝 ──
            if (bottomBlankEnabled) {
                bottomSpacer.visibility = View.VISIBLE
                bottomSpacer.layoutParams.height =
                    (ctx.resources.displayMetrics.heightPixels * BOTTOM_BLANK_RATIO).toInt()
                bottomSpacer.requestLayout()
            } else {
                bottomSpacer.visibility = View.GONE
            }

            // ── 重建课程卡片 ──
            courseContainer.removeAllViews()
            val gapPx = (2 * density).toInt()
            val cellWidth = contentWidth / 7f
            // 「课表整体 → 高亮当日」：当日列淡底垫在卡片之下（clear 后第 0 位，先于卡片循环）
            bindTodayColumn(highlightToday, cellWidth)
            val textColor = Color.WHITE
            val colors = courseColors

            // 按 (day, startTime, endTime) 分组检测重叠
            val groups = weekCourses.groupBy {
                Triple(it.dayOfWeek, it.startTime, it.endTime)
            }

            for ((_, group) in groups) {
                // 组内按优先级排序
                val sorted = group.sortedWith(compareBy { courseSortKey(it) })
                val primary = sorted[0]

                val ci = if (primary.color > 0) (primary.color - 1) % colors.size else 0
                val isDark = ThemeManager.isDarkMode(ctx)
                val alpha = if (isDark) 191 else 128
                val bgColor = ColorUtils.setAlphaComponent(colors[ci], alpha)
                // 描边：跟随课程色时取该课不透明课程色（填充仍是半透明，实色描边轮廓清晰）；
                // 否则用「课表外观 → 边框颜色」的自定义色（默认半透明白）
                val strokeColor = if (borderFollowCourse) colors[ci] else borderColor

                val rowStart = (primary.startTime - 1).coerceIn(0, maxNodes - 1)
                val span = (primary.endTime - primary.startTime + 1).coerceAtLeast(1)
                    .coerceAtMost(maxNodes - rowStart)
                val dayCol = (primary.dayOfWeek - 1).coerceIn(0, 6)

                val cardW = (cellWidth - 2 * gapPx).toInt()
                val cardH = cellHeight * span - 2 * gapPx
                val leftMargin = (dayCol * cellWidth + gapPx).toInt()
                val topMargin = rowStart * cellHeight + gapPx

                val cardBg = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 14f
                    setStroke((2 * density).toInt(), strokeColor)
                }

                val card = FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(cardW, cardH).apply {
                        setMargins(leftMargin, topMargin, 0, 0)
                    }
                    background = cardBg
                    setOnClickListener {
                        if (sorted.size > 1) {
                            showOverlapPicker(ctx, sorted, colors)
                        } else {
                            SchedulePageDetailDialog.show(
                                ctx, listOf(primary), colors, SchedulePageDetailDialog.Source.WEEK
                            )
                        }
                    }
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(160)
                                    .setInterpolator(OvershootInterpolator(1.5f))
                                    .start()
                            }
                        }
                        false
                    }
                }

                // 课程名恒显示；教室/教师按「课表外观 → 课程内容」的开关取舍，空值本来就跳过
                val parts = mutableListOf(primary.name)
                if (showClassroom && primary.classroom.isNotBlank()) parts.add(primary.classroom)
                if (showTeacher && primary.teacher.isNotBlank()) parts.add(primary.teacher)

                val tv = TextView(ctx).apply {
                    text = parts.joinToString("\n")
                    setTextSizeRes(R.dimen.text_label)
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    setPadding((4 * density).toInt(), (2 * density).toInt(),
                        (4 * density).toInt(), (2 * density).toInt())
                    setTypeface(Typeface.DEFAULT_BOLD)
                }
                card.addView(tv)

                // 角标：组内多于1门课时显示 +N
                if (sorted.size > 1) {
                    val badge = OverlapBadgeView(ctx).apply {
                        setCount(sorted.size - 1)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.BOTTOM or Gravity.END
                            setMargins(0, 0, (4 * density).toInt(), (4 * density).toInt())
                        }
                        setOnClickListener {
                            showOverlapPicker(ctx, sorted, colors)
                        }
                    }
                    card.addView(badge)
                }

                courseContainer.addView(card)
            }
        }

        private fun buildTimeSlotView(ctx: Context, node: Int, start: String?, end: String?, cellHeight: Int): LinearLayout {
            return LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cellHeight
                )
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(4, 4, 4, 4)

                addView(TextView(ctx).apply {
                    text = node.toString()
                    setTextSizeRes(R.dimen.text_caption)
                    setTypeface(null, Typeface.BOLD)
                    val typedValue = android.util.TypedValue()
                    ctx.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurface, typedValue, true
                    )
                    setTextColor(typedValue.data)
                })
                addView(TextView(ctx).apply {
                    text = start?.takeIf { it.isNotBlank() } ?: "--:--"
                    setTextSizeRes(R.dimen.text_label)
                    val typedValue = android.util.TypedValue()
                    ctx.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true
                    )
                    setTextColor(typedValue.data)
                })
                addView(TextView(ctx).apply {
                    text = end?.takeIf { it.isNotBlank() } ?: "--:--"
                    setTextSizeRes(R.dimen.text_label)
                    val typedValue = android.util.TypedValue()
                    ctx.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true
                    )
                    setTextColor(typedValue.data)
                })
            }
        }

        // 课程类别优先级（数字越小越优先）
        private fun categoryPriority(category: String): Int = when {
            category.contains("专业核心") -> 1
            category.contains("学类核心") -> 2
            category.contains("通识必修") -> 3
            category.contains("集中实践必修") -> 4
            category.contains("专业选修") -> 5
            else -> 99
        }

        // 课程优先级排序：实体课 > 虚拟教室；同级按类别排序
        private fun courseSortKey(course: Course): Int {
            val isVirtual = course.classroom.contains("虚拟") ||
                            course.classroom.contains("慕课")
            val tier = if (isVirtual) 2 else 1
            return tier * 100 + categoryPriority(course.courseCategory)
        }

        private fun showOverlapPicker(
            ctx: Context,
            courses: List<Course>,
            colors: IntArray
        ) {
            val items = courses.mapIndexed { _, c ->
                val priority = when {
                    courseSortKey(c) in 1..199 -> "实体课"
                    else -> "网课"
                }
                "${c.name}\n${c.classroom} | ${c.teacher} | [$priority]"
            }

            // 防止连点打开多个重叠课程弹窗：先关闭已存在的
            overlapPickerDialog?.dismiss()
            val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("重叠课程 (${courses.size}门)")
                .setItems(items.toTypedArray()) { _, which ->
                    SchedulePageDetailDialog.show(
                        ctx, listOf(courses[which]), colors, SchedulePageDetailDialog.Source.WEEK
                    )
                }
                .setNegativeButton("取消", null)
                .create()
            overlapPickerDialog = dialog
            dialog.setOnDismissListener { if (overlapPickerDialog === dialog) overlapPickerDialog = null }
            dialog.show()
        }
    }

    companion object {
        /** 日期栏上下文（学期开始日期 / 当前周）变化的 payload 标记 */
        private const val PAYLOAD_WEEK_CONTEXT = "week_context"

        /** 边框默认色：50% 半透明白（十六进制超出 Int 范围需 toInt），与历史版本写死的描边一致 */
        val DEFAULT_BORDER_COLOR = 0x80FFFFFF.toInt()

        /** 底部留白高度占屏高的比例（课表整体 → 底部留白） */
        private const val BOTTOM_BLANK_RATIO = 0.15f

        /**
         * 用代码构建页面根布局。
         * 网格沿用原始 app 的零 XML inflation 做法；日期栏复用 XML 里的 style，
         * 按 ViewHolder 构造时 inflate 一次（不是每次 bind），见 item_date_header。
         */
        private fun buildPageRoot(context: Context): LinearLayout {
            val density = context.resources.displayMetrics.density

            // 右侧内容区
            val gridBg = GridBackgroundView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val courseContainer = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val emptyIcon = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (80 * density).toInt(), (80 * density).toInt()
                ).apply { gravity = Gravity.CENTER }
                setImageResource(R.drawable.ic_mtrl_calendar_month)
                alpha = 0.3f
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true
                )
                setColorFilter(typedValue.data)
            }

            val emptyText = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (16 * density).toInt() }
                text = "暂无课程"
                setTextSizeRes(R.dimen.text_subtitle)
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true
                )
                setTextColor(typedValue.data)
            }

            val emptyLayout = LinearLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                addView(emptyIcon)
                addView(emptyText)
            }

            val contentArea = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(gridBg)
                addView(courseContainer)
                addView(emptyLayout)
            }

            val timeAxis = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (32 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
            }

            val contentLayout = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                addView(timeAxis)
                addView(contentArea)
            }

            // 「课表整体 → 底部留白」：网格之下追加一段空白（开关在 bind 里生效），
            // 让底部的课程能上滑到屏幕中部查看
            val bottomSpacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0
                )
                visibility = View.GONE
            }

            val scrollContent = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.VERTICAL
                addView(contentLayout)
                addView(bottomSpacer)
            }

            val scrollView = VerticalScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                addView(scrollContent)
            }

            val root = LinearLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
            }
            // 以 root 当 parent 才能拿到 XML 根标签生成的 LinearLayout.LayoutParams；
            // 传 null 会退化成 wrap_content，日期栏宽度会塌掉
            root.addView(
                LayoutInflater.from(context).inflate(R.layout.item_date_header, root, false)
            )
            root.addView(scrollView)
            return root
        }
    }
}
