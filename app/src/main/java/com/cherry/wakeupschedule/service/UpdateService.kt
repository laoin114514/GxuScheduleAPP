package com.cherry.wakeupschedule.service

import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.cherry.wakeupschedule.BuildConfig
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新服务
 * 从自有后端检查最新版本（versionCode 整数比较），确认后应用内下载并引导安装。
 */
class UpdateService(private val context: Context) {

    companion object {
        private const val TAG = "UpdateService"

        /** 后端地址，部署后改成自己的域名（不带结尾斜杠） */
        private const val UPDATE_BASE_URL = "https://easo.laoin.work/schedule"

        /** 与后端 / workflow 上传时一致的 appKey */
        private const val APP_KEY = "schedule"

        // DownloadManager.ERROR_* 部分常量只在 API 31+ 暴露，这里用官方数值做错误文案映射
        private const val DM_ERROR_FILE_ALREADY_EXISTS = 1001L
        private const val DM_ERROR_FILE_ACCESS = 1002L
        private const val DM_ERROR_INSUFFICIENT_SPACE = 1003L
        private const val DM_ERROR_CANNOT_RESUME = 1004L
        private const val DM_ERROR_HTTP_4XX = 4001L
        private const val DM_ERROR_HTTP_5XX = 5001L
    }

    private val currentVersionName: String = BuildConfig.VERSION_NAME
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE

    /** 下载完成但缺少安装权限时暂存的文件，等从设置页返回后重试安装 */
    private var pendingInstallFile: File? = null

