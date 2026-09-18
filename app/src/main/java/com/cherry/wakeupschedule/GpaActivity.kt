package com.cherry.wakeupschedule

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DimenRes
import androidx.core.view.WindowCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.model.GradeEntity
import com.cherry.wakeupschedule.model.SemesterEntity
import com.cherry.wakeupschedule.service.GpaResult
import com.cherry.wakeupschedule.service.GpaResultStore
import com.cherry.wakeupschedule.service.GradeDataManager
import com.cherry.wakeupschedule.service.GradeImportService
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.ui.component.createAppChip
import com.cherry.wakeupschedule.ui.component.themeColor
import com.cherry.wakeupschedule.ui.screen.grade.GradeStats
import com.cherry.wakeupschedule.ui.screen.grade.GradeSummary
import com.cherry.wakeupschedule.ui.screen.grade.SimulatedCourse
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 绩点计算页。
 *
 * - 计算：点「计算」**遍历全部学期**从教务拉取成绩并整学期覆盖入库（[GradeImportService]），
 *   期间用进度条 + 文案展示「正在获取哪个学期（第几个/共几个）」，让用户有感知
 * - 汇总：入库后按**所有**已有成绩计算平均学分绩点等指标，并把结果持久化（[GpaResultStore]），
 *   下次打开直接展示并显示「上次计算时间」
 * - 明细：按学期列出各学期绩点与学分
 * - 模拟试算：填入学分 + 预期成绩（或绩点），实时推演平均学分绩点变化，不写库
 *
 * 页面只在点「计算」时联网。
 */
class GpaActivity : BaseActivity() {

    /** 模拟试算的输入口径 */
    private enum class SimMode { SCORE, GRADE_POINT }

    private lateinit var gradeDataManager: GradeDataManager
    private lateinit var resultStore: GpaResultStore

    private lateinit var btnCalc: View
    private lateinit var btnSpinner: CircularProgressIndicator
    private lateinit var calcLabel: TextView
    private lateinit var calcStatus: TextView
    private lateinit var calcProgress: LinearProgressIndicator
    private lateinit var lastCalcText: TextView
    private lateinit var gpaText: TextView
    private lateinit var weightedText: TextView
    private lateinit var creditsText: TextView
    private lateinit var coursesText: TextView
    private lateinit var failedText: TextView
    private lateinit var emptyCard: View
    private lateinit var emptyText: TextView
    private lateinit var semesterRows: LinearLayout
    private lateinit var modeChips: LinearLayout
    private lateinit var simRows: LinearLayout
    private lateinit var simHint: TextView
    private lateinit var simGpaText: TextView
    private lateinit var simDeltaText: TextView
    private lateinit var simCreditsText: TextView

    private var simMode = SimMode.SCORE
    private var allGrades: List<GradeEntity> = emptyList()
    private var semesters: List<SemesterEntity> = emptyList()

    /** 最近一次「计算」的持久化结果；null 表示还没计算过 */
    private var lastResult: GpaResult? = null

    /** onCreate 已加载过：避免 onResume 立刻重复查库 */
    private var loadedOnce = false

