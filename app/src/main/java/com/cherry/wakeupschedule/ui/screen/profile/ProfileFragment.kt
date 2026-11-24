package com.cherry.wakeupschedule.ui.screen.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.cherry.wakeupschedule.ui.theme.PageBackground
import com.cherry.wakeupschedule.ui.feedback.AppToast
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.cherry.wakeupschedule.AboutActivity
import com.cherry.wakeupschedule.AppearanceActivity
import com.cherry.wakeupschedule.BindJwxtActivity
import com.cherry.wakeupschedule.FontSizeActivity
import com.cherry.wakeupschedule.ProfileActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.TimeTableEditActivity
import com.cherry.wakeupschedule.WidgetCenterActivity
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.ThemeModeManager
import com.cherry.wakeupschedule.service.UiScaleManager
import com.cherry.wakeupschedule.service.UpdateService
import com.cherry.wakeupschedule.ui.browser.InAppBrowser
import com.cherry.wakeupschedule.ui.component.SemesterWheelDialog
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.screen.schedule.ScheduleAppearanceSheet
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import com.google.android.material.card.MaterialCardView
import com.gxu.jwxt.model.Term
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 反馈建议问卷（腾讯问卷）：点击「反馈建议」用内置浏览器填写 */
private const val FEEDBACK_SURVEY_URL = "https://wj.qq.com/s2/28554281/u3po/"

class ProfileFragment : Fragment() {

    private lateinit var settingsManager: SettingsManager

    /** 分组入场是否已播过：同一屏只播一次（转屏/从二级页返回不重播） */
    private var entrancePlayed = false

