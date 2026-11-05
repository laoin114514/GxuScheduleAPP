package com.cherry.wakeupschedule.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.BaseActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.databinding.ActivityInAppBrowserBinding
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader

/**
 * 内置浏览器（WebView 宿主）。
 *
 * 设计目标与边界：
 *
 * - **只在 App 内打开网页**：应用里所有 `http/https` 链接统一走
 *   [InAppBrowser.open]，不把用户甩到外部浏览器；系统应用链接（mailto/tel 等）
 *   仍按 [BrowserUrlPolicy] 转交系统。
 * - **零新增依赖**：只用系统 WebView，不引 `androidx.webkit`/Custom Tabs，
 *   对 APK 体积几乎无影响（新代码约十几 KB，无新增资源/原生库）。
 * - **不执行危险协议**：`file:/content:/javascript:/data:/intent:` 一律拒绝；
 *   SSL 证书错误直接取消，不做「继续访问」。
 * - **不可用时兜底**：进入本页面前 [InAppBrowser] 已确认系统有 WebView 实现；
 *   万一仍失败，页面给出错误态 + 重试，用户还可从菜单改用系统浏览器。
 *
 * 交互：顶部返回键 = WebView 后退（无历史时退出页面）；右上角菜单 = 刷新 /
 * 在浏览器中打开。页面内 `target=_blank` 链接会被改成当前页打开（WebView 不弹新窗）。
 */
class InAppBrowserActivity : BaseActivity() {

    private lateinit var binding: ActivityInAppBrowserBinding
    private lateinit var webView: WebView

    /** 调用方指定的固定标题（为空则用网页标题） */
    private var fixedTitle: String? = null

    /** 最近一次加载的 URL，用于错误页重试 */
    private var lastUrl: String? = null

    /** 当前是否处于错误态（错误态下菜单「刷新」走重试逻辑） */
    private var isError = false

