package com.cherry.wakeupschedule

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.databinding.ActivityTimeTableEditBinding
import com.cherry.wakeupschedule.service.TimeTableManager
import com.cherry.wakeupschedule.ui.component.SelectOption
import com.cherry.wakeupschedule.ui.component.SelectionDialog
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

class TimeTableEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimeTableEditBinding
    private lateinit var timeTableManager: TimeTableManager

    private val timeSlotViews = mutableListOf<TimeSlotView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityTimeTableEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        timeTableManager = TimeTableManager.getInstance(this)

        setupPageHeader(binding.toolbar, "编辑时间表")
        setupButtons()
        loadAndDisplayTimeSlots()
    }

    private fun setupButtons() {
        binding.btnSetMaxNodes.setOnClickListener {
            showMaxNodesDialog()
        }

        binding.btnResetToDefault.setOnConfirmed {
            timeTableManager.resetToDefault()
            Toast.makeText(this, "已重置为默认时间表", Toast.LENGTH_SHORT).show()
            loadAndDisplayTimeSlots()
        }

        binding.btnSave.setOnClickListener {
            saveTimeSlots()
        }
    }

    private fun loadAndDisplayTimeSlots() {
        val maxNodes = timeTableManager.getMaxNodes()
        binding.tvMaxNodes.text = "$maxNodes 节"

        val timeSlots = timeTableManager.getTimeSlots().sortedBy { it.node }

        binding.llTimeSlots.removeAllViews()
        timeSlotViews.clear()

        for (node in 1..maxNodes) {
            val timeSlot = timeSlots.find { it.node == node }
                ?: TimeTableManager.TimeSlot(node, getDefaultStartTime(node), getDefaultEndTime(node))

            addTimeSlotView(timeSlot)
        }
    }

    private fun addTimeSlotView(timeSlot: TimeTableManager.TimeSlot) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_time_slot_simple, binding.llTimeSlots, false)

        val tvNode = view.findViewById<TextView>(R.id.tv_node)
        val tvStartTime = view.findViewById<TextView>(R.id.tv_start_time)
        val tvEndTime = view.findViewById<TextView>(R.id.tv_end_time)

        tvNode.text = "第${timeSlot.node}节"
        tvStartTime.text = timeSlot.startTime
        tvEndTime.text = timeSlot.endTime

        tvStartTime.setOnClickListener {
            showMaterialTimePicker(tvStartTime)
        }

        tvEndTime.setOnClickListener {
            showMaterialTimePicker(tvEndTime)
        }

        val slotView = TimeSlotView(timeSlot.node, tvStartTime, tvEndTime)
        timeSlotViews.add(slotView)
        binding.llTimeSlots.addView(view)
    }

    /**
     * Use Material 3 TimePicker instead of the legacy [TimePickerDialog],
     * so the clock UI follows the current M3 theme palette.
     */
    private fun showMaterialTimePicker(textView: TextView) {
        val currentText = textView.text.toString()
        var hour = 8
        var minute = 0
        if (currentText.isNotEmpty() && currentText.contains(":")) {
            val parts = currentText.split(":")
            hour = parts[0].toIntOrNull() ?: 8
            minute = parts[1].toIntOrNull() ?: 0
        }

        val isSystem24Hour = DateFormat.is24HourFormat(this)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (isSystem24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText("选择时间")
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .build()

        picker.addOnPositiveButtonClickListener {
            textView.text = String.format("%02d:%02d", picker.hour, picker.minute)
        }

        picker.show(supportFragmentManager, "time_picker")
    }

    private fun showMaxNodesDialog() {
        val currentMax = timeTableManager.getMaxNodes()
        val options = (4..16).map { SelectOption(label = "$it 节") }
        val currentIndex = (4..16).indexOf(currentMax).coerceAtLeast(0)

        SelectionDialog.show(
            context = this,
            title = "设置每天课程数",
            options = options,
            selectedIndex = currentIndex,
            onSelected = { index ->
                val maxNodes = index + 4
                timeTableManager.setMaxNodes(maxNodes)
                binding.tvMaxNodes.text = "$maxNodes 节"

                val currentSlots = timeTableManager.getTimeSlots()
                val maxNodeInSlots = currentSlots.maxOfOrNull { it.node } ?: 0
                if (maxNodes > maxNodeInSlots) {
                    for (node in (maxNodeInSlots + 1)..maxNodes) {
                        timeTableManager.addTimeSlot(
                            node,
                            getDefaultStartTime(node),
                            getDefaultEndTime(node)
                        )
                    }
                }

                loadAndDisplayTimeSlots()
            }
        )
    }

    private fun saveTimeSlots() {
        for (slotView in timeSlotViews) {
            timeTableManager.updateTimeSlot(
                slotView.node,
                slotView.tvStartTime.text.toString(),
                slotView.tvEndTime.text.toString()
            )
        }
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun getDefaultStartTime(node: Int): String {
        return when (node) {
            1 -> "08:00"
            2 -> "08:55"
            3 -> "10:00"
            4 -> "10:55"
            5 -> "14:30"
            6 -> "15:25"
            7 -> "16:30"
            8 -> "17:25"
            9 -> "19:00"
            10 -> "19:55"
            11 -> "20:50"
            12 -> "21:45"
            13 -> "22:40"
            14 -> "23:35"
            15 -> "00:30"
            16 -> "01:25"
            else -> "08:00"
        }
    }

    private fun getDefaultEndTime(node: Int): String {
        return when (node) {
            1 -> "08:45"
            2 -> "09:40"
            3 -> "10:45"
            4 -> "11:40"
            5 -> "15:15"
            6 -> "16:10"
            7 -> "17:15"
            8 -> "18:10"
            9 -> "19:45"
            10 -> "20:40"
            11 -> "21:35"
            12 -> "22:30"
            13 -> "23:25"
            14 -> "00:20"
            15 -> "01:15"
            16 -> "02:10"
            else -> "08:45"
        }
    }

    private data class TimeSlotView(
        val node: Int,
        val tvStartTime: TextView,
        val tvEndTime: TextView
    )
}
