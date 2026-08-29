package com.cherry.wakeupschedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WidgetTimeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        when (intent?.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // 更新小组件
                ScheduleWidgetProvider.triggerUpdate(context)
                MinimalWidgetProvider.triggerUpdate(context)
                WidgetMidnightReceiver.scheduleMidnightUpdate(context)
                ScheduleWidgetUpdateService.scheduleNextUpdate(context)
            }
        }
    }
}
