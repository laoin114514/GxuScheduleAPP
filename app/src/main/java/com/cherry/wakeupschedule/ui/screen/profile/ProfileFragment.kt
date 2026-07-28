package com.cherry.wakeupschedule.ui.screen.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.cherry.wakeupschedule.AboutActivity
import com.cherry.wakeupschedule.BindJwxtActivity
import com.cherry.wakeupschedule.ProfileActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.SchoolImportActivity
import com.cherry.wakeupschedule.TimeTableEditActivity
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager

class ProfileFragment : Fragment() {

    private lateinit var settingsManager: SettingsManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())
        setupClickListeners(view)
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
}
