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
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.appbar.MaterialToolbar

class AboutActivity : BaseActivity() {

    private lateinit var updateService: UpdateService

    /** 入场动画是否已播过：同一屏只播一次（转屏不重播） */
    private var entrancePlayed = false

    /** 更新红点刷新回调：手动/静默检查完成后即时刷新「检查更新」行 */
    private val updateHintListener: () -> Unit = { refreshUpdateHint() }

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

        playEntranceOnce(restored = savedInstanceState?.getBoolean(STATE_ENTRANCE_PLAYED) == true)
    }

    // ==================== 入场动效 ====================

    /**
     * 内容错峰上浮淡入（品牌区 + 四个链接行），封顶 10 项；
     * 同一屏只播一次：转屏返回不重复播，避免"每次回来都闪一下"。
     */
    private fun playEntranceOnce(restored: Boolean) {
        if (restored || entrancePlayed) return
        entrancePlayed = true
        // 系统关闭动画时不做入场，直接显示终态
        if (!Motion.enabled()) return
        val views = listOfNotNull(
            findViewById<View>(R.id.ll_about_brand),
            findViewById<View>(R.id.ll_check_update),
            findViewById<View>(R.id.ll_official_website),
            findViewById<View>(R.id.ll_github),
            findViewById<View>(R.id.ll_license)
        ).filter { it.visibility == View.VISIBLE }
        // post 一帧再播：避开 Activity 转场窗口动画，入场不会和窗口位移抢同一批帧
        window.decorView.post { Motion.stagger(views) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ENTRANCE_PLAYED, entrancePlayed)
    }

    override fun onResume() {
        super.onResume()
        // 从"安装未知应用"设置页返回后的安装续接由 BaseActivity 统一处理
        refreshUpdateHint()
        // 静默检查完成后红点即时刷新（MainActivity 底部导航红点也注册了同一份回调）
        UpdateService.addHintChangedListener(updateHintListener)
    }

    override fun onPause() {
        super.onPause()
        UpdateService.removeHintChangedListener(updateHintListener)
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

    companion object {
        /** 入场动画已播标记（savedInstanceState 键）：重建/转屏不重复播 */
        private const val STATE_ENTRANCE_PLAYED = "about_entrance_played"
    }
}
