package com.cherry.wakeupschedule.ui.screen.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.AboutActivity
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.BindJwxtActivity
import com.cherry.wakeupschedule.ProfileActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.TimeTableEditActivity
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtImportService
import com.cherry.wakeupschedule.service.SemesterManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.component.SelectOption
import com.cherry.wakeupschedule.ui.component.SelectionDialog
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.widget.ScheduleWidgetUpdateService
import com.gxu.jwxt.model.Term
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                Toast.makeText(requireContext(), "请先绑定教务账号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        // 解绑
        view.findViewById<View>(R.id.btn_unbind).setOnClickListener {
            StyledDialog.Builder(requireContext())
                .title("解绑教务账号")
                .message("确定要解绑教务账号吗？解绑后个人信息将被清除。")
                .positiveButton("解绑") {
                    JwxtAuthManager.unbind()
                    updateAccountSection()
                    updateDisplay()
                    Toast.makeText(requireContext(), "已解绑教务账号", Toast.LENGTH_SHORT).show()
                }
                .negativeButton("取消")
                .show()
        }

        view.findViewById<View>(R.id.item_semester).setOnClickListener {
            showSemesterDialog()
        }

        view.findViewById<View>(R.id.item_week).setOnClickListener {
            showWeekDialog()
        }


        view.findViewById<View>(R.id.item_theme_palette).visibility = View.GONE

        // 更新外观当前值
        updateThemeModeDisplay(view)

        view.findViewById<View>(R.id.item_theme_mode).setOnClickListener {
            showThemeModeDialog()
        }

        view.findViewById<View>(R.id.item_alarm).setOnClickListener {
            StyledDialog.Builder(requireContext())
                .title("课前提醒")
                .items(arrayOf("开启课前提醒", "关闭课前提醒")) { which ->
                    settingsManager.setAlarmEnabled(which == 0)
                    Toast.makeText(requireContext(),
                        if (which == 0) "课前提醒已开启" else "课前提醒已关闭",
                        Toast.LENGTH_SHORT).show()
                }
                .show()
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
                Toast.makeText(requireContext(), "未找到邮件应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSemesterDialog() {
        val semesters = SemesterManager.getAll()
        if (semesters.isEmpty()) {
            Toast.makeText(requireContext(), "请先绑定教务账号", Toast.LENGTH_SHORT).show()
            return
        }

        val currentIndex = settingsManager.getCurrentSemesterIndex()
        val options = semesters.map { s ->
            SelectOption(
                label = "${s.label}  (${s.academicYear}学年 ${s.termName})",
                subtitle = if (s.sortOrder == currentIndex) "← 当前" else null
            )
        }

        SelectionDialog.show(
            context = requireContext(),
            title = "选择当前学期",
            options = options,
            selectedIndex = currentIndex.coerceAtLeast(0),
            onSelected = { index ->
                settingsManager.setCurrentSemesterIndex(index)
                // 切换学期后重新加载对应课表
                CourseDataManager.getInstance(requireContext()).switchSemester(semesters[index].id)
                // 若该学期日期信息为空，从教务获取课表
                val sem = semesters[index]
                if (sem.startDate == 0L || sem.totalWeeks == 0) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        JwxtImportService.fetchAndSaveScheduleForSemester(requireContext(), sem)
                    }
                }
                updateDisplay()
            }
        )
    }

    private fun showWeekDialog() {
        val options = listOf(
            SelectOption(label = "设置学期开始日期（自动计算当前周）"),
            SelectOption(label = "直接设置当前周")
        )
        SelectionDialog.show(
            context = requireContext(),
            title = "周次设置",
            options = options,
            selectedIndex = 0,
            onSelected = { index ->
                when (index) {
                    0 -> showSemesterStartDatePicker()
                    1 -> showCurrentWeekPicker()
                }
            }
        )
    }

    private fun showSemesterStartDatePicker() {
        val calendar = Calendar.getInstance()
        val currentStartDate = settingsManager.getSemesterStartDate()
        if (currentStartDate > 0) calendar.timeInMillis = currentStartDate

        android.app.DatePickerDialog(requireContext(),
            { _, year, month, dayOfMonth ->
                val sel = Calendar.getInstance()
                sel.set(year, month, dayOfMonth, 0, 0, 0)
                sel.set(Calendar.MILLISECOND, 0)
                settingsManager.setSemesterStartDate(sel.timeInMillis)
                val diffDays = ((System.currentTimeMillis() - sel.timeInMillis) / 86400000L).toInt()
                val week = (diffDays / 7 + 1).coerceIn(1, 20)
                Toast.makeText(requireContext(), "学期开始日期已设置，当前为第${week}周", Toast.LENGTH_LONG).show()
                updateDisplay()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showCurrentWeekPicker() {
        val weeks = (1..20).map { SelectOption(label = "第${it}周") }
        val semesterStartDate = settingsManager.getSemesterStartDate()
        val currentWeek = if (semesterStartDate > 0) {
            ((System.currentTimeMillis() - semesterStartDate) / 86400000L).toInt() / 7 + 1
        } else settingsManager.getDefaultWeek()

        SelectionDialog.show(
            context = requireContext(),
            title = "设置当前周（将调整学期开始日期）",
            options = weeks,
            selectedIndex = currentWeek.coerceIn(1, 20) - 1,
            onSelected = { index ->
                val selectedWeek = index + 1
                val daysToSubtract = (selectedWeek - 1) * 7L
                settingsManager.setSemesterStartDate(System.currentTimeMillis() - daysToSubtract * 86400000L)
                settingsManager.setDefaultWeek(selectedWeek)
                updateDisplay()
            }
        )
    }

    private fun updateDisplay() {
        val tv = view?.findViewById<TextView>(R.id.tv_semester_value)
        val current = SemesterManager.getCurrent()
        if (current != null) {
            tv?.text = "${current.label} · ${current.academicYear}学年 ${current.termName}"
        } else {
            tv?.text = "未设置"
        }

        val tvWeek = view?.findViewById<TextView>(R.id.tv_week_value)
        val startMs = settingsManager.getSemesterStartDate()
        val currentWeek = if (startMs > 0) {
            val diffDays = ((System.currentTimeMillis() - startMs) / 86400000L).toInt()
            (diffDays / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks().coerceAtLeast(1))
        } else {
            settingsManager.getDefaultWeek()
        }
        tvWeek?.text = "第${currentWeek}周"

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

    private fun showThemeModeDialog() {
        val modes = listOf("浅色", "深色", "跟随系统")
        val modeKeys = listOf("light", "dark", "system")
        val currentMode = settingsManager.getThemeMode()
        val currentIndex = modeKeys.indexOf(currentMode).coerceAtLeast(0)

        val options = modes.map { SelectOption(label = it) }
        SelectionDialog.show(
            context = requireContext(),
            title = "外观",
            options = options,
            selectedIndex = currentIndex,
            onSelected = { index ->
                val newMode = modeKeys[index]
                settingsManager.setThemeMode(newMode)
                applyThemeMode(newMode)
                updateThemeModeDisplay(requireView())
                Toast.makeText(requireContext(), "已切换至: ${modes[index]}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun updateThemeModeDisplay(view: View) {
        val label = when (settingsManager.getThemeMode()) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }
        val tv = view.findViewById<TextView>(R.id.tv_theme_mode_value)
        tv?.text = label
    }
}
