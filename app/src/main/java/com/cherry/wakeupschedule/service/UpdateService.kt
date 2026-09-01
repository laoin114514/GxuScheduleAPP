package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager

import android.net.NetworkCapabilities
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 更新服务
 * 从自有后端检查最新版本（versionCode 整数比较），确认后应用内下载并引导安装。
 *
 * 下载走自实现流式落盘（OkHttp 16KB 缓冲逐块写文件，不整包进内存）：
 * - 先写 <name>.apk.part，完成并校验（SHA256 + 应用签名一致）后改为正式文件名
 * - 同版本已下载完全时跳过下载直接安装；版本/校验不一致则删除旧包与记录重下
 * - 取消/中断时保留 .part 与元数据，下次 Range 续传
 */
class UpdateService(private val context: Context) {

    companion object {
        private const val TAG = "UpdateService"

        /** 后端地址，部署后改成自己的域名（不带结尾斜杠） */
        private const val UPDATE_BASE_URL = "https://easo.laoin.work/schedule"

        /** 与后端 / workflow 上传时一致的 appKey */
        private const val APP_KEY = "schedule"

        /** 流式下载缓冲大小 */
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024

        /** 下载后的记录文件后缀 */
        private const val META_SUFFIX = ".meta.json"

        /** 下载中的临时文件后缀 */
        private const val PART_SUFFIX = ".part"

        /** 清理下载目录中超过该年龄的 apk/记录/临时文件 */
        private const val STALE_FILE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** 下载前预留的剩余空间（安装包本身之外留 50MB 余量） */
        private const val SPACE_RESERVE_BYTES = 50L * 1024 * 1024

        /** Application 级下载协程域：Activity 销毁后下载继续；进程被杀则中断（保留进度可续传） */
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 当前进行中的下载任务（跨 UpdateService 实例共享） */
        @Volatile
        private var activeJob: Job? = null

        /**
         * 更新提示（红点）状态变化回调，主线程回调。
         * 同一时刻仅允许一个前台页面注册（ProfileFragment / AboutActivity）。
         */
        @Volatile
        var hintChangedListener: (() -> Unit)? = null

        /** 通知已注册的页面刷新更新红点 */
        private fun notifyHintChanged() {
            Handler(Looper.getMainLooper()).post { hintChangedListener?.invoke() }
        }

        /** 下载用 OkHttp 客户端单例：大文件下载读超时给足余量 */
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
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

    /** 下载目标文件的落盘记录（与 apk 同目录的 .meta.json） */
    data class UpdateFileMeta(
        val versionCode: Long,
        val versionName: String,
        val sha256: String,
        val fileSize: Long,
        val downloadedBytes: Long,
        val complete: Boolean,
        val downloadedAt: Long,
    )

    /** 下载结果 */
    private sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        object Canceled : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()
    }

