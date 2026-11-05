package com.cherry.wakeupschedule.ui.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.feedback.AppToast

/**
 * 应用内网页跳转的统一入口。
 *
 * 约定：**凡是打开网页的地方都走这里**，不再直接 `startActivity(ACTION_VIEW)`。
 * 这样有三个收益：
 *
 * 1. 用户体验统一：页面在 App 内打开，顶部有标题/返回，不把用户甩到外部浏览器；
 * 2. 下载类链接仍交给系统浏览器（WebView 不处理文件下载，见
 *    [InAppBrowserActivity] 的 DownloadListener）；
 * 3. 安全边界集中：`http/https` 才进内置浏览器，其余协议按
 *    [BrowserUrlPolicy] 决定转系统应用还是拒绝。
 *
 * 注意：本类不持有 Context，不注册任何监听；调用方传 Activity / Fragment 的 Context 即可。
 */
object InAppBrowser {

    /** 内置浏览器 Activity 的地址参数 */
    const val EXTRA_URL = "com.cherry.wakeupschedule.extra.browser_url"

    /** 内置浏览器 Activity 的标题参数（可为空，空则显示网页标题） */
    const val EXTRA_TITLE = "com.cherry.wakeupschedule.extra.browser_title"

    /**
     * 打开一个链接（首选内置浏览器）。
     *
     * @return 是否已成功发起跳转；`false` 时调用方可提示「无法打开链接」。
     */
    fun open(context: Context, url: String?, title: String? = null): Boolean {
        val target = url?.trim().orEmpty()
        if (target.isEmpty()) return false

        return when (BrowserUrlPolicy.classify(target)) {
            BrowserDestination.IN_APP ->
                if (supportsWebView()) {
                    startInApp(context, target, title)
                } else {
                    // 系统没有 WebView 实现（极少数定制 ROM / 精简系统）时退回外部浏览器，
                    // 保证「打开网页」这个动作本身仍然可用。
                    openExternally(context, target)
                }

            BrowserDestination.EXTERNAL -> openExternally(context, target)

            BrowserDestination.BLOCKED -> false
        }
    }

    /** 显式用系统浏览器打开（内置浏览器右上角菜单、以及下载类链接使用）。 */
    fun openExternally(context: Context, url: String?): Boolean {
        val target = url?.trim().orEmpty()
        if (target.isEmpty()) return false

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            AppToast.error(context, R.string.in_app_browser_no_handler)
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun startInApp(context: Context, url: String, title: String?): Boolean {
        return try {
            val intent = Intent(context, InAppBrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 系统是否提供 WebView 实现（`getCurrentWebViewPackage()` 为 null 表示没有）。 */
    private fun supportsWebView(): Boolean {
        return try {
            WebView.getCurrentWebViewPackage() != null
        } catch (e: Exception) {
            false
        }
    }
}
