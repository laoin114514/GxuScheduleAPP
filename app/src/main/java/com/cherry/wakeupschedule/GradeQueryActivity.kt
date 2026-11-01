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
import com.cherry.wakeupschedule.ui.screen.grade.GradeFilter
import com.cherry.wakeupschedule.ui.screen.grade.GradeStats
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                btnQuery.alpha = if (loading) 0.5f else 1f
            }
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

    private suspend fun renderGrades() {
        semesterGrades = gradeDataManager.loadGrades()

        val summary = GradeStats.summarize(semesterGrades)
        // 等宽数字：两个指标并排时数字宽度一致，切换学期时不会左右抖
        gpaText.fontFeatureSettings = "'tnum'"
        creditsText.fontFeatureSettings = "'tnum'"
        gpaText.text = GradeStats.formatGpa(summary.averageGpa)
        creditsText.text = GradeStats.formatCredits(summary.totalCredits)

        val visible = GradeStats.sort(
            GradeStats.filter(semesterGrades, currentFilter),
            descending = sortDescending
        )
        adapter.submit(visible)

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
                    lifecycleScope.launch { renderGrades() }
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
                lifecycleScope.launch { renderGrades() }
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
}
