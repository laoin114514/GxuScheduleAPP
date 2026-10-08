package com.cherry.wakeupschedule.widget

/**
 * 「一键添加小组件」的结果核验决策。
 *
 * `AppWidgetManager#requestPinAppWidget` 的返回值只表示桌面**声称**支持主动添加：
 * vivo / iQOO 的桌面会直接吞掉请求，小米 / 红米在缺少「桌面快捷方式」权限时静默不添加，
 * 华为 / 荣耀则无论成败都不回调 PendingIntent。所以调用方只能按「桌面上的组件个数有没有变多」
 * 来判定，并在等满若干轮后给出可操作的手动添加引导。
 *
 * 判定与 UI 解耦放在这里，便于纯 JVM 单测（见 WidgetPinPolicyTest）。
 */
object WidgetPinPolicy {

    /** 判定「没加上」前至少要核验几轮，给系统确认框和桌面落地留时间 */
    const val MIN_ATTEMPTS = 3

    /** 核验轮次上限：系统确认框久留时不能无限轮询，剩下的交给「回到页面再核验」 */
    const val MAX_ATTEMPTS = 8

    /** 一轮核验的结论 */
    enum class Outcome {
        /** 组件数变多了：这次添加成功 */
        ADDED,

        /** 暂时没有结论，过一会儿再核验 */
        RETRY,

        /** 系统确认框出现过，最终却没加上 —— 多半是用户自己取消，温和提示即可 */
        CANCELED,

        /** 桌面从头到尾毫无反应 —— 需要弹手动添加引导（「点了没反应」的那批机型） */
        NO_RESPONSE,
    }

    /**
     * @param baselineCount 发起请求时桌面上该组件的个数
     * @param currentCount 本次核验时桌面上的个数
     * @param attempts 含本次在内已核验的轮次（从 1 开始）
     * @param leftForeground 请求之后本页是否被顶到后台（系统确认框出现过的证据）
     * @param foregroundWithFocus 本页是否仍在前台且持有窗口焦点
     *        —— 为 false 说明系统界面还压在本页之上，此时不该抢着下结论
     */
    fun evaluate(
        baselineCount: Int,
        currentCount: Int,
        attempts: Int,
        leftForeground: Boolean,
        foregroundWithFocus: Boolean,
    ): Outcome {
        if (currentCount > baselineCount) return Outcome.ADDED
        if (attempts < MIN_ATTEMPTS) return Outcome.RETRY
        if (!foregroundWithFocus && attempts < MAX_ATTEMPTS) return Outcome.RETRY
        return if (leftForeground) Outcome.CANCELED else Outcome.NO_RESPONSE
    }
}
