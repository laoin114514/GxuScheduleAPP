package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 小组件更新服务
 * 用于管理小组件的定时更新
 *
 * 性能口径（工作流 C）：
 * - 所有入口先问 [hasAnyWidget]（进程内缓存，几乎零成本）：桌面上一个组件都没有时
 *   不再逐个 getAppWidgetIds（IPC）、不再实例化 provider、也不再排 30 分钟兜底链；
 * - 刷新统一走 [WidgetPresence] 里缓存的实例 id，省掉每次 refresh 前的 IPC；
 * - 各闹钟接收器在对应组件已不存在时直接返回（旧版本残留闹钟醒来也不再白跑）。
 */
class ScheduleWidgetUpdateService {

    companion object {
        private const val TAG = "ScheduleWidgetUpdate"

        /** 全部 7 款桌面小组件的 provider 类（顺序与 [triggerUpdate] 的刷新顺序一致） */
        private val WIDGET_PROVIDER_CLASSES = arrayOf(
            ScheduleWidgetProvider::class.java,
            ScheduleWideWidgetProvider::class.java,
            MinimalWidgetProvider::class.java,
            UpcomingDaysWidgetProvider::class.java,
            WeekViewWidgetProvider::class.java,
            NextCourseWidgetProvider::class.java,
            NextCourseCompactWidgetProvider::class.java
        )

        private const val WIDGET_UPDATE_REQUEST_CODE = 10001

        /**
         * 所有小组件共用的 15 分钟周期刷新闹钟。
         * requestCode 沿用 2×2「今日课程」原来的 10003，升级后系统里已存在的旧闹钟会被这一条覆盖，
         * 不会留下孤儿闹钟。
         */
        private const val SHARED_PERIODIC_REQUEST_CODE = 10003
        private const val SHARED_PERIODIC_INTERVAL = 15 * 60 * 1000L

        /** 需要 15 分钟兜底刷新的组件（一周课程只在跨天/数据变更时变化，不参与，保持原有刷新时机） */
        private val PERIODIC_PROVIDER_CLASSES = arrayOf(
            ScheduleWidgetProvider::class.java,
            ScheduleWideWidgetProvider::class.java,
            MinimalWidgetProvider::class.java,
            UpcomingDaysWidgetProvider::class.java,
            NextCourseWidgetProvider::class.java,
            NextCourseCompactWidgetProvider::class.java
        )

        /** 30 分钟兜底链的 PendingIntent（进程内复用，避免每次重排都跨进程申请） */
        @Volatile
        private var chainPendingIntent: PendingIntent? = null

        /** 共用的 15 分钟周期闹钟 PendingIntent（进程内复用） */
        @Volatile
        private var periodicPendingIntent: PendingIntent? = null

        /** 共用周期闹钟是否已挂：重复 setInexactRepeating 只会重置锚点，纯浪费 */
        @Volatile
        private var periodicArmed = false

        /** 升级前每款组件各挂一条的重复闹钟是否已清理（只清一次） */
        @Volatile
        private var legacyPeriodicCleaned = false

        /**
         * 任意一款桌面小组件是否至少有一个实例。
         *
         * 供 App 的 TIME_TICK / 刷新链短路：进程内缓存，首次调用查一次 AppWidgetManager，
         * 之后由各 provider 的 onUpdate/onEnabled/onDeleted/onDisabled 维护，不每次 IPC。
         * 线程安全，不持有 Context。
         */
        fun hasAnyWidget(context: Context): Boolean =
            WIDGET_PROVIDER_CLASSES.any { WidgetPresence.isPresent(context, it) }

        /**
         * 触发所有小组件更新。
         *
         * 无任何实例时直接返回（并把兜底链撤掉）；有实例时只刷新「确实还在桌面」的组件，
         * 实例 id 用进程内缓存，不再逐个 getAppWidgetIds。
         */
        fun triggerUpdate(context: Context) {
            if (!hasAnyWidget(context)) {
                // 这里保留 cancel：旧版本/移除瞬间残留的 30 分钟链醒来时会走到这里，一次就自愈
                cancelScheduledUpdate(context)
                return
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)

            // 今日课程小组件
            refreshIfPresent(context, appWidgetManager, ScheduleWidgetProvider::class.java) { appWidgetIds ->
                ScheduleWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }

            // 今日课程 4×2 小组件
            refreshIfPresent(context, appWidgetManager, ScheduleWideWidgetProvider::class.java) { appWidgetIds ->
                ScheduleWideWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }

            // 最小化/下课倒计时小组件
            refreshIfPresent(context, appWidgetManager, MinimalWidgetProvider::class.java) { appWidgetIds ->
                MinimalWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }

            // 近日课程小组件
            refreshIfPresent(context, appWidgetManager, UpcomingDaysWidgetProvider::class.java) { appWidgetIds ->
                UpcomingDaysWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }

            // 一周课程小组件
            refreshIfPresent(context, appWidgetManager, WeekViewWidgetProvider::class.java) { appWidgetIds ->
                WeekViewWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }

            // 下一门课提醒小组件
            refreshIfPresent(context, appWidgetManager, NextCourseWidgetProvider::class.java) { appWidgetIds ->
                NextCourseWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }

            // 下一门课提醒 2×2 小组件
            refreshIfPresent(
                context, appWidgetManager, NextCourseCompactWidgetProvider::class.java
            ) { appWidgetIds ->
                NextCourseCompactWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }

            scheduleNextUpdate(context)
        }

        /**
         * 有实例才实例化 provider 并刷新；没有就纯内存跳过。
         * （旧实现每个 provider 都先 getAppWidgetIds 一次才判断，即 6 次 IPC）
         */
        private inline fun refreshIfPresent(
            context: Context,
            appWidgetManager: AppWidgetManager,
            providerClass: Class<*>,
            refresh: (IntArray) -> Unit
        ) {
            val appWidgetIds = WidgetPresence.widgetIds(context, providerClass)
            if (appWidgetIds.isNotEmpty()) refresh(appWidgetIds)
        }

        /**
         * 调度下次小组件更新
         *
         * 无任何实例时不排（这是 App 每次启动都会调用的入口：没有组件还每 30 分钟醒一次
         * 纯属白耗电）。不在这里 cancel：残留链醒来会在 triggerUpdate 的无实例分支里一次自愈，
         * 每次启动都先付两笔 cancel 的 IPC 不划算。
         */
        fun scheduleNextUpdate(context: Context) {
            if (!hasAnyWidget(context)) return
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pendingIntent = chainAlarmPendingIntent(context)
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
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(chainAlarmPendingIntent(context))
                Log.d(TAG, "已取消小组件定时更新")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 某款小组件最后一个实例被移除后由 provider.onDisabled 调用：
         * - 没有任何需要 15 分钟兜底刷新的组件了 → 撤掉共用周期闹钟；
         * - 桌面上一个组件都不剩 → 再把 30 分钟兜底链和午夜闹钟停掉。
         * 这样既不会长期空转唤醒，也不会误伤还留在桌面上的其它组件。
         */
        fun onProviderDisabled(context: Context) {
            if (!hasAnyPeriodicWidget(context)) cancelPeriodicUpdate(context)
            if (hasAnyWidget(context)) return
            cancelScheduledUpdate(context)
            WidgetMidnightReceiver.cancelMidnightAlarms(context)
        }

        /**
         * 确保「所有小组件共用的一条 15 分钟周期刷新闹钟」已挂上。
         *
         * 旧实现是每款小组件各挂一条 setInexactRepeating（最多 5 条），每条醒来只刷自己；
         * 合并成一条后：唤醒次数降到 1/5，每次醒来把「有实例的 5 款组件」一起刷完
         * （内容都是分钟级，同一次醒来刷新与各自错开刷新在视觉上没有区别 —— 15 分钟兜底
         * 的时效完全等价）。一周课程组件不参与（内容只在跨天/数据变更时变化，保持原时机）。
         */
        fun ensurePeriodicUpdate(context: Context) {
            if (periodicArmed) return
            if (!hasAnyPeriodicWidget(context)) return
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + SHARED_PERIODIC_INTERVAL,
                    SHARED_PERIODIC_INTERVAL,
                    periodicAlarmPendingIntent(context)
                )
                periodicArmed = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // 升级前的 4 条每组件周期闹钟（10004/10009/10010/10015）是重复的，清掉一次，别让它们继续唤醒
            cleanLegacyPeriodicAlarms(context)
        }

        /** 撤掉共用周期闹钟（没有组件需要兜底刷新时） */
        fun cancelPeriodicUpdate(context: Context) {
            periodicArmed = false
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(periodicAlarmPendingIntent(context))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            cleanLegacyPeriodicAlarms(context)
        }

        /** 是否有任何需要 15 分钟兜底刷新的组件 */
        private fun hasAnyPeriodicWidget(context: Context): Boolean =
            PERIODIC_PROVIDER_CLASSES.any { WidgetPresence.isPresent(context, it) }

        private fun periodicAlarmPendingIntent(context: Context): PendingIntent =
            periodicPendingIntent ?: PendingIntent.getBroadcast(
                context,
                SHARED_PERIODIC_REQUEST_CODE,
                Intent(context, WidgetPeriodicUpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { periodicPendingIntent = it }

        /**
         * 清理旧版本「每款组件一条 15 分钟闹钟」留下的重复闹钟。
         * 重复闹钟会一直存活到应用被杀/设备重启，不清掉的话升级后反而会多出几条唤醒。
         * PendingIntent 的匹配只看「requestCode + Intent（组件）」，用 FLAG_NO_CREATE 探测，
         * 不存在时不会真的创建。
         */
        internal fun cleanLegacyPeriodicAlarms(context: Context) {
            if (legacyPeriodicCleaned) return
            legacyPeriodicCleaned = true
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val legacyAlarms = arrayOf(
                    LEGACY_MINIMAL_PERIODIC_CODE to MinimalWidgetPeriodicReceiver::class.java,
                    LEGACY_NEXT_COURSE_PERIODIC_CODE to NextCoursePeriodicReceiver::class.java,
                    LEGACY_UPCOMING_PERIODIC_CODE to UpcomingDaysPeriodicReceiver::class.java,
                    LEGACY_WIDE_PERIODIC_CODE to WidgetPeriodicUpdateReceiver::class.java
                )
                for ((requestCode, receiverClass) in legacyAlarms) {
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        Intent(context, receiverClass),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    ) ?: continue
                    alarmManager.cancel(pendingIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 旧版本残留闹钟醒来、但桌面上已经没有任何小组件时调用：把旧链一次清干净，之后不再被唤醒。
         * 重复闹钟会一直存活到被取消，所以「升级后没放组件的用户」也会被旧闹钟每 15 分钟唤醒一次；
         * 这里在任何一次残留闹钟醒来时就把它们全部撤掉（清理标记保证每进程最多付一次 IPC）。
         */
        internal fun pruneResidualAlarms(context: Context) {
            cleanLegacyPeriodicAlarms(context)
            cancelScheduledUpdate(context)
            WidgetMidnightReceiver.cancelMidnightAlarms(context)
        }

        /** 旧版本每款组件各自的周期闹钟 requestCode（新版不再调度，只负责清理） */
        private const val LEGACY_MINIMAL_PERIODIC_CODE = 10004
        private const val LEGACY_NEXT_COURSE_PERIODIC_CODE = 10009
        private const val LEGACY_UPCOMING_PERIODIC_CODE = 10010
        private const val LEGACY_WIDE_PERIODIC_CODE = 10015

        private fun chainAlarmPendingIntent(context: Context): PendingIntent =
            chainPendingIntent ?: PendingIntent.getBroadcast(
                context,
                WIDGET_UPDATE_REQUEST_CODE,
                Intent(context, WidgetUpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { chainPendingIntent = it }
    }
}

/**
 * 小组件更新接收器
 */
class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            // triggerUpdate 内部已按「是否有实例」短路；这里不额外判断，保持单一入口
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
                // 一个组件都没放：开机只付出一次缓存查询，不再把所有 15 分钟/午夜闹钟排一遍
                if (!ScheduleWidgetUpdateService.hasAnyWidget(it)) return
                ScheduleWidgetUpdateService.triggerUpdate(it)
                ScheduleWidgetProvider().schedulePeriodicUpdate(it)
                MinimalWidgetProvider().schedulePeriodicUpdate(it)
                UpcomingDaysWidgetProvider().schedulePeriodicUpdate(it)
                NextCourseWidgetProvider().schedulePeriodicUpdate(it)
                NextCourseCompactWidgetProvider().schedulePeriodicUpdate(it)
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
            // 已经没实例（旧进程/移除前的残留闹钟）：清掉残留闹钟后结束，不再空跑一次数据读取
            if (!WidgetPresence.isPresent(it, UpcomingDaysWidgetProvider::class.java)) {
                ScheduleWidgetUpdateService.pruneResidualAlarms(it)
                return
            }
            UpcomingDaysWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                WidgetPresence.widgetIds(it, UpcomingDaysWidgetProvider::class.java)
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
            if (!WidgetPresence.isPresent(it, NextCourseWidgetProvider::class.java)) {
                ScheduleWidgetUpdateService.pruneResidualAlarms(it)
                return
            }
            NextCourseWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                WidgetPresence.widgetIds(it, NextCourseWidgetProvider::class.java)
            )
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }
}

/**
 * 下一门课提醒小组件状态切换接收器（上课/下课/明天开课的精确时刻触发）。
 * 4×2 与 2×2 变体共用本接收器，各自用独立 requestCode 挂闹钟；醒来时把两个变体一起刷新。
 */
class NextCourseTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            val appWidgetManager = AppWidgetManager.getInstance(it)
            val wideIds = WidgetPresence.widgetIds(it, NextCourseWidgetProvider::class.java)
            if (wideIds.isNotEmpty()) {
                NextCourseWidgetProvider().onUpdate(it, appWidgetManager, wideIds)
            }
            val compactIds = WidgetPresence.widgetIds(it, NextCourseCompactWidgetProvider::class.java)
            if (compactIds.isNotEmpty()) {
                NextCourseCompactWidgetProvider().onUpdate(it, appWidgetManager, compactIds)
            }
        }
    }
}