    /** 更新红点刷新回调：静默检查 / 手动检查完成后即时刷新「关于应用」行 */
    private val updateHintListener: () -> Unit = {
        view?.let { refreshUpdateHint(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        PageBackground.apply(view)
        entrancePlayed = savedInstanceState?.getBoolean(KEY_ENTRANCE_PLAYED) ?: false
        settingsManager = SettingsManager(requireContext())
        setupClickListeners(view)
        updateDisplay()
        updateAccountSection()
        playGroupEntranceIfNeeded(view)
    }

    private fun setupClickListeners(view: View) {
        view.findViewById<View>(R.id.item_bind_jwxt).setOnClickListener {
            startActivity(Intent(requireContext(), BindJwxtActivity::class.java))
        }

        view.findViewById<View>(R.id.item_profile).setOnClickListener {
            if (!JwxtAuthManager.isBound()) {
                AppToast.warn(requireContext(), getString(R.string.common_bind_first))
                return@setOnClickListener
            }
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        // 更新登录密码
        view.findViewById<View>(R.id.btn_edit_account).setOnClickListener {
            startActivity(Intent(requireContext(), BindJwxtActivity::class.java))
        }

        // 解绑
        view.findViewById<View>(R.id.btn_unbind).setOnClickListener {
            StyledDialog.Builder(requireContext())
                .title("解绑教务账号")
                .message("确定要解绑教务账号吗？解绑后个人信息将被清除。")
                .dangerButton("解绑") {
                    JwxtAuthManager.unbind()
                    updateAccountSection()
                    updateDisplay()
                    AppToast.success(requireContext(), getString(R.string.profile_unbound_toast))
                }
                .negativeButton("取消")
                .show()
        }

        view.findViewById<View>(R.id.item_semester).setOnClickListener {
            showSemesterDialog()
        }


        // 主题：跳转到独立的主题设置页（浅色/深色 + 自动切换）
        view.findViewById<View>(R.id.item_theme_mode).setOnClickListener {
            startActivity(Intent(requireContext(), AppearanceActivity::class.java))
        }

        // 课表外观：跳到课表页签弹出悬浮面板（改动实时生效在面板上方的课表），
        // 待打开标志由 ScheduleFragment.onResume 消费
        view.findViewById<View>(R.id.item_schedule_appearance).setOnClickListener {
            ScheduleAppearanceSheet.pendingAutoOpen = true
            goTab(R.id.nav_schedule)
        }

        // 字体大小：整体 UI 缩放档位（字体与界面尺寸一起变）
        view.findViewById<View>(R.id.item_font_size).setOnClickListener {
            startActivity(Intent(requireContext(), FontSizeActivity::class.java))
        }

        view.findViewById<View>(R.id.item_time_table).setOnClickListener {
            startActivity(Intent(requireContext(), TimeTableEditActivity::class.java))
        }

        view.findViewById<View>(R.id.item_widget).setOnClickListener {
            startActivity(Intent(requireContext(), WidgetCenterActivity::class.java))
        }

        view.findViewById<View>(R.id.item_about).setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        // 反馈建议：内置浏览器打开腾讯问卷（不再走邮件、也不再甩到外部浏览器）
        view.findViewById<View>(R.id.item_feedback).setOnClickListener {
            val opened = InAppBrowser.open(
                requireContext(),
                FEEDBACK_SURVEY_URL,
                getString(R.string.profile_feedback)
            )
            if (!opened) {
                AppToast.error(requireContext(), getString(R.string.profile_no_browser_app))
            }
        }

        // 行按压统一走 Motion.press（与工具卡/底部导航同一手感）；
        // 这些行内部没有其它 scale 动画，不会互相抢 transform
        listOf(
            R.id.item_bind_jwxt, R.id.item_profile, R.id.btn_edit_account, R.id.btn_unbind,
            R.id.item_semester, R.id.item_theme_mode, R.id.item_schedule_appearance,
            R.id.item_font_size, R.id.item_time_table, R.id.item_widget,
            R.id.item_about, R.id.item_feedback
        ).forEach { id -> view.findViewById<View>(id)?.let { Motion.press(it) } }
    }

    /**
     * 首次进入「我的」页：分组卡片按 60ms 步进依次上浮淡入。
     * 布局里卡片没有单独 id，按 ScrollView 内容列的子项类型取（AppSectionHeader 不参与）。
     */
    private fun playGroupEntranceIfNeeded(root: View) {
        if (entrancePlayed) return
        entrancePlayed = true
        if (!Motion.enabled()) return
        val cards = sectionCards(root)
        if (cards.isEmpty()) return
        Motion.stagger(
            cards,
            step = GROUP_STAGGER_STEP,
            duration = Motion.ENTRANCE,
            fromY = 10f
        )
    }

    private fun sectionCards(root: View): List<View> {
        val scrollView = (root as? ViewGroup)?.let { parent ->
            (0 until parent.childCount)
                .mapNotNull { parent.getChildAt(it) }
                .filterIsInstance<ScrollView>()
                .firstOrNull()
        } ?: return emptyList()
        val column = scrollView.getChildAt(0) as? ViewGroup ?: return emptyList()
        return (0 until column.childCount)
            .mapNotNull { column.getChildAt(it) }
            .filterIsInstance<MaterialCardView>()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 转屏重建后不再重播分组入场
        outState.putBoolean(KEY_ENTRANCE_PLAYED, entrancePlayed)
    }

    private fun showSemesterDialog() {
        val semesters = SemesterManager.getAll()
        if (semesters.isEmpty()) {
            AppToast.warn(requireContext(), getString(R.string.common_bind_first))
            return
        }

        // 循环滚轮式学期选择：已导入有风景图案、未导入点击即导入并显示 loading
        SemesterWheelDialog(requireContext()) { updateDisplay() }.show()
    }

    private fun updateDisplay() {
        // 同步开关状态（不触发监听器提示）

        // 主题模式展示（从主题页返回时刷新）
        updateThemeModeDisplay(requireView())

        // 字体大小档位展示（从字体大小页返回时刷新）
        updateFontSizeDisplay(requireView())

        val tv = view?.findViewById<TextView>(R.id.tv_semester_value)
        val current = SemesterManager.getCurrent()
        if (current != null) {
            tv?.text = "${current.label} · ${current.academicYear}学年 ${current.termName}"
        } else {
            tv?.text = getString(R.string.common_not_set)
        }

        val startMs = settingsManager.getSemesterStartDate()
        val totalWeeks = settingsManager.getTotalWeeks()
        val tvRange = view?.findViewById<TextView>(R.id.tv_semester_date_range)
        val tvWeeks = view?.findViewById<TextView>(R.id.tv_total_weeks)
        if (startMs > 0 && totalWeeks > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val endCal = Calendar.getInstance().apply {
                timeInMillis = startMs
                add(Calendar.DAY_OF_YEAR, totalWeeks * 7 - 1)
            }
            tvRange?.text = "${sdf.format(startMs)} ~ ${sdf.format(endCal.time)}"
            tvWeeks?.text = "${totalWeeks}周"
        } else {
            tvRange?.text = "刷新课表后获取"
            tvWeeks?.text = "—"
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccountSection()
        updateDisplay()
        refreshUpdateHint(requireView())
        // 静默检查完成后红点即时刷新（MainActivity 底部导航红点也注册了同一份回调）
        UpdateService.addHintChangedListener(updateHintListener)
    }

    override fun onPause() {
        super.onPause()
        UpdateService.removeHintChangedListener(updateHintListener)
    }

    /** 有未处理的新版本更新时，在"关于应用"行右侧显示红点 */
    private fun refreshUpdateHint(view: View) {
        view.findViewById<View>(R.id.dot_update_hint).visibility =
            if (settingsManager.hasNewUpdateHint()) View.VISIBLE else View.GONE
    }

    private fun updateAccountSection() {
        val isBound = JwxtAuthManager.isBound()
        val view = requireView()

        view.findViewById<View>(R.id.item_bind_jwxt).visibility =
            if (isBound) View.GONE else View.VISIBLE

        view.findViewById<View>(R.id.item_account_info).visibility =
            if (isBound) View.VISIBLE else View.GONE

        view.findViewById<View>(R.id.btn_unbind).visibility =
            if (isBound) View.VISIBLE else View.GONE

        if (isBound) {
            val profile = JwxtAccountManager.getProfile()
            val tvName = view.findViewById<TextView>(R.id.tv_account_name)
            val tvId = view.findViewById<TextView>(R.id.tv_account_id)
            tvName.text = profile?.name ?: JwxtAuthManager.getBoundUsername()
            tvId.text = if (profile?.studentId != null) "学号: ${profile.studentId}" else "已绑定"
        }
    }

    private fun updateThemeModeDisplay(view: View) {
        val tv = view.findViewById<TextView>(R.id.tv_theme_mode_value)
        tv?.text = ThemeModeManager.effectiveLabel(requireContext())
    }

    private fun updateFontSizeDisplay(view: View) {
        val tv = view.findViewById<TextView>(R.id.tv_font_size_value)
        tv?.text = UiScaleManager.labelOf(requireContext())
    }

    /** 顶层页签式跳转（与底部导航一致的栈行为，照 ToolsFragment.goTab） */
    private fun goTab(id: Int) {
        val nav = findNavController()
        nav.navigate(id, null, navOptions {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        })
    }

    private companion object {
        /** 分组入场是否已播过的实例状态 key */
        private const val KEY_ENTRANCE_PLAYED = "profile_entrance_played"

        /** 分组卡片之间的错峰步进 */
        private const val GROUP_STAGGER_STEP = 60L
    }
}
