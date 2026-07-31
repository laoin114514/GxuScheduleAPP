package com.cherry.wakeupschedule

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.BuildConfig
import com.cherry.wakeupschedule.ui.theme.ThemeManager

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_about)

        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        val llOfficialWebsite = findViewById<LinearLayout>(R.id.ll_official_website)
        val llGithub = findViewById<LinearLayout>(R.id.ll_github)
        val llLicense = findViewById<LinearLayout>(R.id.ll_license)
        val llUpdateAdapter = findViewById<LinearLayout>(R.id.ll_update_adapter)

        tvVersion.text = "版本: ${BuildConfig.VERSION_NAME}"

        btnBack.setOnClickListener { finish() }

        llOfficialWebsite.setOnClickListener { openUrl("https://laoin114514.github.io/GxuScheduleAPP/") }

        llGithub.setOnClickListener { openUrl("https://github.com/laoin114514/GxuScheduleAPP") }

        llLicense.setOnClickListener { openUrl("https://github.com/laoin114514/GxuScheduleAPP/blob/main/LICENSE") }

        llUpdateAdapter.setOnClickListener { startActivity(Intent(this, SchoolListActivity::class.java)) }
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
