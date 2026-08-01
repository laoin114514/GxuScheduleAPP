package com.cherry.wakeupschedule

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.databinding.ActivityWebviewBinding
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.ImportService
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding
    private lateinit var courseDataManager: CourseDataManager
    private lateinit var progressBar: ProgressBar
    private lateinit var importService: ImportService
    private var autoFillPassword: Boolean = false

    private val allowedDomains = listOf(
        // 常见教务系统域名（不含子域名匹配）
        "jw", "jwc", "jwxt", "edu", "edu.cn", "educom", "jiaowu",
        // 蓝奏云
        "lanzou", "lanzoui", "lanzoux", "lanzouo", "lanzn", "woozooo"
    )

    // 下载监听
    private var downloadReceiver: BroadcastReceiver? = null
    private var currentDownloadId: Long = -1

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val url = intent.getStringExtra("url") ?: ""
        val schoolName = intent.getStringExtra("schoolName") ?: ""
        autoFillPassword = intent.getBooleanExtra("autoFillPassword", false)

        // 设置标题 + 返回按钮
        setupPageHeader(binding.toolbar, if (schoolName.isNotEmpty()) schoolName else "网页浏览")

        // 初始化
        courseDataManager = CourseDataManager.getInstance(this)
        importService = ImportService(this)

        // 初始化进度条
        progressBar = binding.progressBar

        setupWebView(url)
        setupClickListeners()
        setupDownloadListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销下载监听
        downloadReceiver?.let {
            unregisterReceiver(it)
        }
    }

    private fun setupDownloadListener() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId == currentDownloadId) {
                    handleDownloadedFile(downloadId)
                }
            }
        }

        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            RECEIVER_NOT_EXPORTED
        )
    }

    private fun handleDownloadedFile(downloadId: Long) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor = downloadManager.query(query)

        if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = cursor.getInt(columnIndex)

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    var sourceUri: Uri? = null

                    // 尝试获取文件URI和文件名（兼容不同Android版本）
                    try {
                        val uriColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (uriColumnIndex != -1) {
                            val uriString = cursor.getString(uriColumnIndex)
                            if (!uriString.isNullOrEmpty()) {
                                sourceUri = Uri.parse(uriString)
                            }
                        }

                        if (sourceUri == null) {
                            val uriColIdx = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                            if (uriColIdx != -1) {
                                val uriString = cursor.getString(uriColIdx)
                                if (!uriString.isNullOrEmpty()) {
                                    sourceUri = Uri.parse(uriString)
                                }
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("WebViewActivity", "获取文件信息失败", e)
                    }

                    if (sourceUri == null) {
                        Toast.makeText(
                            this@WebViewActivity,
                            "下载失败：无法获取文件路径",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    // 验证文件大小，确保文件不为空
                    val filePath = sourceUri.path
                    if (filePath != null && java.io.File(filePath).length() == 0L) {
                        Toast.makeText(
                            this@WebViewActivity,
                            "下载的文件为空",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    // 直接导入下载的文件
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val success = importService.importFromFile(sourceUri!!)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(
                                        this@WebViewActivity,
                                        "课程表已下载并导入成功 ✓",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        this@WebViewActivity,
                                        "导入失败，请检查文件格式",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                finish()
                            }
                        } catch (e: Exception) {
                            Log.e("WebViewActivity", "导入下载文件失败", e)
                            withContext(Dispatchers.Main) {
                                // 导入失败时保存到 pending_imports 作为兜底
                                val prefs = getSharedPreferences("pending_imports", Context.MODE_PRIVATE)
                                prefs.edit().putString("pending_file", sourceUri.toString()).apply()
                                Toast.makeText(
                                    this@WebViewActivity,
                                    "下载完成，返回首页自动导入",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        }
                    }

                } else {
                    // 下载失败
                    val errorColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val errorReason = if (errorColumnIndex != -1) cursor.getInt(errorColumnIndex) else -1
                    Toast.makeText(
                        this@WebViewActivity,
                        "下载失败，错误码: $errorReason",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            cursor.close()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String) {
        binding.webView.apply {
            webViewClient = object : WebViewClient() {
                private fun isUrlAllowed(url: String): Boolean {
                    return try {
                        val host = Uri.parse(url).host ?: return true
                        allowedDomains.any { domain -> host.contains(domain, ignoreCase = true) }
                    } catch (e: Exception) {
                        true
                    }
                }

                private fun isLanZouUrl(url: String): Boolean {
                    return try {
                        val host = Uri.parse(url).host ?: return false
                        allowedDomains.any { domain -> 
                            domain.contains("lanzou") || domain.contains("lanzn") || 
                            domain.contains("woozooo") || host.contains("lanzou", ignoreCase = true) || 
                            host.contains("lanzn", ignoreCase = true) || 
                            host.contains("woozooo", ignoreCase = true)
                        }
                    } catch (e: Exception) {
                        false
                    }
                }

                @Deprecated("Deprecated in Java")
                @SuppressLint("Deprecated")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    url?.let {
                        // 蓝奏云的所有链接都在 WebView 内部处理，包括文件名点击跳转
                        if (isLanZouUrl(it)) {
                            Log.d("WebViewActivity", "蓝奏云链接，在 WebView 内部加载: $it")
                            return false
                        }

                        if (!isUrlAllowed(it)) {
                            Log.w("WebViewActivity", "拦截外部域名跳转: $it")
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                                startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("WebViewActivity", "无法打开外部链接", e)
                            }
                            return true
                        }
                        // 拦截特定的URL，尝试解析课程表
                        if (it.contains("xskbcx.aspx") || it.contains("course") || it.contains("schedule") ||
                            it.contains("kb") || it.contains("timetable") || it.contains("课表") ||
                            it.contains("curriculum") || it.contains("time")) {
                            // 延迟注入JavaScript来解析页面，等待页面完全加载
                            postDelayed({ injectJavaScriptForParsing() }, 1000)
                            return false // 让WebView继续加载页面
                        }
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d("WebViewActivity", "页面开始加载: $url")
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("WebViewActivity", "页面加载完成: $url")
                    progressBar.visibility = View.GONE

                    // 如果启用了自动填密功能，尝试自动填入密码
                    if (autoFillPassword) {
                        postDelayed({ autoFillLanzouPassword() }, 1000)
                    }

                    // 页面加载完成后尝试解析课程表
                    if (url?.contains("xskbcx.aspx") == true || url?.contains("kb") == true ||
                        url?.contains("timetable") == true || url?.contains("schedule") == true ||
                        url?.contains("课表") == true || url?.contains("curriculum") == true) {
                        // 延迟注入JavaScript来解析页面，等待页面完全加载
                        postDelayed({ injectJavaScriptForParsing() }, 1500)
                    }
                }
            }

            // 设置WebChromeClient以支持进度显示
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false

                // 启用更多功能以支持复杂的教务系统
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)

                // 启用混合内容 - 允许同时加载 HTTPS 和 HTTP 内容
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // 设置User-Agent为桌面浏览器，避免移动端限制
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }

            // 设置下载监听器 - 使用OkHttp直接下载
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                Log.d("WebViewActivity", "捕获到下载请求: $url")
                Log.d("WebViewActivity", "Content-Disposition: $contentDisposition")
                Log.d("WebViewActivity", "MIME类型: $mimeType")

                // 如果是 APK 文件，让系统浏览器处理
                if (url.contains(".apk", ignoreCase = true)) {
                    Log.d("WebViewActivity", "检测到 APK 下载，跳过并使用系统浏览器")
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@WebViewActivity, "无法打开下载页面", Toast.LENGTH_SHORT).show()
                    }
                    return@setDownloadListener
                }

                // 在主线程获取当前URL，然后启动下载
                val currentUrl = this.url
                downloadWithOkHttp(url, contentDisposition, mimeType, userAgent, currentUrl)
            }

            // 添加JavaScript接口
            addJavascriptInterface(WebAppInterface(), "Android")

            Log.d("WebViewActivity", "开始加载URL: $url")
            loadUrl(url)
        }
    }

    private fun setupClickListeners() {
        // 移除手动导入和自动解析按钮的点击事件
    }
    
    private fun autoFillLanzouPassword() {
        val jsCode = """
            javascript:(function() {
                // 尝试找到蓝奏云密码输入框
                var passwordInputs = document.querySelectorAll('input[type="password"], input[name="pwd"], input[placeholder*="密码"]');
                
                for (var i = 0; i < passwordInputs.length; i++) {
                    var input = passwordInputs[i];
                    input.value = '666';
                    // 触发change事件
                    var event = new Event('change', { bubbles: true });
                    input.dispatchEvent(event);
                }
                
                // 尝试找到提交按钮并点击
                var submitButtons = document.querySelectorAll('input[type="submit"], button[type="submit"], button:contains("提交"), .submit-btn, [class*="submit"]');
                
                for (var i = 0; i < submitButtons.length; i++) {
                    var btn = submitButtons[i];
                    if (btn.offsetParent !== null) { // 确保按钮可见
                        btn.click();
                        break;
                    }
                }
            })();
        """.trimIndent()
        
        binding.webView.evaluateJavascript(jsCode, null)
    }
    
    private fun downloadWithSystemManager(url: String, contentDisposition: String?, mimeType: String?) {
        Toast.makeText(this@WebViewActivity, "正在下载课程表...", Toast.LENGTH_SHORT).show()
        
        try {
            // 检查DownloadManager是否可用
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val currentNetwork = connectivityManager.activeNetwork
            val networkCapabilities = if (currentNetwork != null) {
                connectivityManager.getNetworkCapabilities(currentNetwork)
            } else null
            if (networkCapabilities == null || !networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                Toast.makeText(this, "请检查网络连接", Toast.LENGTH_LONG).show()
                return
            }
            Log.d("WebViewActivity", "网络已连接")
            
            // 解析文件名
            var fileName = "schedule_${System.currentTimeMillis()}.xls"
            if (contentDisposition != null) {
                val match = Regex("filename=([^;]+)").find(contentDisposition)
                if (match != null) {
                    fileName = match.groupValues[1].replace("\"", "").trim()
                    Log.d("WebViewActivity", "从Content-Disposition获取文件名: $fileName")
                }
            }
            
            // 根据MIME类型确定扩展名
            if (!fileName.endsWith(".xls") && !fileName.endsWith(".xlsx") && !fileName.endsWith(".csv")) {
                when {
                    mimeType?.contains("excel") == true || mimeType?.contains("spreadsheet") == true -> {
                        fileName += ".xls"
                    }
                    mimeType?.contains("csv") == true -> {
                        fileName += ".csv"
                    }
                    mimeType?.contains("zip") == true -> {
                        fileName += ".zip"
                    }
                    else -> {
                        fileName += ".xls"
                    }
                }
            }
            
            Log.d("WebViewActivity", "最终文件名: $fileName")
            Log.d("WebViewActivity", "下载URL: $url")
            
            // 创建下载请求 - 使用系统DownloadManager像浏览器一样下载
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("正在下载课程表...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            
            // 设置下载目标路径 - 使用应用外部文件目录（不需要额外权限）
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            Log.d("WebViewActivity", "下载目标: ${getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)}/$fileName")
            
            // 添加Cookie - 这是关键，让DownloadManager使用WebView的会话
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("Cookie", cookies)
                Log.d("WebViewActivity", "添加Cookie到下载请求: $cookies")
            } else {
                Log.w("WebViewActivity", "没有获取到Cookie，可能导致下载失败")
            }
            
            // 添加User-Agent，模拟浏览器
            request.addRequestHeader("User-Agent", binding.webView.settings.userAgentString)
            
            // 添加Referer - 某些教务系统需要验证来源页面
            val currentUrl = binding.webView.url
            if (!currentUrl.isNullOrEmpty()) {
                request.addRequestHeader("Referer", currentUrl)
                Log.d("WebViewActivity", "添加Referer: $currentUrl")
            }
            
            // 添加Accept头
            request.addRequestHeader("Accept", "*/*")
            
            // 添加Connection头
            request.addRequestHeader("Connection", "keep-alive")
            
            // 开始下载
            currentDownloadId = downloadManager.enqueue(request)
            
            if (currentDownloadId > 0) {
                Log.d("WebViewActivity", "开始系统下载，ID: $currentDownloadId, 文件名: $fileName")
                
                // 启动定时检查下载状态（作为BroadcastReceiver的备份）
                startDownloadStatusCheck(currentDownloadId)
            } else {
                Log.e("WebViewActivity", "DownloadManager返回无效ID: $currentDownloadId")
                Toast.makeText(this, "下载启动失败，DownloadManager不可用", Toast.LENGTH_LONG).show()
            }
            
        } catch (e: Exception) {
            Log.e("WebViewActivity", "启动下载失败", e)
            Toast.makeText(this, "下载启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun startDownloadStatusCheck(downloadId: Long) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val checkInterval = 1000L // 每秒检查一次
        val maxChecks = 60 // 最多检查60次（60秒）
        var checkCount = 0
        
        val checkRunnable = object : Runnable {
            override fun run() {
                checkCount++
                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIndex)
                    
                    // 获取下载字节数
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val bytesDownloaded = if (bytesDownloadedIndex != -1) cursor.getLong(bytesDownloadedIndex) else 0
                    val bytesTotal = if (bytesTotalIndex != -1) cursor.getLong(bytesTotalIndex) else 0
                    
                    // 状态名称
                    val statusName = when (status) {
                        DownloadManager.STATUS_PENDING -> "PENDING(挂起)"
                        DownloadManager.STATUS_RUNNING -> "RUNNING(下载中)"
                        DownloadManager.STATUS_PAUSED -> "PAUSED(暂停)"
                        DownloadManager.STATUS_SUCCESSFUL -> "SUCCESSFUL(成功)"
                        DownloadManager.STATUS_FAILED -> "FAILED(失败)"
                        else -> "UNKNOWN(未知:$status)"
                    }
                    
                    Log.d("WebViewActivity", "下载状态检查 #$checkCount: $statusName, 已下载: $bytesDownloaded / $bytesTotal 字节")
                    
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.d("WebViewActivity", "下载成功检测到")
                            cursor.close()
                            handleDownloadedFile(downloadId)
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIndex != -1) cursor.getInt(reasonIndex) else -1
                            Log.e("WebViewActivity", "下载失败，原因码: $reason")
                            cursor.close()
                            Toast.makeText(this@WebViewActivity, "下载失败，错误码: $reason", Toast.LENGTH_SHORT).show()
                            return
                        }
                        else -> {
                            // 下载中，继续检查
                            if (checkCount < maxChecks) {
                                cursor.close()
                                handler.postDelayed(this, checkInterval)
                            } else {
                                Log.w("WebViewActivity", "下载检查超时，最后状态: $statusName")
                                cursor.close()
                                // 超时后尝试取消下载
                                downloadManager.remove(downloadId)
                                Toast.makeText(this@WebViewActivity, "下载超时，请检查网络连接", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    cursor.close()
                    Log.w("WebViewActivity", "无法查询下载状态，下载ID可能无效")
                }
            }
        }
        
        handler.postDelayed(checkRunnable, checkInterval)
    }
    
    private fun downloadWithOkHttp(url: String, contentDisposition: String?, mimeType: String?, userAgent: String, refererUrl: String?) {
        Toast.makeText(this@WebViewActivity, "正在下载课程表...", Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                
                // 解析文件名
                var fileName = "schedule_${System.currentTimeMillis()}.xls"
                if (contentDisposition != null) {
                    val match = Regex("filename=([^;]+)").find(contentDisposition)
                    if (match != null) {
                        fileName = match.groupValues[1].replace("\"", "").trim()
                    }
                }
                
                // 根据MIME类型确定扩展名
                if (!fileName.endsWith(".xls") && !fileName.endsWith(".xlsx") && !fileName.endsWith(".csv")) {
                    when {
                        mimeType?.contains("excel") == true || mimeType?.contains("spreadsheet") == true -> {
                            fileName += ".xls"
                        }
                        mimeType?.contains("csv") == true -> {
                            fileName += ".csv"
                        }
                        mimeType?.contains("zip") == true -> {
                            fileName += ".zip"
                        }
                        else -> {
                            fileName += ".xls"
                        }
                    }
                }
                
                // 构建请求
                val requestBuilder = Request.Builder().url(url)
                
                // 添加Cookie
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    requestBuilder.addHeader("Cookie", cookies)
                    Log.d("WebViewActivity", "添加Cookie: $cookies")
                }
                
                // 添加User-Agent
                requestBuilder.addHeader("User-Agent", userAgent)
                
                // 添加Referer（从参数传入，避免在非主线程访问WebView）
                if (!refererUrl.isNullOrEmpty()) {
                    requestBuilder.addHeader("Referer", refererUrl)
                    Log.d("WebViewActivity", "添加Referer: $refererUrl")
                }
                
                // 添加其他请求头
                requestBuilder.addHeader("Accept", "*/*")
                requestBuilder.addHeader("Connection", "keep-alive")
                
                Log.d("WebViewActivity", "开始OkHttp下载: $url")
                val response = client.newCall(requestBuilder.build()).execute()
                
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WebViewActivity, "下载失败: HTTP ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // 保存文件到应用下载目录
                val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir != null && !downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                val destFile = File(downloadDir, fileName)
                
                Log.d("WebViewActivity", "保存文件到: ${destFile.absolutePath}")
                
                // 写入文件
                response.body?.byteStream()?.use { input ->
                    File(destFile.absolutePath).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                Log.d("WebViewActivity", "文件保存完成，大小: ${destFile.length()} 字节")
                
                // 验证文件
                if (destFile.length() == 0L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WebViewActivity, "下载的文件为空", Toast.LENGTH_SHORT).show()
                    }
                    destFile.delete()
                    return@launch
                }

                // 直接导入下载的文件
                withContext(Dispatchers.Main) {
                    try {
                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                            this@WebViewActivity,
                            "${packageName}.fileprovider",
                            destFile
                        )
                        val importService = ImportService(this@WebViewActivity)
                        val success = importService.importFromFile(fileUri)
                        if (success) {
                            Toast.makeText(this@WebViewActivity, "课程表已下载并导入成功 ✓", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@WebViewActivity, "导入失败，请检查文件格式", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e("WebViewActivity", "导入下载文件失败", e)
                        // 导入失败时保存到 pending_imports 作为兜底
                        val prefs = getSharedPreferences("pending_imports", Context.MODE_PRIVATE)
                        prefs.edit().putString("pending_file", destFile.absolutePath).apply()
                        Toast.makeText(this@WebViewActivity, "下载完成，返回首页自动导入", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
                
            } catch (e: Exception) {
                Log.e("WebViewActivity", "OkHttp下载失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WebViewActivity, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun injectJavaScriptForParsing() {
        val jsCode = """
            javascript:(function() {
                // 增强版课程表解析 - 适配多种教务系统
                var courses = [];

                // 1. 首先查找常见的课表容器
                var courseContainers = [
                    // 强智教务系统
                    '#kbtable', '.kbcontent', '#courseTable', '#kebiao',
                    // 正方教务系统
                    '#DataList1', '#gvkb', '.datelist', '#Table1',
                    // 青果教务系统
                    '#tblCourse', '#course-schedule', '.schedule-table',
                    // 金智教务系统
                    '.course-table', '.timetable', '#schedule',
                    // 通用选择器
                    'table[class*="kb"]', 'table[id*="kb"]',
                    'table[class*="course"]', 'table[id*="course"]',
                    'div[class*="schedule"]', 'div[id*="schedule"]',
                    'table[class*="timetable"]', 'table[id*="timetable"]'
                ];

                var foundTable = null;
                for (var selector of courseContainers) {
                    foundTable = document.querySelector(selector);
                    if (foundTable) {
                        console.log('找到课表容器: ' + selector);
                        break;
                    }
                }

                // 2. 如果没有找到特定容器，查找所有表格
                if (!foundTable) {
                    var allTables = document.querySelectorAll('table');
                    for (var table of allTables) {
                        var rows = table.querySelectorAll('tr');
                        var cells = table.querySelectorAll('td');
                        // 课表通常有较多行和单元格
                        if (rows.length >= 5 && cells.length >= 20) {
                            foundTable = table;
                            console.log('通过通用选择器找到课表');
                            break;
                        }
                    }
                }

                // 3. 尝试从页面文本中提取课程信息
                if (!foundTable) {
                    console.log('未找到课表表格，尝试文本解析');
                    courses = parseFromPageText();
                    if (courses.length > 0) {
                        Android.onCoursesParsed(JSON.stringify(courses));
                    } else {
                        Android.onNoCoursesFound();
                    }
                    return;
                }

                // 4. 解析表格数据
                var rows = foundTable.querySelectorAll('tr');
                console.log('课表行数: ' + rows.length);

                for (var i = 1; i < rows.length; i++) { // 跳过标题行
                    var row = rows[i];
                    var cells = row.querySelectorAll('td, th');

                    if (cells.length >= 4) {
                        // 尝试不同的列索引组合
                        var courseData = tryParseCourseRow(cells);
                        if (courseData && courseData.name) {
                            courses.push(courseData);
                        }
                    }
                }

                // 5. 如果表格解析失败，尝试解析页面文本
                if (courses.length === 0) {
                    console.log('表格解析未找到课程，尝试文本解析');
                    courses = parseFromPageText();
                }

                console.log('总共解析到 ' + courses.length + ' 门课程');

                if (courses.length > 0) {
                    Android.onCoursesParsed(JSON.stringify(courses));
                } else {
                    Android.onNoCoursesFound();
                }

                function tryParseCourseRow(cells) {
                    // 尝试不同的列索引组合
                    var patterns = [
                        {name:0, teacher:1, classroom:2, time:3},
                        {name:1, teacher:2, classroom:3, time:4},
                        {name:0, teacher:2, classroom:3, time:1},
                        {name:0, teacher:1, time:2, classroom:3}
                    ];

                    for (var pattern of patterns) {
                        if (cells.length > Math.max(pattern.name, pattern.teacher, pattern.classroom, pattern.time)) {
                            var name = cells[pattern.name]?.textContent?.trim();
                            var teacher = cells[pattern.teacher]?.textContent?.trim();
                            var classroom = cells[pattern.classroom]?.textContent?.trim();
                            var timeInfo = cells[pattern.time]?.textContent?.trim();

                            if (name && name.length > 1 && !name.includes('节') && !name.includes('星期') && !name.includes('时间')) {
                                var parsedTime = parseTimeInfo(timeInfo || '');
                                return {
                                    name: name,
                                    teacher: teacher || '',
                                    classroom: classroom || '',
                                    dayOfWeek: parsedTime.dayOfWeek,
                                    startTime: parsedTime.startTime,
                                    endTime: parsedTime.endTime,
                                    startWeek: parsedTime.startWeek,
                                    endWeek: parsedTime.endWeek
                                };
                            }
                        }
                    }
                    return null;
                }

                function parseTimeInfo(timeStr) {
                    var result = {dayOfWeek:1, startTime:1, endTime:2, startWeek:1, endWeek:16};

                    // 解析星期
                    var dayMap = {'一':1, '二':2, '三':3, '四':4, '五':5, '六':6, '日':7, '天':7};
                    for (var day in dayMap) {
                        if (timeStr.includes('周' + day) || timeStr.includes('星期' + day)) {
                            result.dayOfWeek = dayMap[day];
                            break;
                        }
                    }

                    // 解析节次
                    var timeMatch = timeStr.match(/(\d+)[-~](\d+)节/);
                    if (timeMatch) {
                        result.startTime = parseInt(timeMatch[1]);
                        result.endTime = parseInt(timeMatch[2]);
                    } else {
                        // 尝试匹配单节
                        var singleMatch = timeStr.match(/第(\d+)节/);
                        if (singleMatch) {
                            result.startTime = parseInt(singleMatch[1]);
                            result.endTime = result.startTime;
                        }
                    }

                    // 解析周次
                    var weekMatch = timeStr.match(/(\d+)[-~](\d+)周/);
                    if (weekMatch) {
                        result.startWeek = parseInt(weekMatch[1]);
                        result.endWeek = parseInt(weekMatch[2]);
                    } else {
                        // 尝试匹配单周
                        var singleWeekMatch = timeStr.match(/第(\d+)周/);
                        if (singleWeekMatch) {
                            result.startWeek = parseInt(singleWeekMatch[1]);
                            result.endWeek = result.startWeek;
                        }
                    }

                    return result;
                }

                function parseFromPageText() {
                    var foundCourses = [];
                    var textNodes = document.querySelectorAll('td, div, span, p');

                    for (var node of textNodes) {
                        var text = node.textContent.trim();
                        if (text.length > 2 && text.length < 200) {
                            // 检查是否包含课程特征
                            if ((text.includes('节') || text.includes('周')) &&
                                (text.includes('星期') || text.includes('周') || /[一二三四五六日]/.test(text))) {

                                var parsedTime = parseTimeInfo(text);
                                var lines = text.split(/[\s\n]+/);

                                // 尝试找到课程名称（通常是第一个非时间相关的文本）
                                var courseName = '';
                                for (var line of lines) {
                                    if (line.length > 1 &&
                                        !line.includes('节') &&
                                        !line.includes('周') &&
                                        !line.includes('星期') &&
                                        !/^\d+$/.test(line)) {
                                        courseName = line;
                                        break;
                                    }
                                }

                                if (courseName) {
                                    foundCourses.push({
                                        name: courseName,
                                        teacher: '',
                                        classroom: '',
                                        dayOfWeek: parsedTime.dayOfWeek,
                                        startTime: parsedTime.startTime,
                                        endTime: parsedTime.endTime,
                                        startWeek: parsedTime.startWeek,
                                        endWeek: parsedTime.endWeek
                                    });
                                }
                            }
                        }
                    }
                    return foundCourses;
                }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(jsCode, null)
    }
    


    inner class WebAppInterface {
        private fun isCurrentUrlTrusted(): Boolean {
            val currentUrl = binding.webView.url ?: return false
            return try {
                val host = Uri.parse(currentUrl).host ?: return false
                allowedDomains.any { domain -> host.contains(domain, ignoreCase = true) }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun onCoursesParsed(coursesJson: String) {
            if (!isCurrentUrlTrusted()) {
                Log.w("WebViewActivity", "拦截来自不受信任页面的JavaScript接口调用: ${binding.webView.url}")
                return
            }
            Log.d("WebViewActivity", "解析到课程数据: $coursesJson")

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // 解析JSON并保存课程
                    val courses = parseCoursesFromJson(coursesJson)
                    if (courses.isNotEmpty()) {
                        // 替换当前学期课程
                        courseDataManager.replaceAllCourses(courses)

                        Toast.makeText(this@WebViewActivity, "成功导入 ${courses.size} 门课程", Toast.LENGTH_LONG).show()

                        // 退出WebView并返回主界面
                        finish()
                    } else {
                        Toast.makeText(this@WebViewActivity, "未找到有效课程数据", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e("WebViewActivity", "解析课程数据失败", e)
                    Toast.makeText(this@WebViewActivity, "解析课程表失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun parseCoursesFromJson(coursesJson: String): List<Course> {
            return try {
                val courses = mutableListOf<Course>()

                // 解析JSON数组
                val jsonArray = org.json.JSONArray(coursesJson)

                for (i in 0 until jsonArray.length()) {
                    val courseObj = jsonArray.getJSONObject(i)

                    val course = Course(
                        name = courseObj.getString("name").takeIf { it.isNotBlank() } ?: continue,
                        teacher = courseObj.optString("teacher", ""),
                        classroom = courseObj.optString("classroom", ""),
                        dayOfWeek = courseObj.optInt("dayOfWeek", 1).coerceIn(1, 7),
                        startTime = courseObj.optInt("startTime", 1).coerceIn(1, 12),
                        endTime = courseObj.optInt("endTime", 2).coerceIn(1, 12),
                        weekBitmap = Course.bitmapFromRange(
                            courseObj.optInt("startWeek", 1).coerceIn(1, 20),
                            courseObj.optInt("endWeek", 16).coerceIn(1, 20)
                        ),
                        alarmEnabled = true,
                        alarmMinutesBefore = 15,
                        color = 0xFF6200EE.toInt()
                    )

                    courses.add(course)
                }

                courses
            } catch (e: Exception) {
                Log.e("WebViewActivity", "JSON解析失败", e)
                emptyList()
            }
        }

        @JavascriptInterface
        fun onNoCoursesFound() {
            if (!isCurrentUrlTrusted()) {
                Log.w("WebViewActivity", "拦截来自不受信任页面的JavaScript接口调用: ${binding.webView.url}")
                return
            }
            Log.w("WebViewActivity", "未找到课程表数据")

            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(this@WebViewActivity, "未找到课程表数据，请尝试手动导入或检查是否已登录", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
