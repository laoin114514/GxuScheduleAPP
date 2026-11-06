package com.cherry.wakeupschedule.ui.screen.schedule

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.ui.feedback.AppToast
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.CircularProgressIndicator
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtImportService
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.adapter.WeekPagerAdapter
import com.cherry.wakeupschedule.ui.component.createAppChip
import com.cherry.wakeupschedule.ui.theme.PageBackground
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.widget.CircleIconButton
import com.cherry.wakeupschedule.ui.widget.SemesterSceneryView
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes

class ScheduleFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tvDate: TextView
    private lateinit var tvWeekInfo: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var btnRefresh: View
    private lateinit var progressRefresh: CircularProgressIndicator
    private lateinit var overlayLoading: View
    private lateinit var groupLoading: View
    private lateinit var groupSuccess: View
    private lateinit var tvLoadResult: TextView
    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: WeekPagerAdapter
    private lateinit var courseViewModel: CourseViewModel
    private lateinit var btnBackToWeek: CircleIconButton
    private lateinit var layoutOverview: View
    private lateinit var rvCourseOverview: RecyclerView
    private lateinit var llOverviewFilters: LinearLayout
    private lateinit var llOverviewEmpty: View
    private lateinit var tvOverviewEmpty: TextView
    private val overviewAdapter = CourseOverviewAdapter()

    private var allCourses: List<Course> = emptyList()

    /** 当前已应用到课表的格子高度（dp），onResume 对比设置变化后刷新 */
    private var appliedCellHeightDp = 0

    /** 当前已应用到课表的课程内容开关（教室 / 教师），onResume 对比设置变化后刷新 */
    private var appliedShowClassroom = true
    private var appliedShowTeacher = true

    /** 当前已应用到课表的边框颜色 / 跟随课程色开关，onResume 对比设置变化后刷新 */
    private var appliedBorderColor = 0
    private var appliedBorderFollowCourse = false

    /** 当前是否处于总课表视图（周课表 ⇄ 总课表，由底部导航角标切换） */
    private var isOverview = false

    /** 总课表全部条目（按课程名归并后，未筛选）；筛选只作用于渲染 */
    private var overviewGroups: List<CourseOverviewGroup> = emptyList()

    /** 生成 [overviewGroups] 所用的课程列表快照；课程数据没变（引用相同）就不重复归并 */
    private var overviewGroupsSource: List<Course>? = null

    /** 总课表条目总数，用于顶栏「本学期共 N 门课程」 */
    private var overviewGroupCount = 0

    /** 当前筛选下实际展示的条目数 */
    private var overviewVisibleCount = 0

    /** 总课表顶部筛选：全部 / 理论课 / 实践课 */
    private var overviewFilter = CourseTypeFilter.ALL

    /** 当前显示的课表菜单底部弹窗，防止连点叠加 */
    private var activeMenuDialog: Dialog? = null

    /** 是否正在从教务加载课表（刷新按钮 + 菜单切学期共用，防止并发） */
    private var isLoadingSchedule = false

    /** 菜单中正在加载的学期索引（null = 无），色块显示转圈 */
    private var loadingSemesterIndex: Int? = null

    /** 菜单打开时注册的学期色块刷新回调（菜单关闭后置空） */
    private var menuSemesterRefresher: (() -> Unit)? = null

    private val dateFormat = SimpleDateFormat("yyyy/M/d", Locale.getDefault())
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    /** 倒计时复用的 Calendar：旧实现每秒 new 一个（低端机上每秒一次的固定垃圾） */
    private val countdownCalendar = Calendar.getInstance()

    /** 倒计时文本/可见性缓存：值没变就不重复 setText/setVisibility，避免每秒白触发一次重绘 */
    private var countdownShownVisible: Boolean? = null
    private var countdownShownText: String? = null

    /**
     * 倒计时「今日课程」缓存：课程列表 / 当前周 / 自然日 / 时间表 任一变化时才重建。
     * 旧实现每秒都做一次全量 filter + 按时间槽解析分钟排序（比较器里还要反复读时间表），
     * 今日课程与起止分钟其实一天内不变，缓存后每秒只剩一次固定大小的遍历。
     */
    private var countdownCacheCourses: List<Course>? = null
    private var countdownCacheWeek = Int.MIN_VALUE
    private var countdownCacheDay = Int.MIN_VALUE
    private var countdownCacheSlots: List<TimeTableManager.TimeSlot>? = null
    private var countdownTodayCourses: List<Course> = emptyList()
    private var countdownStartMinutes = IntArray(0)
    /** 结束分钟：按需解析（[END_MINUTES_UNSET] = 还没算过），保持旧实现的解析时机 */
    private var countdownEndMinutes = IntArray(0)

    companion object {
        private const val TAG = "ScheduleFragment"

        /** 成功卡片展示时长 */
        private const val SUCCESS_DISPLAY_MS = 1400L

        /** 倒计时缓存里「这门课的结束分钟还没解析」的标记（合法分钟数不会取到负值） */
        private const val END_MINUTES_UNSET = -1

        /**
         * 课表菜单最多占屏高的比例。
         *
         * 外观控件内联进去以后菜单内容接近一屏，若按内容撑满就整张课表都被盖住，
         * 拖外观滑块时看不到后面的实时效果；封顶后「内容多则滚动、内容少则贴内容」，
         * 课表始终露在上方。
         */
        private const val MENU_SHEET_MAX_HEIGHT_RATIO = 0.85f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        PageBackground.apply(view)
        // 加载遮罩背景必须由代码设置：布局里写 @color/状态色列表 在 API<=29 深色模式下
        // 会因 res/color-night-*/ 路径被当普通 Drawable 解析而 inflate 崩溃（见
        // ColorStateListAsDrawableTest）。颜色取页面渐变底端色，换配色/深浅色都跟着变。
        view.findViewById<View>(R.id.overlay_loading)?.background =
            android.graphics.drawable.ColorDrawable(PageBackground.bottomColor(requireContext()))
        settingsManager = SettingsManager(requireContext())
        courseViewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]

        initViews(view)
        setupViewPager()
        setupObservers()
        updateDateTimeHeader()
        // 视图重建后 TextView 是新的：清掉「已展示文本」的缓存，首次 onStart 会立刻重刷
        countdownShownVisible = null
        countdownShownText = null
        // 倒计时在 onStart 启动、onStop 停止（见下方），页面不可见时不再空转
    }

    override fun onStart() {
        super.onStart()
        // 只有页面可见时才每秒刷新倒计时；App 退后台 / 切到其他 tab 后停掉，低端机不再白跑
        startCountdown()
    }

    override fun onStop() {
        super.onStop()
        stopCountdown()
    }

    override fun onResume() {
        super.onResume()
        // 不在 onResume 触发教务刷新，避免每次切tab都重建课表
        // 手动刷新按钮 + 首次启动已覆盖数据更新场景

        // 但教务会话可以做"前台预热"：只探测/必要时静默重登录，不刷新课表数据。
        // 距上次确认会话有效超过阈值才会真正联网（阈值见 JwxtAuthPolicy.PREWARM_AGE_MS），
        // 命中时用户点刷新不用现场等一次完整重登录。
        JwxtAuthManager.prewarmInBackground("schedule-resume")

        // 但「课表」外观页改过的格子高度要在这里同步（脏检查，没变就不动）
        syncCellHeightIfChanged()
        // 同上，课程格子里的教室/教师开关也在外观页
        syncContentVisibilityIfChanged()
        // 同上，格子边框颜色/跟随开关也在外观页
        syncCellBorderIfChanged()
        // 「我的 → 课表外观」入口会跳到本 tab 并要求弹出外观面板（见 ProfileFragment）
        if (ScheduleAppearanceSheet.consumePendingAutoOpen()) {
            view?.post { ScheduleAppearanceSheet.show(this) }
        }
        // 从设置页回来时学期开始日期/当前周可能变了，日期栏也要跟上
        syncWeekContextToAdapter()
        updateWeekJumpButton()
    }

    /**
     * 课表外观面板（ScheduleAppearanceSheet）的实时生效入口：
     * 面板已把设置写入 SettingsManager，这里把缓存值刷进适配器
     * （两个 sync 内部自带脏检查，值没变就不动）。
     */
    fun applyAppearanceChanges() {
        syncCellHeightIfChanged()
        syncContentVisibilityIfChanged()
        syncCellBorderIfChanged()
    }

    /**
     * 同步「我的 → 外观 → 课表」里的格子高度设置。
     * 行高缓存在 ViewHolder 的时间轴里，必须让 bind 重跑才生效（见 WeekPagerAdapter）。
     */
    private fun syncCellHeightIfChanged() {
        if (!::adapter.isInitialized) return
        val heightDp = settingsManager.getCourseCellHeight()
        if (heightDp == appliedCellHeightDp) return
        appliedCellHeightDp = heightDp
        adapter.setCellHeightDp(heightDp)
    }

    /**
     * 同步「课表外观 → 课程内容」里的教室/教师开关。
     * 课程格子的文字在 bind 时拼好，所以同样要重绑才生效（见 WeekPagerAdapter）。
     */
    private fun syncContentVisibilityIfChanged() {
        if (!::adapter.isInitialized) return
        val showClassroom = settingsManager.isShowClassroom()
        val showTeacher = settingsManager.isShowTeacher()
        if (showClassroom == appliedShowClassroom && showTeacher == appliedShowTeacher) return
        appliedShowClassroom = showClassroom
        appliedShowTeacher = showTeacher
        adapter.setContentVisibility(showClassroom, showTeacher)
    }

    /**
     * 同步「课表外观 → 边框颜色」的自定义色与跟随课程色开关。
     * 描边在 bind 时画进卡片背景，同样要重绑才生效（见 WeekPagerAdapter）。
     */
    private fun syncCellBorderIfChanged() {
        if (!::adapter.isInitialized) return
        val color = settingsManager.getCellBorderColor()
        val follow = settingsManager.isBorderFollowCourse()
        if (color == appliedBorderColor && follow == appliedBorderFollowCourse) return
        appliedBorderColor = color
        appliedBorderFollowCourse = follow
        adapter.setBorderColor(color)
        adapter.setBorderFollowCourse(follow)
    }

    /**
     * 把日期栏所需的「学期开始日期 + 当前周」推给适配器。
     * 值没变时 [WeekPagerAdapter.setWeekContext] 内部直接返回，不触发重绑。
     */
    private fun syncWeekContextToAdapter() {
        if (!::adapter.isInitialized) return
        adapter.setWeekContext(settingsManager.getSemesterStartDate(), getCurrentWeek())
    }

    /**
     * 右下角「回到本周」箭头的显隐与方向。
     * 只在本周之外的周课表出现；箭头指向本周：显示周更早 → 右箭头，更晚 → 左箭头。
     */
    private fun updateWeekJumpButton() {
        val displayWk = getDisplayWeek()
        val currentWk = getCurrentWeek()
        val visible = !isOverview && displayWk != currentWk
        btnBackToWeek.isVisible = visible
        if (!visible) return
        btnBackToWeek.setIcon(
            if (displayWk < currentWk) R.drawable.ic_arrow_right else R.drawable.ic_arrow_left
        )
    }

    private fun initViews(view: View) {
        viewPager = view.findViewById(R.id.view_pager)
        tvDate = view.findViewById(R.id.tv_date)
        tvWeekInfo = view.findViewById(R.id.tv_week_info)
        tvCountdown = view.findViewById(R.id.tv_countdown)
        btnRefresh = view.findViewById(R.id.btn_refresh)
        progressRefresh = view.findViewById(R.id.progress_refresh)
        overlayLoading = view.findViewById(R.id.overlay_loading)
        groupLoading = view.findViewById(R.id.group_loading)
        groupSuccess = view.findViewById(R.id.group_success)
        tvLoadResult = view.findViewById(R.id.tv_load_result)
        btnBackToWeek = view.findViewById(R.id.btn_back_to_week)
        layoutOverview = view.findViewById(R.id.layout_overview)
        rvCourseOverview = view.findViewById(R.id.rv_course_overview)
        llOverviewFilters = view.findViewById(R.id.ll_overview_filters)
        llOverviewEmpty = view.findViewById(R.id.ll_overview_empty)
        tvOverviewEmpty = view.findViewById(R.id.tv_overview_empty)
        rvCourseOverview.layoutManager = LinearLayoutManager(requireContext())
        rvCourseOverview.adapter = overviewAdapter
        buildOverviewFilterChips()

        btnRefresh.setOnClickListener {
            refreshScheduleFromJwxt(showError = true)
        }

        // 一键平滑滑回本周（非瞬移）。重复点击是安全的：ViewPager2 的 currentItem
        // 在平滑滚动开始时就已经置为目标页，第二次调用会因 currentItem 相等直接返回。
        btnBackToWeek.setOnClickListener {
            viewPager.setCurrentItem(getCurrentWeek() - 1, true)
        }

        view.findViewById<View>(R.id.btn_menu).setOnClickListener {
            showMenuSheet()
        }
    }

    /**
     * 获取当前周数（便捷访问 ViewModel 或计算）
     */
    private fun getCurrentWeek(): Int {
        if (courseViewModel.currentWeek == 0) {
            courseViewModel.currentWeek = calculateCurrentWeek()
        }
        return courseViewModel.currentWeek
    }

    /**
     * 获取当前显示的周数。
     * ViewModel 作用域为 Activity，tab 切换保持位置，进程死亡后重建回到当前周。
     */
    private fun getDisplayWeek(): Int {
        if (courseViewModel.displayWeek == 0) {
            courseViewModel.displayWeek = calculateCurrentWeek()
        }
        return courseViewModel.displayWeek
    }

    private fun setDisplayWeek(week: Int) {
        courseViewModel.displayWeek = week
    }

    private fun setupViewPager() {
        val currentWk = calculateCurrentWeek()
        courseViewModel.currentWeek = currentWk

        // ViewModel 未初始化（新鲜启动）→ 显示当前周
        // ViewModel 已有值（tab 切换归来）→ 保持位置
        val displayWk = getDisplayWeek()
        val totalWeeks = settingsManager.getTotalWeeks()

        // 格子高度取「我的 → 外观 → 课表」的设置（未设置过时回落 dimens 的 68dp）
        appliedCellHeightDp = settingsManager.getCourseCellHeight()
        appliedShowClassroom = settingsManager.isShowClassroom()
        appliedShowTeacher = settingsManager.isShowTeacher()
        appliedBorderColor = settingsManager.getCellBorderColor()
        appliedBorderFollowCourse = settingsManager.isBorderFollowCourse()
        adapter = WeekPagerAdapter(totalWeeks, appliedCellHeightDp)
        adapter.setContentVisibility(appliedShowClassroom, appliedShowTeacher)
        adapter.setBorderColor(appliedBorderColor)
        adapter.setBorderFollowCourse(appliedBorderFollowCourse)
        // 仅预加载相邻1页（3页总量），减少tab切换时的初始构建压力
        viewPager.offscreenPageLimit = 1

        // 延迟到下一帧设置 adapter + 当前页，让tab切换动画先完成
        viewPager.post {
            viewPager.adapter = adapter
            viewPager.setCurrentItem(displayWk - 1, false)
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setDisplayWeek(position + 1)
                updateDateTimeHeader()
            }
        })
    }

    private fun setupObservers() {
        val viewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]
        viewModel.courses.observe(viewLifecycleOwner) { _ ->
            allCourses = CourseDataManager.getInstance(requireContext()).getAllCourses()
            adapter.updateData(allCourses)
            // 总课表下数据变化（切学期 / 导入 / 刷新）同样要重建列表
            if (isOverview) refreshOverview()
        }
        viewModel.overviewMode.observe(viewLifecycleOwner) { applyScheduleMode(it) }
    }

    /**
     * 切换「周课表 ⇄ 总课表」：两者共用顶部信息栏，只互斥周网格与总课表列表。
     * 日期栏在周课表的每一页内部（见 item_date_header.xml），随 viewPager 一起隐藏。
     */
    private fun applyScheduleMode(overview: Boolean) {
        isOverview = overview
        viewPager.isVisible = !overview
        layoutOverview.isVisible = overview
        if (overview) refreshOverview() else updateDateTimeHeader()
    }

    /**
     * 重建总课表列表：当前学期全部课程按课程名归并成条目，
     * 取色与周课表一致（[ThemeManager.getCourseColors]）。
     */
    private fun refreshOverview() {
        // 切换「周课表 ⇄ 总课表」是高频操作，归并结果只取决于课程数据：
        // 引用没变（DataManager 的列表是整体替换的不可变快照）就复用上次结果，不再全量 groupBy 一遍
        val courses = CourseDataManager.getInstance(requireContext()).getAllCourses()
        if (overviewGroupsSource !== courses) {
            overviewGroups = CourseOverviewGroup.build(courses)
            overviewGroupsSource = courses
        }
        overviewGroupCount = overviewGroups.size
        applyOverviewFilter()
    }

    /** 按顶部筛选渲染总课表列表；筛选后为空时给出对应的空状态文案 */
    private fun applyOverviewFilter() {
        val visible = overviewFilter.apply(overviewGroups)
        overviewVisibleCount = visible.size
        overviewAdapter.submit(visible, ThemeManager.getCourseColors())
        rvCourseOverview.isVisible = visible.isNotEmpty()
        llOverviewEmpty.isVisible = visible.isEmpty()
        tvOverviewEmpty.text = if (overviewGroups.isEmpty()) {
            "本学期暂无课程"
        } else {
            "没有「${overviewFilter.label}」的课程"
        }
        updateDateTimeHeader()
    }

    /** 重建总课表顶部的「全部 / 理论课 / 实践课」筛选 chip */
    private fun buildOverviewFilterChips() {
        llOverviewFilters.removeAllViews()
        CourseTypeFilter.entries.forEach { filter ->
            llOverviewFilters.addView(
                requireContext().createAppChip(
                    label = filter.label,
                    selected = filter == overviewFilter,
                    accent = null,
                    leadingIcon = null
                ) {
                    if (overviewFilter == filter) return@createAppChip
                    overviewFilter = filter
                    buildOverviewFilterChips()
                    applyOverviewFilter()
                }
            )
        }
    }

    private fun calculateCurrentWeek(): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return 1
        val diffDays = ((System.currentTimeMillis() - startDate) / 86400000L).toInt()
        return (diffDays / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks())
    }

    private fun updateDateTimeHeader() {
        // 日期栏在每页内部，这里顺带把周上下文推给适配器；
        // 本方法是所有「周/日期显示状态变化」的统一入口，挂在这里可以保证一条不漏。
        syncWeekContextToAdapter()
        updateWeekJumpButton()

        val semesterLabel = SemesterManager.getCurrent()?.label?.takeIf { it.isNotBlank() }

        if (isOverview) {
            // 总课表：顶栏换成总课表标题 + 条目数（周课表已隐藏，无需刷新日期栏）
            tvWeekInfo.text = if (semesterLabel != null) "总课表 · $semesterLabel" else "总课表"
            tvDate.text = if (overviewFilter == CourseTypeFilter.ALL) {
                "本学期共 $overviewGroupCount 门课程"
            } else {
                "${overviewFilter.label} · 共 $overviewVisibleCount 门"
            }
            return
        }

        val cal = Calendar.getInstance()
        tvDate.text = dateFormat.format(cal.time)
        val displayWk = getDisplayWeek()
        val currentWk = getCurrentWeek()

        val rangeStatus = getSemesterRangeStatus()
        val weekText = when {
            rangeStatus < 0 -> "第${displayWk}周 该学期未开始"
            rangeStatus > 0 -> "第${displayWk}周 该学期已结束"
            displayWk == currentWk -> "第${displayWk}周 (本周)"
            else -> "第${displayWk}周"
        }
        tvWeekInfo.text = if (semesterLabel != null) "$weekText · $semesterLabel" else weekText
    }

    /** 返回 -1=未开始, 0=进行中, 1=已结束 */
    private fun getSemesterRangeStatus(): Int {
        val startDate = settingsManager.getSemesterStartDate()
        if (startDate == 0L) return 0 // 未设置学期日期，不判定
        val totalWeeks = settingsManager.getTotalWeeks()
        val now = System.currentTimeMillis()
        val endDate = startDate + totalWeeks * 7L * 86400000L
        return when {
            now < startDate -> -1
            now > endDate -> 1
            else -> 0
        }
    }

    /**
     * 统一切换"教务加载课表"的 loading 状态：
     * - 刷新按钮隐藏图标、只留转圈，且不可再点
     * - 课表页全页蒙版（导航栏同款色，无缝衔接），中心卡片显示转圈 + 文案
     * - 课表菜单中对应学期色块显示转圈（通过 menuSemesterRefresher 回调刷新）
     */
    private fun setScheduleLoading(loading: Boolean, semesterIndex: Int? = null) {
        isLoadingSchedule = loading
        loadingSemesterIndex = if (loading) semesterIndex else null

        // 加载中刷新图标消失，只留转圈
        btnRefresh.isEnabled = !loading
        btnRefresh.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        progressRefresh.isVisible = loading

        if (loading) {
            showOverlayLoadingState()
            overlayLoading.isVisible = true
        } else {
            overlayLoading.isVisible = false
            resetOverlayState()
        }

        // 菜单打开时同步刷新学期色块（loading 转圈 ↔ 风景/素块）
        menuSemesterRefresher?.invoke()
    }

    /** 遮罩切换为加载中状态（转圈 + "正在加载课表…"） */
    private fun showOverlayLoadingState() {
        groupLoading.isVisible = true
        groupSuccess.isVisible = false
    }

    /** 遮罩切换为成功状态（对勾 + 结果文案），短暂展示后自动收起 */
    private fun showLoadSuccess(count: Int) {
        // 数据已就绪：色块转圈立即消失（新填充的学期出现风景），按钮恢复外观但仍禁用
        loadingSemesterIndex = null
        progressRefresh.isVisible = false
        btnRefresh.visibility = View.VISIBLE
        btnRefresh.isEnabled = false
        menuSemesterRefresher?.invoke()

        // 遮罩换成成功卡片
        groupLoading.isVisible = false
        tvLoadResult.text = "成功导入 $count 门课程"
        groupSuccess.isVisible = true

        // 短暂展示后收起遮罩，恢复交互
        Handler(Looper.getMainLooper()).postDelayed({
            isLoadingSchedule = false
            btnRefresh.isEnabled = true
            overlayLoading.isVisible = false
            resetOverlayState()
        }, SUCCESS_DISPLAY_MS)
    }

    /** 遮罩恢复默认的加载中布局（供下次加载复用） */
    private fun resetOverlayState() {
        groupLoading.isVisible = true
        groupSuccess.isVisible = false
    }

    private fun refreshScheduleFromJwxt(showError: Boolean) {
        // 加载中不允许再次刷新
        if (isLoadingSchedule) return
        if (!JwxtAuthManager.isBound()) return
        val curSem = SemesterManager.getCurrent() ?: return
        // 协程里不用 requireContext()：刷新可能耗时数秒，期间页面被销毁时 requireContext()
        // 会抛 IllegalStateException 直接闪退。这里提前取 applicationContext 固定下来。
        val ctx = context?.applicationContext ?: return

        setScheduleLoading(true, semesterIndex = settingsManager.getCurrentSemesterIndex())
        lifecycleScope.launch(Dispatchers.IO) {
            // 兜底：fetchAndSave 只保证「取数」阶段的异常进 Result；转换/落库阶段的异常
            // 以前会从协程里逃逸 → release 包用户看到的就是「一刷新课表就闪退」。
            // 这里统一收敛成 Result.failure，由页面展示错误而不是打断进程。
            val result = try {
                JwxtImportService.fetchAndSaveScheduleForSemester(ctx, curSem)
            } catch (t: Throwable) {
                Log.e(TAG, "刷新课表时出现未捕获异常（已兜底，不闪退）", t)
                Result.failure(t)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || view == null) return@withContext
                result.onSuccess { count ->
                    courseViewModel.currentWeek = calculateCurrentWeek()
                    allCourses = CourseDataManager.getInstance(ctx).getAllCourses()
                    if (::adapter.isInitialized) adapter.updateData(allCourses)
                    if (isOverview) refreshOverview() else updateDateTimeHeader()
                    // 成功：遮罩换成"成功导入 N 门课程"卡片，替代全局 toast
                    showLoadSuccess(count)
                }.onFailure { e ->
                    setScheduleLoading(false)
                    if (showError) {
                        // 把异常类型也带出来：用户反馈截图即可定位，不用再猜是网络还是本地库问题
                        val detail = e.message?.take(160) ?: e.javaClass.simpleName
                        AppToast.error(ctx, "刷新失败：$detail")
                    }
                }
            }
        }
    }

    // ── 课表菜单 Bottom Sheet ─────────────────────────────

    /**
     * 显示课表菜单底部弹窗，包含周数滑块和学期选择。
     * 参考 SchedulePageDetailDialog 的 BottomSheet 模式。
     */
    private fun showMenuSheet() {
        val ctx = requireContext()
        // 防止连点打开多个底部弹窗：先关闭已存在的
        activeMenuDialog?.dismiss()
        val dialog = Dialog(ctx, R.style.BottomSheetDialog)
        activeMenuDialog = dialog
        // 登记为提示宿主：菜单打开期间的 AppToast 渲染在菜单窗口之上，不被遮罩压住
        AppToast.registerDialogHost(dialog)
        dialog.setOnDismissListener {
            if (activeMenuDialog === dialog) activeMenuDialog = null
            // 菜单关闭后注销色块刷新回调（全局 loading 不再尝试刷新已销毁的菜单）
            menuSemesterRefresher = null
        }
        val inflater = LayoutInflater.from(ctx)
        val sheetView = inflater.inflate(R.layout.bottom_sheet_schedule_menu, null)
        val density = ctx.resources.displayMetrics.density

        // 顶部圆角背景
        val topRadius = 24 * density
        val sheetBg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        // 弹层底色提升一级到 colorSurfaceContainer，
        // 保证暗色模式下与蒙版压暗的背景有明显边界。
        ctx.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

        // ── 主题色取样（浅色/深色自适应） ──
        fun resolveAttr(attr: Int): Int {
            ctx.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }

        val primaryColor = resolveAttr(com.google.android.material.R.attr.colorPrimary)
        val primaryContainer = resolveAttr(com.google.android.material.R.attr.colorPrimaryContainer)
        val secondaryContainer = resolveAttr(com.google.android.material.R.attr.colorSecondaryContainer)
        val tertiaryContainer = resolveAttr(com.google.android.material.R.attr.colorTertiaryContainer)
        val onSurfaceVariantColor =
            resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val onPrimaryContainerColor =
            resolveAttr(com.google.android.material.R.attr.colorOnPrimaryContainer)
        // 未导入课表的学期色块底色（比弹层底色亮一档，才看得出是「一块待选目标」）
        val unimportedBlockColor =
            resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerHighest)

        // 风景里的太阳固定暖色，浅色/深色模式下都醒目
        val sunColor = 0xFFFFD54F.toInt()

        // ── 标题副行：当前学期（菜单里唯一显示「现在是哪个学期」的位置） ──
        // 右上角关闭：内容变长后必须有一个一眼可见的退出方式
        sheetView.findViewById<View>(R.id.btn_menu_close)?.setOnClickListener { dialog.dismiss() }

        // 菜单内联错误条：菜单里的失败信息不再走 AppToast（会被弹层遮罩压住）
        val tvMenuError = sheetView.findViewById<TextView>(R.id.tv_menu_error)
        fun showMenuError(message: String) {
            tvMenuError.text = message
            tvMenuError.isVisible = true
            tvMenuError.alpha = 0f
            tvMenuError.animate().alpha(1f).setDuration(160).start()
        }
        fun clearMenuError() {
            if (tvMenuError.isVisible) {
                tvMenuError.animate().alpha(0f).setDuration(120)
                    .withEndAction { tvMenuError.isVisible = false }.start()
            }
        }

        val tvSubtitle = sheetView.findViewById<TextView>(R.id.tv_menu_subtitle)

        // 副标题跟着当前学期走：切学期后要立刻刷新，否则会一直显示打开菜单时的那个学期
        fun refreshMenuSubtitle() {
            tvSubtitle.text = if (SemesterManager.getCurrent() == null) {
                "未绑定教务账号"
            } else {
                "${settingsManager.getCurrentSemester()} · ${settingsManager.getCurrentSemesterFullName()}"
            }
        }
        refreshMenuSubtitle()

        // ── 周数滑块（Material Slider） ──
        val tvWeekLabel = sheetView.findViewById<TextView>(R.id.tv_week_label)
        val tvWeekHint = sheetView.findViewById<TextView>(R.id.tv_week_hint)
        val llWeekAction = sheetView.findViewById<LinearLayout>(R.id.ll_week_action)
        val sbWeek = sheetView.findViewById<com.google.android.material.slider.Slider>(R.id.sb_week)

        /** 「回到本周」chip 建在 refreshWeekSlider 之后（点击又要回调它），先留个引用给它反查。 */
        var chipThisWeek: View? = null

        /**
         * 已在本周时右侧的静态标记：没有它「第 8 周」这行右侧空着，整行重心全压在左边。
         * 用同一套 chip 外观（surface + 描边）但不可点，避免和真按钮长得不一样。
         */
        val chipNowWeek = ctx.createAppChip("本周", selected = false) {}.apply {
            isClickable = false
            isFocusable = false
        }

        /**
         * 周次右侧快捷区：有学期且有「本周」概念时才显示 ——
         * 不在本周给「回到本周」按钮，已在本周给静态「本周」标记（顶栏也已标注）。
         * 拖滑块时也要跟着变，所以独立成一个函数让 change listener 复用。
         */
        fun syncWeekAction(week: Int) {
            val hasSemester = SemesterManager.getCurrent() != null
            val isOnCurrentWeek = week == calculateCurrentWeek()
            chipThisWeek?.isVisible = hasSemester && !isOnCurrentWeek
            chipNowWeek.isVisible = hasSemester && isOnCurrentWeek
        }

        /** 同步滑块：范围、当前值与提示行。切换学期 / 点「回到本周」后可重复调用。 */
        fun refreshWeekSlider() {
            // coerceAtLeast(2)：M3 Slider 在 valueTo <= valueFrom 时会抛异常，
            // 总周数异常（1）时打开菜单会直接崩
            val totalWeeks = settingsManager.getTotalWeeks().coerceAtLeast(2)
            val week = getDisplayWeek().coerceIn(1, totalWeeks)
            tvWeekLabel.text = "第 $week 周"
            sbWeek.valueFrom = 1f
            sbWeek.valueTo = totalWeeks.toFloat()
            sbWeek.stepSize = 1f
            sbWeek.value = week.toFloat()
            // 18 个离散档位密集到连成虚线，看着像"斑马线"；周次的即时反馈交给拖动气泡
            sbWeek.isTickVisible = false
            // 提示行合并了原来的两端端点标注：轨道左右各被 thumbRadius 内缩，
            // 端点文字贴在卡片内容边缘会明显对不齐，与其猜内缩值做对齐，
            // 不如去掉这层「看起来像刻度」的假精度。
            tvWeekHint.text = "拖动切换周次 · 共 $totalWeeks 周"
            syncWeekAction(week)
        }

        // 「回到本周」：点击后滑回当前周，菜单保持打开，下方课表同步
        val chip = ctx.createAppChip("回到本周", selected = false) {
            val target = calculateCurrentWeek().coerceIn(1, settingsManager.getTotalWeeks())
            setDisplayWeek(target)
            sbWeek.value = target.toFloat()
            viewPager.setCurrentItem(target - 1, false)
            updateDateTimeHeader()
            refreshWeekSlider()
        }
        chipThisWeek = chip
        llWeekAction.addView(chip)
        llWeekAction.addView(chipNowWeek)

        // 气泡显示「第 N 周」，而不是裸数字
        sbWeek.setLabelFormatter { value -> "第 ${value.roundToInt()} 周" }

        sbWeek.addOnChangeListener { _, value, fromUser ->
            val totalWeeks = settingsManager.getTotalWeeks()
            val week = value.roundToInt().coerceIn(1, totalWeeks)
            tvWeekLabel.text = "第 $week 周"
            syncWeekAction(week)
            if (fromUser) {
                setDisplayWeek(week)
                viewPager.setCurrentItem(week - 1, false)
                updateDateTimeHeader()
            }
        }
        refreshWeekSlider()

        // ── 学期选择（横向滑动） ──
        val llSemesterList = sheetView.findViewById<LinearLayout>(R.id.ll_semester_list)

        val blockSize = (58 * density).toInt()
        val itemMarginEnd = (12 * density).toInt()
        val blockRadius = (14 * density).toFloat()

        val semesterItems = mutableListOf<View>()

        /**
         * 重建学期色块列表：刷新选中描边、选中圆点与风景块。
         * animateEntrance 为 true 时色块初始隐藏，等弹窗展示后波浪式入场；
         * 切换学期后的重建直接可见。
         */
        fun refreshSemesterItems(animateEntrance: Boolean) {
            val semesters = SemesterManager.getAll()
            val currentSemesterIndex = settingsManager.getCurrentSemesterIndex()
            val courseCounts = CourseDataManager.getInstance(ctx).getSemesterCourseCounts()

            llSemesterList.removeAllViews()
            semesterItems.clear()

            // 未绑定教务时学期表是空的：整组隐藏，别留一个空卡片和一个「学期」标题
            sheetView.findViewById<View>(R.id.group_semester).isVisible = semesters.isNotEmpty()

            semesters.forEachIndexed { index, sem ->
                val isSelected = index == currentSemesterIndex
                val hasCourses = (courseCounts[sem.id] ?: 0) > 0

                val item = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = itemMarginEnd }
                    isClickable = true
                    isFocusable = true
                    if (animateEntrance) {
                        // 初始隐藏，弹窗展示后按顺序波浪式入场
                        alpha = 0f
                        scaleX = 0.85f
                        scaleY = 0.85f
                    }
                }

                // 色块容器：已填充课表的学期画迷你风景，未填充保持素色块；
                // 该学期正在从教务加载时，色块上叠加转圈
                val isLoadingBlock = index == loadingSemesterIndex
                val blockContainer = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(blockSize, blockSize).apply {
                        bottomMargin = (9 * density).toInt()
                    }
                }
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
                        if (isSelected) {
                            setSelectionStroke(primaryColor, 3f * density)
                        }
                    }
                    blockContainer.addView(scenery)
                    // 课程数徽标：把"这个学期有课"从装饰性风景变成可读信息
                    TextView(ctx).apply {
                        text = (courseCounts[sem.id] ?: 0).toString()
                        setTextSizeRes(R.dimen.text_time_axis)
                        setTextColor(onPrimaryContainerColor)
                        gravity = Gravity.CENTER
                        setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                        background = GradientDrawable().apply {
                            cornerRadius = 8 * density
                            setColor(primaryContainer)
                        }
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, (15 * density).toInt(),
                            Gravity.BOTTOM or Gravity.END
                        ).apply { setMargins(0, 0, (3 * density).toInt(), (3 * density).toInt()) }
                        blockContainer.addView(this)
                    }
                } else {
                    // 未导入课表的学期：素色块。用 surfaceContainerHighest 而不是
                    // surfaceVariant —— 弹层底色本身就是 surfaceContainer，两者太接近时
                    // 未选中的块看起来像「缺图」的灰砖头。
                    val block = View(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(blockSize, blockSize)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = blockRadius
                            setColor(unimportedBlockColor)
                            if (isSelected) {
                                setStroke((3f * density).toInt(), primaryColor)
                            }
                        }
                    }
                    blockContainer.addView(block)
                    // 未导入学期：中央淡色「+」，点击即联网导入该学期课表
                    ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            (18 * density).toInt(), (18 * density).toInt(), Gravity.CENTER
                        )
                        setImageResource(R.drawable.ic_add)
                        setColorFilter(onSurfaceVariantColor)
                        alpha = 0.75f
                        blockContainer.addView(this)
                    }
                }
                if (isLoadingBlock) {
                    val spinnerSize = (26 * density).toInt()
                    val spinner = CircularProgressIndicator(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            spinnerSize, spinnerSize, Gravity.CENTER
                        )
                        isIndeterminate = true
                        setIndicatorColor(primaryColor)
                        trackThickness = (2.5f * density).toInt()
                    }
                    blockContainer.addView(spinner)
                }
                item.addView(blockContainer)

                // 标签文字（只显示前三个字，如"大一上"）。
                // 选中态靠「描边 + 标签主色加粗」双重表达：只换描边在风景色块上不够醒目，
                // 原来额外的选中圆点会让选中项比其它项高一行，导致整排文字基线不齐。
                val label = TextView(ctx).apply {
                    text = sem.label.take(3)
                    setTextSizeRes(R.dimen.text_body_small)
                    gravity = Gravity.CENTER
                    setTextColor(if (isSelected) primaryColor else onSurfaceVariantColor)
                    if (isSelected) {
                        setTypeface(typeface, Typeface.BOLD)
                    }
                }
                item.addView(label)

                item.setOnClickListener {
                    // 加载中禁止并发切换学期
                    if (isLoadingSchedule) return@setOnClickListener
                    if (isSelected) {
                        // 点击当前学期：作为"确认"快捷关闭
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    // 切换学期（菜单保持打开，下方课表与菜单内状态同步刷新）
                    clearMenuError()
                    // 记录切换前的状态：该学期没有本地数据时要联网拉取，失败得能回滚，
                    // 否则会停在一个空学期上（顶栏写着"大一上"、副标题还写着"大二下"）。
                    val prevIndex = settingsManager.getCurrentSemesterIndex()
                    val prevSemesterId = SemesterManager.getCurrent()?.id
                    val prevWeek = getDisplayWeek()

                    settingsManager.setCurrentSemesterIndex(index)
                    CourseDataManager.getInstance(ctx).switchSemester(sem.id)
                    refreshMenuSubtitle()

                    // 调到第一周
                    courseViewModel.currentWeek = calculateCurrentWeek()
                    setDisplayWeek(1)
                    viewPager.setCurrentItem(0, false)
                    updateDateTimeHeader()

                    // 该学期日期信息为空 → 需要从教务拉取课表，进入 loading
                    val needsFetch = sem.startDate == 0L || sem.totalWeeks == 0
                    if (needsFetch) {
                        // 全局 loading：刷新按钮转圈 + 课表页遮罩 + 色块转圈
                        setScheduleLoading(true, semesterIndex = index)
                        lifecycleScope.launch(Dispatchers.IO) {
                            // 与刷新按钮同款兜底：转换/落库异常不允许逃出协程导致闪退
                            val result = try {
                                JwxtImportService.fetchAndSaveScheduleForSemester(ctx, sem)
                            } catch (t: Throwable) {
                                Log.e(TAG, "切换学期导入课表出现未捕获异常（已兜底，不闪退）", t)
                                Result.failure(t)
                            }
                            withContext(Dispatchers.Main) {
                                if (!isAdded || view == null) return@withContext
                                result.onSuccess { count ->
                                    // 更新 ViewPager 总页数
                                    appliedCellHeightDp = settingsManager.getCourseCellHeight()
                                    appliedShowClassroom = settingsManager.isShowClassroom()
                                    appliedShowTeacher = settingsManager.isShowTeacher()
                                    appliedBorderColor = settingsManager.getCellBorderColor()
                                    appliedBorderFollowCourse = settingsManager.isBorderFollowCourse()
                                    adapter = WeekPagerAdapter(settingsManager.getTotalWeeks(), appliedCellHeightDp)
                                    adapter.setContentVisibility(appliedShowClassroom, appliedShowTeacher)
                                    adapter.setBorderColor(appliedBorderColor)
                                    adapter.setBorderFollowCourse(appliedBorderFollowCourse)
                                    // 刚导入完才有学期开始日期，日期栏要按新日期渲染
                                    syncWeekContextToAdapter()
                                    viewPager.adapter = adapter
                                    adapter.updateData(CourseDataManager.getInstance(ctx).getAllCourses())
                                    refreshWeekSlider()
                                    // 成功：色块转圈消失、新填充学期出现风景；遮罩短暂展示成功卡片
                                    showLoadSuccess(count)
                                }.onFailure { e ->
                                    setScheduleLoading(false)
                                    // 回滚到切换前的学期：导入失败不该把用户丢在一个空学期上
                                    if (prevSemesterId != null) {
                                        settingsManager.setCurrentSemesterIndex(prevIndex)
                                        CourseDataManager.getInstance(ctx).switchSemester(prevSemesterId)
                                        courseViewModel.currentWeek = calculateCurrentWeek()
                                        setDisplayWeek(prevWeek)
                                        viewPager.setCurrentItem(prevWeek - 1, false)
                                        updateDateTimeHeader()
                                        refreshWeekSlider()
                                        refreshMenuSubtitle()
                                    }
                                    // 菜单还开着：错误显示在菜单里，用户能立刻看到原因
                                    showMenuError("获取课表失败：${e.message ?: "网络异常"}")
                                    refreshSemesterItems(animateEntrance = false)
                                }
                            }
                        }
                    } else {
                        // 本地已有课表，直接同步菜单状态
                        refreshWeekSlider()
                        refreshSemesterItems(animateEntrance = false)
                    }
                }

                llSemesterList.addView(item)
                semesterItems.add(item)
            }
        }

        refreshSemesterItems(animateEntrance = true)

        // 注册色块刷新回调：全局 loading 状态切换时（如刷新按钮触发）同步菜单色块
        menuSemesterRefresher = { refreshSemesterItems(animateEntrance = false) }

        // ── 外观：折叠分组（标题行常驻，点开才展开控件；展开状态记忆在设置里） ──
        val groupAppearance = sheetView.findViewById<View>(R.id.group_schedule_appearance)
        val rowAppearanceHeader = sheetView.findViewById<View>(R.id.row_appearance_header)
        val ivAppearanceArrow = sheetView.findViewById<ImageView>(R.id.iv_appearance_arrow)
        val appearanceContent =
            sheetView.findViewById<View>(R.id.group_schedule_appearance_content)
        val initiallyExpanded = settingsManager.isScheduleMenuAppearanceExpanded()
        appearanceContent.isVisible = initiallyExpanded
        ivAppearanceArrow.rotation = if (initiallyExpanded) 180f else 0f
        // 入场前先隐藏，等弹窗展示后再淡入，避免 show() 瞬间闪一下
        groupAppearance.alpha = 0f
        ScheduleAppearanceControls.bind(sheetView, ctx, this)

        // 弹窗展示后，学期色块按顺序波浪式入场
        dialog.setOnShowListener {
            llSemesterList.post {
                semesterItems.forEachIndexed { i, v ->
                    v.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(i * 55L)
                        .setDuration(240)
                        .setInterpolator(OvershootInterpolator(1.05f))
                        .start()
                }
                // 课表外观分组与色块同时淡入（整行较宽，只做透明度不做缩放，免得像弹一下）
                groupAppearance.animate()
                    .alpha(1f)
                    .setDuration(240)
                    .start()
            }
        }

        // ── 组装容器 ──
        dialog.setContentView(sheetView)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)

        // ── 高度：固定按「折叠态」量一次，展开外观也不改变菜单高度，只让内容在菜单内下滑 ──
        // 菜单高度始终按折叠态内容决定（最多 85% 屏高）：外观分组展开后只是滚动区变长，
        // 不把整个弹层继续撑高，避免展开时盖住更多课表。
        val metrics = ctx.resources.displayMetrics
        // 弹窗是整屏的：底部系统手势条会压住弹层最后一排内容，给根布局补上底部内边距。
        val navigationBarBottom = ViewCompat
            .getRootWindowInsets(requireActivity().window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        if (navigationBarBottom > 0) {
            sheetView.setPadding(
                sheetView.paddingLeft,
                sheetView.paddingTop,
                sheetView.paddingRight,
                navigationBarBottom
            )
        }
        // 量高度时临时收起外观控件：展开态（含记忆上次展开）也按折叠高度开菜单，
        // 展开后的控件在滚动区里向下排，由用户下滑查看。
        val appearanceExpandedBeforeMeasure = appearanceContent.isVisible
        appearanceContent.isVisible = false
        sheetView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        appearanceContent.isVisible = appearanceExpandedBeforeMeasure
        val maxSheetHeight = (metrics.heightPixels * MENU_SHEET_MAX_HEIGHT_RATIO).toInt()
        val sheetHeight = minOf(sheetView.measuredHeight, maxSheetHeight)
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight
        )

        rowAppearanceHeader.setOnClickListener {
            val expanded = !appearanceContent.isVisible
            appearanceContent.isVisible = expanded
            ivAppearanceArrow.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(180)
                .start()
            settingsManager.setScheduleMenuAppearanceExpanded(expanded)
            // 不调整菜单高度：展开的控件在滚动区下方，下滑查看即可
        }
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnClickListener { dialog.dismiss() }
        })
        container.addView(sheetView)
        dialog.setContentView(container)

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

    // ── 距下课/下节课倒计时 ─────────────────────────────

    /**
     * 每秒刷新顶部倒计时提示（距下课 / 下节课 / 今日课程已结束）。
     * 只在页面可见期间运行（onStart 启动 / onStop 停止），避免 App 退后台后每秒空转。
     */
    private fun startCountdown() {
        // 先清掉旧的，防止 onStart 被重复调用时叠加多个循环
        stopCountdown()
        countdownRunnable = object : Runnable {
            override fun run() {
                updateCountdown()
                countdownHandler.postDelayed(this, 1000)
            }
        }
        countdownHandler.post(countdownRunnable!!)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun updateCountdown() {
        val cal = countdownCalendar
        // 复用同一个 Calendar 实例，只更新时间；字段取值与旧实现（每秒 new 一个）完全一致
        cal.timeInMillis = System.currentTimeMillis()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val adjustedDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val currentSeconds = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)

        val week = getCurrentWeek()
        val slots = TimeTableManager.getInstance(requireContext()).getTimeSlots()
        ensureCountdownTodayCourses(adjustedDay, week, slots)

        // 今日课程已按开始分钟升序缓存：第一个命中的就是「当前课」，与旧 find 语义一致。
        // 结束分钟按需解析并回填缓存（旧实现也只在 find 当前课的路径上解析结束时间，
        // 这里保持同样的解析时机，不把历史行为里可能的解析异常提前到别的课程）。
        var currentIndex = -1
        for (i in countdownTodayCourses.indices) {
            var endMinutes = countdownEndMinutes[i]
            if (endMinutes == END_MINUTES_UNSET) {
                endMinutes = getCourseEndMinutes(countdownTodayCourses[i], slots)
                countdownEndMinutes[i] = endMinutes
            }
            if (nowMinutes in countdownStartMinutes[i]..endMinutes) {
                currentIndex = i
                break
            }
        }
        // 没有当前课时才需要「下节课」；旧实现两遍 find 都会跑，但 find 无副作用，结果完全相同
        var nextIndex = -1
        if (currentIndex < 0) {
            for (i in countdownTodayCourses.indices) {
                if (nowMinutes < countdownStartMinutes[i]) {
                    nextIndex = i
                    break
                }
            }
        }

        when {
            currentIndex >= 0 -> {
                val endSeconds = countdownEndMinutes[currentIndex] * 60
                val remaining = (endSeconds - currentSeconds).coerceAtLeast(0)
                showCountdown(getString(R.string.countdown_class_end, formatDuration(remaining)))
            }
            nextIndex >= 0 -> {
                val startSeconds = countdownStartMinutes[nextIndex] * 60
                val remaining = (startSeconds - currentSeconds).coerceAtLeast(0)
                showCountdown(getString(R.string.countdown_format, formatDuration(remaining)))
            }
            countdownTodayCourses.isNotEmpty() ->
                showCountdown(getString(R.string.countdown_no_more_classes))
            else -> hideCountdown()
        }
    }

    /**
     * 重建「今日课程（按开始分钟升序）+ 每门课起止分钟」缓存。
     *
     * 只有课程列表 / 当前周 / 自然日 / 时间表（用户可能在外观页改了节次时间）变化时才重算：
     * 旧实现每秒都要 filter 全量课程、反复读时间表解析分钟、再排序，这些在一天内都是不变的。
     */
    private fun ensureCountdownTodayCourses(
        adjustedDay: Int,
        week: Int,
        slots: List<TimeTableManager.TimeSlot>
    ) {
        if (countdownCacheCourses === allCourses &&
            countdownCacheWeek == week &&
            countdownCacheDay == adjustedDay &&
            countdownCacheSlots === slots
        ) {
            return
        }

        // 与旧实现逐字段一致：先按（星期、当前周有课）过滤，再按开始分钟升序（稳定排序，
        // 开始时间相同的课程保持 allCourses 里的相对顺序）
        val courses = allCourses.filter { it.dayOfWeek == adjustedDay && it.isActiveInWeek(week) }
        val startMinutes = IntArray(courses.size)
        courses.forEachIndexed { i, course ->
            startMinutes[i] = getCourseStartMinutes(course, slots)
        }
        val order = courses.indices.sortedBy { startMinutes[it] }

        countdownTodayCourses = order.map { courses[it] }
        countdownStartMinutes = IntArray(order.size) { startMinutes[order[it]] }
        // 结束分钟先留空，等 updateCountdown 真正要判断「当前课」时再解析并回填
        countdownEndMinutes = IntArray(order.size) { END_MINUTES_UNSET }
        countdownCacheCourses = allCourses
        countdownCacheWeek = week
        countdownCacheDay = adjustedDay
        countdownCacheSlots = slots
    }

    /** 显示倒计时：文本/可见性没变就不动 View，避免每秒一次无谓的 setText + 重绘 */
    private fun showCountdown(text: String) {
        if (countdownShownVisible != true) {
            tvCountdown.visibility = View.VISIBLE
            countdownShownVisible = true
        }
        if (countdownShownText != text) {
            tvCountdown.text = text
            countdownShownText = text
        }
    }

    private fun hideCountdown() {
        if (countdownShownVisible != false) {
            tvCountdown.visibility = View.GONE
            countdownShownVisible = false
        }
    }

    /** 课程开始节次对应的分钟数（从当日 0 点起算） */
    private fun getCourseStartMinutes(course: Course, slots: List<TimeTableManager.TimeSlot>): Int {
        val slot = slots.find { it.node == course.startTime }
        return if (slot != null) {
            val parts = slot.startTime.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } else (8 + course.startTime) * 60
    }

    /** 课程结束节次对应的分钟数（从当日 0 点起算） */
    private fun getCourseEndMinutes(course: Course, slots: List<TimeTableManager.TimeSlot>): Int {
        val slot = slots.find { it.node == course.endTime }
        return if (slot != null) {
            val parts = slot.endTime.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } else (8 + course.endTime) * 60 + 45
    }

    private fun formatDuration(totalSeconds: Int): String {
        return when {
            totalSeconds >= 3600 -> "${totalSeconds / 3600}小时${(totalSeconds % 3600) / 60}分钟"
            totalSeconds >= 60 -> "${totalSeconds / 60}分${totalSeconds % 60}秒"
            else -> "${totalSeconds}秒"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopCountdown()
    }

}
