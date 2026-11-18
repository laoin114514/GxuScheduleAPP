package com.cherry.wakeupschedule

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.ui.feedback.AppToast
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.model.GradeEntity
import com.cherry.wakeupschedule.model.SemesterEntity
import com.cherry.wakeupschedule.service.GradeDataManager
import com.cherry.wakeupschedule.service.GradeImportService
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.ui.component.createAppChip
import com.cherry.wakeupschedule.ui.component.createChipDivider
import com.cherry.wakeupschedule.ui.component.themeColor
import com.cherry.wakeupschedule.ui.screen.grade.GradeAdapter
import com.cherry.wakeupschedule.ui.screen.grade.GradeDetailDialog
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.screen.grade.GradeFilter
import com.cherry.wakeupschedule.ui.screen.grade.GradeStats
import com.cherry.wakeupschedule.ui.screen.grade.GradeSummary
import com.cherry.wakeupschedule.ui.component.PageStatusController
import com.cherry.wakeupschedule.ui.component.SemesterExpandPicker
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 成绩查询页。
 *
 * - 学期选择：浮层下拉（[SemesterExpandPicker]），**只筛选本页**，不改课表的当前学期
 * - 查询：从教务拉取所选学期成绩，**整学期覆盖**写入本地（[GradeImportService]）；
 *   查询期间「查询」按钮置灰禁用，loading 覆盖层盖住整个成绩列表
 * - 列表：筛选（全部/必修/选修/不及格）+ 按分数排序；点条目看平时/期末详情
 */
class GradeQueryActivity : BaseActivity() {

    private lateinit var gradeDataManager: GradeDataManager
    private lateinit var adapter: GradeAdapter
    private lateinit var picker: SemesterExpandPicker

    private lateinit var recycler: RecyclerView
    private lateinit var status: PageStatusController
    private lateinit var gpaText: TextView
    private lateinit var creditsText: TextView
    private lateinit var filterRow: LinearLayout
    private lateinit var btnQuery: View

    private var currentFilter = GradeFilter.ALL
    private var sortDescending = true

    /** 当前学期全量成绩（不筛选），用于汇总 */
    private var semesterGrades: List<GradeEntity> = emptyList()

    /** 汇总数字上次展示值（滚动的起点）；null = 还没展示过 */
    private var shownGpa: Float? = null
    private var shownCredits: Float? = null

    /** 列表入场是否已播过：转屏/返回不重播（随实例状态保存） */
    private var listEntrancePlayed = false