/**
 * 课程结束时更新小组件
 */
class WidgetCourseEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            // 今日课程 2×2 与 4×2 各自的下课精确闹钟（requestCode 10002 / 10016）都指向本接收器，
            // 同一节课下课时它们在同一秒内各进一次，第二次算出来的内容与第一次完全一样
            // （同一分钟 → 同一批课程状态），直接跳过；兜底链在第一次进来时已经重排过了。
            val now = SystemClock.elapsedRealtime()
            if (now - lastRefreshElapsed < COURSE_END_DEDUPE_MS) return
            lastRefreshElapsed = now

            val appWidgetManager = AppWidgetManager.getInstance(it)
            val scheduleIds = WidgetPresence.widgetIds(it, ScheduleWidgetProvider::class.java)
            if (scheduleIds.isNotEmpty()) {
                ScheduleWidgetProvider().onUpdate(it, appWidgetManager, scheduleIds)
            }
            // 今日课程 4×2（两个组件各自的下课精确闹钟都指向本接收器）
            val wideIds = WidgetPresence.widgetIds(it, ScheduleWideWidgetProvider::class.java)
            if (wideIds.isNotEmpty()) {
                ScheduleWideWidgetProvider().onUpdate(it, appWidgetManager, wideIds)
            }
            val minimalIds = WidgetPresence.widgetIds(it, MinimalWidgetProvider::class.java)
            if (minimalIds.isNotEmpty()) {
                MinimalWidgetProvider().onUpdate(it, appWidgetManager, minimalIds)
            }
            ScheduleWidgetUpdateService.scheduleNextUpdate(it)
        }
    }

    companion object {
        /** 两条下课闹钟的触发时刻本应相同，留 2 秒容差吸收系统批处理抖动 */
        private const val COURSE_END_DEDUPE_MS = 2000L

        @Volatile
        private var lastRefreshElapsed = 0L
    }
}

