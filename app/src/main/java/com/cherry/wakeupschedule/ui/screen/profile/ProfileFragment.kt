package com.cherry.wakeupschedule.ui.screen.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.cherry.wakeupschedule.ui.feedback.AppToast
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.cherry.wakeupschedule.AboutActivity
import com.cherry.wakeupschedule.AppearanceActivity
import com.cherry.wakeupschedule.BindJwxtActivity
import com.cherry.wakeupschedule.FontSizeActivity
import com.cherry.wakeupschedule.ProfileActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ScheduleAppearanceActivity
import com.cherry.wakeupschedule.TimeTableEditActivity
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.ThemeModeManager
import com.cherry.wakeupschedule.service.UiScaleManager
import com.cherry.wakeupschedule.service.UpdateService
import com.cherry.wakeupschedule.ui.component.SemesterWheelDialog
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import com.gxu.jwxt.model.Term
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ProfileFragment : Fragment() {

    private lateinit var settingsManager: SettingsManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())
        setupClickListeners(view)
        updateDisplay()
        updateAccountSection()
    }

    private fun setupClickListeners(view: View) {
        view.findViewById<View>(R.id.item_bind_jwxt).setOnClickListener {
            startActivity(Intent(requireContext(), BindJwxtActivity::class.java))
        }

        view.findViewById<View>(R.id.item_profile).setOnClickListener {
            if (!JwxtAuthManager.isBound()) {
                AppToast.warn(requireContext(), "请先绑定教务账号")
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
                    AppToast.success(requireContext(), "已解绑教务账号")
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

        // 课表：跳转到独立的课表外观设置页（课程格子高度）
        view.findViewById<View>(R.id.item_schedule_appearance).setOnClickListener {
            startActivity(Intent(requireContext(), ScheduleAppearanceActivity::class.java))
        }

        // 字体大小：整体 UI 缩放档位（字体与界面尺寸一起变）
        view.findViewById<View>(R.id.item_font_size).setOnClickListener {
            startActivity(Intent(requireContext(), FontSizeActivity::class.java))
        }

        view.findViewById<View>(R.id.item_time_table).setOnClickListener {
            startActivity(Intent(requireContext(), TimeTableEditActivity::class.java))
        }

        view.findViewById<View>(R.id.item_about).setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        view.findViewById<View>(R.id.item_feedback).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("2908451607@qq.com"))
                    putExtra(Intent.EXTRA_SUBJECT, "西大课栈 应用反馈")
                }
                startActivity(Intent.createChooser(intent, "发送邮件"))
            } catch (e: Exception) {
                AppToast.error(requireContext(), "未找到邮件应用")
            }
        }
    }

    private fun showSemesterDialog() {
        val semesters = SemesterManager.getAll()
        if (semesters.isEmpty()) {
            AppToast.warn(requireContext(), "请先绑定教务账号")
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
            tv?.text = "未设置"
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
        // 静默检查完成后红点即时刷新（同一时刻仅本页面在前台注册）
        UpdateService.hintChangedListener = { refreshUpdateHint(requireView()) }
    }

    override fun onPause() {
        super.onPause()
        UpdateService.hintChangedListener = null
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
}
