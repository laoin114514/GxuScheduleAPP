package com.cherry.wakeupschedule.ui.component

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.SemesterEntity
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.JwxtImportService
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.widget.SemesterSceneryView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 学期滚轮选择弹窗。
 *
 * 与课表菜单的学期色块同语义：
 * - 已导入课表的学期：左侧迷你风景图案
 * - 未导入的学期：左侧素色块，点击切换时当场从教务拉取课表并显示 loading
 *
 * 交互参照闹钟时间选取的循环滚轮：列表多份复制实现无限循环，点击某行即切换，
 * 每行左侧为色块图案，右侧为学期名全称。
 */
class SemesterWheelDialog(
    context: Context,
    private val onSemesterSwitched: ((Int) -> Unit)? = null
) : Dialog(context, R.style.RoundedDialog) {

    private val ctx: Context = context
    private val settingsManager = SettingsManager(ctx)

    private val semesters: List<SemesterEntity> = SemesterManager.getAll()
    private var courseCounts: Map<Long, Int> =
        CourseDataManager.getInstance(ctx).getSemesterCourseCounts()

    private var currentIndex = settingsManager.getCurrentSemesterIndex()

    /** 正在从教务导入课表的学期索引（null = 无），该项色块上叠加转圈 */
    private var loadingIndex: Int? = null
    private var isLoading = false

    /** 滚轮列表的学期份数：多份复制实现循环（两端仍可继续滚） */
    private val multiplier = 1000

    private val fetchScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── 主题色取样（浅色/深色自适应，与课表菜单同款） ──
    private fun resolveAttr(attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private val primaryColor = resolveAttr(com.google.android.material.R.attr.colorPrimary)
    private val primaryContainer = resolveAttr(com.google.android.material.R.attr.colorPrimaryContainer)
    private val secondaryContainer = resolveAttr(com.google.android.material.R.attr.colorSecondaryContainer)
    private val tertiaryContainer = resolveAttr(com.google.android.material.R.attr.colorTertiaryContainer)
    private val onSurfaceColor = resolveAttr(com.google.android.material.R.attr.colorOnSurface)
    private val blockColor = resolveAttr(com.google.android.material.R.attr.colorSurfaceVariant)
    private val surfaceContainer = resolveAttr(com.google.android.material.R.attr.colorSurfaceContainer)

    // 风景里的太阳固定暖色，浅色/深色模式下都醒目
    private val sunColor = 0xFFFFD54F.toInt()

    private val density = ctx.resources.displayMetrics.density
    private val itemHeight = (60 * density).roundToInt()
    private val blockSize = (54 * density).roundToInt()

    private lateinit var rv: RecyclerView
    private lateinit var adapter: WheelAdapter
    private lateinit var lm: LinearLayoutManager

    /** 当前居中的 adapter 位置（绑定行与滚动时的高亮都读它） */
    private var centerPosition: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_semester_wheel)

        setupWindow()
        setupBackground()
        setupAccentLine()
        setupWheel()
        setupWheelDecor()
        setupButtons()

        setOnDismissListener { fetchScope.cancel() }
    }

    // ── Window ──────────────────────────────────────────

    private fun setupWindow() {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (ctx.resources.displayMetrics.widthPixels * 0.88).toInt()
            setLayout(minOf(width, dp2px(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun setupBackground() {
        // 弹层底色与课表菜单同款：surfaceContainer + 24dp 圆角
        val root = window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            ?.getChildAt(0) ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24 * density
            setColor(surfaceContainer)
        }
    }

    private fun setupAccentLine() {
        val vAccent = findViewById<View>(R.id.v_accent)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                primaryColor,
                primaryColor and 0x40FFFFFF.toInt(),
                primaryColor and 0x00FFFFFF.toInt()
            )
        )
        vAccent.background = gradient
    }

    // ── 滚轮 ─────────────────────────────────────────────

    private fun setupWheel() {
        rv = findViewById(R.id.rv_semester_wheel)
        lm = LinearLayoutManager(ctx)

        val initialPosition = semesterInitialPosition()
        centerPosition = initialPosition
        adapter = WheelAdapter()
        rv.layoutManager = lm
        rv.adapter = adapter
        rv.itemAnimator = null

        // 中心吸附：滚动停止时最近的一行对齐滚轮中心
        CenterSnapHelper().attachToRecyclerView(rv)

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val centerY = rv.height / 2
                var nearest: View? = null
                var minDist = Int.MAX_VALUE
                for (i in 0 until lm.childCount) {
                    val child = lm.getChildAt(i) ?: continue
                    val dist = abs((child.top + child.bottom) / 2 - centerY)
                    if (dist < minDist) {
                        minDist = dist
                        nearest = child
                    }
                }
                nearest?.let { updateCenterHighlight(lm.getPosition(it)) }
            }
        })

        // 初始定位：中间份数的对应学期（item 顶边对齐 paddingTop 即天然居中）
        lm.scrollToPositionWithOffset(initialPosition, 0)
        // 求一次精确对中，避免任何像素级偏移
        rv.post { centerOn(initialPosition) }
    }

    /**
     * 滚动时更新中心行高亮。
     * 直接改已绑定行的背景/字体，不用 notifyItemChanged —— 后者会在滚动中反复触发布局，
     * 与吸附滚轮打架（表现为不停抖动）。
     */
    private fun updateCenterHighlight(newCenter: Int) {
        if (centerPosition == newCenter) return
        applyCenterHighlight(lm.findViewByPosition(centerPosition), isCenter = false)
        centerPosition = newCenter
        applyCenterHighlight(lm.findViewByPosition(newCenter), isCenter = true)
    }

    private fun applyCenterHighlight(view: View?, isCenter: Boolean) {
        val holder = view?.let { rv.findContainingViewHolder(it) as? WheelAdapter.ViewHolder }
            ?: return
        holder.row.background = rowBackground(isCenter)
        holder.label.setTypeface(null, if (isCenter) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rowBackground(isCenter: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * density
        setColor(if (isCenter) ColorUtils.setAlphaComponent(primaryColor, 28) else 0)
    }

    private fun semesterInitialPosition(): Int {
        val n = semesters.size
        if (n == 0) return 0
        return n * (multiplier / 2) + currentIndex.coerceIn(0, n - 1)
    }

    /** 滚动到指定 adapter 位置并使其居中 */
    private fun centerOn(adapterPosition: Int) {
        if (adapterPosition == RecyclerView.NO_POSITION) return
        val target = lm.findViewByPosition(adapterPosition)
        if (target == null) {
            // 目标不在视野内：先跳到附近，下一帧再精确对中
            lm.scrollToPositionWithOffset(adapterPosition, 0)
            rv.post { centerOn(adapterPosition) }
            return
        }
        val delta = (target.top + target.bottom) / 2 - rv.height / 2
        rv.smoothScrollBy(0, delta)
    }

    // ── 滚轮装饰（中心高亮带 + 上下渐隐） ──────────────────

    private fun setupWheelDecor() {
        // 中心行高亮带：主题色低透明度圆角条
        findViewById<View>(R.id.center_band).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * density
            setColor(ColorUtils.setAlphaComponent(primaryColor, 22))
        }
        // 上下渐隐：与弹层底色同色渐变，突出中心行
        val transparentSurface = ColorUtils.setAlphaComponent(surfaceContainer, 0)
        findViewById<View>(R.id.fade_top).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(surfaceContainer, transparentSurface)
        )
        findViewById<View>(R.id.fade_bottom).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(transparentSurface, surfaceContainer)
        )
    }

    // ── 切换逻辑（与课表菜单同语义） ─────────────────────

    private fun onSemesterClicked(index: Int) {
        if (isLoading) return
        val sem = semesters[index]
        if (index == currentIndex) {
            // 点击当前学期：作为"确认"快捷关闭
            dismiss()
            return
        }
        settingsManager.setCurrentSemesterIndex(index)
        currentIndex = index
        CourseDataManager.getInstance(ctx).switchSemester(sem.id)

        val needsFetch = sem.startDate == 0L || sem.totalWeeks == 0
        if (!needsFetch) {
            // 本地已有课表，直接切换完成
            onSemesterSwitched?.invoke(index)
            dismiss()
            return
        }

        // 未导入：从教务拉取课表，该行色块上叠加转圈
        isLoading = true
        loadingIndex = index
        adapter.notifyDataSetChanged()
        fetchScope.launch {
            val result = withContext(Dispatchers.IO) {
                JwxtImportService.fetchAndSaveScheduleForSemester(ctx, sem)
            }
            isLoading = false
            loadingIndex = null
            result.onSuccess { count ->
                // 刷新图案状态：已导入的学期行变风景色块
                courseCounts = CourseDataManager.getInstance(ctx).getSemesterCourseCounts()
                adapter.notifyDataSetChanged()
                onSemesterSwitched?.invoke(index)
                Toast.makeText(ctx, "已导入 $count 门课程", Toast.LENGTH_SHORT).show()
                dismiss()
            }.onFailure { e ->
                adapter.notifyDataSetChanged()
                Toast.makeText(ctx, "获取课表失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Buttons ────────────────────────────────────────────

    private fun setupButtons() {
        findViewById<TextView>(R.id.btn_cancel).setOnClickListener { dismiss() }
    }

    // ── Adapter ────────────────────────────────────────────

    /** 滚轮行：左侧色块（风景/素块 + loading 转圈），右侧学期名全称 */
    private inner class WheelAdapter :
        RecyclerView.Adapter<WheelAdapter.ViewHolder>() {

        override fun getItemCount(): Int = semesters.size * multiplier

        inner class ViewHolder(
            val row: LinearLayout,
            val blockContainer: FrameLayout,
            val label: TextView
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemHeight
                )
                setPadding(dp2px(14), 0, dp2px(14), 0)
            }
            val blockContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(blockSize, blockSize)
            }
            row.addView(blockContainer)

            val label = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp2px(14) }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onSurfaceColor)
            }
            row.addView(label)
            return ViewHolder(row, blockContainer, label)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val index = position % semesters.size
            val sem = semesters[index]
            val isCenter = position == centerPosition
            val isCurrent = index == currentIndex
            val hasCourses = (courseCounts[sem.id] ?: 0) > 0
            val isLoadingBlock = index == loadingIndex

            // 行背景：中心行主题色高亮圆角条
            holder.row.background = rowBackground(isCenter)

            // 左侧色块：已导入 → 迷你风景；未导入 → 素色块；loading → 叠转圈
            val ctx = holder.itemView.context
            holder.blockContainer.removeAllViews()
            if (hasCourses) {
                val scenery = SemesterSceneryView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(blockSize, blockSize)
                    setPalette(
                        skyTop = primaryContainer,
                        skyBottom = ColorUtils.blendARGB(primaryContainer, primaryColor, 0.35f),
                        hillBack = tertiaryContainer,
                        hillFront = secondaryContainer,
                        sunColor = sunColor
                    )
                    if (isCurrent) setSelectionStroke(primaryColor, 2.5f * density)
                }
                holder.blockContainer.addView(scenery)
            } else {
                holder.blockContainer.addView(View(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(blockSize, blockSize)
                    background = GradientDrawable().apply {
                        cornerRadius = 14 * density
                        setColor(blockColor)
                        if (isCurrent) {
                            setStroke((2.5f * density).roundToInt(), primaryColor)
                        }
                    }
                })
            }
            if (isLoadingBlock) {
                val spinnerSize = (24 * density).roundToInt()
                holder.blockContainer.addView(
                    com.google.android.material.progressindicator.CircularProgressIndicator(
                        ctx
                    ).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            spinnerSize, spinnerSize, Gravity.CENTER
                        )
                        isIndeterminate = true
                        setIndicatorColor(primaryColor)
                        trackThickness = (2.5f * density).roundToInt()
                    }
                )
            }

            // 右侧学期名全称
            holder.label.text =
                "${sem.label}  ${sem.academicYear}学年 ${sem.termName}"
            holder.label.setTypeface(null, if (isCenter) Typeface.BOLD else Typeface.NORMAL)

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val index = pos % semesters.size
                centerOn(pos)
                onSemesterClicked(index)
            }
        }
    }

    // ── 中心吸附（滚轮停在最靠近中心的行） ───────────────

    private class CenterSnapHelper : SnapHelper() {
        override fun findSnapView(lm: RecyclerView.LayoutManager?): View? {
            (lm as? LinearLayoutManager) ?: return null
            var nearest: View? = null
            var minDist = Int.MAX_VALUE
            val centerY = lm.height / 2
            for (i in 0 until lm.childCount) {
                val child = lm.getChildAt(i) ?: continue
                val dist = abs((child.top + child.bottom) / 2 - centerY)
                if (dist < minDist) {
                    minDist = dist
                    nearest = child
                }
            }
            return nearest
        }

        override fun calculateDistanceToFinalSnap(
            lm: RecyclerView.LayoutManager,
            targetView: View
        ): IntArray {
            // 约定与 SnapHelper/LinearSnapHelper 一致：返回「行中心 - 容器中心」，
            // SnapHelper 会原样交给 smoothScrollBy(0, dy)，反向会导致吸附永远朝
            // 远处滚、停稳后又被修正，表现为滚轮来回抖动。
            val out = IntArray(2)
            (lm as? LinearLayoutManager)?.let {
                val itemCenter = (targetView.top + targetView.bottom) / 2
                out[1] = itemCenter - it.height / 2
            }
            return out
        }

        /**
         * 依据 fling 距离推算目标行。
         * 行高均匀，用「目标像素 / 行高」取整即可得精确目标。
         */
        override fun findTargetSnapPosition(
            lm: RecyclerView.LayoutManager,
            velocityX: Int,
            velocityY: Int
        ): Int {
            val linear = lm as? LinearLayoutManager ?: return RecyclerView.NO_POSITION
            val itemCount = linear.itemCount
            if (itemCount == 0) return RecyclerView.NO_POSITION

            val distanceY = calculateScrollDistance(velocityX, velocityY)[1]
            val firstChild = linear.getChildAt(0) ?: return RecyclerView.NO_POSITION
            val itemH = firstChild.height
            if (itemH <= 0) return RecyclerView.NO_POSITION

            // 当前滚动偏移（px）：第一个可见行顶边相对内容区顶边的位置
            val scrollOffset =
                linear.paddingTop + linear.findFirstVisibleItemPosition() * itemH - firstChild.top
            val targetPx = scrollOffset + distanceY
            val target = (targetPx.toFloat() / itemH).roundToInt()
            return target.coerceIn(0, itemCount - 1)
        }
    }

    // ── Utility ─────────────────────────────────────────────

    private fun dp2px(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            ctx.resources.displayMetrics
        ).roundToInt()

    private fun dp2px(dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            ctx.resources.displayMetrics
        ).roundToInt()
}
