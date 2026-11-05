package com.cherry.wakeupschedule.ui.feedback

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.StringRes

/**
 * 应用内浮动提示 —— 全项目统一的消息提示入口，替代 `android.widget.Toast`。
 *
 * ### 为什么不用系统 Toast
 * 系统 Toast 是 `TYPE_TOAST` 系统窗口：退出应用后仍悬浮在桌面上，且样式完全不受
 * App 主题控制。本组件的提示卡平时挂在当前页面的 `android.R.id.content` 里，属于应用窗口，
 * 退到桌面即不可见；配色走 M3 主题属性，自动跟随 5 套调色板、深色模式与 App 内字号档位。
 * **弹层打开时**容器会搬进最上层弹层的窗口（见 [registerDialogHost]），保证提示显示在
 * 弹层遮罩/弹层内容之上，而不是被压暗或盖住。
 *
 * ### 用法
 * ```kotlin
 * AppToast.success(this, "保存成功")
 * AppToast.info(this, "正在下载课程表...")
 * AppToast.warn(this, "请先绑定教务账号")
 * AppToast.error(this, "下载失败: ${e.message}")
 *
 * // 动态文案需要归并成一条时，显式给 groupKey（同键 2.5s 内只显示一张卡并累加 ×N）
 * AppToast.error(this, "下载失败: HTTP 500", groupKey = "download_failed")
 * AppToast.error(this, "下载失败: HTTP 502", groupKey = "download_failed")
 *
 * // 长文案、需要更多阅读时间
 * AppToast.error(this, msg, AppToast.Duration.LONG)
 * ```
 *
 * ### 排队与合并
 * - 同屏最多 3 张卡，其余进等待队列（容量 8），按触发顺序显示。
 * - 同类型 + 同文案（或同 [groupKey]）在 2.5s 内重复触发会**合并**为一张卡，右侧出现 `×N`。
 * - 错误提示可抢占普通提示；但被抢占的卡片展示不足 400ms 时改为插队，避免闪烁。
 * - 应用在后台时提示暂存 8s，页面回来后补发；超时则丢弃并打日志（不会像系统 Toast 那样事后弹出）。
 *
 * ### 线程
 * 任意线程可调用；非主线程会自动 post 到主 Looper。
 */
object AppToast {

    /** 提示语义类型。优先级决定排队与抢占顺序（数值越大越优先） */
    enum class Type(val priority: Int, val label: String) {
        INFO(0, "提示"),
        SUCCESS(1, "成功"),
        WARN(2, "警告"),
        ERROR(3, "错误"),
    }

    /** 展示时长档位，对应原来的 `Toast.LENGTH_SHORT` / `LENGTH_LONG` */
    enum class Duration(val millis: Long) {
        SHORT(2200L),
        LONG(3600L),
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 成功 ─────────────────────────────────────────────

    fun success(
        context: Context,
        message: String,
        duration: Duration = Duration.SHORT,
        groupKey: String? = null,
    ) = show(context, Type.SUCCESS, message, duration, groupKey)

    fun success(
        context: Context,
        @StringRes messageRes: Int,
        duration: Duration = Duration.SHORT,
        groupKey: String? = null,
    ) = success(context, context.getString(messageRes), duration, groupKey)

    // ── 信息 ─────────────────────────────────────────────

    fun info(
        context: Context,
        message: String,
        duration: Duration = Duration.SHORT,
        groupKey: String? = null,
    ) = show(context, Type.INFO, message, duration, groupKey)

    fun info(
        context: Context,
        @StringRes messageRes: Int,
        duration: Duration = Duration.SHORT,
        groupKey: String? = null,
    ) = info(context, context.getString(messageRes), duration, groupKey)

    // ── 警告 ─────────────────────────────────────────────

    fun warn(
        context: Context,
        message: String,
        duration: Duration = Duration.LONG,
        groupKey: String? = null,
    ) = show(context, Type.WARN, message, duration, groupKey)

    fun warn(
        context: Context,
        @StringRes messageRes: Int,
        duration: Duration = Duration.LONG,
        groupKey: String? = null,
    ) = warn(context, context.getString(messageRes), duration, groupKey)

    // ── 错误 ─────────────────────────────────────────────

    fun error(
        context: Context,
        message: String,
        duration: Duration = Duration.LONG,
        groupKey: String? = null,
    ) = show(context, Type.ERROR, message, duration, groupKey)

    fun error(
        context: Context,
        @StringRes messageRes: Int,
        duration: Duration = Duration.LONG,
        groupKey: String? = null,
    ) = error(context, context.getString(messageRes), duration, groupKey)

    // ── 通用 ─────────────────────────────────────────────

    /**
     * 提交一条提示。空白文案直接忽略（原 Toast 会显示一个空胶囊）。
     */
    fun show(
        context: Context,
        type: Type,
        message: String,
        duration: Duration = Duration.SHORT,
        groupKey: String? = null,
    ) {
        if (message.isBlank()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ToastManager.submit(context, type, message, duration, groupKey)
        } else {
            mainHandler.post { ToastManager.submit(context, type, message, duration, groupKey) }
        }
    }

    /** 清空所有排队与在显示的提示（退出登录、清理数据等场景） */
    fun cancelAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ToastManager.cancelAll()
        } else {
            mainHandler.post { ToastManager.cancelAll() }
        }
    }

    // ── 弹层宿主接线（由各 Dialog 创建处调用）──────────────

    /**
     * 把一个弹层登记为提示宿主：弹层显示期间，提示卡会渲染进该弹层自己的窗口顶部，
     * 从而不会被独立 Dialog 窗口的遮罩压暗、也不会被弹层内容盖住
     * （对应 docs/UI-优化方案.md「Toast 暗区」）。
     *
     * 内部只监听弹层 DecorView 的 attach/detach（即窗口显示/关闭），不占用弹层自身的
     * [Dialog.setOnShowListener] / [Dialog.setOnDismissListener]，对弹层现有逻辑零侵入。
     * 在弹层 `show()` 之前调用即可。
     */
    fun registerDialogHost(dialog: Dialog) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val decor = dialog.window?.decorView ?: return
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                ToastManager.onDialogAttached(dialog)
            }

            override fun onViewDetachedFromWindow(v: View) {
                ToastManager.onDialogDetached(dialog)
            }
        })
    }

    // ── 生命周期接线（仅供 App 的 ActivityLifecycleCallbacks 调用）──────

    /** 前台页面就绪：把提示容器挂到该页面，并补发在后台暂存的提示 */
    fun onActivityResumed(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        ToastManager.onHostResumed(activity)
    }

    /** 页面销毁：摘掉容器但保留队列，等下一个页面接管 */
    fun onActivityDestroyed(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        ToastManager.onHostDestroyed(activity)
    }
}
