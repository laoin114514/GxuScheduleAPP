package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 小组件更新服务
 * 用于管理小组件的定时更新
 */
class ScheduleWidgetUpdateService {

    companion object {
        private const val TAG = "ScheduleWidgetUpdate"

        /**
         * 触发所有小组件更新
         */
        fun triggerUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            
            // 今日课程小组件
            val componentName = ComponentName(context, ScheduleWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isNotEmpty()) {
                val provider = ScheduleWidgetProvider()
                provider.onUpdate(context, appWidgetManager, appWidgetIds)
            }

            // 今日课程 4×2 小组件
            val wideComponentName = ComponentName(context, ScheduleWideWidgetProvider::class.java)
            val wideAppWidgetIds = appWidgetManager.getAppWidgetIds(wideComponentName)
            if (wideAppWidgetIds.isNotEmpty()) {
                val wideProvider = ScheduleWideWidgetProvider()
                wideProvider.onUpdate(context, appWidgetManager, wideAppWidgetIds)
            }

            // 最小化/下课倒计时小组件
            val minimalComponentName = ComponentName(context, MinimalWidgetProvider::class.java)
            val minimalAppWidgetIds = appWidgetManager.getAppWidgetIds(minimalComponentName)
            if (minimalAppWidgetIds.isNotEmpty()) {
                val minimalProvider = MinimalWidgetProvider()
                minimalProvider.onUpdate(context, appWidgetManager, minimalAppWidgetIds)
            }

            // 近日课程小组件
            val upcomingDaysComponentName = ComponentName(context, UpcomingDaysWidgetProvider::class.java)
            val upcomingDaysAppWidgetIds = appWidgetManager.getAppWidgetIds(upcomingDaysComponentName)
            if (upcomingDaysAppWidgetIds.isNotEmpty()) {
                val upcomingDaysProvider = UpcomingDaysWidgetProvider()
                upcomingDaysProvider.onUpdate(context, appWidgetManager, upcomingDaysAppWidgetIds)
            }

            // 一周课程小组件
            val weekViewComponentName = ComponentName(context, WeekViewWidgetProvider::class.java)
            val weekViewAppWidgetIds = appWidgetManager.getAppWidgetIds(weekViewComponentName)
            if (weekViewAppWidgetIds.isNotEmpty()) {
                val weekViewProvider = WeekViewWidgetProvider()
                weekViewProvider.onUpdate(context, appWidgetManager, weekViewAppWidgetIds)
            }

            // 下一门课提醒小组件
            val nextCourseComponentName = ComponentName(context, NextCourseWidgetProvider::class.java)
            val nextCourseAppWidgetIds = appWidgetManager.getAppWidgetIds(nextCourseComponentName)
            if (nextCourseAppWidgetIds.isNotEmpty()) {
                val nextCourseProvider = NextCourseWidgetProvider()
                nextCourseProvider.onUpdate(context, appWidgetManager, nextCourseAppWidgetIds)
            }

            scheduleNextUpdate(context)
        }

        /**
         * 调度下次小组件更新
         */
        fun scheduleNextUpdate(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WidgetUpdateReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WIDGET_UPDATE_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val updateInterval = 30 * 60 * 1000L

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + updateInterval,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + updateInterval,
                        pendingIntent
                    )
                }
                Log.d(TAG, "已调度小组件下次更新")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "调度小组件更新失败：闹钟数量已达系统上限", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "调度小组件更新失败：缺少闹钟权限", e)
            }
        }

        /**
         * 取消定时更新
         */
        fun cancelScheduledUpdate(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WidgetUpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WIDGET_UPDATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "已取消小组件定时更新")
        }

        private const val WIDGET_UPDATE_REQUEST_CODE = 10001
    }
}

/**
 * 小组件更新接收器
 */
class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            ScheduleWidgetUpdateService.triggerUpdate(it)
        }
    }
}

/**
 * 开机广播接收器
 * 设备启动后恢复小组件
 */
class WidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context?.let {
                ScheduleWidgetUpdateService.triggerUpdate(it)
                ScheduleWidgetProvider().schedulePeriodicUpdate(it)
                MinimalWidgetProvider().schedulePeriodicUpdate(it)
                UpcomingDaysWidgetProvider().schedulePeriodicUpdate(it)
                NextCourseWidgetProvider().schedulePeriodicUpdate(it)
                WidgetMidnightReceiver.scheduleMidnightUpdate(it)
            }
        }
    }
}

/**
 * 近日课程小组件周期性更新接收器
 */
class UpcomingDaysPeriodicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            UpcomingDaysWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                AppWidgetManager.getInstance(it).getAppWidgetIds(ComponentName(it, UpcomingDaysWidgetProvider::class.java))
            )
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }
}

/**
 * 下一门课提醒小组件周期性更新接收器
 */
class NextCoursePeriodicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            NextCourseWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                AppWidgetManager.getInstance(it).getAppWidgetIds(ComponentName(it, NextCourseWidgetProvider::class.java))
            )
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }
}

/**
 * 下一门课提醒小组件状态切换接收器（上课/下课/明天开课的精确时刻触发）
 */
class NextCourseTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            NextCourseWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                AppWidgetManager.getInstance(it).getAppWidgetIds(ComponentName(it, NextCourseWidgetProvider::class.java))
            )
        }
    }
}

/**
 * 课程结束时更新小组件
 */
class WidgetCourseEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            val provider = ScheduleWidgetProvider()
            val appWidgetManager = AppWidgetManager.getInstance(it)
            provider.onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(ComponentName(it, ScheduleWidgetProvider::class.java))
            )
            // 今日课程 4×2（两个组件各自的下课精确闹钟都指向本接收器）
            ScheduleWideWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(ComponentName(it, ScheduleWideWidgetProvider::class.java))
            )
            MinimalWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(ComponentName(it, MinimalWidgetProvider::class.java))
            )
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }
}

/**
 * 周期性更新小组件接收器
 */
class WidgetPeriodicUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            val appWidgetManager = AppWidgetManager.getInstance(it)
            ScheduleWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(
                    ComponentName(it, ScheduleWidgetProvider::class.java)
                )
            )
            // 今日课程 4×2 的周期闹钟也指向本接收器，一并刷新
            ScheduleWideWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(
                    ComponentName(it, ScheduleWideWidgetProvider::class.java)
                )
            )
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }
}

/**
 * 最小化小组件周期性更新接收器
 */
class MinimalWidgetPeriodicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            MinimalWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                AppWidgetManager.getInstance(it).getAppWidgetIds(
                    ComponentName(it, MinimalWidgetProvider::class.java)
                )
            )
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }
}

/**
 * 最小化小组件课程结束更新接收器
 */
class MinimalWidgetCourseEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            val appWidgetManager = AppWidgetManager.getInstance(it)
            MinimalWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(
                    ComponentName(it, MinimalWidgetProvider::class.java)
                )
            )
            ScheduleWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(
                    ComponentName(it, ScheduleWidgetProvider::class.java)
                )
            )
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }
}

/**
 * 最小化小组件倒计时安全更新接收器
 * 当倒计时即将归零时触发，确保小组件及时切换到"当前没课"状态
 */
class MinimalWidgetSafetyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            val appWidgetManager = AppWidgetManager.getInstance(it)
            MinimalWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(
                    ComponentName(it, MinimalWidgetProvider::class.java)
                )
            )
            ScheduleWidgetProvider().onUpdate(
                it,
                appWidgetManager,
                appWidgetManager.getAppWidgetIds(
                    ComponentName(it, ScheduleWidgetProvider::class.java)
                )
            )
        }
    }
}

/**
 * 最小化小组件短周期刷新接收器（仅用于 API < 24 的低版本设备）
 */
class MinimalWidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            MinimalWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                AppWidgetManager.getInstance(it).getAppWidgetIds(
                    ComponentName(it, MinimalWidgetProvider::class.java)
                )
            )
        }
    }
}
