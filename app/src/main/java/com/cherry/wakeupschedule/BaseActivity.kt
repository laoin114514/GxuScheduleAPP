package com.cherry.wakeupschedule

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtAuthNotice
import com.cherry.wakeupschedule.service.UiScaleManager
import com.cherry.wakeupschedule.service.UpdateService
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.ui.theme.PageBackground
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 所有页面的基类，负责应用全局 UI 缩放档位。
 *
 * [attachBaseContext] 里按当前档位改写 densityDpi（dp 与 sp 同步等比缩放）；
 * [onResume] 比对倍率，若档位在别处被改过就重建自己 —— 这样从字体大小页返回时，
 * 后台栈中的页面会以新倍率重新渲染，无需维护全局 Activity 注册表。
 * 同时兜底"从安装未知应用设置页返回后继续引导安装更新包"（无待安装记录时开销极小）。
 *
 * 另外在这里统一收集 [JwxtAuthManager.notices]（教务会话自动恢复事件），
 * 仅在页面 RESUMED 时收集，用 [AppToast] 提示"正在恢复/已恢复/被踢恢复"，
 * 让用户知道刷新变慢是在后台重登录，而不是 App 卡死。
 */
abstract class BaseActivity : AppCompatActivity() {

    /** 本实例创建时生效的倍率，用于检测档位变更 */
    private var appliedScale: Float = 1f

    /** 本实例创建时生效的调色板索引，用于检测配色变更 */
    private var appliedPalette: Int = -1

    /** 登录态事件收集任务：只在页面 RESUMED 期间存活，避免后台页面重复弹提示。 */
    private var authNoticeJob: Job? = null

    /**
     * 是否在 onResume 时续接"待安装更新包"引导。
     * 闪屏页这类立刻退场的过渡页面关掉，避免安装弹窗一闪即逝。
     */
    protected open val resumesPendingInstall: Boolean = true

    /**
     * 内容视图挂上后统一铺页面渐变底：渐变按当前调色板派生（见 [PageBackground]），
     * 布局里那层 @drawable/bg_page_gradient 作为兜底保留，这里覆盖掉。
     */
    override fun onContentChanged() {
        super.onContentChanged()
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        PageBackground.apply(root)
    }

    override fun attachBaseContext(newBase: Context) {
        appliedScale = UiScaleManager.currentScale(newBase)
        appliedPalette = ThemeManager.getPaletteIndex(newBase)
        super.attachBaseContext(UiScaleManager.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (appliedScale != UiScaleManager.currentScale(this)) {
            recreate()
            return
        }
        // 配色是主题 overlay，只能在 setContentView 之前生效：
        // 在「主题」页换了配色后，后台栈里的页面要靠这次重建才能跟着换色。
        if (appliedPalette != ThemeManager.getPaletteIndex(this)) {
            recreate()
            return
        }
        // 授权设置页返回时用户可能落在任意页面，这里接着引导安装，避免安装入口凭空消失
        if (resumesPendingInstall) UpdateService(this).retryPendingInstall(this)

        startAuthNoticeCollection()
    }

    override fun onPause() {
        authNoticeJob?.cancel()
        authNoticeJob = null
        super.onPause()
    }

    /**
     * 收集登录态事件（仅在当前 RESUMED 的页面展示，避免多个页面同时弹提示）。
     *
     * 失败提示仍由各调用方根据 `Result.failure` 展示；这里只负责"自动恢复过程"的可见性。
     */
    private fun startAuthNoticeCollection() {
        authNoticeJob?.cancel()
        authNoticeJob = lifecycleScope.launch {
            JwxtAuthManager.notices.collect { notice -> showAuthNotice(notice) }
        }
    }

    private fun showAuthNotice(notice: JwxtAuthNotice) {
        when (notice) {
            JwxtAuthNotice.RECOVERING_EXPIRED ->
                AppToast.info(
                    this,
                    "教务登录已过期，正在自动恢复…",
                    AppToast.Duration.LONG,
                    groupKey = "jwxt_auth_recovering"
                )

            JwxtAuthNotice.RECOVERING_KICKED ->
                AppToast.warn(
                    this,
                    "账号在其它设备登录，正在恢复本机登录…",
                    groupKey = "jwxt_auth_recovering"
                )

            JwxtAuthNotice.RECOVERED ->
                AppToast.success(this, "教务登录已恢复", groupKey = "jwxt_auth_recovered")

            JwxtAuthNotice.RECOVERED_KICKED ->
                AppToast.success(
                    this,
                    "已恢复本机登录；如非本人操作，建议修改密码",
                    AppToast.Duration.LONG,
                    groupKey = "jwxt_auth_recovered"
                )
        }
    }
}
