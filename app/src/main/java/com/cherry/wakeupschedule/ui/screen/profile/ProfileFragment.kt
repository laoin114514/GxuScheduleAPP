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
import com.cherry.wakeupschedule.BindJwxtActivity
import com.cherry.wakeupschedule.ProfileActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.SchoolImportActivity
import com.cherry.wakeupschedule.TimeTableEditActivity
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtImportService
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
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

        view.findViewById<View>(R.id.item_semester).setOnClickListener {
            showSemesterDialog()
        }

        view.findViewById<View>(R.id.item_week).setOnClickListener {
            showWeekDialog()
        }

        view.findViewById<View>(R.id.btn_fetch_semester_info).setOnClickListener {
            fetchSemesterInfo()
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

        view.findViewById<View>(R.id.item_import).setOnClickListener {
            startActivity(Intent(requireContext(), SchoolImportActivity::class.java))
        }

        view.findViewById<View>(R.id.item_export).setOnClickListener {
            // Open export options — re-use MainActivity export logic? For now, just show a toast
            Toast.makeText(requireContext(), "导出功能请从课表页操作", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.item_about).setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        view.findViewById<View>(R.id.item_feedback).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("Yngu196@qq.com"))
                    putExtra(Intent.EXTRA_SUBJECT, "Schedule 应用反馈")
                }
                startActivity(Intent.createChooser(intent, "发送邮件"))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "未找到邮件应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSemesterDialog() {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
        val currentAcademicStart = if (currentMonth >= 9) currentYear else currentYear - 1

        val academicYears = (-1 until 9).map { offset ->
            val start = currentAcademicStart - offset
            "${start}-${start + 1}学年"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("选择学年")
            .setItems(academicYears) { _, which ->
                showTermPicker(academicYears[which])
            }
            .setPositiveButton("管理学期") { _, _ -> showManageSemestersDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTermPicker(academicYear: String) {
        val terms = arrayOf("第一学期", "第二学期")
        AlertDialog.Builder(requireContext())
            .setTitle("选择学期 — $academicYear")
            .setItems(terms) { _, which ->
                val semester = "$academicYear ${terms[which]}"
                settingsManager.setCurrentSemester(semester)
                settingsManager.addCustomSemester(semester)
                updateDisplay()
                Toast.makeText(requireContext(), "已切换至: $semester", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showManageSemestersDialog() {
        val semesters = settingsManager.getCustomSemesters().toMutableList()
        val currentSemester = settingsManager.getCurrentSemester()
        if (semesters.isEmpty()) {
            Toast.makeText(requireContext(), "没有可管理的学期", Toast.LENGTH_SHORT).show()
            return
        }
        val displaySemesters = semesters.map {
            if (it == currentSemester) "$it (当前)" else it
        }.toMutableList()

        val listView = android.widget.ListView(requireContext())
        listView.adapter = android.widget.ArrayAdapter(requireContext(),
            android.R.layout.simple_list_item_1, displaySemesters)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("管理学期 (长按删除)")
            .setView(listView)
            .setPositiveButton("新增") { _, _ -> showAddSemesterDialog() }
            .setNegativeButton("关闭", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            settingsManager.setCurrentSemester(semesters[position])
            updateDisplay()
            Toast.makeText(requireContext(), "已切换到: ${semesters[position]}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val toDelete = semesters[position]
            if (toDelete == currentSemester) {
                Toast.makeText(requireContext(), "不能删除当前正在使用的学期", Toast.LENGTH_SHORT).show()
                return@setOnItemLongClickListener true
            }
            AlertDialog.Builder(requireContext())
                .setTitle("删除学期")
                .setMessage("确定要删除学期 \"$toDelete\" 吗？")
                .setPositiveButton("删除") { _, _ ->
                    settingsManager.removeCustomSemester(toDelete)
                    updateDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        dialog.show()
    }

    private fun showAddSemesterDialog() {
        val editView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_text, null)
        val editText = editView.findViewById<android.widget.EditText>(R.id.et_input)
        editText.hint = "例如: 2024-2025学年 第一学期"

        AlertDialog.Builder(requireContext())
            .setTitle("新增学期")
            .setView(editView)
            .setPositiveButton("添加") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    settingsManager.addCustomSemester(name)
                    settingsManager.setCurrentSemester(name)
                    updateDisplay()
                    Toast.makeText(requireContext(), "已添加: $name", Toast.LENGTH_SHORT).show()
                }
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
        tv?.text = settingsManager.getCurrentSemester()
        val tvWeek = view?.findViewById<TextView>(R.id.tv_week_value)
        // 自动计算当前周，而不是使用存储的默认值
        val startMs = settingsManager.getSemesterStartDate()
        val currentWeek = if (startMs > 0) {
            val diffDays = ((System.currentTimeMillis() - startMs) / 86400000L).toInt()
            (diffDays / 7 + 1).coerceIn(1, settingsManager.getTotalWeeks())
        } else {
            settingsManager.getDefaultWeek()
        }
        tvWeek?.text = "第${currentWeek}周"

        val totalWeeks = settingsManager.getTotalWeeks()
        val tvRange = view?.findViewById<TextView>(R.id.tv_semester_date_range)
        val tvWeeks = view?.findViewById<TextView>(R.id.tv_total_weeks)
        if (startMs > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val endCal = Calendar.getInstance().apply {
                timeInMillis = startMs
                add(Calendar.DAY_OF_YEAR, totalWeeks * 7 - 1)
            }
            tvRange?.text = "${sdf.format(startMs)} ~ ${sdf.format(endCal.time)}"
            tvWeeks?.text = "${totalWeeks}周"
        } else {
            tvRange?.text = "未获取"
            tvWeeks?.text = "20周"
        }
    }

    private fun fetchSemesterInfo() {
        if (!JwxtAuthManager.isBound()) {
            Toast.makeText(requireContext(), "请先绑定教务账号", Toast.LENGTH_SHORT).show()
            return
        }

        val btn = view?.findViewById<View>(R.id.btn_fetch_semester_info)
        btn?.isEnabled = false

        val cached = JwxtAccountManager.getCachedProfile()
        val classId = cached?.className ?: ""
        val gradeCode = cached?.grade ?: ""
        val majorCode = cached?.major ?: ""

        if (classId.isEmpty() || gradeCode.isEmpty()) {
            Toast.makeText(requireContext(), "请先获取个人信息以获取班级信息", Toast.LENGTH_SHORT).show()
            btn?.isEnabled = true
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val selectedSemester = settingsManager.getCurrentSemester()
            val (year, termCode) = JwxtImportService.getYearTermForSemester(selectedSemester)
            val term = Term.fromCode(termCode) ?: Term.SPRING

            val result = JwxtAuthManager.doWithAuth { client ->
                client.schedule().classDetail(year, term, classId, gradeCode, majorCode)
            }

            withContext(Dispatchers.Main) {
                btn?.isEnabled = true
                result.onSuccess { resp ->
                    val startStr = resp.semesterStartDate
                    val weeks = resp.weeks?.size ?: 0
                    if (startStr != null && weeks > 0) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val startMs = sdf.parse(startStr)?.time ?: 0
                        settingsManager.setSemesterStartDate(startMs)
                        settingsManager.setTotalWeeks(weeks)
                        updateDisplay()
                        Toast.makeText(requireContext(), "已获取：${startStr}，共${weeks}周", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "获取失败：数据不完整", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { e ->
                    Toast.makeText(requireContext(), "获取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
