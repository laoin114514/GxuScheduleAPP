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
 * 检查GitHub最新版本并提示用户更新
 */
class UpdateService(private val context: Context) {

    companion object {
        private const val TAG = "UpdateService"
        private const val GITHUB_API_URL = "https://api.github.com/repos/laoin114514/GxuScheduleAPP/releases"
    }

    private val currentVersion: String = com.cherry.wakeupschedule.BuildConfig.VERSION_NAME

    private var latestVersion: String = ""
    private var downloadUrl: String = ""
    private var releaseNotes: String = ""

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
                if (result.first) {
                    val latestVer = result.second
                    val latestUrl = result.third
                    val notes = result.fourth
                    if (isNewVersion(latestVer)) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(latestVer, latestUrl, notes)
                        }
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
                    if (result.first) {
                        val latestVer = result.second
                        val latestUrl = result.third
                        val notes = result.fourth
                        if (isNewVersion(latestVer)) {
                            showUpdateDialog(latestVer, latestUrl, notes)
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

    // 获取最新发布信息（包括预发布版本）
    private suspend fun fetchLatestRelease(): Quartet<Boolean, String, String, String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "Schedule-App")

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val releases = org.json.JSONArray(response)
                if (releases.length() > 0) {
                    // 遍历所有 release，找到最新的版本（包括预发布）
                    for (i in 0 until releases.length()) {
                        val json = releases.getJSONObject(i)
                        val version = json.optString("tag_name", "").removePrefix("v")
                        
                        if (version.isNotEmpty()) {
                            val assets = json.optJSONArray("assets")
                            var apkUrl = ""
                            if (assets != null) {
                                for (j in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(j)
                                    val name = asset.optString("name", "")
                                    if (name.endsWith(".apk")) {
                                        apkUrl = asset.optString("browser_download_url", "")
                                        break
                                    }
                                }
                            }
                            if (apkUrl.isEmpty()) {
                                apkUrl = json.optString("html_url", "")
                            }
                            
                            // 检查这个版本是否比当前找到的更新
                            if (isNewerVersion(version, latestVersion)) {
                                latestVersion = version
                                downloadUrl = apkUrl
                                releaseNotes = json.optString("body", "")
                            }
                        }
                    }
                }

                if (latestVersion.isNotEmpty() && downloadUrl.isNotEmpty()) {
                    Quartet(true, latestVersion, downloadUrl, releaseNotes)
                } else Quartet(false, "", "", "")
            } else {
                Quartet(false, "", "", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取发布信息失败", e)
            Quartet(false, "", "", "")
        }
    }

    // 检查 serverVersion 是否比 currentLatest 更新
    private fun isNewerVersion(serverVersion: String, currentLatest: String): Boolean {
        if (currentLatest.isEmpty()) return true
        return compareVersions(serverVersion, currentLatest) > 0
    }

    // 检查是否有新版本（比当前应用版本更新）
    private fun isNewVersion(serverVersion: String): Boolean {
        if (serverVersion.isEmpty()) return false
        return compareVersions(serverVersion, currentVersion) > 0
    }

    // 比较两个版本号，返回：
    // >0: version1 > version2
    // =0: version1 = version2
    // <0: version1 < version2
    private fun compareVersions(version1: String, version2: String): Int {
        val parts1 = parseVersion(version1)
        val parts2 = parseVersion(version2)
        
        // 比较主版本号
        for (i in 0 until maxOf(parts1.first.size, parts2.first.size)) {
            val v1 = parts1.first.getOrElse(i) { 0 }
            val v2 = parts2.first.getOrElse(i) { 0 }
            if (v1 > v2) return 1
            if (v1 < v2) return -1
        }
        
        // 如果主版本相同，比较预发布标识符
        // 没有预发布标识符的版本 > 有预发布标识符的版本（正式版 > 测试版）
        if (parts1.second.isEmpty() && parts2.second.isNotEmpty()) return 1
        if (parts1.second.isNotEmpty() && parts2.second.isEmpty()) return -1
        
        // 如果都有预发布标识符，逐部分比较
        for (i in 0 until maxOf(parts1.second.size, parts2.second.size)) {
            val p1 = parts1.second.getOrElse(i) { "" }
            val p2 = parts2.second.getOrElse(i) { "" }
            val compare = comparePreReleasePart(p1, p2)
            if (compare != 0) return compare
        }
        
        return 0
    }

    // 解析版本号，返回（数字部分，预发布标识符）
    private fun parseVersion(version: String): Pair<List<Int>, List<String>> {
        val hyphenIndex = version.indexOf('-')
        val mainPart = if (hyphenIndex >= 0) version.substring(0, hyphenIndex) else version
        val preReleasePart = if (hyphenIndex >= 0) version.substring(hyphenIndex + 1) else ""
        
        val mainNumbers = mainPart.split('.').map { it.toIntOrNull() ?: 0 }
        val preReleaseParts = if (preReleasePart.isEmpty()) emptyList() else preReleasePart.split('.')
        
        return Pair(mainNumbers, preReleaseParts)
    }

    // 比较预发布标识符的单个部分
    private fun comparePreReleasePart(p1: String, p2: String): Int {
        // 数字比较
        val num1 = p1.toIntOrNull()
        val num2 = p2.toIntOrNull()
        
        return if (num1 != null && num2 != null) {
            num1.compareTo(num2)
        } else if (num1 != null) {
            -1 // 数字 < 字母
        } else if (num2 != null) {
            1 // 字母 > 数字
        } else {
            p1.compareTo(p2)
        }
    }

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

    // 四元组数据类
    data class Quartet<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
