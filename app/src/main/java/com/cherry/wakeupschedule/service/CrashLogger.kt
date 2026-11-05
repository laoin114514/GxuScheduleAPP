package com.cherry.wakeupschedule.service

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场记录器。
 *
 * 应用走自有更新服务（不经应用市场），用户报「一刷新课表就闪退」时往往拿不到 logcat。
 * 这里把未捕获异常的完整栈落盘，保留最近一次，方便用户直接把文件发给开发者：
 * - 内部私有目录：[Context.getFilesDir]/crash/last-crash.txt（adb run-as 可取，debug 方便）
 * - 外部私有目录：[Context.getExternalFilesDir]/crash/last-crash.txt
 *   （`Android/data/com.cherry.wakeupschedule/files/crash/`，用户在文件管理器里能找到，
 *   无需任何存储权限）
 *
 * 约定：
 * 1. 只在真正未捕获时写一次（覆盖写），不会无限增长；
 * 2. 记录完成后**继续交给系统默认处理器**，不拦截、不吞崩溃（系统统计与弹窗行为不变）；
 * 3. 落盘本身失败绝不能影响崩溃处理 —— 全程 try/catch。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val DIR_NAME = "crash"
    private const val FILE_NAME = "last-crash.txt"

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 调用一次；幂等。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (t: Throwable) {
                Log.e(TAG, "写入崩溃日志失败", t)
            }
            // 交给系统默认处理器：保持原有的崩溃上报/退出行为，不改变用户可见结果
            previous?.uncaughtException(thread, error)
        }
    }

    /** 读取最近一次崩溃文本（内部目录优先）；没有返回 null。 */
    fun readLastCrash(context: Context): String? {
        val internal = File(File(context.filesDir, DIR_NAME), FILE_NAME)
        return try {
            if (internal.isFile) internal.readText() else null
        } catch (t: Throwable) {
            Log.e(TAG, "读取崩溃日志失败", t)
            null
        }
    }

    private fun write(app: Context, thread: Thread, error: Throwable) {
        val text = buildString {
            appendLine("===== 西大课栈崩溃记录 =====")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.CHINA).format(Date())}")
            appendLine("版本: ${appVersion(app)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("线程: ${thread.name}")
            appendLine()
            appendLine(Log.getStackTraceString(error))
        }
        writeBoth(app, text)
        // 同时打一条带统一 tag 的日志，便于 adb logcat 直接过滤
        Log.e(TAG, "未捕获异常已落盘：\n$text")
    }

    private fun writeBoth(app: Context, text: String) {
        val targets = listOfNotNull(
            File(File(app.filesDir, DIR_NAME), FILE_NAME),
            app.getExternalFilesDir(null)?.let { File(File(it, DIR_NAME), FILE_NAME) },
        )
        for (file in targets) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(text)
            } catch (t: Throwable) {
                Log.e(TAG, "崩溃日志写入失败: ${file.absolutePath}", t)
            }
        }
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // longVersionCode 是 API 28 才有的方法，minSdk 26：低版本用已废弃的 versionCode 兜底，
        // 避免「应用崩溃时连崩溃日志都写不出来」。
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (t: Throwable) {
        "unknown"
    }
}
