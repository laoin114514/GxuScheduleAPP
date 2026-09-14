package com.cherry.wakeupschedule

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
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
import com.cherry.wakeupschedule.ui.screen.grade.GradeAdapter
import com.cherry.wakeupschedule.ui.screen.grade.GradeDetailDialog
import com.cherry.wakeupschedule.ui.screen.grade.GradeFilter
import com.cherry.wakeupschedule.ui.screen.grade.GradeStats
import com.cherry.wakeupschedule.ui.screen.grade.SemesterExpandPicker
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 成绩查询页。
 *
 * - 学期选择：页内下拉展开（[SemesterExpandPicker]），**只筛选本页**，不改课表的当前学期
 * - 查询：从教务拉取所选学期成绩，**整学期覆盖**写入本地（[GradeImportService]）
 * - 列表：筛选（全部/必修/选修/不及格）+ 按分数排序；点条目看平时/期末详情
 */
class GradeQueryActivity : BaseActivity() {

    private lateinit var gradeDataManager: GradeDataManager
    private lateinit var adapter: GradeAdapter
    private lateinit var picker: SemesterExpandPicker

    private lateinit var recycler: RecyclerView
    private lateinit var statusContainer: View
    private lateinit var statusText: TextView
    private lateinit var loadingIndicator: View
    private lateinit var gpaText: TextView
    private lateinit var creditsText: TextView
    private lateinit var filterRow: LinearLayout

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
        statusContainer = findViewById(R.id.ll_status)
        statusText = findViewById(R.id.tv_status)
        loadingIndicator = findViewById(R.id.pb_loading)
        gpaText = findViewById(R.id.tv_gpa)
        creditsText = findViewById(R.id.tv_credits)
        filterRow = findViewById(R.id.ll_filters)

        loadingIndicator.visibility = View.GONE
        (loadingIndicator as? com.google.android.material.progressindicator.CircularProgressIndicator)
            ?.apply {
                isIndeterminate = true
                setIndicatorColor(attr(com.google.android.material.R.attr.colorPrimary))
            }

        adapter = GradeAdapter { showDetail(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        picker = SemesterExpandPicker(
            context = this,
            field = findViewById(R.id.ll_semester_field),
            blockContainer = findViewById(R.id.fl_semester_block),
            labelView = findViewById(R.id.tv_semester_value),
            arrow = findViewById(R.id.iv_expand),
            optionsContainer = findViewById(R.id.ll_semester_options),
            onSelected = { semester -> onSemesterSelected(semester) }
        )

        findViewById<View>(R.id.btn_query).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(attr(com.google.android.material.R.attr.colorPrimary))
            }
            setOnClickListener { querySelectedSemester() }
        }

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
            toast("请先选择学期")
            return
        }
        if (!JwxtAuthManager.isBound()) {
            toast("请先绑定教务账号")
            return
        }
        picker.collapse()
        showStatus("正在从教务获取成绩…", loading = true)

        lifecycleScope.launch {
            val result = GradeImportService.fetchAndSaveGradesForSemester(this@GradeQueryActivity, semester)
            result.onSuccess { count ->
                toast(if (count == 0) "该学期暂无成绩" else "已更新 $count 条成绩")
                refreshSemesterOptions()
                renderGrades()
            }.onFailure { error ->
                // CaptchaRequiredException 等已带友好文案，直接展示
                showStatus(error.message ?: "查询失败", loading = false)
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
        gpaText.text = GradeStats.formatGpa(summary.averageGpa)
        creditsText.text = GradeStats.formatCredits(summary.totalCredits)

        val visible = GradeStats.sort(
            GradeStats.filter(semesterGrades, currentFilter),
            descending = sortDescending
        )
        adapter.submit(visible)

        when {
            semesterGrades.isEmpty() -> showStatus("该学期还没有成绩，点「查询」从教务获取", loading = false)
            visible.isEmpty() -> showStatus("没有符合「${currentFilter.label}」的成绩", loading = false)
            else -> hideStatus()
        }
    }

    // ── 筛选 chip ─────────────────────────────────────────

    private fun buildFilterChips() {
        filterRow.removeAllViews()
        GradeFilter.values().forEach { filter ->
            filterRow.addView(
                createChip(
                    label = filter.label,
                    selected = filter == currentFilter,
                    accent = if (filter == GradeFilter.FAILED) {
                        attr(com.google.android.material.R.attr.colorError)
                    } else null,
                    leadingIcon = null
                ) {
                    currentFilter = filter
                    buildFilterChips()
                    lifecycleScope.launch { renderGrades() }
                }
            )
        }

        filterRow.addView(createDivider())

        filterRow.addView(
            createChip(
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

    private fun createChip(
        label: String,
        selected: Boolean,
        accent: Int?,
        leadingIcon: Int?,
        onClick: () -> Unit
    ): View {
        val primary = attr(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = attr(com.google.android.material.R.attr.colorOnPrimary)
        val onError = attr(com.google.android.material.R.attr.colorOnError)
        val outline = attr(com.google.android.material.R.attr.colorOutlineVariant)
        val surface = attr(com.google.android.material.R.attr.colorSurfaceContainerLowest)
        val onSurfaceVariant = attr(com.google.android.material.R.attr.colorOnSurfaceVariant)

        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                when {
                    selected -> setColor(accent ?: primary)
                    accent != null -> {
                        setColor(surface)
                        setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x4D))
                    }
                    else -> {
                        setColor(surface)
                        setStroke(dp(1), outline)
                    }
                }
            }
            setOnClickListener { onClick() }
        }

        val textColor = when {
            selected -> if (accent != null) onError else onPrimary
            accent != null -> accent
            else -> onSurfaceVariant
        }

        if (leadingIcon != null) {
            chip.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
                setImageResource(leadingIcon)
                setColorFilter(textColor)
            })
        }

        chip.addView(TextView(this).apply {
            text = label
            setTextSizeRes(R.dimen.text_caption)
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        })

        return chip
    }

    private fun createDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(16)).apply {
            marginStart = dp(2)
            marginEnd = dp(10)
        }
        setBackgroundColor(ColorUtils.setAlphaComponent(
            attr(com.google.android.material.R.attr.colorOutlineVariant), 0x66
        ))
    }

    // ── 成绩详情 ──────────────────────────────────────────

    private fun showDetail(grade: GradeEntity) {
        if (!JwxtAuthManager.isBound()) {
            toast("请先绑定教务账号")
            return
        }
        if (grade.classId.isBlank() || grade.studentId.isBlank()) {
            toast("该成绩缺少详情参数")
            return
        }

        val dialog = GradeDetailDialog.show(this, grade.courseName)
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

    private fun showStatus(text: String, loading: Boolean) {
        statusContainer.visibility = View.VISIBLE
        loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        statusText.text = text
    }

    private fun hideStatus() {
        statusContainer.visibility = View.GONE
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /**
     * 解析当前主题色。
     *
     * 必须用 Activity 自己的 theme：调色板 overlay 是 [ThemeManager.applyToTheme] 在
     * setContentView 之前 applyStyle 到 activity.theme 上的，而
     * `window.decorView.context.theme` 是另一个没叠加 overlay 的 Theme 实例，
     * 从它解析会拿到 M3 基线色（紫 #6750A4）而不是当前调色板的主色。
     */
    private fun attr(@AttrRes attribute: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attribute, tv, true)) tv.data else Color.TRANSPARENT
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