    /** 返回键：有历史先后退，否则退出页面 */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            goBackOrFinish()
        }
    }

    /** 页面 `<input type="file">` 的文件选择回调（问卷上传截图/附件等场景） */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** 系统文件选择器：页面请求上传时打开，把选中的 Uri 回传给 WebView */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            fileChooserCallback = null
            callback.onReceiveValue(resolveFileChooserResult(result.resultCode, result.data))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityInAppBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        fixedTitle = intent.getStringExtra(InAppBrowser.EXTRA_TITLE)
        setupPageHeader(
            binding.toolbar,
            fixedTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.in_app_browser_title)
        ) { goBackOrFinish() }

        onBackPressedDispatcher.addCallback(this, backCallback)
        setupWebView()

        val initialUrl = intent.getStringExtra(InAppBrowser.EXTRA_URL)
        lastUrl = initialUrl

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            // 屏幕旋转 / 重建：恢复页面与前进后退历史，不重新加载
            hideError()
        } else if (!initialUrl.isNullOrBlank() &&
            BrowserUrlPolicy.classify(initialUrl) == BrowserDestination.IN_APP
        ) {
            // 入口侧已经过滤过，这里再兜一道：本 Activity 永远不加载非 http(s) 的地址
            webView.loadUrl(initialUrl)
        } else {
            showError(getString(R.string.in_app_browser_invalid_url))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_in_app_browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                if (isError) {
                    lastUrl?.let(::loadUrl) ?: showError(getString(R.string.in_app_browser_invalid_url))
                } else {
                    webView.reload()
                }
                true
            }

            R.id.action_open_external -> {
                val url = webView.url ?: lastUrl
                if (!url.isNullOrBlank()) InAppBrowser.openExternally(this, url)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            // 先摘出视图树再销毁，避免 WebView 在 detach 后仍持有 Activity 引用
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    // ==================== WebView 配置 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = binding.webView
        val settings = webView.settings
        // 问卷、GitHub 等页面依赖 JS；页面均为 https，同时关掉本地文件与混合内容，
        // 避免远端页面借 WebView 读本地文件或降级加载 http 资源。
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 加载空白期的底色跟随当前主题，避免深色模式下闪一下白屏
        webView.setBackgroundColor(ThemeManager.currentPalette(this).surface)

        webView.webViewClient = BrowserClient()
        webView.webChromeClient = BrowserChromeClient()

        // WebView 不处理文件下载：点 http(s) 下载链接时交给系统浏览器
        //（更新包下载仍走 App 内 OkHttp 链路；data:/blob: 等伪协议不触发外部跳转）
        webView.setDownloadListener { url, _, _, _, _ ->
            if (BrowserUrlPolicy.classify(url) == BrowserDestination.IN_APP) {
                InAppBrowser.openExternally(this, url)
            }
        }
    }

    private fun loadUrl(url: String) {
        lastUrl = url
        hideError()
        webView.loadUrl(url)
    }

    /** 链接拦截：内置可加载的返回 false 交给 WebView，系统协议转交系统应用，其余拒绝。 */
    private fun interceptUrl(request: WebResourceRequest): Boolean {
        val url = request.url?.toString()
        return when (BrowserUrlPolicy.classify(url)) {
            BrowserDestination.IN_APP -> false
            BrowserDestination.EXTERNAL -> {
                // 只响应用户点击：页面加载时自动触发的 tel:/market: 不应拉起系统应用
                if (request.hasGesture()) InAppBrowser.openExternally(this, url)
                true
            }
            BrowserDestination.BLOCKED -> {
                // 用户主动点了不支持的协议（weixin:// / alipays:// 等）时给一次反馈，
                // 自动触发的不弹提示，避免页面一加载就刷屏。
                if (request.hasGesture()) {
                    AppToast.warn(this, getString(R.string.in_app_browser_unsupported_link))
                }
                true
            }
        }
    }

    private fun goBackOrFinish() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    private fun updateTitle() {
        if (!fixedTitle.isNullOrBlank()) return
        val pageTitle = webView.title?.trim()
        if (!pageTitle.isNullOrBlank() && pageTitle != webView.url) {
            supportActionBar?.title = pageTitle
        }
    }

    // ==================== 状态层（进度 / 错误） ====================

    private fun showError(message: String) {
        isError = true
        binding.progress.visibility = View.GONE
        webView.visibility = View.GONE
        binding.emptyState.root.visibility = View.VISIBLE
        with(binding.emptyState) {
            pbLoading.visibility = View.GONE
            ivStatusIcon.visibility = View.VISIBLE
            ivStatusIcon.setImageResource(R.drawable.ic_mtrl_link)
            tvStatus.text = message
            // 有目标地址才给「重试」；链接本身无效时藏起来，避免点了没反应
            tvStatusAction.visibility =
                if (lastUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            tvStatusAction.setText(R.string.in_app_browser_retry)
            tvStatusAction.setOnClickListener {
                lastUrl?.let(::loadUrl)
            }
        }
    }

    private fun hideError() {
        isError = false
        binding.emptyState.root.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /** 文件选择结果 → WebView 需要的 Uri 数组（支持多选） */
    private fun resolveFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    // ==================== WebViewClient / WebChromeClient ====================

    private inner class BrowserClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = interceptUrl(request)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            lastUrl = url ?: lastUrl
            binding.progress.visibility = View.VISIBLE
            binding.progress.progress = 0
            hideError()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            lastUrl = url ?: lastUrl
            binding.progress.visibility = View.GONE
            updateTitle()
            // WebView 不支持多窗口，页面里 target=_blank 的链接点了会没反应；
            // 页面加载完把 target 摘掉，改成当前页打开（问卷、GitHub 上很常见）。
            view.evaluateJavascript(TARGET_BLANK_FIX_JS, null)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            showError(getString(R.string.in_app_browser_error))
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError?
        ) {
            // 不忽略证书错误：宁可展示错误页，也不让用户在不安全的页面上输入信息。
            // 只有主文档证书错误才整页报错；子资源（图片/统计脚本）出错仅取消该请求，
            // 否则一个第三方图片证书异常就会让整页变成错误态。
            handler.cancel()
            if (error?.url == view.url) {
                showError(getString(R.string.in_app_browser_ssl_error))
            }
        }
    }

    private inner class BrowserChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (isError) return
            if (newProgress >= 100) {
                binding.progress.visibility = View.GONE
            } else {
                binding.progress.visibility = View.VISIBLE
                binding.progress.progress = newProgress
            }
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            // 上一次未完成的回调先置空，避免 WebView 一直等待导致页面输入框卡死
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (e: Exception) {
                fileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!fixedTitle.isNullOrBlank()) return
            if (!title.isNullOrBlank()) {
                supportActionBar?.title = title
            }
        }
    }

    private companion object {
        /** 去掉 target=_blank（WebView 不开新窗口） */
        const val TARGET_BLANK_FIX_JS =
            "(function(){try{document.querySelectorAll('a[target=\"_blank\"]')" +
                ".forEach(function(a){a.removeAttribute('target');});}catch(e){}})()"
    }
}
