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
 * 今日课程桌面小组件提供者
 * 显示当前日期和接下来要上的课程
 *
 * 性能口径（工作流 C）：
 * - 高频入口 [triggerUpdate] 无实例时纯内存返回；有实例直接进程内刷新，不再走 AMS 广播；
 * - 各实例内容一致，一份 RemoteViews 批量下发；实例 id 走进程内缓存，不再每次 getAppWidgetIds；
 * - 15 分钟周期闹钟挂上后不再重复 set（原先每次刷新都会重设），下课闹钟按目标时刻去重。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.ACTION_REFRESH"
        private const val WIDGET_COURSE_END_REQUEST_CODE = 10002

        /** 上次通知 ListView 重载时的列表内容签名（进程级缓存；null 表示未知，首次必通知） */
        @Volatile
        private var lastListSignature: String? = null

        // ── PendingIntent 进程内复用（每次刷新都重新申请都是跨进程 IPC） ──
        @Volatile
        private var clickPendingIntent: PendingIntent? = null

        @Volatile
        private var courseEndPendingIntent: PendingIntent? = null

        /** 列表行点击模板（跳转到主应用，requestCode 20001；进程内复用，避免每次刷新都申请一遍） */
        @Volatile
        private var rowClickPendingIntent: PendingIntent? = null

        // ── 闹钟去重状态（本进程口径） ──

        /** 已挂的下课精确闹钟目标时刻（0 = 当前没挂），目标不变就不再重复 setExact */
        @Volatile
        private var courseEndAlarmTarget = 0L

        /**
         * 触发小组件更新。
         *
         * 旧实现是 sendBroadcast → onReceive → getAppWidgetIds；App 分钟 tick 每次都调，
         * 等于每分钟一次 AMS 广播 + 一次 IPC。现在无实例纯内存返回；有实例直接进程内刷新
         * （同一进程、视觉等价，只是提前几毫秒）。
         */
        fun triggerUpdate(context: Context) {
            if (!WidgetPresence.isPresent(context, ScheduleWidgetProvider::class.java)) return
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
                context, 20001,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { rowClickPendingIntent = it }

        /** 刷新所有实例（内容 + 闹钟）；调用方已确认有实例 */
        private fun updateAllWidgets(context: Context) {
            val appWidgetIds = WidgetPresence.widgetIds(context, ScheduleWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            ScheduleWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds)
        }
    }

    @Suppress("DEPRECATION")
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 系统回调带来当前完整的实例列表：顺手校准「是否有实例」的进程内缓存
        WidgetPresence.markUpdated(ScheduleWidgetProvider::class.java, appWidgetIds)
        if (appWidgetIds.isNotEmpty()) {
            // 各实例内容完全一致：只构建一份 RemoteViews，一次 IPC 批量下发
            val views = RemoteViews(context.packageName, R.layout.widget_today_schedule)
            views.setOnClickPendingIntent(R.id.widget_container, clickPendingIntent(context))
            updateWidgetContent(context, views)
            appWidgetManager.updateAppWidget(appWidgetIds, views)

            // 仅当列表可见内容可能变化时才通知 ListView 重载（每次都通知会重置滚动条，导致右侧滑动条持续闪烁）
            val signature = computeListSignature(context)
            if (signature != lastListSignature) {
                lastListSignature = signature
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_today_courses)
            }
        }
        scheduleNextCourseEndUpdate(context)
        schedulePeriodicUpdate(context)
    }

    /**
     * 列表可见内容的轻量签名：星期、周次、课程集合（id/名称/教室/节次），
     * 以及每门课相对当前时刻的阶段标记（未开始/进行中/已上完的组合决定每行徽标）。
     * 阶段标记只在某节课开始或结束的分钟边界变化，因此数据未变时不会触发重载。
     */
    /** 列表内容签名：抽到 WidgetListSignature 供 2×2 与 4×2 共用 */
    private fun computeListSignature(context: Context): String =
        WidgetListSignature.todaySignature(context)

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
        schedulePeriodicUpdate(context)
        WidgetMidnightReceiver.scheduleMidnightUpdate(context)
        ScheduleWidgetUpdateService.triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.markAbsent(ScheduleWidgetProvider::class.java)
        try {
            cancelCourseEndUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 只有桌面上最后一个小组件被移除时才停掉 30 分钟兜底链/午夜闹钟
        ScheduleWidgetUpdateService.onProviderDisabled(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPresence.markDeleted(ScheduleWidgetProvider::class.java, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE" -> updateAllWidgets(context)
        }
    }

    /**
     * 周期刷新：并入「所有小组件共用的一条 15 分钟闹钟」
     * （见 [ScheduleWidgetUpdateService.ensurePeriodicUpdate]），不再单独挂一条。
     */
    fun schedulePeriodicUpdate(context: Context) {
        ScheduleWidgetUpdateService.ensurePeriodicUpdate(context)
    }

    /**
     * 调度下次课程结束时更新小组件。
     * 目标时刻没变就不再重复 setExact（原先每次刷新都会重设一遍）。
     * 这里用「当天 0 点 + 下课分钟数」算出绝对时刻，保证每次算出来的目标值完全一致（可去重）。
     */
    private fun scheduleNextCourseEndUpdate(context: Context) {
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
                cancelCourseEndUpdate(context)
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
                // 距离触发时刻已不足 5 秒：闹钟意义不大；本次 onUpdate 在调度之前已经刷过内容，
                // 撤掉旧闹钟即可，不再触发一轮刷新（旧实现「刷新 → 再判断 → 再刷新」会递归）。
                cancelCourseEndUpdate(context)
                return
            }

            if (targetMillis == courseEndAlarmTarget) return

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(
                context,
                WIDGET_COURSE_END_REQUEST_CODE,
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

    private fun cancelCourseEndUpdate(context: Context) {
        // 本进程没挂过就不白跑一次 IPC
        if (courseEndAlarmTarget == 0L) return
        courseEndAlarmTarget = 0L
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(
                alarmPendingIntent(
                    context,
                    WIDGET_COURSE_END_REQUEST_CODE,
                    WidgetCourseEndReceiver::class.java,
                    courseEndPendingIntent
                )
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 更新小组件内容
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
            views.setTextViewText(R.id.tv_widget_header, "今日课程 · $todayCount 门")
            views.setTextViewText(R.id.tv_widget_day, weekLabel)

            // 绑定 ListView 到 RemoteViewsService，显示完整今日课程列表（含已结束，标注状态）
            // 使用 data Uri 让系统识别为唯一绑定
            val todayIntent = Intent(context, WidgetCourseListService::class.java).apply {
                putExtra(WidgetCourseListService.EXTRA_SOURCE, WidgetCourseListService.SOURCE_TODAY)
                data = android.net.Uri.parse("widget://course-list/today")
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.lv_today_courses, todayIntent)
            views.setEmptyView(R.id.lv_today_courses, android.R.id.empty)

            // 设置每行的点击 PendingIntent（跳转到主应用）
            views.setPendingIntentTemplate(R.id.lv_today_courses, rowClickPendingIntent(context))
        } catch (e: Exception) { e.printStackTrace() }
    }
}
