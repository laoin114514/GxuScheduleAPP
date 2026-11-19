package com.cherry.wakeupschedule

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.ui.component.PageStatusController
import com.cherry.wakeupschedule.ui.feedback.AppToast
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.model.ExamScheduleEntity
import com.cherry.wakeupschedule.model.SemesterEntity
import com.cherry.wakeupschedule.service.ExamScheduleDataManager
import com.cherry.wakeupschedule.service.ExamScheduleImportService
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.ui.component.SemesterExpandPicker
import com.cherry.wakeupschedule.ui.component.createAppChip
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.component.themeColor
import com.cherry.wakeupschedule.ui.screen.exam.ExamFilter
import com.cherry.wakeupschedule.ui.screen.exam.ExamScheduleAdapter
import com.cherry.wakeupschedule.ui.screen.exam.ExamScheduleStats
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 考试安排页。
 *
 * - 学期选择：浮层下拉（[SemesterExpandPicker]），**只筛选本页**，不改课表的当前学期
 * - 查询：从教务拉取所选学期的考试（[ExamScheduleImportService]），**整学期覆盖**写入本地；
 *   查询期间「查询」按钮置灰禁用，loading 覆盖层盖住整个考试列表
 * - 列表：筛选（全部/未开始/进行中/已结束）；排序固定为「未开始优先，再按距现在最近」
 * - 场地只展示教务的 cdmc（为空显示「地点待定」）
 */
class ExamScheduleActivity : BaseActivity() {

    private lateinit var examDataManager: ExamScheduleDataManager
    private lateinit var adapter: ExamScheduleAdapter
    private lateinit var picker: SemesterExpandPicker

    private lateinit var recycler: RecyclerView
    private lateinit var statusContainer: View
    private lateinit var status: PageStatusController
    private lateinit var loadingIndicator: View
    private lateinit var filterRow: LinearLayout
    private lateinit var btnQuery: View

    private var currentFilter = ExamFilter.ALL

    /** 当前学期全量考试（不筛选），用于判断该学期是否已有缓存 */
    private var semesterExams: List<ExamScheduleEntity> = emptyList()

    /** 列表入场是否已播过：转屏/返回不重播（随实例状态保存） */
    private var listEntrancePlayed = false

    /** 列表数据替换的世代号：延迟入场只在世代未变时生效，避免旧结果盖掉新结果 */
    private var listRevealGeneration = 0

    /** 首帧渲染完成前 onResume 不重复渲染（loadSemesters 自己会渲染一次） */
    private var renderedOnce = false

