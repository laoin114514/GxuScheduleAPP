package com.cherry.wakeupschedule

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.BuildConfig
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.appbar.MaterialToolbar

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_about)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        val llOfficialWebsite = findViewById<LinearLayout>(R.id.ll_official_website)
        val llGithub = findViewById<LinearLayout>(R.id.ll_github)
        val llLicense = findViewById<LinearLayout>(R.id.ll_license)
        val llUpdateAdapter = findViewById<LinearLayout>(R.id.ll_update_adapter)
        val vDividerUpdateAdapter = findViewById<View>(R.id.v_divider_update_adapter)

        setupPageHeader(toolbar, "关于")
        tvVersion.text = "版本: ${BuildConfig.VERSION_NAME}"

        llOfficialWebsite.setOnClickListener { openUrl("https://laoin114514.github.io/GxuScheduleAPP/") }

        llGithub.setOnClickListener { openUrl("https://github.com/laoin114514/GxuScheduleAPP") }

        llLicense.setOnClickListener { openUrl("https://github.com/laoin114514/GxuScheduleAPP/blob/main/LICENSE") }

        // 隐藏"已适配的教务系统"入口及其上方的分割线
        llUpdateAdapter.visibility = View.GONE
        vDividerUpdateAdapter.visibility = View.GONE
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }
}
