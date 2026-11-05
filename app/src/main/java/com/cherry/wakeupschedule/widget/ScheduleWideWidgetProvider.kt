package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import java.util.Calendar

/**
 * 今日课程概览 4×2 小组件（2026-10 新增变体，与 2×2 的 ScheduleWidgetProvider 并存）。
 *
 * 样式与 2×2 完全同款（彩底行卡 + 徽标三色 + 文字 token），只做布局扩展：
 * 标题行「今日课程 · N 门 | 第N周 · 周X」+ 双行行卡列表
 * （行 = 课名 + 徽标 + 起时间 / 教室·节次 + 止时间），110dp 可见约 2 门、可上下滑动。
 * 数据源复用 WidgetCourseListService 的 today_wide 渠道；刷新链与 2×2 一致
 * （15 分钟周期 + 课程结束精确闹钟 + 分钟 tick + TIME_SET + 0 点跨天 + 30 分钟全局链），
 * 闹钟 requestCode 独立（10015/10016），移除本组件不影响 2×2 的闹钟。
 *
 * 性能口径（工作流 C）：与 2×2 相同 —— 无实例纯内存短路；有实例直接进程内刷新（不走广播）；
 * 一份 RemoteViews 批量下发；周期/下课闹钟按「已挂状态 / 目标时刻」去重。
 */
class ScheduleWideWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.wide.ACTION_REFRESH"
        private const val WIDE_COURSE_END_REQUEST_CODE = 10016

        /** 上次通知 ListView 重载时的列表内容签名（进程级缓存；null 表示未知，首次必通知） */
        @Volatile
        private var lastListSignature: String? = null

        // ── PendingIntent 进程内复用 ──
        @Volatile
        private var clickPendingIntent: PendingIntent? = null

        @Volatile
        private var courseEndPendingIntent: PendingIntent? = null

        /** 列表行点击模板（requestCode 20002；进程内复用） */
        @Volatile
        private var rowClickPendingIntent: PendingIntent? = null

        // ── 闹钟去重状态（本进程口径） ──
        @Volatile
        private var courseEndAlarmTarget = 0L

        fun triggerUpdate(context: Context) {
            if (!WidgetPresence.isPresent(context, ScheduleWideWidgetProvider::class.java)) return
            updateAllWidgets(context)
        }

        private fun clickPendingIntent(context: Context): PendingIntent =
            clickPendingIntent ?: PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { clickPendingIntent = it }

        private fun alarmPendingIntent(
            context: Context,
            requestCode: Int,
            receiverClass: Class<*>,
            cached: PendingIntent?
        ): PendingIntent = cached ?: PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, receiverClass),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** 列表行点击模板 PendingIntent（进程内复用） */
        private fun rowClickPendingIntent(context: Context): PendingIntent =
            rowClickPendingIntent ?: PendingIntent.getActivity(
                context, 20002,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { rowClickPendingIntent = it }

        /** 刷新所有实例（内容 + 闹钟）；调用方已确认有实例 */
        private fun updateAllWidgets(context: Context) {
            val appWidgetIds = WidgetPresence.widgetIds(context, ScheduleWideWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            ScheduleWideWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds)
        }
    }

    @Suppress("DEPRECATION")
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 系统回调带来当前完整的实例列表：顺手校准进程内缓存
        WidgetPresence.markUpdated(ScheduleWideWidgetProvider::class.java, appWidgetIds)
        if (appWidgetIds.isNotEmpty()) {
            // 各实例内容完全一致：一份 RemoteViews 一次 IPC 批量下发
            val views = RemoteViews(context.packageName, R.layout.widget_today_schedule_wide)
            views.setOnClickPendingIntent(R.id.widget_container, clickPendingIntent(context))
            updateWidgetContent(context, views)
            appWidgetManager.updateAppWidget(appWidgetIds, views)

            // 仅当列表可见内容可能变化时才通知 ListView 重载（每次都通知会重置滚动条导致闪烁）
            val signature = WidgetListSignature.todaySignature(context)
            if (signature != lastListSignature) {
                lastListSignature = signature
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_wide_courses)
            }
        }
        scheduleWideCourseEndUpdate(context)
        scheduleWidePeriodicUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
        scheduleWidePeriodicUpdate(context)
        WidgetMidnightReceiver.scheduleMidnightUpdate(context)
        // 借用全局更新链调度 30 分钟兜底刷新
        ScheduleWidgetUpdateService.triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.markAbsent(ScheduleWideWidgetProvider::class.java)
        try {
            cancelWideCourseEndUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 如果这是桌面上最后一个小组件，顺手停掉 30 分钟兜底链
        ScheduleWidgetUpdateService.onProviderDisabled(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPresence.markDeleted(ScheduleWideWidgetProvider::class.java, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE" -> updateAllWidgets(context)
        }
    }

    private fun updateWidgetContent(context: Context, views: RemoteViews) {
        try {
            val settingsManager = SettingsManager(context)
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val currentWeek = CourseTimeUtils.getCurrentWeek(settingsManager)

            val todayCount = CourseDataManager.getAllCoursesSafely(context).count {
                it.dayOfWeek == dayOfWeek && it.isActiveInWeek(currentWeek)
            }
            val weekLabel = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")[dayOfWeek]
            views.setTextViewText(R.id.tv_wide_header, "今日课程 · $todayCount 门")
            views.setTextViewText(R.id.tv_wide_day, "第${currentWeek}周 · $weekLabel")

            // 绑定 ListView 到 RemoteViewsService 的 today_wide 渠道（宽版行：右列起止时间）
            // 使用 data Uri 让系统识别为唯一绑定（RemoteViews 缓存只比 Uri 不比 extras）
            val todayIntent = Intent(context, WidgetCourseListService::class.java).apply {
                putExtra(WidgetCourseListService.EXTRA_SOURCE, WidgetCourseListService.SOURCE_TODAY_WIDE)
                data = android.net.Uri.parse("widget://course-list/today_wide")
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.lv_wide_courses, todayIntent)
            views.setEmptyView(R.id.lv_wide_courses, android.R.id.empty)

            // 设置每行的点击 PendingIntent（跳转到主应用）
            views.setPendingIntentTemplate(R.id.lv_wide_courses, rowClickPendingIntent(context))
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * 调度今天最近一个未结束课程的下课时刻精确刷新（与 2×2 同逻辑，独立 requestCode）。
     * 目标时刻没变就不再重复 setExact；用「当天 0 点 + 下课分钟数」算绝对时刻，保证可去重。
     */
    private fun scheduleWideCourseEndUpdate(context: Context) {
        try {
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val currentTimeMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val currentWeek = CourseTimeUtils.getCurrentWeek(SettingsManager(context))

            val todayEndCourses = CourseDataManager.getAllCoursesSafely(context)
                .filter { it.dayOfWeek == dayOfWeek && it.isActiveInWeek(currentWeek) }
                .mapNotNull { val end = CourseTimeUtils.getEndMinutes(context, it); if (end > currentTimeMinutes) end to it else null }
                .sortedBy { it.first }

            if (todayEndCourses.isEmpty()) {
                cancelWideCourseEndUpdate(context)
                return
            }
            val endMinutes = todayEndCourses[0].first
            val targetMillis = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, endMinutes)
            }.timeInMillis + 5000L

            if (targetMillis <= System.currentTimeMillis() + 5000L) {
                // 距触发不足 5 秒：内容在本次 onUpdate 里已经刷过，撤掉旧闹钟即可，不再递归刷新
                cancelWideCourseEndUpdate(context)
                return
            }

            if (targetMillis == courseEndAlarmTarget) return

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(
                context,
                WIDE_COURSE_END_REQUEST_CODE,
                WidgetCourseEndReceiver::class.java,
                courseEndPendingIntent
            )
            courseEndPendingIntent = pendingIntent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
            }
            // 只有 set 成功才记目标：尊重 Android 14+ 精确闹钟被拒时的原有失败回退语义
            courseEndAlarmTarget = targetMillis
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cancelWideCourseEndUpdate(context: Context) {
        if (courseEndAlarmTarget == 0L) return
        courseEndAlarmTarget = 0L
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(
                alarmPendingIntent(
                    context,
                    WIDE_COURSE_END_REQUEST_CODE,
                    WidgetCourseEndReceiver::class.java,
                    courseEndPendingIntent
                )
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * 周期刷新：并入「所有小组件共用的一条 15 分钟闹钟」
     * （见 [ScheduleWidgetUpdateService.ensurePeriodicUpdate]），不再单独挂一条。
     */
    fun scheduleWidePeriodicUpdate(context: Context) {
        ScheduleWidgetUpdateService.ensurePeriodicUpdate(context)
    }
}