    /** 计算进行中：防止重复触发 */
    private var calculating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_gpa)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupPageHeader(findViewById(R.id.toolbar), "绩点计算")

        gradeDataManager = GradeDataManager.getInstance(this)
        resultStore = GpaResultStore(this)
        lastResult = resultStore.load()

        btnCalc = findViewById(R.id.btn_calc)
        btnSpinner = findViewById(R.id.pb_calc_button)
        calcLabel = findViewById(R.id.tv_calc_label)
        calcStatus = findViewById(R.id.tv_calc_status)
        calcProgress = findViewById(R.id.pb_calc)
        lastCalcText = findViewById(R.id.tv_last_calc)
        gpaText = findViewById(R.id.tv_gpa)
        weightedText = findViewById(R.id.tv_metric_weighted)
        creditsText = findViewById(R.id.tv_metric_credits)
        coursesText = findViewById(R.id.tv_metric_courses)
        failedText = findViewById(R.id.tv_metric_failed)
        emptyCard = findViewById(R.id.card_empty)
        emptyText = findViewById(R.id.tv_empty)
        semesterRows = findViewById(R.id.ll_semester_rows)
        modeChips = findViewById(R.id.ll_mode_chips)
        simRows = findViewById(R.id.ll_sim_rows)
        simHint = findViewById(R.id.tv_sim_hint)
        simGpaText = findViewById(R.id.tv_sim_gpa)
        simDeltaText = findViewById(R.id.tv_sim_delta)
        simCreditsText = findViewById(R.id.tv_sim_credits)

        btnCalc.background = primaryButtonBackground()
        btnCalc.setOnClickListener { calculate() }
        // 按压缩放反馈（与工具页卡片同一手感），让按钮不至于“点下去没反应”
        btnCalc.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
        findViewById<View>(R.id.btn_add_course).setOnClickListener { addSimRow(focus = true) }
        findViewById<View>(R.id.btn_clear_sim).setOnClickListener { resetSimRows() }

        calcProgress.max = 1
        calcProgress.setProgressCompat(0, false)
        btnSpinner.isIndeterminate = true
        btnSpinner.setIndicatorColor(themeColor(com.google.android.material.R.attr.colorOnPrimary))

        buildModeChips()
        addSimRow(focus = false)
        renderSummary()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从「成绩查询」等页面返回时刷新明细；汇总卡仍展示上次计算保存的结果
        if (loadedOnce) refresh()
    }

    // ── 数据 ──────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch {
            val grades = gradeDataManager.loadAllGrades()
            if (isFinishing || isDestroyed) return@launch
            allGrades = grades
            semesters = SemesterManager.getAll()
            loadedOnce = true
            if (lastResult == null) renderSummary()
            render()
        }
    }

    // ── 计算（遍历全部学期） ──────────────────────────────

    private fun calculate() {
        if (calculating) return
        if (!JwxtAuthManager.isBound()) {
            toast("请先绑定教务账号")
            return
        }
        val targets = SemesterManager.getAll()
        if (targets.isEmpty()) {
            toast("没有可用学期，请先绑定教务账号")
            return
        }

        setCalculating(true)
        showProgress(0, targets.size, "正在连接教务…")

        lifecycleScope.launch {
            var totalCount = 0
            for ((index, semester) in targets.withIndex()) {
                showProgress(index, targets.size, "正在获取 ${semester.label}（${index + 1}/${targets.size}）…")
                val result = GradeImportService.fetchAndSaveGradesForSemester(this@GpaActivity, semester)
                if (isFinishing || isDestroyed) return@launch
                val error = result.exceptionOrNull()
                if (error != null) {
                    setCalculating(false)
                    showCalcError(error.message ?: "获取成绩失败")
                    return@launch
                }
                totalCount += result.getOrDefault(0)
                // 该学期已完成，进度推进一格
                showProgress(index + 1, targets.size, "已完成 ${semester.label}（${index + 1}/${targets.size}）")
            }

            showProgress(targets.size, targets.size, "正在汇总计算绩点…")
            val grades = gradeDataManager.loadAllGrades()
            if (isFinishing || isDestroyed) return@launch
            allGrades = grades
            semesters = SemesterManager.getAll()

            val saved = GradeStats.summarize(grades).toResult(System.currentTimeMillis())
            resultStore.save(saved)
            lastResult = saved

            setCalculating(false)
            renderSummary()
            render()
            toast("已更新 $totalCount 条成绩")
        }
    }

    /**
     * 计算态开关：同时负责 [calculating] 标志与按钮/进度的显隐。
     *
     * 标志必须在这里复位——之前只切换了按钮样式、没复位标志，导致算过一次后
     * 再点「计算」会被 [calculate] 开头的防重入判断直接拦掉（点了没反应）。
     */
    private fun setCalculating(loading: Boolean) {
        calculating = loading
        btnCalc.isEnabled = !loading
        btnCalc.alpha = if (loading) 0.7f else 1f
        calcLabel.text = if (loading) "计算中…" else "计算"
        // 按钮内转圈：只在计算时出现并在原地旋转
        btnSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        if (!loading) {
            calcProgress.visibility = View.GONE
            calcStatus.visibility = View.GONE
        }
    }

    /** 进度反馈：进度条 +「正在获取 xx（3/8）」文案 */
    private fun showProgress(done: Int, total: Int, text: String) {
        calcProgress.visibility = View.VISIBLE
        calcProgress.max = total.coerceAtLeast(1)
        calcProgress.setProgressCompat(done, true)
        calcStatus.visibility = View.VISIBLE
        calcStatus.setTextColor(primaryColor)
        calcStatus.text = text
    }

    private fun showCalcError(message: String) {
        calcProgress.visibility = View.GONE
        calcStatus.visibility = View.VISIBLE
        calcStatus.setTextColor(errorColor)
        calcStatus.text = message
        renderLastCalc()
    }

    private fun renderLastCalc() {
        val result = lastResult
        lastCalcText.setTextColor(onSurfaceVariantColor)
        lastCalcText.text = if (result == null) {
            "还没有计算过，点「计算」从教务获取全部学期成绩"
        } else {
            "上次计算：${formatTime(result.calculatedAt)}"
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    // ── 渲染 ──────────────────────────────────────────────

    private fun renderSummary() {
        // 有保存的结果就展示保存值（配「上次计算时间」）；从未计算过则展示本地已缓存成绩的即时汇总
        val summary = lastResult?.toSummary() ?: GradeStats.summarize(allGrades)
        gpaText.text = GradeStats.formatGpa(summary.averageGpa)
        weightedText.text = GradeStats.formatGpa(summary.weightedAverageScore)
        creditsText.text = GradeStats.formatCredits(summary.totalCredits)
        coursesText.text = summary.courseCount.toString()
        failedText.text = summary.failedCount.toString()
        renderLastCalc()
    }

    private fun render() {
        emptyCard.visibility = if (allGrades.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (semesters.isEmpty()) {
            "还没有学期数据，先绑定教务账号，再点上面的「计算」"
        } else {
            "本地还没有成绩数据，点上面的「计算」从教务获取全部学期成绩"
        }

        renderSemesterRows()
        renderSimulation()
    }

    private fun renderSemesterRows() {
        semesterRows.removeAllViews()
        val rows = semesters
            .map { semester -> semester to allGrades.filter { it.semesterId == semester.id } }
            .filter { it.second.isNotEmpty() }
            .reversed()

        if (rows.isEmpty()) {
            semesterRows.addView(
                labelView("还没有学期成绩，先点「计算」", R.dimen.text_body_small, onSurfaceVariantColor).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(16))
                }
            )
            return
        }

        rows.forEach { (semester, grades) ->
            semesterRows.addView(buildSemesterRow(semester, GradeStats.summarize(grades)))
        }
    }

    private fun buildSemesterRow(semester: SemesterEntity, summary: GradeSummary): View {
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelView(GradeStats.semesterTitle(semester), R.dimen.text_body, onSurfaceColor, bold = true))
            addView(
                labelView(
                    "${summary.courseCount} 门 · 已获 ${GradeStats.formatCredits(summary.totalCredits)} 学分",
                    R.dimen.text_caption,
                    onSurfaceVariantColor
                ).apply { setPadding(0, dp(2), 0, 0) }
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(labelView(GradeStats.formatGpa(summary.averageGpa), R.dimen.text_title, primaryColor, bold = true))
        }
    }

    // ── 模拟试算 ──────────────────────────────────────────

    private fun buildModeChips() {
        modeChips.removeAllViews()
        modeChips.addView(createAppChip("按成绩", simMode == SimMode.SCORE) {
            setSimMode(SimMode.SCORE)
        })
        modeChips.addView(createAppChip("按绩点", simMode == SimMode.GRADE_POINT) {
            setSimMode(SimMode.GRADE_POINT)
        })
        updateSimHint()
    }

    private fun setSimMode(mode: SimMode) {
        if (simMode == mode) return
        simMode = mode
        buildModeChips()
        // 口径变了，已有行的输入提示也要跟着变
        for (i in 0 until simRows.childCount) {
            simRows.getChildAt(i).findViewById<EditText>(R.id.et_value).hint = valueHint()
        }
        renderSimulation()
    }

    private fun updateSimHint() {
        simHint.text = when (simMode) {
            SimMode.SCORE ->
                "填入学分与预期成绩，绩点按 (成绩−50)/10 估算（60 分以下计 0），仅供参考"
            SimMode.GRADE_POINT ->
                "填入学分与预期绩点（0–5，绩点 ≥ 1 记为通过）"
        }
    }

    private fun valueHint(): String = if (simMode == SimMode.SCORE) "成绩" else "绩点"

    /** 新增一行假设课程；[focus] 为真时把光标放进学分输入框 */
    private fun addSimRow(focus: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_gpa_sim_row, simRows, false)
        row.findViewById<EditText>(R.id.et_value).hint = valueHint()
        listOf(R.id.et_credits, R.id.et_value).forEach { id ->
            row.findViewById<EditText>(id).addTextChangedListener { renderSimulation() }
        }
        row.findViewById<View>(R.id.btn_remove).setOnClickListener {
            if (simRows.childCount <= 1) {
                // 只剩一行时「删除」= 清空输入，避免出现没有输入行的空卡
                row.findViewById<EditText>(R.id.et_credits).text?.clear()
                row.findViewById<EditText>(R.id.et_value).text?.clear()
                renderSimulation()
            } else {
                simRows.removeView(row)
                renumberSimRows()
                renderSimulation()
            }
        }
        simRows.addView(row)
        renumberSimRows()
        if (focus) row.findViewById<EditText>(R.id.et_credits).requestFocus()
        renderSimulation()
    }

    private fun resetSimRows() {
        simRows.removeAllViews()
        addSimRow(focus = false)
    }

    private fun renumberSimRows() {
        for (i in 0 until simRows.childCount) {
            simRows.getChildAt(i).findViewById<TextView>(R.id.tv_index).text = (i + 1).toString()
        }
    }

    /** 读取输入行，忽略未填写/非法的行 */
    private fun readSimulatedCourses(): List<SimulatedCourse> {
        val courses = mutableListOf<SimulatedCourse>()
        for (i in 0 until simRows.childCount) {
            val row = simRows.getChildAt(i)
            val credits = GradeStats.parseNumber(
                row.findViewById<EditText>(R.id.et_credits).text?.toString()
            ) ?: continue
            if (credits <= 0) continue
            val raw = GradeStats.parseNumber(
                row.findViewById<EditText>(R.id.et_value).text?.toString()
            ) ?: continue

            courses += when (simMode) {
                SimMode.SCORE -> {
                    val score = raw.coerceIn(0.0, 100.0)
                    SimulatedCourse(
                        credits = credits,
                        gradePoint = GradeStats.estimateGradePoint(score),
                        passed = score >= GradeStats.PASS_SCORE
                    )
                }
                SimMode.GRADE_POINT -> {
                    val point = raw.coerceIn(0.0, 5.0)
                    SimulatedCourse(credits = credits, gradePoint = point, passed = point >= 1.0)
                }
            }
        }
        return courses
    }

    private fun renderSimulation() {
        val simulated = readSimulatedCourses()
        val summary = GradeStats.summarize(allGrades, simulated)
        simGpaText.text = GradeStats.formatGpa(summary.averageGpa)

        val baseGpa = GradeStats.summarize(allGrades).averageGpa
        val delta = if (summary.averageGpa != null && baseGpa != null) {
            summary.averageGpa - baseGpa
        } else {
            null
        }

        when {
            simulated.isEmpty() -> {
                simDeltaText.text = "填入假设课程后显示变化"
                simDeltaText.setTextColor(onSurfaceVariantColor)
            }
            delta == null -> {
                // 没有真实成绩可对比：只展示模拟值，说明清楚为什么没有差值
                simDeltaText.text = if (baseGpa == null) "（当前没有成绩）" else ""
                simDeltaText.setTextColor(onSurfaceVariantColor)
            }
            else -> {
                val arrow = if (delta >= 0) "▲" else "▼"
                simDeltaText.text = String.format(Locale.ROOT, "%s %+.2f", arrow, delta)
                simDeltaText.setTextColor(if (delta >= 0) primaryColor else errorColor)
            }
        }

        simCreditsText.text = "模拟已获学分 ${GradeStats.formatCredits(summary.totalCredits)}"
    }

    // ── 视图工具 ──────────────────────────────────────────

    private fun labelView(
        text: String,
        @DimenRes sizeRes: Int,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        setTextSizeRes(sizeRes)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun primaryButtonBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(primaryColor)
    }

    private fun GradeSummary.toResult(now: Long): GpaResult = GpaResult(
        averageGpa = averageGpa,
        weightedAverageScore = weightedAverageScore,
        totalCredits = totalCredits,
        courseCount = courseCount,
        failedCount = failedCount,
        calculatedAt = now
    )

    private fun GpaResult.toSummary(): GradeSummary = GradeSummary(
        averageGpa = averageGpa,
        totalCredits = totalCredits,
        weightedAverageScore = weightedAverageScore,
        courseCount = courseCount,
        failedCount = failedCount
    )

    private val primaryColor: Int
        get() = themeColor(com.google.android.material.R.attr.colorPrimary)

    private val errorColor: Int
        get() = themeColor(com.google.android.material.R.attr.colorError)

    private val onSurfaceColor: Int
        get() = themeColor(com.google.android.material.R.attr.colorOnSurface)

    private val onSurfaceVariantColor: Int
        get() = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