/**
 * 周期性更新小组件接收器。
 *
 * 这是「所有小组件共用的那条 15 分钟兜底闹钟」的接收器（见
 * [ScheduleWidgetUpdateService.ensurePeriodicUpdate]）：一次醒来把桌面上有实例的组件一起刷新，
 * 取代旧版每款组件各挂一条重复闹钟、各刷各的做法。
 * 一周课程组件不在此列（内容只在跨天/数据变更时变化，保持 0 点与 30 分钟链的原有刷新时机）。
 */
class WidgetPeriodicUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            if (!ScheduleWidgetUpdateService.hasAnyWidget(it)) {
                // 共用闹钟醒来但桌面上已经没有组件：清掉残留闹钟（旧版本每组件闹钟等）后结束
                ScheduleWidgetUpdateService.pruneResidualAlarms(it)
                return
            }
            val appWidgetManager = AppWidgetManager.getInstance(it)

            val scheduleIds = WidgetPresence.widgetIds(it, ScheduleWidgetProvider::class.java)
            if (scheduleIds.isNotEmpty()) {
                ScheduleWidgetProvider().onUpdate(it, appWidgetManager, scheduleIds)
            }
            val wideIds = WidgetPresence.widgetIds(it, ScheduleWideWidgetProvider::class.java)
            if (wideIds.isNotEmpty()) {
                ScheduleWideWidgetProvider().onUpdate(it, appWidgetManager, wideIds)
            }
            val minimalIds = WidgetPresence.widgetIds(it, MinimalWidgetProvider::class.java)
            if (minimalIds.isNotEmpty()) {
                MinimalWidgetProvider().onUpdate(it, appWidgetManager, minimalIds)
            }
            val upcomingIds = WidgetPresence.widgetIds(it, UpcomingDaysWidgetProvider::class.java)
            if (upcomingIds.isNotEmpty()) {
                UpcomingDaysWidgetProvider().onUpdate(it, appWidgetManager, upcomingIds)
            }
            val nextCourseIds = WidgetPresence.widgetIds(it, NextCourseWidgetProvider::class.java)
            if (nextCourseIds.isNotEmpty()) {
                NextCourseWidgetProvider().onUpdate(it, appWidgetManager, nextCourseIds)
            }
            val nextCompactIds = WidgetPresence.widgetIds(it, NextCourseCompactWidgetProvider::class.java)
            if (nextCompactIds.isNotEmpty()) {
                NextCourseCompactWidgetProvider().onUpdate(it, appWidgetManager, nextCompactIds)
            }
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
            if (!WidgetPresence.isPresent(it, MinimalWidgetProvider::class.java)) {
                ScheduleWidgetUpdateService.pruneResidualAlarms(it)
                return
            }
            MinimalWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                WidgetPresence.widgetIds(it, MinimalWidgetProvider::class.java)
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
            val minimalIds = WidgetPresence.widgetIds(it, MinimalWidgetProvider::class.java)
            if (minimalIds.isEmpty() && !WidgetPresence.isPresent(it, ScheduleWidgetProvider::class.java)) return
            if (minimalIds.isNotEmpty()) {
                MinimalWidgetProvider().onUpdate(it, appWidgetManager, minimalIds)
            }
            val scheduleIds = WidgetPresence.widgetIds(it, ScheduleWidgetProvider::class.java)
            if (scheduleIds.isNotEmpty()) {
                ScheduleWidgetProvider().onUpdate(it, appWidgetManager, scheduleIds)
            }
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
            val minimalIds = WidgetPresence.widgetIds(it, MinimalWidgetProvider::class.java)
            if (minimalIds.isEmpty() && !WidgetPresence.isPresent(it, ScheduleWidgetProvider::class.java)) return
            if (minimalIds.isNotEmpty()) {
                MinimalWidgetProvider().onUpdate(it, appWidgetManager, minimalIds)
            }
            val scheduleIds = WidgetPresence.widgetIds(it, ScheduleWidgetProvider::class.java)
            if (scheduleIds.isNotEmpty()) {
                ScheduleWidgetProvider().onUpdate(it, appWidgetManager, scheduleIds)
            }
        }
    }
}

/**
 * 最小化小组件短周期刷新接收器（仅用于 API < 24 的低版本设备）
 */
class MinimalWidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            if (!WidgetPresence.isPresent(it, MinimalWidgetProvider::class.java)) return
            MinimalWidgetProvider().onUpdate(
                it,
                AppWidgetManager.getInstance(it),
                WidgetPresence.widgetIds(it, MinimalWidgetProvider::class.java)
            )
        }
    }
}