    /** 列表数据替换的世代号：延迟入场只在世代未变时生效，避免旧结果盖掉新结果 */
    private var listRevealGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listEntrancePlayed = savedInstanceState?.getBoolean(KEY_LIST_ENTRANCE_PLAYED) ?: false
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_grade_query)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupPageHeader(findViewById(R.id.toolbar), "成绩查询")

        gradeDataManager = GradeDataManager.getInstance(this)

        recycler = findViewById(R.id.rv_grades)
        gpaText = findViewById(R.id.tv_gpa)
        creditsText = findViewById(R.id.tv_credits)
        filterRow = findViewById(R.id.ll_filters)

        btnQuery = findViewById(R.id.btn_query)
        val loadingIndicator =
            findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(
                R.id.pb_loading
            )
        loadingIndicator.isIndeterminate = true
        loadingIndicator.setIndicatorColor(themeColor(com.google.android.material.R.attr.colorPrimary))
        // 状态层（加载/空态/失败）统一由 PageStatusController 管理，与考试安排页共用一份实现
        status = PageStatusController(
            container = findViewById(R.id.ll_status),
            textView = findViewById(R.id.tv_status),
            loadingView = loadingIndicator,
            iconView = findViewById(R.id.iv_status_icon),
            actionView = findViewById(R.id.tv_status_action),
            listView = recycler,
            onLoadingChanged = { loading ->
                btnQuery.isEnabled = !loading
            },
            loadingPulseView = btnQuery
        )

        adapter = GradeAdapter { showDetail(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        picker = SemesterExpandPicker(
            context = this,
            field = findViewById(R.id.ll_semester_field),
            blockContainer = findViewById(R.id.fl_semester_block),
            labelView = findViewById(R.id.tv_semester_value),
            arrow = findViewById(R.id.iv_expand),
            onSelected = { semester -> onSemesterSelected(semester) }
        )

        // 下拉是独立窗口，页面滚动后不会跟着字段走，滚动时直接收起
        findViewById<View>(R.id.scroll_content).setOnScrollChangeListener { _, _, _, _, _ ->
            picker.collapse()
        }

        btnQuery.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(themeColor(com.google.android.material.R.attr.colorPrimary))
        }
        btnQuery.setOnClickListener { querySelectedSemester() }

        buildFilterChips()
        loadSemesters()
    }

    // ── 学期 ──────────────────────────────────────────────

    private fun loadSemesters() {
        val semesters = SemesterManager.getAll()
        if (semesters.isEmpty()) {
            picker.submit(emptyList(), emptyMap(), 0L)
            showStatus("请先绑定教务账号，绑定后会自动生成学期", loading = false)
            gpaText.text = "—"
            creditsText.text = "—"
            adapter.submit(emptyList())
            return
        }

        lifecycleScope.launch {
            val counts = gradeDataManager.getSemesterGradeCounts()
            // 初始选中：上次选的 → 全局当前学期 → 首个已有成绩的学期 → 第一个
            val selected = gradeDataManager.selectedSemesterId
                .takeIf { id -> semesters.any { it.id == id } }
                ?: SemesterManager.getCurrent()?.id
                ?: counts.keys.firstOrNull()
                ?: semesters.first().id
            gradeDataManager.selectSemester(selected)
            picker.submit(semesters, counts, selected)
            renderGrades()
        }
    }

    private fun onSemesterSelected(semester: SemesterEntity) {
        gradeDataManager.selectSemester(semester.id)
        lifecycleScope.launch { renderGrades() }
    }

    // ── 查询（覆盖入库） ──────────────────────────────────

    private fun querySelectedSemester() {
        val semester = currentSemester()
        if (semester == null) {
            AppToast.warn(this, "请先选择学期")
            return
        }
        if (!JwxtAuthManager.isBound()) {
            AppToast.warn(this, "请先绑定教务账号")
            return
        }
        picker.collapse()
        showStatus("正在从教务获取成绩…", loading = true)

        lifecycleScope.launch {
            val result = GradeImportService.fetchAndSaveGradesForSemester(this@GradeQueryActivity, semester)
            result.onSuccess { count ->
                if (count == 0) {
                    toast("该学期暂无成绩")
                } else {
                    AppToast.success(this@GradeQueryActivity, "已更新 $count 条成绩")
                }
                refreshSemesterOptions()
                renderGrades()
            }.onFailure { error ->
                // 失败原因走浮层提示（CaptchaRequiredException 等已带友好文案）
                AppToast.error(this@GradeQueryActivity, error.message ?: "查询失败")
                if (semesterGrades.isEmpty()) {
                    // 本来就没数据时，空态换成"失败 + 重试"，而不是继续显示"还没有成绩"
                    showStatus(
                        "查询失败：${error.message ?: "网络异常"}",
                        loading = false,
                        iconRes = R.drawable.ic_mtrl_error_outline,
                        actionText = "重试",
                        action = { querySelectedSemester() }
                    )
                } else {
                    renderGrades()
                }
            }
        }
    }

    private suspend fun refreshSemesterOptions() {
        picker.submit(
            SemesterManager.getAll(),
            gradeDataManager.getSemesterGradeCounts(),
            gradeDataManager.selectedSemesterId
        )
    }

    // ── 渲染 ──────────────────────────────────────────────

    private suspend fun renderGrades(revealAfterFade: Boolean = false) {
        val filterAtStart = currentFilter
        val descendingAtStart = sortDescending
        semesterGrades = gradeDataManager.loadGrades()

        renderSummaryNumbers(GradeStats.summarize(semesterGrades))

        // 加载期间用户又切了筛选/排序：本次结果作废，等新的渲染（避免旧结果盖掉新结果）
        if (filterAtStart != currentFilter || descendingAtStart != sortDescending) return

        val visible = GradeStats.sort(
            GradeStats.filter(semesterGrades, filterAtStart),
            descending = descendingAtStart
        )
        // 状态层（loading/空态）正显示时：结果落位后等它淡出再播列表入场，顺序更自然；
        // 这种"数据落位"每次查询完成都值得播一次，不属于返回/转屏时的重复入场
        val statusWasShowing = status.isShowing
        submitGrades(
            visible = visible,
            animateIn = revealAfterFade || !listEntrancePlayed || statusWasShowing,
            startDelay = if (!revealAfterFade && statusWasShowing) Motion.SHORT else 0L
        )

        when {
            semesterGrades.isEmpty() -> showStatus(
                "该学期还没有成绩，点「查询」从教务获取",
                loading = false,
                iconRes = R.drawable.ic_mtrl_assignment,
                actionText = if (JwxtAuthManager.isBound()) "立即查询" else "去绑定教务",
                action = {
                    if (JwxtAuthManager.isBound()) querySelectedSemester()
                    else startActivity(
                        android.content.Intent(this, BindJwxtActivity::class.java)
                    )
                }
            )
            visible.isEmpty() -> showStatus(
                "没有符合「${currentFilter.label}」的成绩",
                loading = false,
                iconRes = R.drawable.ic_mtrl_search
            )
            else -> hideStatus()
        }
    }

    /**
     * 列表数据落位。
     *
     * - 首次数据到达 / 筛选切换：等本次 notifyDataSetChanged 完成布局后再对可见项错峰入场
     *   （只做 alpha + 6dp 上浮，不做缩放；封顶 10 项）；
     * - 其它刷新（返回页面、跨时刻重渲染）：只换数据，不重播。
     */
    private fun submitGrades(visible: List<GradeEntity>, animateIn: Boolean, startDelay: Long) {
        val generation = ++listRevealGeneration
        // 先停掉可能还在跑的筛选淡出，避免它的 alpha 动画覆盖下面设置的目标透明度
        recycler.animate().cancel()
        adapter.submit(visible)
        if (!animateIn || visible.isEmpty() || !Motion.enabled()) {
            recycler.alpha = 1f
            return
        }
        listEntrancePlayed = true
        // 入场前先把列表隐藏：状态层淡出期间不会先露出"已经铺满"的静态列表，
        // 下一帧再整体淡入并错峰，形成"旧状态退场 → 数据落位"的连续过程
        recycler.alpha = 0f
        // notifyDataSetChanged 之后直接 post 可能早于布局（拿到的还是旧子项），
        // 等 RecyclerView 完成本次布局再错峰，保证动画作用在新列表上
        recycler.doOnNextLayout {
            if (generation != listRevealGeneration) return@doOnNextLayout
            val reveal = Runnable {
                if (generation != listRevealGeneration || isFinishing || isDestroyed) return@Runnable
                recycler.alpha = 1f
                val children = (0 until recycler.childCount).mapNotNull { recycler.getChildAt(it) }
                if (children.isEmpty()) return@Runnable
                // 与 Motion.staggerRecycler 同一套错峰规则；区别是这里需要 6dp 上浮，
                // staggerRecycler 固定 alphaOnly=true 会忽略 fromY（Motion.kt 不归本任务改）
                Motion.stagger(
                    children,
                    step = Motion.STAGGER_STEP,
                    duration = Motion.ENTRANCE,
                    fromY = 6f,
                    fromScale = 1f
                )
            }
            if (startDelay > 0L) recycler.postDelayed(reveal, startDelay) else recycler.post(reveal)
        }
    }

    /** 筛选/排序切换：先淡出旧列表再换数据淡入，避免"啪"地替换（不阻塞 chip 状态切换） */
    private fun reloadWithFade() {
        if (!Motion.enabled()) {
            lifecycleScope.launch { renderGrades() }
            return
        }
        val generation = ++listRevealGeneration
        recycler.animate().cancel()
        recycler.animate()
            .alpha(0f)
            .setDuration(Motion.FAST)
            .setInterpolator(Motion.accelerate)
            .withEndAction {
                if (generation != listRevealGeneration || isFinishing || isDestroyed) {
                    return@withEndAction
                }
                lifecycleScope.launch { renderGrades(revealAfterFade = true) }
            }
            .start()
    }

    /** 汇总数字滚动：从上一次展示值滚到新值；无数据走「—」占位，不滚「0.00」 */
    private fun renderSummaryNumbers(summary: GradeSummary) {
        // 等宽数字：两个指标并排时数字宽度一致，切换学期时不会左右抖
        gpaText.fontFeatureSettings = "'tnum'"
        creditsText.fontFeatureSettings = "'tnum'"
        shownGpa = animateMetric(gpaText, summary.averageGpa?.toFloat(), shownGpa) { value ->
            GradeStats.formatGpa(value.toDouble())
        }
        shownCredits = animateMetric(creditsText, summary.totalCredits.toFloat(), shownCredits) { value ->
            GradeStats.formatCredits(value.toDouble())
        }
    }

    /**
     * 单个指标的数字滚动。返回本次目标值，作为下次滚动的起点。
     *
     * [target] 为 null 时直接落「—」占位；首次展示从 0 滚入，之后从旧值滚到新值，
     * 不会出现先显示 0.00 再跳过去的多余闪烁。
     */
    private fun animateMetric(
        textView: TextView,
        target: Float?,
        previous: Float?,
        format: (Float) -> String
    ): Float? {
        if (target == null) {
            // 先取消可能在跑的滚动（Motion 内部按 TextView 去重），再落占位符
            Motion.countUp(textView, 0f, duration = 0L) { "" }
            textView.text = "—"
            return null
        }
        if (previous == null) {
            if (target == 0f || !Motion.enabled()) {
                textView.text = format(target)
            } else {
                Motion.countUp(textView, target, format = format)
            }
            return target
        }
        if (previous == target || !Motion.enabled()) {
            textView.text = format(target)
            return target
        }
        Motion.countUp(textView, target, from = previous, format = format)
        return target
    }

    // ── 筛选 chip ─────────────────────────────────────────

    private fun buildFilterChips() {
        filterRow.removeAllViews()
        GradeFilter.values().forEach { filter ->
            filterRow.addView(
                createAppChip(
                    label = filter.label,
                    selected = filter == currentFilter,
                    accent = if (filter == GradeFilter.FAILED) {
                        themeColor(com.google.android.material.R.attr.colorError)
                    } else null,
                    leadingIcon = null
                ) {
                    currentFilter = filter
                    buildFilterChips()
                    reloadWithFade()
                }
            )
        }

        filterRow.addView(createChipDivider())

        filterRow.addView(
            createAppChip(
                label = if (sortDescending) "成绩从高到低" else "成绩从低到高",
                selected = false,
                accent = null,
                leadingIcon = R.drawable.ic_mtrl_swap_horiz
            ) {
                sortDescending = !sortDescending
                buildFilterChips()
                reloadWithFade()
            }
        )
    }

    // ── 成绩详情 ──────────────────────────────────────────

    private fun showDetail(grade: GradeEntity) {
        if (!JwxtAuthManager.isBound()) {
            AppToast.warn(this, "请先绑定教务账号")
            return
        }
        if (grade.classId.isBlank() || grade.studentId.isBlank()) {
            AppToast.warn(this, "该成绩缺少详情参数")
            return
        }

        val dialog = GradeDetailDialog.show(this, grade)
        lifecycleScope.launch {
            val result = JwxtAuthManager.doWithAuth { client ->
                client.grades().detail(
                    grade.classId, grade.schoolYear, grade.term, grade.studentId, grade.courseName
                )
            }
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { dialog.bind(it) }
                .onFailure { dialog.showError(it.message ?: "获取成绩详情失败") }
        }
    }

    // ── 状态 / 工具 ───────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 转屏重建后不再重播列表入场
        outState.putBoolean(KEY_LIST_ENTRANCE_PLAYED, listEntrancePlayed)
    }

    private fun currentSemester(): SemesterEntity? =
        SemesterManager.getAll().firstOrNull { it.id == gradeDataManager.selectedSemesterId }

    private fun showStatus(
        text: String,
        loading: Boolean,
        @androidx.annotation.DrawableRes iconRes: Int? = null,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) = status.show(text, loading, iconRes, actionText, action)

    private fun hideStatus() = status.hide()

    /** 页面内轻提示；失败类文案请直接用 AppToast.error */
    private fun toast(text: String) =
        AppToast.info(this, text)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        /** 列表入场是否已播过的实例状态 key */
        private const val KEY_LIST_ENTRANCE_PLAYED = "grade_list_entrance_played"
    }
}
