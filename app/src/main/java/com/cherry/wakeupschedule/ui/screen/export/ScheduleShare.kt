package com.cherry.wakeupschedule.ui.screen.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 课表图片分享：把导出的整页课表 PNG 落到应用缓存目录，再通过 FileProvider
 * 以 `content://` 形式交给系统分享面板。
 *
 * 设计要点：
 * - **不需要存储权限**：文件写在 app 私有 `cacheDir/schedule-share/`，
 *   FileProvider 的 `cache-path` 已覆盖该目录（见 `res/xml/file_paths.xml`），
 *   Android 7+ 不允许 `file://` 共享，这里统一走 content URI。
 * - **URI 授权**：除 `FLAG_GRANT_READ_URI_PERMISSION` 外还设置 `clipData`，
 *   部分接收方（尤其国内 IM）依赖 clipData 传播 URI 读权限。
 * - **缓存清理**：每次写入前清掉超过 [MAX_FILE_AGE_MS] 的旧分享文件，避免缓存无限增长；
 *   当前文件不删，分享面板还在读取时也能正常访问。
 * - 与「保存到相册」互不影响：相册那份走 MediaStore，分享这份只活在 cache。
 */
object ScheduleShare {

    private const val TAG = "ScheduleShare"

    /** 分享缓存子目录（位于 cacheDir 下） */
    private const val SHARE_DIR = "schedule-share"

    private const val MIME_TYPE = "image/png"

    /** 分享缓存保留时长：超过 7 天的旧图在下次分享时清掉 */
    private const val MAX_FILE_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 把 Bitmap 写成分享用的 PNG 缓存文件。
     *
     * 该方法内部切到 IO 线程，可在主线程协程里直接调用。
     *
     * @param fileName 文件名（与相册一致：课表_第X周_时间戳.png）
     * @return 写好的文件；失败返回 null（已打日志，调用方提示用户重试即可）
     */
    suspend fun prepareShareFile(context: Context, bitmap: Bitmap, fileName: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, SHARE_DIR)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "cannot create share dir: ${dir.absolutePath}")
                    return@withContext null
                }
                pruneOldFiles(dir)

                val file = File(dir, fileName)
                val written = FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!written) {
                    file.delete()
                    return@withContext null
                }
                file
            } catch (t: Throwable) {
                Log.e(TAG, "prepare share file failed: $fileName", t)
                null
            }
        }

    /**
     * 构建系统分享面板 Intent。
     *
     * @param file 由 [prepareShareFile] 写出的缓存文件
     * @param chooserTitle 分享面板标题（如「分享课表」）
     */
    fun buildShareIntent(context: Context, file: File, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            // 部分接收方通过 clipData 拿 URI 授权，与 flag 一起给出更稳
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /** 清理分享目录中超过保留期的旧文件（只在写入新文件前调用，避免影响正在分享的当前文件） */
    private fun pruneOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_FILE_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}
