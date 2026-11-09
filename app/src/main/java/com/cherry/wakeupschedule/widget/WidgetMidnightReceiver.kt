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
 *
 * 性能口径（工作流 C）：桌面上一个小组件都没有时，onReceive 直接返回、scheduleMidnightUpdate
 * 也只做「撤销旧闹钟」，不再每夜 0 点/1 点把一个空进程唤醒两次。
 */
class WidgetMidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        // 没有实例时既没有可见内容可刷、也不用再排下一次午夜闹钟
        if (!ScheduleWidgetUpdateService.hasAnyWidget(context)) {
            cancelMidnightAlarms(context)
            return
        }
        ScheduleWidgetProvider.triggerUpdate(context)
        ScheduleWideWidgetProvider.triggerUpdate(context)
        MinimalWidgetProvider.triggerUpdateNow(context)
        WeekViewWidgetProvider.triggerUpdate(context)
        NextCourseWidgetProvider.triggerUpdate(context)
        NextCourseCompactWidgetProvider.triggerUpdate(context)
        ScheduleWidgetUpdateService.scheduleNextUpdate(context)
        scheduleMidnightUpdate(context)
        scheduleBackupUpdate(context)
    }

    companion object {
        private const val REQUEST_CODE_MIDNIGHT = 10005
        private const val REQUEST_CODE_MIDNIGHT_BACKUP = 10007

        @Volatile
        private var midnightPendingIntent: PendingIntent? = null

        @Volatile
        private var backupPendingIntent: PendingIntent? = null

        private fun midnightAlarmPendingIntent(context: Context): PendingIntent =
            midnightPendingIntent ?: PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_MIDNIGHT,
                Intent(context, WidgetMidnightReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { midnightPendingIntent = it }

        private fun backupAlarmPendingIntent(context: Context): PendingIntent =
            backupPendingIntent ?: PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_MIDNIGHT_BACKUP,
                Intent(context, WidgetMidnightReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { backupPendingIntent = it }

        /**
         * 安排午夜（0点）更新闹钟。
         * 没有实例时不排（App 每次启动都会调用本方法，没有组件还每夜醒两次纯属白耗电）。
         * 不在这里 cancel：残留的午夜闹钟醒来时 onReceive 会走无实例分支自行撤销。
         */
        fun scheduleMidnightUpdate(context: Context) {
            if (!ScheduleWidgetUpdateService.hasAnyWidget(context)) return
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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
                    midnightAlarmPendingIntent(context)
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
                        backupAlarmPendingIntent(context)
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        backupAlarmPendingIntent(context)
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 撤销 0 点 / 1 点两个闹钟（无实例时调用；本进程没挂过也会尝试一次，覆盖旧进程遗留）
         */
        internal fun cancelMidnightAlarms(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(midnightAlarmPendingIntent(context))
                alarmManager.cancel(backupAlarmPendingIntent(context))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
