package com.cherry.wakeupschedule

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.ui.feedback.AppToast
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.BuildConfig
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.UpdateService
import com.cherry.wakeupschedule.ui.browser.InAppBrowser
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.appbar.MaterialToolbar

class AboutActivity : BaseActivity() {

    private lateinit var updateService: UpdateService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_about)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        updateService = UpdateService(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        val llCheckUpdate = findViewById<LinearLayout>(R.id.ll_check_update)
        val llOfficialWebsite = findViewById<LinearLayout>(R.id.ll_official_website)
        val llGithub = findViewById<LinearLayout>(R.id.ll_github)
        val llLicense = findViewById<LinearLayout>(R.id.ll_license)
        val llUpdateAdapter = findViewById<LinearLayout>(R.id.ll_update_adapter)
        val vDividerUpdateAdapter = findViewById<View>(R.id.v_divider_update_adapter)

        setupPageHeader(toolbar, "关于")
        // 测试版在版本号后标注渠道，避免测试者把 beta 当成正式版反馈问题
        tvVersion.text = if (BuildConfig.APP_CHANNEL == "beta") {
            "版本: ${BuildConfig.VERSION_NAME} · ${getString(R.string.about_beta_badge)}"
        } else {
            "版本: ${BuildConfig.VERSION_NAME}"
        }

        llCheckUpdate.setOnClickListener { updateService.checkForUpdate(showNoUpdateToast = true) }

        llOfficialWebsite.setOnClickListener {
            openUrl("https://easo.laoin.work/schedule", getString(R.string.about_website))
        }

        llGithub.setOnClickListener {
            openUrl("https://github.com/laoin114514/GxuScheduleAPP", getString(R.string.about_github_title))
        }

        llLicense.setOnClickListener {
            openUrl(
                "https://github.com/laoin114514/GxuScheduleAPP/blob/main/LICENSE",
                getString(R.string.about_license_title)
            )
        }

        // 隐藏"已适配的教务系统"入口及其上方的分割线
        llUpdateAdapter.visibility = View.GONE
        vDividerUpdateAdapter.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // 从"安装未知应用"设置页返回后的安装续接由 BaseActivity 统一处理
        refreshUpdateHint()
        // 静默检查完成后红点即时刷新（同一时刻仅本页面在前台注册）
        UpdateService.hintChangedListener = { refreshUpdateHint() }
    }

    override fun onPause() {
        super.onPause()
        UpdateService.hintChangedListener = null
    }

    /** 有未处理的新版本更新时，在"检查更新"行右侧显示红点 */
    private fun refreshUpdateHint() {
        findViewById<View>(R.id.dot_update_hint).visibility =
            if (SettingsManager(this).hasNewUpdateHint()) View.VISIBLE else View.GONE
    }

    /** 统一走内置浏览器，失败时提示（不再把用户甩到外部浏览器） */
    private fun openUrl(url: String, title: String) {
        if (!InAppBrowser.open(this, url, title)) {
            AppToast.error(this, getString(R.string.in_app_browser_no_handler))
        }
    }
}