    // 静默检查更新（不显示任何提示，只在新版本时弹出对话框；用户跳过的版本不再提示）
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
                    recordLatestSeen(info, settingsManager)
                    if (info.latestVersionCode != settingsManager.getSkippedUpdateVersionCode()) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(info)
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
                val settingsManager = SettingsManager(context)
                if (showNoUpdateToast) showToast("正在检查更新...")
                val info = fetchUpdateInfo()
                withContext(Dispatchers.Main) {
                    when {
                        info == null -> {
                            if (showNoUpdateToast) showToast("检查更新失败，请稍后重试")
                        }
                        info.hasUpdate -> {
                            recordLatestSeen(info, settingsManager)
                            if (info.latestVersionCode == settingsManager.getSkippedUpdateVersionCode()) {
                                if (showNoUpdateToast) showToast("当前版本已跳过，等待新版本发布")
                            } else {
                                showUpdateDialog(info)
                            }
                        }
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
     * 记录最近检查到的最新版本并通知界面刷新红点（有新版本可更新提示）。
     */
    private fun recordLatestSeen(info: UpdateInfo, settingsManager: SettingsManager) {
        settingsManager.setLastSeenLatestVersionCode(info.latestVersionCode)
        notifyHintChanged()
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
        val btnSkipVersion = dialogView.findViewById<TextView>(R.id.btn_skip_version)

        tvVersionInfo.text = "发现新版本: v${info.latestVersionName}\n当前版本: v$currentVersionName"

        // 强制更新时隐藏"跳过此版本"和"稍后"
        btnLater.visibility = if (info.forced) android.view.View.GONE else android.view.View.VISIBLE
        btnSkipVersion.visibility = if (info.forced) android.view.View.GONE else android.view.View.VISIBLE

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
        // 跳过此版本：本版本不再提醒，记录后红点同步熄灭
        btnSkipVersion.setOnClickListener {
            dialog.dismiss()
            SettingsManager(context).setSkippedUpdateVersionCode(info.latestVersionCode)
            notifyHintChanged()
            showToast("已跳过 v${info.latestVersionName}，有新版本时会再提醒")
        }
    }

    // ========== 下载 ==========

    // 应用内流式下载入口：复用判断 → 空间预检 → 网络策略 → 启动
    private fun startDownload(info: UpdateInfo) {
        if (info.downloadUrl.isBlank()) {
            showToast("下载地址为空，无法更新")
            return
        }

        // 本地已有同版本完整安装包：跳过下载直接安装
        cachedApkFor(info)?.let {
            Log.i(TAG, "本地已有同版本完整安装包，跳过下载: ${it.name}")
            promptInstall(it, info.latestVersionName)
            return
        }

        // 未完成的续传进度（版本一致且 SHA 一致）保留给 Range 续传；
        // 其余情况（无记录/已下载完/版本或 SHA 不一致）删除旧包与记录文件重新下载
        val meta = loadMeta(info)
        val incompleteResume = meta != null &&
            !meta.complete &&
            meta.versionCode == info.latestVersionCode &&
            meta.sha256.equals(info.sha256, ignoreCase = true)
        if (!incompleteResume) {
            apkFile(info).delete()
            partFile(info).delete()
            metaFile(info).delete()
        }
        cleanupOtherVersionFiles(info)

        // 下载进行中防并发
        if (activeJob?.isActive == true) {
            showToast("下载已在进行中")
            return
        }

        // 网络可用性预检
        if (!hasNetwork()) {
            showToast("无网络连接，无法下载")
            return
        }

        // 存储空间预检
        val downloadDir = downloadDir()
        if (info.fileSize > 0 && downloadDir.usableSpace < info.fileSize + SPACE_RESERVE_BYTES) {
            showToast("存储空间不足，无法下载更新")
            return
        }

        // 移动数据下提示流量消耗，Wi-Fi 直接下载
        if (!isWifiActive()) {
            val sizeText = if (info.fileSize > 0) "约 ${formatSize(info.fileSize)}" else "少量"
            StyledDialog.Builder(context)
                .title("流量提示")
                .message("当前为移动数据网络，本次更新将消耗 $sizeText 流量，是否继续？")
                .positiveButton("继续下载") { doDownload(info) }
                .negativeButton("取消")
                .show()
        } else {
            doDownload(info)
        }
    }

    /**
     * 本地完整安装包复用：记录版本一致、下载完全且文件仍在，且与服务器 SHA256 一致。
     * 任一不满足返回 null（调用方随后清旧重下）。
     */
    private fun cachedApkFor(info: UpdateInfo): File? {
        val apk = apkFile(info)
        val meta = loadMeta(info) ?: return null
        return if (
            meta.complete &&
            meta.versionCode == info.latestVersionCode &&
            meta.sha256.equals(info.sha256, ignoreCase = true) &&
            apk.exists() &&
            (info.fileSize <= 0 || apk.length() == info.fileSize)
        ) apk else null
    }

    // 删除非当前目标版本的 apk/记录/临时文件，保持下载目录干净
    private fun cleanupOtherVersionFiles(info: UpdateInfo) {
        val target = setOf(apkFileName(info), apkFileName(info) + PART_SUFFIX, apkFileName(info) + META_SUFFIX)
        try {
            downloadDir().listFiles()?.forEach { f ->
                if (f.name in target) return@forEach
                if (f.name.endsWith(".apk") || f.name.endsWith(META_SUFFIX) || f.name.endsWith(PART_SUFFIX)) {
                    Log.i(TAG, "清理旧版本下载文件: ${f.name}")
                    f.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup other versions failed", e)
        }
    }

    // 清理下载目录中超过 30 天的旧文件（保留 keep 指定的目标文件名）
    private fun cleanupStaleFiles(keep: String?) {
        try {
            val now = System.currentTimeMillis()
            downloadDir().listFiles()?.forEach { f ->
                if (f.name == keep) return@forEach
                val isManaged = f.name.endsWith(".apk") ||
                    f.name.endsWith(META_SUFFIX) ||
                    f.name.endsWith(PART_SUFFIX)
                if (isManaged && now - f.lastModified() > STALE_FILE_MAX_AGE_MS) {
                    Log.i(TAG, "清理过期下载文件: ${f.name}")
                    f.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup stale files failed", e)
        }
    }

    // 启动流式下载协程并展示进度弹窗
    private fun doDownload(info: UpdateInfo) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update_progress, null)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_progress_status)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tv_progress_percent)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_download)

        tvStatus.text = "正在下载 v${info.latestVersionName} ..."
        tvPercent.text = "0%"

        val mainHandler = Handler(Looper.getMainLooper())
        val dialog = StyledDialog.Builder(context)
            .title("下载更新")
            .view(dialogView)
            .negativeButton("取消") { activeJob?.cancel() }
            .show()

        val job = downloadScope.launch {
            val result = downloadApk(info) { done, total ->
                mainHandler.post {
                    if (!dialog.isShowing) return@post
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    progressBar.max = 100
                    progressBar.progress = percent
                    tvPercent.text = if (total > 0) "$percent%" else formatSize(done)
                    tvStatus.text = if (total > 0) {
                        "正在下载 v${info.latestVersionName} ...  ${formatSize(done)} / ${formatSize(total)}"
                    } else {
                        "正在下载 v${info.latestVersionName} ...  ${formatSize(done)}"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (dialog.isShowing) dialog.dismiss()
                when (result) {
                    is DownloadResult.Success -> promptInstall(result.file, info.latestVersionName)
                    DownloadResult.Canceled -> showToast("已取消下载，进度已保留")
                    is DownloadResult.Failed -> showToast("下载失败: ${result.reason}")
                }
            }
        }
        activeJob = job
        job.invokeOnCompletion { if (activeJob == job) activeJob = null }
    }

    /**
     * 流式下载核心：OkHttp 单连接 + 16KB 缓冲逐块写盘。
     * - 已有未完成的 .part 且记录版本一致时，发 Range 请求续传（206）；服务器返回 200 则覆盖重下
     * - 完成后流式计算 SHA256 + 校验应用签名，全部通过才把 .part 改名并写入 complete 记录
     */
    private suspend fun downloadApk(info: UpdateInfo, onProgress: (Long, Long) -> Unit): DownloadResult {
        val partFile = partFile(info)
        val url = info.downloadUrl.let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        // 续传起点取记录值与 .part 实际大小的较小者，避免异常中断时记录超前于落盘
        val recorded = loadMeta(info)
            ?.takeIf { !it.complete && it.versionCode == info.latestVersionCode }
            ?.downloadedBytes?.coerceAtLeast(0L) ?: 0L
        val resumeFrom = if (partFile.exists()) minOf(recorded, partFile.length()) else 0L

        val request = Request.Builder()
            .url(url)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return DownloadResult.Failed("HTTP ${response.code}")
                }
                val body = response.body ?: return DownloadResult.Failed("响应内容为空")

                // 206 → 续写；200（服务器不支持/文件已变）→ 清空重下
                val append = response.code == 206 && resumeFrom > 0
                if (!append) partFile.delete()
                val total = when {
                    info.fileSize > 0 -> info.fileSize
                    body.contentLength() > 0 -> body.contentLength() + (if (append) resumeFrom else 0)
                    else -> 0L
                }
                var written = if (append) resumeFrom else 0L

                BufferedInputStream(body.byteStream(), DOWNLOAD_BUFFER_SIZE).use { input ->
                    BufferedOutputStream(FileOutputStream(partFile, append), DOWNLOAD_BUFFER_SIZE).use { output ->
                        val buf = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (true) {
                            // 响应协程取消（进度块间隙检查，保证取消时及时中断）
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }

                // 大小校验：后端声明的 fileSize 与落盘字节必须一致
                if (info.fileSize > 0 && written != info.fileSize) {
                    Log.w(TAG, "downloaded size mismatch: $written != ${info.fileSize}")
                    partFile.delete()
                    metaFile(info).delete()
                    return DownloadResult.Failed("下载内容不完整")
                }

                // 内容校验：SHA256
                if (!verifySha256(partFile, info.sha256)) {
                    partFile.delete()
                    metaFile(info).delete()
                    return DownloadResult.Failed("文件校验失败（SHA256 不匹配）")
                }

                // 签名校验：确保与当前已安装应用签名一致，拦截被替换的安装包
                if (!verifyApkSignature(partFile)) {
                    partFile.delete()
                    metaFile(info).delete()
                    return DownloadResult.Failed("安装包签名校验失败")
                }

                val finalFile = apkFile(info)
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.delete()
                    metaFile(info).delete()
                    return DownloadResult.Failed("保存安装包失败")
                }
                saveMeta(info, written, complete = true, sha256 = info.sha256)

                DownloadResult.Success(finalFile)
            }
        } catch (e: CancellationException) {
            // 取消/中断：保留 .part 与进度记录，供下次 Range 续传
            saveMeta(info, partFile.length(), complete = false, sha256 = info.sha256)
            DownloadResult.Canceled
        } catch (e: Exception) {
            // 网络类异常同样保留进度（HTTP 错误与校验失败已在上方 return 并清理）
            Log.e(TAG, "download failed", e)
            saveMeta(info, partFile.length(), complete = false, sha256 = info.sha256)
            DownloadResult.Failed(e.message ?: "未知错误")
        }
    }

    // ========== 校验 ==========

    /** 流式计算文件 SHA256 并与期望值比较（期望为空视为跳过校验） */
    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return try {
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
    }

    /**
     * 校验归档 APK 的签名证书与当前已安装应用一致。
     * 即使 SHA256 校验通过，也能拦住被替换/伪冒的安装包。
     */
    @Suppress("DEPRECATION")
    private fun verifyApkSignature(file: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val current = pm.getPackageInfo(context.packageName, flags)
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            val currentSig = if (Build.VERSION.SDK_INT >= 28) {
                current.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                current.signatures?.firstOrNull()?.toByteArray()
            }
            val archiveSig = if (Build.VERSION.SDK_INT >= 28) {
                archive?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                archive?.signatures?.firstOrNull()?.toByteArray()
            }
            if (currentSig != null && archiveSig != null) {
                currentSig.contentEquals(archiveSig)
            } else {
                Log.w(TAG, "签名信息缺失: current=${currentSig != null} archive=${archiveSig != null}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "verify signature failed", e)
            false
        }
    }

    // ========== 元数据记录 ==========

    private fun downloadDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    private fun apkFileName(info: UpdateInfo) = "GxuScheduleAPP-${info.latestVersionName}-universal.apk"

    private fun apkFile(info: UpdateInfo) = File(downloadDir(), apkFileName(info))

    private fun partFile(info: UpdateInfo) = File(downloadDir(), apkFileName(info) + PART_SUFFIX)

    private fun metaFile(info: UpdateInfo) = File(downloadDir(), apkFileName(info) + META_SUFFIX)

    private fun loadMeta(info: UpdateInfo): UpdateFileMeta? {
        val file = metaFile(info)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            UpdateFileMeta(
                versionCode = json.optLong("versionCode", 0),
                versionName = json.optString("versionName", ""),
                sha256 = json.optString("sha256", ""),
                fileSize = json.optLong("fileSize", 0),
                downloadedBytes = json.optLong("downloadedBytes", 0),
                complete = json.optBoolean("complete", false),
                downloadedAt = json.optLong("downloadedAt", 0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "load meta failed, will redownload", e)
            null
        }
    }

    private fun saveMeta(info: UpdateInfo, downloadedBytes: Long, complete: Boolean, sha256: String) {
        val json = JSONObject()
            .put("versionCode", info.latestVersionCode)
            .put("versionName", info.latestVersionName)
            .put("sha256", sha256)
            .put("fileSize", info.fileSize)
            .put("downloadedBytes", downloadedBytes)
            .put("complete", complete)
            .put("downloadedAt", System.currentTimeMillis())
        try {
            metaFile(info).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "save meta failed", e)
        }
    }

    // ========== 网络状态 ==========

    private fun hasNetwork(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        Log.w(TAG, "hasNetwork failed", e)
        false
    }

    private fun isWifiActive(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) {
        Log.w(TAG, "isWifi failed", e)
        false
    }

    // ========== 安装 ==========

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
                    <p style="color: $emptyColor;font-size: 30px;line-height: 1.6;padding: 8px;margin: 8px 0;">暂无更新说明</p>
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
