package com.cherry.wakeupschedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 系统改时间 / 改时区 / 跨天（DATE_CHANGED）时刷新小组件。
 *
 * 性能口径（工作流 C）：没有实例时不再触发 4 个 provider 的刷新广播（现在 triggerUpdate
 * 本身也是进程内直调 + 无实例短路），并把旧的时间敏感闹钟撤销/重排，避免按旧时钟继续等。
 */
class WidgetTimeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        when (intent?.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // 没有实例：没有任何可见内容需要刷新（残留闹钟醒来时会在各自入口自行撤销）
                if (!ScheduleWidgetUpdateService.hasAnyWidget(context)) return
                // 更新小组件（triggerUpdate 已改为进程内直调，不再逐款发广播）
                ScheduleWidgetProvider.triggerUpdate(context)
                ScheduleWideWidgetProvider.triggerUpdate(context)
                MinimalWidgetProvider.triggerUpdateNow(context)
                NextCourseWidgetProvider.triggerUpdate(context)
                NextCourseCompactWidgetProvider.triggerUpdate(context)
                WidgetMidnightReceiver.scheduleMidnightUpdate(context)
                ScheduleWidgetUpdateService.scheduleNextUpdate(context)
            }
        }
    }
}
