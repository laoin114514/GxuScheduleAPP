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
    }
}
