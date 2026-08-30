package com.cherry.wakeupschedule.ui.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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

/**
 * ViewPager2 适配器。
 * 完全模仿原始 app：RecyclerView.Adapter + 代码构建 View（零 XML inflation）+ 每页直接渲染。
 */
class WeekPagerAdapter(
    private val totalWeeks: Int
) : RecyclerView.Adapter<WeekPagerAdapter.WeekViewHolder>() {

    private var allCourses: List<Course> = emptyList()

    fun updateData(courses: List<Course>) {
        allCourses = courses
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = totalWeeks

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekViewHolder {
        return WeekViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: WeekViewHolder, position: Int) {
        val week = position + 1
        holder.bind(week, allCourses)
    }

    class WeekViewHolder(context: Context) : RecyclerView.ViewHolder(
        buildPageRoot(context)
    ) {
        // ── 缓存的 view 引用 ──
        private val scrollView: VerticalScrollView
        private val gridBg: GridBackgroundView
        private val timeAxis: LinearLayout
        private val courseContainer: FrameLayout
        private val emptyView: LinearLayout

        private var axisBuilt = false
        private var builtNodes = 0
        private val courseColors: IntArray get() = ThemeManager.getCourseColors()

        /** 当前显示的重叠课程弹窗，防止连点叠加 */
        private var overlapPickerDialog: android.app.Dialog? = null

        init {
            val root = itemView as LinearLayout
            scrollView = root.getChildAt(0) as VerticalScrollView
            val contentLayout = scrollView.getChildAt(0) as LinearLayout
            timeAxis = contentLayout.getChildAt(0) as LinearLayout
            val contentArea = contentLayout.getChildAt(1) as FrameLayout
            gridBg = contentArea.getChildAt(0) as GridBackgroundView
            courseContainer = contentArea.getChildAt(1) as FrameLayout
            emptyView = contentArea.getChildAt(2) as LinearLayout
        }

        fun bind(week: Int, allCourses: List<Course>) {
            val ctx = itemView.context
            val cellHeight = ctx.resources.getDimensionPixelSize(R.dimen.course_cell_height)
            val maxNodes = TimeTableManager.getInstance(ctx).getMaxNodes()
            val density = ctx.resources.displayMetrics.density

            // ── 网格背景（只配置一次） ──
            if (!axisBuilt || builtNodes != maxNodes) {
                gridBg.rowCount = maxNodes
                gridBg.columnCount = 7
                gridBg.gridColor = android.graphics.Color.TRANSPARENT
            }

            // ── 时间轴（只构建一次或节点数变化时重建） ──
            if (!axisBuilt || builtNodes != maxNodes) {
                timeAxis.removeAllViews()
                val slots = TimeTableManager.getInstance(ctx).getTimeSlots()
                for (node in 1..maxNodes) {
                    val slot = slots.find { it.node == node }
                    val timeView = buildTimeSlotView(ctx, node, slot?.startTime, slot?.endTime, cellHeight)
                    timeAxis.addView(timeView)
                }
                axisBuilt = true
                builtNodes = maxNodes
            }

            // ── 筛选本周课程 ──
            val weekCourses = allCourses.filter { it.isActiveInWeek(week) }

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
                            SchedulePageDetailDialog.show(ctx, primary, colors)
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
                    textSize = 10f
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
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    val typedValue = android.util.TypedValue()
                    ctx.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurface, typedValue, true
                    )
                    setTextColor(typedValue.data)
                })
                addView(TextView(ctx).apply {
                    text = start?.takeIf { it.isNotBlank() } ?: "--:--"
                    textSize = 9f
                    val typedValue = android.util.TypedValue()
                    ctx.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true
                    )
                    setTextColor(typedValue.data)
                })
                addView(TextView(ctx).apply {
                    text = end?.takeIf { it.isNotBlank() } ?: "--:--"
                    textSize = 9f
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
                    SchedulePageDetailDialog.show(ctx, courses[which], colors)
                }
                .setNegativeButton("取消", null)
                .create()
            overlapPickerDialog = dialog
            dialog.setOnDismissListener { if (overlapPickerDialog === dialog) overlapPickerDialog = null }
            dialog.show()
        }
    }

    companion object {
        /** 用代码构建页面根布局（零 XML inflation，跟原始 app 一致） */
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
                textSize = 16f
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
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // 保留默认 overScrollMode：纵向拉到尽头触发系统拉伸回弹，与 ViewPager2 横向一致
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                addView(contentLayout)
            }

            return LinearLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(scrollView)
            }
        }
    }
}
