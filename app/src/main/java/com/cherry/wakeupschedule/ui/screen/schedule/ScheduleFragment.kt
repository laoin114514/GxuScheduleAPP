package com.cherry.wakeupschedule.ui.screen.schedule

import android.content.Intent
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
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
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.theme.PageBackground
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.widget.CircleIconButton
import com.cherry.wakeupschedule.ui.widget.SemesterSceneryView
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
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
    private lateinit var refreshIcon: View
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

    /** 视图模式首次下发（含 tab 切回 / 重建）直接落位，不播交叉淡入（B4 同一屏只动一次） */
    private var scheduleModeInitialized = false

    /** 总课表列表的错峰入场是否已播（B4；课程数据变化后重置） */
    private var overviewEntrancePlayed = false

    // ── 顶部日期 / 周次文字的淡出→换字→淡入过渡（B5）────────────────

    /** 文字过渡专用 Handler（与倒计时 Handler 分开，避免互相 removeCallbacks） */
    private val headerAnimHandler = Handler(Looper.getMainLooper())

    /** 首帧直接落字，不播过渡（页面刚创建时没有"旧文字"可淡出） */
    private var headerTextInitialized = false

    /** 过渡是否进行中：进行中又换周说明用户在快速翻周/拖滑杆，直接换字保证跟手 */
    private var headerTransitionActive = false
    private var headerTransitionGeneration = 0

    /**
     * 程序化瞬跳标记（滑杆 / 切学期 / 回到本周 chip）：瞬跳期间不播课程格子入场，
     * 直接落终态保证"拖动跟手、不闪一下"。setCurrentItem(…, false) 会同步派发 onPageSelected，
     * 之后再用 post 兜底清掉标记（万一某版本异步派发）。
     */
    private var instantPageJump = false

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

        /** 顶部周次文字过渡：淡出 120ms / 淡入 180ms（B5） */
        private const val HEADER_FADE_OUT_MS = 120L
        private const val HEADER_FADE_IN_MS = 180L

        /** B1 周视图翻页景深：页面内容 1.04→1.0、alpha 0.6→1，页内日期栏 0.35x 反向视差 */
        private const val PAGE_DEPTH_SCALE_DELTA = 0.04f
        private const val PAGE_DEPTH_ALPHA_DELTA = 0.4f
        private const val PAGE_DATE_PARALLAX = 0.35f

        /** 底部弹层遮罩（原窗口 dim 0.5 黑），改由内容层自己做 alpha 渐变（B3 / B8） */

        /** 课表菜单：弹层滑入时长 / 滑出时长（ms） */
        private const val MENU_ENTER_MS = 260L
        private const val MENU_EXIT_MS = 180L

        /**
         * 课程数据快照引用：只在数据「真正变化」时才重播课程格子入场（B2）。
         * LiveData 在切 tab / 重建视图后会把同一份快照再次下发，引用没变就不重播。
         */
        private var lastEntranceCourses: List<Course>? = null

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
        // CircleIconButton 构造时第一个加入的子 View 是图标（之后才是刷新中的转圈），
        // 点击刷新只转图标本身，不转按钮的圆底 / 不打断按钮的按压缩放
        refreshIcon = (btnRefresh as ViewGroup).getChildAt(0)
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
            if (isLoadingSchedule) return@setOnClickListener
            // B6：点击刷新时刷新图标转一圈。loading 状态会立刻隐藏整个刷新按钮，
            // 所以转完这一圈（约 180ms）再真正进入 loading，否则用户根本看不到转圈反馈。
            btnRefresh.isEnabled = false
            Motion.spinOnce(refreshIcon, Motion.SHORT)
            btnRefresh.postDelayed({
                refreshScheduleFromJwxt(showError = true)
                // 未绑定教务等「点刷新但不会进入 loading」的情况：把按钮恢复可点
                if (!isLoadingSchedule) btnRefresh.isEnabled = true
            }, if (Motion.enabled()) Motion.SHORT else 0L)
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
        // 先同步当前页位置：bind 靠它区分「可见页」与「预加载的相邻页」（B2）
        adapter.setCurrentPosition(displayWk - 1)
        // 仅预加载相邻1页（3页总量），减少tab切换时的初始构建压力
        viewPager.offscreenPageLimit = 1
        // B1 翻页景深：页面内容随滑动缩放/淡入淡出，页内日期栏做反向视差
        viewPager.setPageTransformer(weekPageDepthTransformer)

        // 延迟到下一帧设置 adapter + 当前页，让tab切换动画先完成
        viewPager.post {
            viewPager.adapter = adapter
            setCurrentPageInstant(displayWk - 1)
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.setCurrentPosition(position)
                setDisplayWeek(position + 1)
                updateDateTimeHeader()
                // B2 切周入场：用户滑动时让新页格子入场；程序化瞬跳直接落终态，保证跟手不闪。
                if (instantPageJump || viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    adapter.skipEntranceForCurrent(viewPager, position)
                    // B1 兜底：程序化跳周不经过 onScrolled，手动把当前页景深复位
                    resetCurrentPageDepthTransform()
                } else {
                    adapter.playEntranceForCurrent(viewPager, position)
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager2.SCROLL_STATE_IDLE) return
                // B1 兜底：滑动终点后当前页必须完全恢复 scale=1 / alpha=1（含首尾页）
                resetCurrentPageDepthTransform()
            }
        })
    }

    /** 程序化瞬跳周次：瞬跳期间 onPageSelected 不播课程格子入场（滑杆拖动要跟手、不闪） */
    private fun setCurrentPageInstant(position: Int) {
        instantPageJump = true
        viewPager.setCurrentItem(position.coerceAtLeast(0), false)
        viewPager.post { instantPageJump = false }
    }

    /**
     * B1 周视图翻页景深：横向滑动时页面内容（课程格子区）按滑动进度从 1.04 缩到 1.0、
     * alpha 0.6→1；页内日期栏做 0.35x 反向视差，制造前后两层错位。
     *
     * 只写 transform（scale / alpha / translationX），不改 layoutParams；
     * 落位后由 [resetCurrentPageDepthTransform] 兜底恢复，避免任何页停在缩放/半透明状态。
     */
    private val weekPageDepthTransformer = ViewPager2.PageTransformer { page, position ->
        val content = WeekPagerAdapter.pageContentView(page)
        val header = WeekPagerAdapter.pageDateHeaderView(page)
        if (!Motion.enabled()) {
            content?.let {
                it.scaleX = 1f
                it.scaleY = 1f
                it.alpha = 1f
            }
            header?.translationX = 0f
            return@PageTransformer
        }
        val offset = position.coerceIn(-1f, 1f)
        val depth = abs(offset)
        content?.let {
            val scale = 1f + PAGE_DEPTH_SCALE_DELTA * depth
            it.scaleX = scale
            it.scaleY = scale
            it.alpha = 1f - PAGE_DEPTH_ALPHA_DELTA * depth
        }
        header?.let {
            val width = if (page.width > 0) page.width.toFloat() else viewPager.width.toFloat()
            // 反向视差：页面右移（position>0）时日期栏相对左偏，视觉上落在页面平移后面
            it.translationX = -offset * width * PAGE_DATE_PARALLAX
        }
    }

    /** 把当前页的景深 transform 复位（程序化跳周 / 滑动结束后兜底） */
    private fun resetCurrentPageDepthTransform() {
        if (!::viewPager.isInitialized || !::adapter.isInitialized) return
        val recycler = viewPager.getChildAt(0) as? RecyclerView ?: return
        val page = recycler.findViewHolderForAdapterPosition(viewPager.currentItem)?.itemView ?: return
        WeekPagerAdapter.pageContentView(page)?.let {
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
        }
        WeekPagerAdapter.pageDateHeaderView(page)?.translationX = 0f
    }

    private fun setupObservers() {
        val viewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]
        viewModel.courses.observe(viewLifecycleOwner) { _ ->
            applyScheduleCourses(CourseDataManager.getInstance(requireContext()).getAllCourses())
            // 总课表下数据变化（切学期 / 导入 / 刷新）同样要重建列表，并允许重新播放入场
            if (isOverview) {
                overviewEntrancePlayed = false
                refreshOverview()
                playOverviewEntranceOnce()
            }
        }
        viewModel.overviewMode.observe(viewLifecycleOwner) { applyScheduleMode(it) }
    }

    /**
     * 课程数据落位（B2）：
     * - 更新适配器；
     * - 数据快照引用真正变化时，让当前页重播一次课程格子错峰入场；
     * - LiveData 在切 tab / 重建视图后会把同一份快照再次下发，引用没变就不重播（同一屏只动一次）。
     */
    private fun applyScheduleCourses(courses: List<Course>) {
        allCourses = courses
        if (::adapter.isInitialized) adapter.updateData(courses)
        if (lastEntranceCourses !== courses) {
            lastEntranceCourses = courses
            // loading 遮罩期间的入场会被挡住，等成功卡片收起后再播（见 showLoadSuccess）
            if (!isLoadingSchedule) requestCourseEntranceForDisplayedPage()
        }
    }

    /** 让当前页课程格子重播一次入场（加载遮罩收起后调用，避免入场被遮罩挡住） */
    private fun replayCourseEntranceForCurrentPage() {
        if (!isAdded || view == null) return
        if (!::adapter.isInitialized || !::viewPager.isInitialized) return
        if (viewPager.adapter !== adapter) return
        adapter.replayEntranceForCurrent(viewPager, viewPager.currentItem.coerceAtLeast(0))
    }

    /** 请求当前显示页在下一次绑定时播一次课程格子入场（B2，数据首次可见 / 刷新 / 切学期） */
    private fun requestCourseEntranceForDisplayedPage() {
        if (!::adapter.isInitialized) return
        // adapter 还没挂上 ViewPager 时 currentItem 是 0，用 ViewModel 里记住的显示周推算
        val position = if (viewPager.adapter === adapter) {
            viewPager.currentItem.coerceAtLeast(0)
        } else {
            (getDisplayWeek() - 1).coerceAtLeast(0)
        }
        adapter.requestEntrance(position)
    }

    /** B4 总课表列表出现后错峰入场；同一屏只播一次（数据变化后由调用方重置标记） */
    private fun playOverviewEntranceOnce() {
        if (overviewEntrancePlayed || !Motion.enabled()) return
        overviewEntrancePlayed = true
        rvCourseOverview.doOnNextLayout { Motion.staggerRecycler(rvCourseOverview, fromY = 6f) }
    }

    /**
     * 切换「周课表 ⇄ 总课表」：两者共用顶部信息栏，只互斥周网格与总课表列表。
     * 日期栏在周课表的每一页内部（见 item_date_header.xml），随 viewPager 一起隐藏。
     *
     * B4：不再 visibility 瞬切，改为交叉淡入（旧的轻微缩小淡出、新的放大淡入）；
     * 首次下发 / tab 切回重建时直接落位，不重复播（动画只在"首次可见"播一次）。
     */
    private fun applyScheduleMode(overview: Boolean) {
        val changed = overview != isOverview
        isOverview = overview
        if (!scheduleModeInitialized || !changed) {
            scheduleModeInitialized = true
            viewPager.isVisible = !overview
            viewPager.alpha = 1f
            viewPager.scaleX = 1f
            viewPager.scaleY = 1f
            layoutOverview.isVisible = overview
            layoutOverview.alpha = 1f
            layoutOverview.scaleX = 1f
            layoutOverview.scaleY = 1f
            if (overview) refreshOverview() else updateDateTimeHeader()
            return
        }
        if (overview) {
            refreshOverview()
            Motion.crossFade(viewPager, layoutOverview, outFromScale = 0.98f, inFromScale = 0.98f)
            playOverviewEntranceOnce()
        } else {
            updateDateTimeHeader()
            Motion.crossFade(layoutOverview, viewPager, outFromScale = 0.98f, inFromScale = 0.98f)
        }
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
            val weekInfo = if (semesterLabel != null) "总课表 · $semesterLabel" else "总课表"
            val date = if (overviewFilter == CourseTypeFilter.ALL) {
                "本学期共 $overviewGroupCount 门课程"
            } else {
                "${overviewFilter.label} · 共 $overviewVisibleCount 门"
            }
            setHeaderTexts(weekInfo, date)
            return
        }

        val cal = Calendar.getInstance()
        val dateText = dateFormat.format(cal.time)
        val displayWk = getDisplayWeek()
        val currentWk = getCurrentWeek()

        val rangeStatus = getSemesterRangeStatus()
        val weekText = when {
            rangeStatus < 0 -> "第${displayWk}周 该学期未开始"
            rangeStatus > 0 -> "第${displayWk}周 该学期已结束"
            displayWk == currentWk -> "第${displayWk}周 (本周)"
            else -> "第${displayWk}周"
        }
        val weekInfo = if (semesterLabel != null) "$weekText · $semesterLabel" else weekText
        setHeaderTexts(weekInfo, dateText)
    }

    /**
     * B5 顶部日期 / 周次文字切换：淡出 → 换字 → 淡入，替代翻周瞬间的硬切。
     *
     * 快速连续翻周 / 拖动周次滑杆时（上一轮过渡还没结束），直接换字并淡入，
     * 保证文字跟手；每次切换都会取消上一轮动画并复位透明度，不会残留半透明状态。
     */
    private fun setHeaderTexts(weekInfo: String, date: String) {
        if (!headerTextInitialized || !Motion.enabled()) {
            headerTextInitialized = true
            resetHeaderTextTransition()
            tvWeekInfo.text = weekInfo
            tvDate.text = date
            return
        }
        if (tvWeekInfo.text == weekInfo && tvDate.text == date) return

        val rapid = headerTransitionActive
        headerTransitionGeneration++
        val generation = headerTransitionGeneration
        headerTransitionActive = true
        headerAnimHandler.removeCallbacksAndMessages(null)
        tvWeekInfo.animate().cancel()
        tvDate.animate().cancel()

        if (rapid) {
            // 过渡中又换周：立即换字 + 短淡入（拖动滑杆时日期/周次必须实时跟手）
            tvWeekInfo.text = weekInfo
            tvDate.text = date
            fadeInHeaderTexts(generation)
            return
        }

        val fadeOut = { view: View ->
            view.animate()
                .alpha(0f)
                .setDuration(HEADER_FADE_OUT_MS)
                .setInterpolator(Motion.accelerate)
                .start()
        }
        fadeOut(tvWeekInfo)
        fadeOut(tvDate)
        headerAnimHandler.postDelayed({
            if (generation != headerTransitionGeneration) return@postDelayed
            tvWeekInfo.text = weekInfo
            tvDate.text = date
            fadeInHeaderTexts(generation)
        }, HEADER_FADE_OUT_MS)
    }

    private fun fadeInHeaderTexts(generation: Int) {
        val endAction = {
            if (generation == headerTransitionGeneration) headerTransitionActive = false
        }
        tvWeekInfo.animate()
            .alpha(1f)
            .setDuration(HEADER_FADE_IN_MS)
            .setInterpolator(Motion.standard)
            .withEndAction(endAction)
            .start()
        tvDate.animate()
            .alpha(1f)
            .setDuration(HEADER_FADE_IN_MS)
            .setInterpolator(Motion.standard)
            .start()
    }

    /** 取消未完成的文字过渡并把透明度复位（避免视图销毁 / 动画取消后停在半透明） */
    private fun resetHeaderTextTransition() {
        if (!::tvWeekInfo.isInitialized || !::tvDate.isInitialized) return
        headerTransitionGeneration++
        headerTransitionActive = false
        headerAnimHandler.removeCallbacksAndMessages(null)
        tvWeekInfo.animate().cancel()
        tvDate.animate().cancel()
        tvWeekInfo.alpha = 1f
        tvDate.alpha = 1f
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
        // B6：成功状态弹入（0.86 → 1 + 淡入），给"刷新完成"一个明确的正反馈
        Motion.enter(groupSuccess, duration = Motion.SHORT, fromY = 0f, fromScale = 0.86f)

        // 短暂展示后收起遮罩，恢复交互
        Handler(Looper.getMainLooper()).postDelayed({
            isLoadingSchedule = false
            btnRefresh.isEnabled = true
            overlayLoading.isVisible = false
            resetOverlayState()
            // B2：刷新/导入期间格子入场被成功卡片遮住了，遮罩收起后再播一次，让"数据落位"看得见
            if (!isOverview) replayCourseEntranceForCurrentPage()
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
                    // B2：数据快照变了 → 当前页课程格子重播一次错峰入场
                    applyScheduleCourses(CourseDataManager.getInstance(ctx).getAllCourses())
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
        // B8：菜单自底部滑入 + 遮罩 alpha 渐入的收尾是"下拉 + 遮罩淡出"，由下方统一实现。
        // 这里先声明（关闭按钮 / 点遮罩 / 返回键 / 点当前学期都要复用），弹窗 show 前完成赋值。
        var dismissMenuAnimated: (() -> Unit)? = null

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
        sheetView.findViewById<View>(R.id.btn_menu_close)?.setOnClickListener {
            dismissMenuAnimated?.invoke() ?: dialog.dismiss()
        }

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
            setCurrentPageInstant(target - 1)
            updateDateTimeHeader()
            refreshWeekSlider()
        }
        chipThisWeek = chip
        llWeekAction.addView(chip)
        llWeekAction.addView(chipNowWeek)

        // 气泡显示「第 N 周」，而不是裸数字
        sbWeek.setLabelFormatter { value -> "第 ${value.roundToInt()} 周" }

        // B7 拖动时跟手：滑块本身按 stepSize=1 走离散档位，只有周次真的变了才更新标题/日期/翻页
        sbWeek.addOnChangeListener { _, value, fromUser ->
            // 程序化 setValue（refreshWeekSlider / 回到本周）已经同步过界面，这里不重复处理
            if (!fromUser) return@addOnChangeListener
            val totalWeeks = settingsManager.getTotalWeeks()
            val week = value.roundToInt().coerceIn(1, totalWeeks)
            if (week == getDisplayWeek()) return@addOnChangeListener
            tvWeekLabel.text = "第 $week 周"
            syncWeekAction(week)
            setDisplayWeek(week)
            setCurrentPageInstant(week - 1)
            updateDateTimeHeader()
        }
        // B7 松手吸附后：目标周胶囊 / 周标题做一次 spring 回弹 + 轻触感，确认已经落在这一周
        sbWeek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                val week = slider.value.roundToInt().coerceIn(1, settingsManager.getTotalWeeks())
                slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (!Motion.enabled()) return
                Motion.pulse(tvWeekLabel, scale = 1.05f, duration = Motion.SHORT)
                val targetChip: View? = if (week == calculateCurrentWeek()) chipNowWeek else chipThisWeek
                if (targetChip?.isVisible == true) {
                    Motion.pulse(targetChip, scale = 1.04f, duration = Motion.SHORT)
                }
            }
        })
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
                // B8：学期色块按压缩放反馈（沿用波浪入场；按压会取消入场动画，
                // 所以按下时兜底把透明度拉满，避免色块停在半透明）
                val pressFeedback = Motion.press(item, pressedScale = 0.94f)
                item.setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) v.alpha = 1f
                    pressFeedback.onTouch(v, event)
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
                        dismissMenuAnimated?.invoke() ?: dialog.dismiss()
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
                    setCurrentPageInstant(0)
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
                                    adapter.setCurrentPosition(0)
                                    // 刚导入完才有学期开始日期，日期栏要按新日期渲染
                                    syncWeekContextToAdapter()
                                    viewPager.adapter = adapter
                                    val importedCourses = CourseDataManager.getInstance(ctx).getAllCourses()
                                    adapter.updateData(importedCourses)
                                    lastEntranceCourses = importedCourses
                                    // B2 切学期入场：loading 遮罩期间先不播，等成功卡片收起后再播（见 showLoadSuccess）
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
                                        setCurrentPageInstant(prevWeek - 1)
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

        // 弹窗展示后，学期色块按顺序波浪式入场（保留原有波浪；系统关闭动画时直接落终态）
        dialog.setOnShowListener {
            if (!Motion.enabled()) {
                semesterItems.forEach {
                    it.alpha = 1f
                    it.scaleX = 1f
                    it.scaleY = 1f
                }
                groupAppearance.alpha = 1f
                return@setOnShowListener
            }
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
        // B8：遮罩用系统 dim 层逐帧渐变（窗口配置见 SheetWindow），内容层不再叠自绘遮罩
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
            // 窗口现在铺到手势条下面：容器底部留出导航条高度，弹层整体抬到手势条上方
            // （与「窗口被系统缩到导航条以上」时的视觉位置一致）
            container.setPadding(0, 0, 0, navigationBarBottom)
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

        // ── B8 退场：弹层下拉 + 遮罩 alpha 淡出（干脆利落），结束后再 dismiss ──
        var menuClosing = false
        fun playMenuExit() {
            if (menuClosing) return
            menuClosing = true
            if (!Motion.enabled()) {
                dialog.dismiss()
                return
            }
            sheetView.animate().cancel()
            sheetView.animate()
                .translationY(sheetHeight.toFloat())
                .alpha(0f)
                .setDuration(MENU_EXIT_MS)
                .setInterpolator(Motion.accelerate)
                .withEndAction { dialog.dismiss() }
                .start()
            ValueAnimator.ofFloat(
                com.cherry.wakeupschedule.ui.component.SheetWindow.currentDim(dialog), 0f
            ).apply {
                duration = MENU_EXIT_MS
                interpolator = Motion.accelerate
                addUpdateListener {
                    com.cherry.wakeupschedule.ui.component.SheetWindow.setDim(
                        dialog, it.animatedValue as Float
                    )
                }
                start()
            }
        }
        dismissMenuAnimated = { playMenuExit() }
        // 返回键也走同一套退场动画（系统默认返回是瞬时消失，会和入场动画手感不一致）
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                playMenuExit()
                true
            } else {
                false
            }
        }

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
            setOnClickListener { playMenuExit() }
        })
        container.addView(sheetView)
        dialog.setContentView(container)
        // 入场起始态在 show 之前设好，避免第一帧闪在终态
        sheetView.translationY = sheetHeight.toFloat()

        // 窗口铺满整屏（含状态栏/手势条），自绘遮罩才能盖住系统栏区域
        com.cherry.wakeupschedule.ui.component.SheetWindow.configure(dialog)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        // B8 入场：遮罩 alpha 渐显 + 底部弹层自下滑入
        if (Motion.enabled()) {
            ValueAnimator.ofFloat(0f, com.cherry.wakeupschedule.ui.component.SheetWindow.DIM_AMOUNT).apply {
                duration = Motion.MEDIUM
                interpolator = Motion.standard
                addUpdateListener {
                    com.cherry.wakeupschedule.ui.component.SheetWindow.setDim(
                        dialog, it.animatedValue as Float
                    )
                }
                start()
            }
            sheetView.animate()
                .translationY(0f)
                .setDuration(MENU_ENTER_MS)
                .setInterpolator(Motion.emphasizedDecelerate)
                .start()
        } else {
            com.cherry.wakeupschedule.ui.component.SheetWindow.setDim(
                dialog, com.cherry.wakeupschedule.ui.component.SheetWindow.DIM_AMOUNT
            )
            sheetView.translationY = 0f
        }
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
        // 顶部文字过渡的延迟换字回调必须清掉，避免视图销毁后还去改 text / alpha
        resetHeaderTextTransition()
    }

}
