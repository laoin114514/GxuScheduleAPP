package com.cherry.wakeupschedule.ui.screen.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
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
import com.cherry.wakeupschedule.ui.theme.ThemeManager
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
            AlertDialog.Builder(requireContext())
                .setTitle("解绑教务账号")
                .setMessage("确定要解绑教务账号吗？解绑后个人信息将被清除。")
                .setPositiveButton("解绑") { _, _ ->
                    JwxtAuthManager.unbind()
                    updateAccountSection()
                    Toast.makeText(requireContext(), "已解绑教务账号", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        view.findViewById<View>(R.id.item_semester).setOnClickListener {
            showSemesterDialog()
        }

        view.findViewById<View>(R.id.item_week).setOnClickListener {
            showWeekDialog()
        }


        view.findViewById<View>(R.id.item_theme_palette).setOnClickListener {
            val names = ThemeManager.paletteNames().toTypedArray()
            val currentIndex = ThemeManager.getPaletteIndex(requireContext())
            AlertDialog.Builder(requireContext())
                .setTitle("选择主题色板")
                .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                    ThemeManager.setPaletteIndex(which, requireContext())
                    Toast.makeText(requireContext(), "已选择: ${names[which]}", Toast.LENGTH_SHORT).show()
                    requireActivity().recreate()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        view.findViewById<View>(R.id.item_alarm).setOnClickListener {
            // Open alarm settings
            AlertDialog.Builder(requireContext())
                .setTitle("课前提醒")
                .setItems(arrayOf("开启课前提醒", "关闭课前提醒")) { _, which ->
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
        val labels = semesters.map { s ->
            val mark = if (s.sortOrder == currentIndex) "  ← 当前" else ""
            "${s.label}  (${s.academicYear}学年 ${s.termName})$mark"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("选择当前学期")
            .setSingleChoiceItems(labels, currentIndex.coerceAtLeast(0)) { dialog, which ->
                settingsManager.setCurrentSemesterIndex(which)
                updateDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showWeekDialog() {
        val options = arrayOf("设置学期开始日期（自动计算当前周）", "设置当前周")
        AlertDialog.Builder(requireContext())
            .setTitle("周次设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSemesterStartDatePicker()
                    1 -> showCurrentWeekPicker()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        val weeks = (1..20).map { "第${it}周" }.toTypedArray()
        val semesterStartDate = settingsManager.getSemesterStartDate()
        val currentWeek = if (semesterStartDate > 0) {
            ((System.currentTimeMillis() - semesterStartDate) / 86400000L).toInt() / 7 + 1
        } else settingsManager.getDefaultWeek()

        AlertDialog.Builder(requireContext())
            .setTitle("设置当前周（将调整学期开始日期）")
            .setSingleChoiceItems(weeks, currentWeek.coerceIn(1, 20) - 1) { dialog, which ->
                val selectedWeek = which + 1
                val daysToSubtract = (selectedWeek - 1) * 7L
                settingsManager.setSemesterStartDate(System.currentTimeMillis() - daysToSubtract * 86400000L)
                settingsManager.setDefaultWeek(selectedWeek)
                updateDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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
}
