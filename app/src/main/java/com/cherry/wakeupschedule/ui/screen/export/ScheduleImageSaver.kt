package com.cherry.wakeupschedule.ui.screen.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 把导出的课表 Bitmap 存进系统相册。
 *
 * - Android 10（Q）及以上：走 MediaStore 分区存储，无需任何存储权限。
 *   先以 IS_PENDING=1 插入，写完后置 0，避免相册扫描到写了一半的图。
 * - Android 9 及以下：MediaStore 还没提供 RELATIVE_PATH，只能写公共 Pictures 目录，
 *   需要 WRITE_EXTERNAL_STORAGE 权限（调用方负责申请，见 ToolsFragment），
 *   写完后用 MediaScannerConnection 通知相册。
 *
 * 统一保存到 Pictures/GxuSchedule/ 相册目录，文件名由
 * [ScheduleExportRenderer.buildFileName] 生成（含周次与时间戳，重复导出不会互相覆盖）。
 */
object ScheduleImageSaver {

    private const val TAG = "ScheduleImageSaver"

    /** 相册内的子目录名 */
    const val ALBUM_DIR = "GxuSchedule"

    private const val MIME_TYPE = "image/png"

    /**
     * 保存 PNG 到系统相册。
     *
     * 该方法内部切到 IO 线程，可在主线程协程里直接调用。
     *
     * @return true = 已成功写入相册；false = 失败（已打日志，调用方提示用户重试即可）
     */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(context, bitmap, fileName)
                } else {
                    saveToPublicPictures(context, bitmap, fileName)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "save schedule image failed: $fileName", t)
                false
            }
        }

    /** Android 10+：MediaStore 分区存储（无权限要求） */
    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val written = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        if (!written) {
            // 写失败要把占位记录删掉，否则相册里会留下一条打不开的空图
            resolver.delete(uri, null, null)
            return false
        }
        // 写入完成：解除 pending，相册即可看到
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)
        return true
    }

    /** Android 9 及以下：写公共 Pictures 目录 + 通知媒体扫描（需要存储写权限） */
    @Suppress("DEPRECATION")
    private fun saveToPublicPictures(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val albumDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_DIR
        )
        if (!albumDir.exists() && !albumDir.mkdirs()) {
            Log.e(TAG, "cannot create album dir: ${albumDir.absolutePath}")
            return false
        }
        val file = File(albumDir, fileName)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return false
        }
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf(MIME_TYPE), null
        )
        return true
    }
}