    /** 查询进行中：期间 onResume 的重渲染会盖掉 loading 遮罩，必须跳过 */
    private var querying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listEntrancePlayed = savedInstanceState?.getBoolean(KEY_LIST_ENTRANCE_PLAYED) ?: false
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_exam_schedule)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupPageHeader(findViewById(R.id.toolbar), "考试安排")

        examDataManager = ExamScheduleDataManager.getInstance(this)

        recycler = findViewById(R.id.rv_exams)
        // 先拿到按钮：控制器的 onLoadingChanged 回调会在状态切换时用到它
        btnQuery = findViewById(R.id.btn_query)
        val loadingIndicator =
            findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(
                R.id.pb_loading
            )
        loadingIndicator.isIndeterminate = true
        loadingIndicator.setIndicatorColor(
            themeColor(com.google.android.material.R.attr.colorPrimary)
        )
        // 与成绩查询页共用同一份状态层实现
        status = PageStatusController(
            container = findViewById(R.id.ll_status),
            textView = findViewById(R.id.tv_status),
            loadingView = loadingIndicator,
            iconView = findViewById(R.id.iv_status_icon),
            actionView = findViewById(R.id.tv_status_action),
            listView = recycler,
            onLoadingChanged = { loading ->
                querying = loading
                btnQuery.isEnabled = !loading
            },
            loadingDimView = btnQuery
        )
        filterRow = findViewById(R.id.ll_filters)

        loadingIndicator.visibility = View.GONE
        (loadingIndicator as? com.google.android.material.progressindicator.CircularProgressIndicator)
            ?.apply {
                isIndeterminate = true
                setIndicatorColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            }

        adapter = ExamScheduleAdapter()
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

        (btnQuery as View).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            }
            setOnClickListener { querySelectedSemester() }
        }

        buildFilterChips()
        loadSemesters()
    }

    override fun onResume() {
        super.onResume()
        // 状态取决于「现在」：跨过考试开始/结束时刻后重渲染一次，徽标不滞后
        if (renderedOnce && !querying) {
            lifecycleScope.launch { renderExams() }
        }
    }

    // ── 学期 ──────────────────────────────────────────────

    private fun loadSemesters() {
        val semesters = SemesterManager.getAll()
        if (semesters.isEmpty()) {
            picker.submit(emptyList(), emptyMap(), 0L)
            showStatus(
                "请先绑定教务账号，绑定后会自动生成学期",
                loading = false,
                iconRes = R.drawable.ic_mtrl_info,
                actionText = "去绑定教务",
                action = {
                    startActivity(android.content.Intent(this, BindJwxtActivity::class.java))
                }
            )
            adapter.submit(emptyList(), System.currentTimeMillis())
            return
        }

        lifecycleScope.launch {
            val counts = examDataManager.getSemesterExamCounts()
            // 初始选中：上次选的 → 全局当前学期 → 首个已有缓存的学期 → 第一个
            val selected = examDataManager.selectedSemesterId
                .takeIf { id -> semesters.any { it.id == id } }
                ?: SemesterManager.getCurrent()?.id
                ?: counts.keys.firstOrNull()
                ?: semesters.first().id
            examDataManager.selectSemester(selected)
            picker.submit(semesters, counts, selected)
            renderExams()
        }
    }

    private fun onSemesterSelected(semester: SemesterEntity) {
        examDataManager.selectSemester(semester.id)
        lifecycleScope.launch { renderExams() }
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
        showStatus("正在从教务获取考试安排…", loading = true)

        lifecycleScope.launch {
            val result = ExamScheduleImportService
                .fetchAndSaveExamsForSemester(this@ExamScheduleActivity, semester)
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { count ->
                if (count == 0) {
                    toast("该学期暂无考试安排")
                } else {
                    AppToast.success(this@ExamScheduleActivity, "已更新 $count 条考试安排")
                }
                refreshSemesterOptions()
                renderExams()
            }.onFailure { error ->
                // 失败原因走浮层提示（CaptchaRequiredException 等已带友好文案）；
                // 同时把页面还原成「已有数据 / 空态」，不再把错误嵌在列表区域里
                AppToast.error(this@ExamScheduleActivity, error.message ?: "查询失败")
                renderExams()
            }
        }
    }

    private suspend fun refreshSemesterOptions() {
        picker.submit(
            SemesterManager.getAll(),
            examDataManager.getSemesterExamCounts(),
            examDataManager.selectedSemesterId
        )
    }

    // ── 渲染 ──────────────────────────────────────────────

    private suspend fun renderExams(revealAfterFade: Boolean = false) {
        val filterAtStart = currentFilter
        // 状态与排序共用同一个「现在」，避免列表顺序和徽标互相矛盾
        val now = System.currentTimeMillis()
        semesterExams = examDataManager.loadExams()

        // 加载期间用户又切了筛选：本次结果作废，等新的渲染（避免旧结果盖掉新结果）
        if (filterAtStart != currentFilter) return

        val visible = ExamScheduleStats.sort(
            ExamScheduleStats.filter(semesterExams, filterAtStart, now),
            now
        )
        // 状态层（loading/空态）正显示时：结果落位后等它淡出再播列表入场，顺序更自然；
        // 这种"数据落位"每次查询完成都值得播一次，不属于返回/转屏时的重复入场
        val statusWasShowing = status.isShowing
        submitExams(
            visible = visible,
            now = now,
            animateIn = revealAfterFade || !listEntrancePlayed || statusWasShowing,
            startDelay = if (!revealAfterFade && statusWasShowing) Motion.SHORT else 0L
        )
        renderedOnce = true

        when {
            semesterExams.isEmpty() ->
                showStatus(
                    "该学期还没有考试安排，点「查询」从教务获取",
                    loading = false,
                    iconRes = R.drawable.ic_mtrl_event_note,
                    actionText = if (JwxtAuthManager.isBound()) "立即查询" else "去绑定教务",
                    action = {
                        if (JwxtAuthManager.isBound()) querySelectedSemester()
                        else startActivity(android.content.Intent(this, BindJwxtActivity::class.java))
                    }
                )
            visible.isEmpty() ->
                showStatus(
                    "没有「${currentFilter.label}」的考试安排",
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
    private fun submitExams(
        visible: List<ExamScheduleEntity>,
        now: Long,
        animateIn: Boolean,
        startDelay: Long
    ) {
        val generation = ++listRevealGeneration
        // 先停掉可能还在跑的筛选淡出，避免它的 alpha 动画覆盖下面设置的目标透明度
        recycler.animate().cancel()
        adapter.submit(visible, now)
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

    /** 筛选切换：先淡出旧列表再换数据淡入，避免"啪"地替换（不阻塞 chip 状态切换） */
    private fun reloadWithFade() {
        if (!Motion.enabled()) {
            lifecycleScope.launch { renderExams() }
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
                lifecycleScope.launch { renderExams(revealAfterFade = true) }
            }
            .start()
    }

    // ── 筛选 chip ─────────────────────────────────────────

    private fun buildFilterChips() {
        filterRow.removeAllViews()
        // 排序规则固定（未开始优先 + 距现在最近），因此这里不提供排序切换
        ExamFilter.values().forEach { filter ->
            filterRow.addView(
                createAppChip(
                    label = filter.label,
                    selected = filter == currentFilter,
                    accent = null,
                    leadingIcon = null
                ) {
                    currentFilter = filter
                    buildFilterChips()
                    reloadWithFade()
                }
            )
        }
    }

    // ── 状态 / 工具 ───────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 转屏重建后不再重播列表入场
        outState.putBoolean(KEY_LIST_ENTRANCE_PLAYED, listEntrancePlayed)
    }

    private fun currentSemester(): SemesterEntity? =
        SemesterManager.getAll().firstOrNull { it.id == examDataManager.selectedSemesterId }

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
        private const val KEY_LIST_ENTRANCE_PLAYED = "exam_list_entrance_played"
    }
}
