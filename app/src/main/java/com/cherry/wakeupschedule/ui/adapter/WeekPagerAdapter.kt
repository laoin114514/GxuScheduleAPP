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

    override fun getItemCount(): Int = totalWeeks

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekViewHolder {
        return WeekViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: WeekViewHolder, position: Int) {
        val week = position + 1
        holder.bind(week, allCourses, cellHeightDp, semesterStartDate, currentWeek)
    }

    /** 只有日期栏上下文变化时走这条轻量分支，避免整页重建课程卡片 */
    override fun onBindViewHolder(
        holder: WeekViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_WEEK_CONTEXT)) {
            holder.bindDateHeader(position + 1, semesterStartDate, currentWeek)
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

        // ── 日期栏 ──
        private val dateViews: Array<TextView>
        private val yearView: TextView

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
            val contentLayout = scrollView.getChildAt(0) as LinearLayout
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
         */
        fun bindDateHeader(week: Int, semesterStartDate: Long, currentWeek: Int) {
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

            dateViews.forEach { tv ->
                tv.text = headerDateFormat.format(headerCal.time)
                val isToday = isCurrentWeek &&
                        headerCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                        headerCal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                if (isToday) {
                    tv.setBackgroundResource(R.drawable.bg_date_selected)
                    tv.setTextColor(todayTextColor)
                } else {
                    tv.background = null
                    tv.setTextColor(normalTextColor)
                }
                headerCal.add(Calendar.DAY_OF_MONTH, 1)
            }
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
            currentWeek: Int
        ) {
            // 日期栏先渲染：下面「暂无课程」会提前 return，不能漏掉日期栏
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

            // ── 重建课程卡片 ──
            courseContainer.removeAllViews()
            val gapPx = (2 * density).toInt()
            val cellWidth = contentWidth / 7f
            val textColor = Color.WHITE
            val strokeColor = 0x80FFFFFF.toInt()
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

                val parts = mutableListOf(primary.name)
                if (primary.classroom.isNotBlank()) parts.add(primary.classroom)
                if (primary.teacher.isNotBlank()) parts.add(primary.teacher)

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

            val scrollView = VerticalScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                addView(contentLayout)
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
