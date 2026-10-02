package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 小组件午夜更新接收器
 * 用于在凌晨触发小组件更新和设置第二天的闹钟
 */
class WidgetMidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        ScheduleWidgetProvider.triggerUpdate(context)
        ScheduleWideWidgetProvider.triggerUpdate(context)
        MinimalWidgetProvider.triggerUpdate(context)
        WeekViewWidgetProvider.triggerUpdate(context)
        NextCourseWidgetProvider.triggerUpdate(context)
        ScheduleWidgetUpdateService.scheduleNextUpdate(context)
        scheduleMidnightUpdate(context)
        scheduleBackupUpdate(context)
    }

    companion object {
        private const val REQUEST_CODE_MIDNIGHT = 10005
        private const val REQUEST_CODE_MIDNIGHT_BACKUP = 10007

        /**
         * 安排午夜（0点）更新闹钟
         */
        fun scheduleMidnightUpdate(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WidgetMidnightReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_MIDNIGHT,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val calendar = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 安排备用更新闹钟（1点）
         * 作为0点更新失败的备用方案
         */
        private fun scheduleBackupUpdate(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WidgetMidnightReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_MIDNIGHT_BACKUP,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val calendar = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}