    /** 最新版本信息 */
    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersionCode: Long,
        val latestVersionName: String,
        val changelog: String,
        val downloadUrl: String,
        val sha256: String,
        val fileSize: Long,
        val forced: Boolean,
    )

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
                val info = fetchUpdateInfo()
                if (info?.hasUpdate == true) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(info)
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
                val info = fetchUpdateInfo()
                withContext(Dispatchers.Main) {
                    when {
                        info == null -> {
                            if (showNoUpdateToast) showToast("检查更新失败，请稍后重试")
                        }
                        info.hasUpdate -> showUpdateDialog(info)
                        showNoUpdateToast -> showToast("当前已是最新版本 (v$currentVersionName)")
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

    /**
     * 从后端获取最新版本信息。
     * versionCode 口径与 workflow 一致（BuildConfig.VERSION_CODE）。
     */
    private suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$UPDATE_BASE_URL/api/v1/apps/$APP_KEY/latest?versionCode=$currentVersionCode")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "GxuScheduleAPP/$currentVersionName")

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                if (json.optInt("code", -1) != 0) return@withContext null
                val data = json.optJSONObject("data") ?: return@withContext null
                UpdateInfo(
                    hasUpdate = data.optBoolean("hasUpdate", false),
                    latestVersionCode = data.optLong("latestVersionCode", 0),
                    latestVersionName = data.optString("latestVersionName", ""),
                    changelog = data.optString("changelog", ""),
                    downloadUrl = data.optString("downloadUrl", ""),
                    sha256 = data.optString("sha256", ""),
                    fileSize = data.optLong("fileSize", 0),
                    forced = data.optBoolean("forced", false),
                )
            } else {
                Log.w(TAG, "fetch update failed: HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取更新信息失败", e)
            null
        }
    }

    // 显示更新对话框
    private fun showUpdateDialog(info: UpdateInfo) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)

        val tvVersionInfo = dialogView.findViewById<TextView>(R.id.tv_version_info)
        val webViewNotes = dialogView.findViewById<WebView>(R.id.webview_notes)
        val btnUpdateNow = dialogView.findViewById<TextView>(R.id.btn_update_now)
        val btnLater = dialogView.findViewById<TextView>(R.id.btn_later)

        tvVersionInfo.text = "发现新版本: v${info.latestVersionName}\n当前版本: v$currentVersionName"

        // 强制更新时隐藏"稍后"
        btnLater.visibility = if (info.forced) android.view.View.GONE else android.view.View.VISIBLE

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

        val htmlContent = markdownToHtml(info.changelog)
        webViewNotes.loadDataWithBaseURL(null, htmlContent, "text/html; charset=UTF-8", "UTF-8", null)

        val dialog = StyledDialog.Builder(context)
            .view(dialogView)
            .show()

        btnUpdateNow.setOnClickListener {
            dialog.dismiss()
            startDownload(info)
        }
        btnLater.setOnClickListener {
            dialog.dismiss()
        }
    }

    // 应用内下载（DownloadManager，退出页面/后台都继续下载）
    private fun startDownload(info: UpdateInfo) {
        if (info.downloadUrl.isBlank()) {
            showToast("下载地址为空，无法更新")
            return
        }
        try {
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val apkFile = File(downloadDir, "GxuScheduleAPP-${info.latestVersionName}-universal.apk")
            // 同版本号重下时覆盖旧文件
            if (apkFile.exists()) apkFile.delete()

            // 兼容后端返回不带协议的下载地址（如 static.laoin.work/...），DownloadManager 要求完整 URI
            val downloadUri = info.downloadUrl.let {
                if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
            }
            val request = DownloadManager.Request(Uri.parse(downloadUri))
                .setTitle("西大课栈更新")
                .setDescription("v${info.latestVersionName}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, apkFile.name)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            showProgressDialog(downloadId, apkFile, info)
        } catch (e: Exception) {
            Log.e(TAG, "start download failed", e)
            showToast("无法开始下载: ${e.message ?: "未知错误"}")
        }
    }

    // 下载进度弹窗
    private fun showProgressDialog(downloadId: Long, apkFile: File, info: UpdateInfo) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update_progress, null)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_progress_status)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tv_progress_percent)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_download)

        tvStatus.text = "正在下载 v${info.latestVersionName} ..."
        tvPercent.text = "0%"

        val dialog = StyledDialog.Builder(context)
            .title("下载更新")
            .view(dialogView)
            .negativeButton("取消") {
                try {
                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                } catch (e: Exception) {
                    Log.e(TAG, "cancel download failed", e)
                }
            }
            .show()

        val handler = Handler(Looper.getMainLooper())
        val pollRunnable = object : Runnable {
            override fun run() {
                pollDownloadProgress(downloadId, tvStatus, tvPercent, progressBar, dialog, apkFile, info, handler, this)
            }
        }
        // 弹窗关闭（取消/失败/完成）时停止轮询
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        handler.post(pollRunnable)
    }

    private fun pollDownloadProgress(
        downloadId: Long,
        tvStatus: TextView,
        tvPercent: TextView,
        progressBar: LinearProgressIndicator,
        dialog: Dialog,
        apkFile: File,
        info: UpdateInfo,
        handler: Handler,
        runnable: Runnable,
    ) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var cursor: android.database.Cursor? = null
        try {
            cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
            if (cursor == null || !cursor.moveToFirst()) {
                handler.postDelayed(runnable, 400)
                return
            }
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    progressBar.max = 100
                    progressBar.progress = percent
                    tvPercent.text = "$percent%"
                    tvStatus.text = if (total > 0) {
                        "正在下载 v${info.latestVersionName} ...  ${formatSize(done)} / ${formatSize(total)}"
                    } else {
                        "正在下载 v${info.latestVersionName} ...  ${formatSize(done)}"
                    }
                    handler.postDelayed(runnable, 400)
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    tvPercent.text = "100%"
                    tvStatus.text = "下载完成，正在校验..."
                    onDownloadSucceeded(dialog, apkFile, info)
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    dialog.dismiss()
                    showToast("下载失败: ${downloadErrorText(reason)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "poll progress failed", e)
            handler.postDelayed(runnable, 400)
        } finally {
            cursor?.close()
        }
    }

    private fun onDownloadSucceeded(dialog: Dialog, apkFile: File, info: UpdateInfo) {
        // 校验后端下发的 SHA256，防止内容被劫持/篡改
        verifySha256(apkFile, info.sha256) { ok ->
            dialog.dismiss()
            if (ok) {
                promptInstall(apkFile, info.latestVersionName)
            } else {
                apkFile.delete()
                showToast("文件校验失败，请重新下载")
            }
        }
    }

    private fun verifySha256(file: File, expected: String, onResult: (Boolean) -> Unit) {
        if (expected.isBlank()) {
            onResult(true)
            return
        }
        Thread {
            val ok = try {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
                    .equals(expected, ignoreCase = true)
            } catch (e: Exception) {
                Log.e(TAG, "sha256 verify failed", e)
                false
            }
            Handler(Looper.getMainLooper()).post { onResult(ok) }
        }.start()
    }

    private fun promptInstall(file: File, newVersionName: String) {
        Log.i(TAG, "promptInstall: canRequestPackageInstalls=${canRequestPackageInstalls()}")
        if (canRequestPackageInstalls()) {
            StyledDialog.Builder(context)
                .title("更新完成")
                .message("已下载新版本 v$newVersionName 的安装包，是否立即安装？")
                .positiveButton("立即安装") { installApk(file) }
                .negativeButton("稍后")
                .show()
        } else {
            pendingInstallFile = file
            showInstallPermissionPrompt()
        }
    }

    private fun showInstallPermissionPrompt() {
        StyledDialog.Builder(context)
            .title("需要授权")
            .message("安装应用需要开启\"安装未知应用\"权限")
            .positiveButton("去设置") {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "open install permission settings failed", e)
                }
            }
            .negativeButton("取消") { pendingInstallFile = null }
            .show()
    }

    /**
     * 从"安装未知应用"设置页返回后调用（如 AboutActivity.onResume），
     * 权限就绪则自动继续安装。
     */
    fun retryPendingInstall() {
        val file = pendingInstallFile ?: return
        Log.i(TAG, "retryPendingInstall: file=$file canRequest=${canRequestPackageInstalls()}")
        if (canRequestPackageInstalls()) {
            pendingInstallFile = null
            installApk(file)
        } else {
            showInstallPermissionPrompt()
        }
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            Log.i(TAG, "install intent launched: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "launch install failed", e)
            showToast("安装失败: ${e.message ?: "未知错误"}")
        }
    }

    private fun downloadErrorText(reason: Int): String = when (reason.toLong()) {
        DM_ERROR_HTTP_4XX -> "HTTP 4xx 错误"
        DM_ERROR_HTTP_5XX -> "服务器错误"
        DM_ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DM_ERROR_CANNOT_RESUME -> "无法续传"
        DM_ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
        DM_ERROR_FILE_ACCESS -> "文件访问错误"
        else -> "未知错误($reason)"
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 10) "${(mb).toInt()}MB" else String.format("%.1fMB", mb)
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

    private fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
