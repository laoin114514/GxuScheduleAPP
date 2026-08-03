package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.TextView
import com.cherry.wakeupschedule.WebViewActivity
import com.cherry.wakeupschedule.ui.component.StyledDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新服务
 * 从自建后端检查最新版本并提示用户更新
 */
class UpdateService(private val context: Context) {

    companion object {
        private const val TAG = "UpdateService"
        // 自建后端地址；部署后把 DEFAULT_API_BASE 或 BuildConfig.API_BASE_URL 改为真实域名
        private const val DEFAULT_API_BASE = "https://your-server.example.com"
        private val API_BASE: String =
            com.cherry.wakeupschedule.BuildConfig.API_BASE_URL
                .takeIf { it.isNotBlank() && it != "http://localhost" }
                ?: DEFAULT_API_BASE
        private val UPDATE_API_URL = "$API_BASE/api/versions/latest"
    }

    private val currentVersion: String = com.cherry.wakeupschedule.BuildConfig.VERSION_NAME
    private val currentVersionCode: Int = com.cherry.wakeupschedule.BuildConfig.VERSION_CODE

    // 设备 ABI（后端据此返回匹配的下载链接；缺失时后端回退 universal）
    private val deviceAbi: String
        get() = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    // 静默检查更新（不显示任何提示，只在新版本时弹出对话框）
    fun checkForUpdateSilently() {
        val settingsManager = SettingsManager(context)
        // 检查是否允许更新提醒
        if (!settingsManager.isUpdateRemindEnabled()) {
            Log.d(TAG, "用户已关闭更新提醒，跳过检查")
            return
        }
        if (settingsManager.isCheckedForUpdateToday()) {
            Log.d(TAG, "今日已检查过更新，跳过")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = fetchLatestRelease()
                if (result != null && isNewVersion(result.versionCode)) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(result.versionName, result.downloadUrl, result.changelog)
                    }
                }
                settingsManager.markUpdateCheckedToday()
            } catch (e: Exception) {
                Log.e(TAG, "静默检查更新失败", e)
                settingsManager.markUpdateCheckedToday()
            }
        }
    }

    // 手动检查更新（显示提示）
    fun manualUpdate() = checkForUpdate(showNoUpdateToast = true)

    // 检查更新
    fun checkForUpdate(showNoUpdateToast: Boolean = true) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (showNoUpdateToast) showToast("正在检查更新...")
                val result = fetchLatestRelease()
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        if (isNewVersion(result.versionCode)) {
                            showUpdateDialog(result.versionName, result.downloadUrl, result.changelog)
                        } else {
                            if (showNoUpdateToast) showToast("当前已是最新版本 ($currentVersion)")
                        }
                    } else {
                        if (showNoUpdateToast) showToast("检查更新失败，请稍后重试")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新失败", e)
                withContext(Dispatchers.Main) {
                    if (showNoUpdateToast) showToast("检查更新失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    // 从自建后端获取最新发布信息（携带设备 ABI）
    private suspend fun fetchLatestRelease(): UpdateCheckResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$UPDATE_API_URL?abi=${java.net.URLEncoder.encode(deviceAbi, "UTF-8")}")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "Schedule-App")

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val info = UpdateResponseParser.parse(response)
                if (info != null && info.downloadUrl.isNotEmpty()) {
                    UpdateCheckResult(info.versionCode, info.versionName, info.downloadUrl, info.changelog)
                } else null
            } else {
                Log.w(TAG, "检查更新失败，HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取发布信息失败", e)
            null
        }
    }

    // 检查是否有新版本（按 versionCode 数值比较，避免版本名格式差异）
    private fun isNewVersion(serverVersionCode: Int): Boolean = serverVersionCode > currentVersionCode

    // 简单的 Markdown 到 HTML 转换
    private fun markdownToHtml(markdown: String): String {
        if (markdown.isBlank()) {
            val emptyColor = if (com.cherry.wakeupschedule.ui.theme.ThemeManager.isDarkMode(context)) "#D0D0D0" else "#333333"
            return """
                <!DOCTYPE html>
                <html>
                <body>
                    <p style="color: $emptyColor">暂无更新说明</p>
                </body>
                </html>
            """.trimIndent()
        }
        
        var html = markdown
            // 处理标题
            .replace(Regex("^### (.+)", RegexOption.MULTILINE), "<h3>$1</h3>")
            .replace(Regex("^## (.+)", RegexOption.MULTILINE), "<h2>$1</h2>")
            .replace(Regex("^# (.+)", RegexOption.MULTILINE), "<h1>$1</h1>")
            // 处理加粗和斜体
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
            .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
            // 处理代码
            .replace(Regex("`(.+?)`"), "<code>$1</code>")
            // 处理列表
            .replace(Regex("^- (.+)", RegexOption.MULTILINE), "<li>$1</li>")
            .replace(Regex("^\\* (.+)", RegexOption.MULTILINE), "<li>$1</li>")
            // 处理换行
            .replace(Regex("\\n\\n"), "</p><p>")
            .replace("\n", "<br>")

        // 包裹 HTML 结构（正文色跟随浅色/深色模式）
        val bodyColor = if (com.cherry.wakeupschedule.ui.theme.ThemeManager.isDarkMode(context)) "#D0D0D0" else "#333333"
        html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <style>
                    body {
                        font-family: sans-serif;
                        font-size: 30px;
                        line-height: 1.6;
                        padding: 8px;
                        margin: 0;
                        color: $bodyColor;
                    }
                    h1 { font-size: 36px; margin: 10px 0; }
                    h2 { font-size: 33px; margin: 10px 0; }
                    h3 { font-size: 31px; margin: 10px 0; }
                    p { margin: 8px 0; }
                    li { margin-left: 16px; margin-bottom: 6px; }
                    code {
                        background: #f0f0f0;
                        padding: 2px 6px;
                        border-radius: 3px;
                        font-size: 28px;
                    }
                </style>
            </head>
            <body>
                <p>$html</p>
            </body>
            </html>
        """.trimIndent()
        return html
    }

    // 显示更新对话框
    private fun showUpdateDialog(version: String, url: String, notes: String) {
        val dialogView = LayoutInflater.from(context).inflate(com.cherry.wakeupschedule.R.layout.dialog_update, null)
        
        val tvVersionInfo = dialogView.findViewById<TextView>(com.cherry.wakeupschedule.R.id.tv_version_info)
        val webViewNotes = dialogView.findViewById<WebView>(com.cherry.wakeupschedule.R.id.webview_notes)
        
        tvVersionInfo.text = "发现新版本: $version\n当前版本: $currentVersion"
        
        // 配置 WebView 确保内容能正常显示
        webViewNotes.settings.javaScriptEnabled = false
        webViewNotes.isVerticalScrollBarEnabled = true
        webViewNotes.isHorizontalScrollBarEnabled = false
        webViewNotes.settings.useWideViewPort = true
        webViewNotes.settings.loadWithOverviewMode = true
        webViewNotes.setBackgroundColor(0x00000000) // 透明背景
        webViewNotes.settings.setSupportZoom(true)
        webViewNotes.settings.builtInZoomControls = true
        webViewNotes.settings.displayZoomControls = false
        webViewNotes.settings.textZoom = 150
        
        val htmlContent = markdownToHtml(notes)
        webViewNotes.loadDataWithBaseURL(null, htmlContent, "text/html; charset=UTF-8", "UTF-8", null)
        
        val dialog = StyledDialog.Builder(context)
            .view(dialogView)
            .show()

        dialogView.findViewById<TextView>(com.cherry.wakeupschedule.R.id.btn_download_original).setOnClickListener {
            openDownloadPage(url)
            dialog.dismiss()
        }
        dialogView.findViewById<TextView>(com.cherry.wakeupschedule.R.id.btn_later).setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun openDownloadPage(downloadUrl: String) {
        try {
            // 使用内置 WebViewActivity 打开下载页面
            // WebViewActivity 会检测 APK 文件并使用系统浏览器处理
            val intent = Intent(context, WebViewActivity::class.java).apply {
                putExtra("url", downloadUrl)
                putExtra("title", "下载更新")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            showToast("无法打开下载页面")
        }
    }

    private fun openDownloadWithSystemBrowser(downloadUrl: String) {
        try {
            // 直接用系统浏览器打开，处理 Proxy 等直接下载链接
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            showToast("无法打开下载页面")
        }
    }

    private fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** 后端返回的最新版本信息 */
data class UpdateCheckResult(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: String,
)

// ---- 新后端响应解析（供单元测试）----
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
)

object UpdateResponseParser {

    /** 解析后端 GET /api/versions/latest 响应；结构不符返回 null。 */
    fun parse(raw: String): UpdateInfo? {
        return try {
            val json = org.json.JSONObject(raw)
            if (json.optInt("code") != 0) return null
            val data = json.getJSONObject("data")
            val files = data.getJSONArray("files")
            if (files.length() == 0) return null
            val first = files.getJSONObject(0)
            UpdateInfo(
                versionCode = data.getInt("versionCode"),
                versionName = data.optString("versionName", ""),
                changelog = data.optString("changelog", ""),
                downloadUrl = first.optString("url", ""),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isNewer(serverCode: Int, currentCode: Int): Boolean = serverCode > currentCode
}
