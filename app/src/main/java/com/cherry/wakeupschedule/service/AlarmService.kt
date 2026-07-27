package com.cherry.wakeupschedule.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 用于跳过 setAlarmClock 的控制异常
 * 非最近闹钟抛出此异常以直接降级到 setExactAndAllowWhileIdle，避免覆盖全局唯一的 setAlarmClock
 */
private class SkipSetAlarmClockException : Exception()

/**
 * 闹钟服务
 * 负责课程提醒闹钟的设置、取消和管理
 * 使用系统AlarmManager实现精确闹钟提醒
 *
 * @param context 上下文环境
 */
class AlarmService(private val context: Context) {

    // 懒加载AlarmManager服务
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    // 懒加载时间表管理器
    private val timeTableManager by lazy { TimeTableManager.getInstance(context) }
    // 懒加载通知助手
    private val notificationHelper by lazy { NotificationHelper(context) }
    // 节假日管理器
    private val holidayManager by lazy { HolidayManager.getInstance(context) }
    // 设置管理器
    private val settingsManager by lazy { SettingsManager(context) }

    /**
     * 检查设备是否支持精确闹钟（SCHEDULE_EXACT_ALARM 权限）
     * Android 12+ 用户可随时撤回该权限，必须每次都检查
     */
    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 设置闹钟的兜底实现：根据权限选择最可靠的 API
     * 仅对最近一个闹钟使用 setAlarmClock（全局唯一），其他使用 setExactAndAllowWhileIdle
     */
    private fun scheduleAlarmWithFallback(
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        tag: String,
        isNearestAlarm: Boolean = false
    ): Boolean {
        return scheduleAlarmWithFallback(context, alarmManager, triggerAtMillis, pendingIntent, tag, isNearestAlarm)
    }

    /**
     * 设置课程闹钟
     * 在设置新闹钟前会先取消该课程的旧闹钟，避免重复提醒
     *
     * @param course 要设置闹钟的课程
     */
    fun setCourseAlarm(course: Course) {
        Log.d("CourseAlarmDebug", "setCourseAlarm called: ${course.name}, alarmEnabled: ${course.alarmEnabled}")
        // 如果课程未启用闹钟，则取消该课程的闹钟
        if (!course.alarmEnabled) {
            cancelCourseAlarm(course)
            return
        }

        // 先取消旧闹钟，避免重复
        cancelCourseAlarm(course)

        val currentWeek = getCurrentWeek()

        // 检查课程是否在当前周范围内
        if (currentWeek < course.startWeek || currentWeek > course.endWeek) return
        // 检查单双周是否匹配
        if (!isWeekTypeMatched(course, currentWeek)) return

        // 使用学期开始日期精确计算闹钟时间
        var alarmTime = calculateAlarmTimeMillis(course, currentWeek, course.alarmMinutesBefore)

        // 如果学期开始日期未设置，跳过
        if (alarmTime == 0L) {
            Log.w("AlarmService", "Semester start date not set, cannot set alarm for ${course.name}")
            return
        }

        // 如果设置的时间已过，先检查是否需要补救（课程尚未开始），再推送到下周
        if (alarmTime <= System.currentTimeMillis()) {
            val courseStartTime = calculateAlarmTimeMillis(course, currentWeek, 0)
            if (courseStartTime > 0L && System.currentTimeMillis() < courseStartTime) {
                Log.w("AlarmService", "setCourseAlarm 补救：闹钟时间已过但课程尚未开始，通过 WorkManager 立即通知")
                try {
                    ExactAlarmWorker.scheduleImmediate(context, course)
                } catch (e: Exception) {
                    Log.e("AlarmService", "setCourseAlarm 补救调度失败", e)
                }
            }
            alarmTime = calculateAlarmTimeMillis(course, currentWeek + 1, course.alarmMinutesBefore)
        }

        if (alarmTime <= System.currentTimeMillis()) return

        // 创建广播Intent，传递给AlarmReceiver
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("course_name", course.name)
            putExtra("course_teacher", course.teacher)
            putExtra("course_location", course.classroom)
            putExtra("course", course)
        }

        // 创建PendingIntent
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            course.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 使用多重兜底设置闹钟，国产 ROM 也能尽量保证触发
        scheduleAlarmWithFallback(alarmTime, pendingIntent, "course#${course.id}")
        DebugLogger.logAlarmSet(course.name, java.util.Date(alarmTime), course.id.toInt())

        // 兜底：使用 WorkManager 安排一个延迟检查
        // 应对国产 ROM 杀进程 / 用户撤回精确闹钟权限导致主闹钟失效的场景
        try {
            val delayMillis = alarmTime - System.currentTimeMillis()
            val delayMinutes = (delayMillis / (1000 * 60)).coerceAtLeast(1)
            ExactAlarmWorker.scheduleReminder(context, course, delayMinutes)
        } catch (e: Exception) {
            Log.w("AlarmService", "WorkManager 兜底调度失败", e)
        }
    }

    /**
     * 取消课程的闹钟
     * 同时取消AlarmManager闹钟和WorkManager提醒
     * 以及整学期所有周次的预设闹钟
     *
     * @param course 要取消闹钟的课程
     */
    fun cancelCourseAlarm(course: Course) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            course.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        // 同时取消整学期所有周次的预设闹钟（requestCode = course.id * 100 + week）
        val totalWeeks = settingsManager.getTotalWeeks()
        for (week in 1..totalWeeks) {
            val weekIntent = Intent(context, AlarmReceiver::class.java)
            val weekPendingIntent = PendingIntent.getBroadcast(
                context,
                (course.id * 100 + week).toInt(),
                weekIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(weekPendingIntent)

            // 同时取消自动启动闹钟
            val autoStartIntent = Intent(context, AutoStartReceiver::class.java).apply {
                action = AutoStartReceiver.ACTION_AUTO_START
            }
            val autoStartPendingIntent = PendingIntent.getBroadcast(
                context,
                (course.id * 100 + week + 10000).toInt(),
                autoStartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(autoStartPendingIntent)
        }

        // 同时取消WorkManager的提醒
        ExactAlarmWorker.cancelReminder(context, course.id)
        // 同时清理通知栏中已展示的该课程通知，避免删除/修改后旧通知残留
        // 使用统一通知ID公式：cancelCourseNotifications 内部已覆盖
        // 无周次 (courseId.toInt()) 和每周 (courseId*100+week)
        notificationHelper.cancelCourseNotifications(course.id, course.name)
        // 释放该课程占用的所有闹钟槽位
        AlarmSlotManager.getInstance(context).freeAllSlotsForCourse(course.id)
        Log.d("AlarmService", "Cancelled alarm for ${course.name}")
    }

    /**
     * 使用WorkManager安排精确提醒
     * 作为AlarmManager的备份方案
     *
     * @param course 要安排的课程提醒
     */
    fun scheduleExactReminder(course: Course) {
        // 如果未启用闹钟，则取消
        if (!course.alarmEnabled) {
            cancelCourseAlarm(course)
            return
        }

        // ★ 关键：不再调用 cancelCourseAlarm(course)，避免破坏 registerAllCourseNotifications()
        // 已注册的 AlarmManager 全学期闹钟。此处仅叠加一个 WorkManager 作为额外兜底。

        val currentWeek = getCurrentWeek()

        // 检查周范围和单双周
        if (currentWeek < course.startWeek || currentWeek > course.endWeek) return
        if (!isWeekTypeMatched(course, currentWeek)) return

        // 使用学期开始日期精确计算闹钟时间
        var alarmTime = calculateAlarmTimeMillis(course, currentWeek, course.alarmMinutesBefore)

        // 如果时间已过，先检查是否需要补救（课程尚未开始），再推送到下周
        if (alarmTime <= System.currentTimeMillis()) {
            val courseStartTime = calculateAlarmTimeMillis(course, currentWeek, 0)
            if (courseStartTime > 0L && System.currentTimeMillis() < courseStartTime) {
                Log.w("AlarmService", "scheduleExactReminder 补救：闹钟时间已过但课程尚未开始，通过 WorkManager 立即通知")
                try {
                    ExactAlarmWorker.scheduleImmediate(context, course)
                } catch (e: Exception) {
                    Log.e("AlarmService", "scheduleExactReminder 补救调度失败", e)
                }
            }
            alarmTime = calculateAlarmTimeMillis(course, currentWeek + 1, course.alarmMinutesBefore)
        }

        if (alarmTime <= System.currentTimeMillis()) return

        // 计算延迟时间（分钟）
        val delayMillis = alarmTime - System.currentTimeMillis()
        val delayMinutes = (delayMillis / (1000 * 60)).coerceAtLeast(1)

        // 使用WorkManager安排提醒（叠加式，不取消已有闹钟）
        ExactAlarmWorker.scheduleReminder(context, course, delayMinutes)
        Log.d("AlarmService", "Scheduled WorkManager reminder for ${course.name} in $delayMinutes minutes")
    }

    /**
     * 设置所有课程的提醒
     * 在应用启动或设置更改时调用
     */
    fun scheduleAllReminders() {
        registerAllCourseNotifications()
    }

    // ────────── 每日多时段闹钟刷新（解决 vivo/OPPO 杀进程后闹钟丢失问题）──────────

    companion object {
        private val DAILY_REFRESH_HOURS = intArrayOf(4, 8, 12, 16, 19)
        private const val DAILY_REFRESH_BASE_REQUEST_CODE = 99990
        const val DAILY_REFRESH_ACTION = "com.cherry.wakeupschedule.DAILY_ALARM_REFRESH"

        /**
         * 静态兜底实现：使用最可靠的 API 设置闹钟
         * 优先级（仅最近一个闹钟）：setAlarmClock > setExactAndAllowWhileIdle > setExact > setAndAllowWhileIdle > set
         * 其他闹钟（非最近）：setExactAndAllowWhileIdle > setExact > setAndAllowWhileIdle > set
         * 注意：setAlarmClock 全局只能存在 1 个，必须仅用于最近即将触发的闹钟
         */
        fun scheduleAlarmWithFallback(
            context: Context,
            alarmManager: AlarmManager,
            triggerAtMillis: Long,
            pendingIntent: PendingIntent,
            tag: String,
            isNearestAlarm: Boolean = false
        ): Boolean {
            return try {
                if (isNearestAlarm) {
                    // 仅对最近一个闹钟使用 setAlarmClock：系统在状态栏显示下一个闹钟并以最高优先级触发
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAtMillis, null),
                        pendingIntent
                    )
                    Log.d("AlarmService", "闹钟($tag)使用 setAlarmClock 设置（最近闹钟），时间=${java.util.Date(triggerAtMillis)}")
                } else {
                    // 非最近闹钟跳过 setAlarmClock，直接降级到 setExactAndAllowWhileIdle，避免覆盖最近的 setAlarmClock
                    throw SkipSetAlarmClockException()
                }
                true
            } catch (e: SkipSetAlarmClockException) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    }
                    Log.d("AlarmService", "闹钟($tag)使用 setExactAndAllowWhileIdle 设置，时间=${java.util.Date(triggerAtMillis)}")
                    true
                } catch (e2: SecurityException) {
                    Log.w("AlarmService", "setExact 权限被拒绝($tag)，降级到 setAndAllowWhileIdle", e2)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                triggerAtMillis,
                                pendingIntent
                            )
                        } else {
                            alarmManager.set(
                                AlarmManager.RTC_WAKEUP,
                                triggerAtMillis,
                                pendingIntent
                            )
                        }
                        Log.d("AlarmService", "闹钟($tag)使用 setAndAllowWhileIdle 兜底设置")
                        true
                    } catch (e3: Exception) {
                        Log.e("AlarmService", "闹钟($tag)所有 set 方法都失败", e3)
                        false
                    }
                } catch (e2: Exception) {
                    Log.e("AlarmService", "闹钟($tag)setExact 失败", e2)
                    false
                }
            } catch (e: SecurityException) {
                Log.w("AlarmService", "setAlarmClock 权限被拒绝($tag)，降级到 setExactAndAllowWhileIdle", e)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    }
                    Log.d("AlarmService", "闹钟($tag)使用 setExactAndAllowWhileIdle 降级设置")
                    true
                } catch (e2: SecurityException) {
                    Log.w("AlarmService", "setExact 权限被拒绝($tag)，降级到 setAndAllowWhileIdle", e2)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                triggerAtMillis,
                                pendingIntent
                            )
                        } else {
                            alarmManager.set(
                                AlarmManager.RTC_WAKEUP,
                                triggerAtMillis,
                                pendingIntent
                            )
                        }
                        Log.d("AlarmService", "闹钟($tag)使用 setAndAllowWhileIdle 兜底设置")
                        true
                    } catch (e3: Exception) {
                        Log.e("AlarmService", "闹钟($tag)所有 set 方法都失败", e3)
                        false
                    }
                } catch (e2: Exception) {
                    Log.e("AlarmService", "闹钟($tag)setExact 失败", e2)
                    false
                }
            } catch (e: Exception) {
                Log.e("AlarmService", "闹钟($tag)setAlarmClock 失败", e)
                false
            }
        }

        /**
         * 调度每日多时段闹钟刷新（每4小时一次）
         * 解决国产手机（vivo/OPPO/小米）杀进程后 AlarmManager 闹钟被清除的问题
         */
        fun scheduleDailyAlarmRefreshes(context: Context) {
            val settingsManager = SettingsManager(context)
            if (!settingsManager.isAlarmEnabled()) {
                return
            }
            DAILY_REFRESH_HOURS.forEachIndexed { index, hour ->
                scheduleDailyAlarmRefreshAtHour(context, hour, DAILY_REFRESH_BASE_REQUEST_CODE + index)
            }
        }

        /**
         * 在指定小时调度刷新闹钟
         */
        fun scheduleDailyAlarmRefreshAtHour(context: Context, hour: Int, requestCode: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(context, DailyAlarmReceiver::class.java).apply {
                action = DAILY_REFRESH_ACTION
                putExtra("refresh_hour", hour)
                putExtra("refresh_request_code", requestCode)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                if (am != null) {
                    scheduleAlarmWithFallback(context, am, calendar.timeInMillis, pendingIntent, "daily_refresh#${hour}")
                }
            } catch (e: Exception) {
                DebugLogger.logError("每日刷新闹钟设置失败(${hour}:00)", e)
            }
        }

        /**
         * 取消所有每日刷新闹钟
         */
        fun cancelDailyAlarmRefreshes(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            DAILY_REFRESH_HOURS.forEachIndexed { index, _ ->
                val requestCode = DAILY_REFRESH_BASE_REQUEST_CODE + index
                val intent = Intent(context, DailyAlarmReceiver::class.java).apply {
                    action = DAILY_REFRESH_ACTION
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
            }
        }
    }

    /**
     * 注册所有课程的通知
     * 为整个学期的每周课程安排闹钟
     *
     * 关键：不再无条件 cancel + re-register，避免在闹钟即将触发的时间窗口内
     * 把本周闹钟推向下一周导致课前通知丢失。
     * 仅在课程数据发生变更时才完全重建；定期兜底刷新使用增量合并策略。
     */
    fun registerAllCourseNotifications() {
        // 启动每日多时段刷新闹钟（防止 vivo/OPPO 杀进程后闹钟丢失）
        scheduleDailyAlarmRefreshes(context)
        // 启动定期检查和前台服务
        CourseReminderWorker.schedulePeriodicCheck(context)
        scheduleForegroundServiceIfNeeded()

        val allCourses = CourseDataManager.getInstance(context).getAllCourses()
        val semesterEndWeek = allCourses.maxOfOrNull { it.endWeek } ?: 20

        val currentWeek = getCurrentWeek()

        // 为每门课程的每周安排通知
        allCourses.forEach { course ->
            if (course.alarmEnabled) {
                collectValidAlarmTimes(course, semesterEndWeek).forEach { (alarmTime, week) ->
            // 使用课程ID和周数生成唯一的通知ID（统一公式）
            val notificationId = NotificationHelper.generateNotificationId(course.id, week)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId,
                Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("course_name", course.name)
                    putExtra("course_teacher", course.teacher)
                    putExtra("course_location", course.classroom)
                    putExtra("course", course)
                    putExtra("notification_week", week)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 使用多重兜底设置闹钟，国产 ROM 也能尽量保证触发
            scheduleAlarmWithFallback(alarmTime, pendingIntent, "${course.name}#w${week}")
            Log.d("AlarmService", "Registered alarm for ${course.name} week $week at ${java.util.Date(alarmTime)}")

            // 分配到闹钟槽位，追踪活跃状态
            val slotId = AlarmSlotManager.getInstance(context).allocateSlot(
                courseId = course.id,
                courseName = course.name,
                week = week,
                alarmTimeMillis = alarmTime
            )
            if (slotId >= 0) {
                Log.d("AlarmService", "槽位 $slotId 已分配: ${course.name} week=$week")
            }

            // 仅对当前周和下一周的闹钟设置 WorkManager 备份（比 AlarmManager 晚 1 分钟触发）
            // 不对更远的周次做备份，避免 WorkManager 任务数量爆炸（20周×N门课会超出系统限制）
            if (week == currentWeek || week == currentWeek + 1) {
                val backupAlarmTime = alarmTime + 60 * 1000L
                val backupDelayMinutes = ((backupAlarmTime - System.currentTimeMillis()) / (1000 * 60)).coerceAtLeast(1)
                try {
                    ExactAlarmWorker.scheduleReminder(context, course, backupDelayMinutes)
                    Log.d("AlarmService", "WorkManager 备份已调度: ${course.name} week $week, 延迟${backupDelayMinutes}分钟")
                } catch (e: Exception) {
                    Log.w("AlarmService", "WorkManager 备份调度失败: ${course.name} week $week", e)
                }
            }

            // 设置自动启动闹钟（在课前通知前1分钟自动启动应用）
            val autoStartTime = alarmTime - 60 * 1000L
            if (autoStartTime > System.currentTimeMillis()) {
                val autoStartIntent = Intent(context, AutoStartReceiver::class.java).apply {
                    action = AutoStartReceiver.ACTION_AUTO_START
                    putExtra(AutoStartReceiver.EXTRA_COURSE_NAME, course.name)
                }
                val autoStartRequestCode = (course.id * 100 + week + 10000).toInt()
                val autoStartPendingIntent = PendingIntent.getBroadcast(
                    context,
                    autoStartRequestCode,
                    autoStartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                scheduleAlarmWithFallback(autoStartTime, autoStartPendingIntent, "autostart#${course.name}#w${week}")
                Log.d("AlarmService", "Registered auto-start for ${course.name} week $week at ${java.util.Date(autoStartTime)}")
            }
                }
            }
        }
        Log.d("AlarmService", "Registered all notifications for ${allCourses.size} courses for semester")
    }

    /**
     * 收集单门课程整个学期所有有效的闹钟时间
     */
    private fun collectValidAlarmTimes(course: Course, semesterEndWeek: Int): List<Pair<Long, Int>> {
        val currentWeek = getCurrentWeek()
        val result = mutableListOf<Pair<Long, Int>>()

        for (week in course.startWeek..Math.min(course.endWeek, semesterEndWeek)) {
            if (!isWeekTypeMatched(course, week)) continue

            var alarmTime = calculateAlarmTimeMillis(course, week, course.alarmMinutesBefore)
            if (alarmTime == 0L) break

            if (settingsManager.isHideHolidayCourses()) {
                val calendar = Calendar.getInstance().apply { timeInMillis = alarmTime }
                if (holidayManager.isHoliday(calendar)) continue
            }

            // 如果时间已过且是当前周，尝试补救而非直接推到下周
            if (alarmTime <= System.currentTimeMillis()) {
                if (week == currentWeek) {
                    // 检查是否在可补救窗口内：闹钟预期触发时间 + 课程时长 范围内
                    // 例如上课前30分钟闹钟本应在9:30触发，课程10:00开始，现在9:45刷新
                    // 如果上课还没开始，通过 WorkManager 立即补救通知
                    val courseStartTime = calculateAlarmTimeMillis(course, week, 0)
                    if (courseStartTime > 0L && System.currentTimeMillis() < courseStartTime) {
                        Log.w("AlarmService", "补救本周 ${course.name} Week$week 的闹钟：闹钟时间已过但课程尚未开始，通过 WorkManager 立即通知")
                        try {
                            // 使用独立 workName="course_catchup_{id}"，不会被后续标准备份（REPLACE）覆盖
                            ExactAlarmWorker.scheduleImmediate(context, course)
                        } catch (e: Exception) {
                            Log.e("AlarmService", "补救 WorkManager 调度失败", e)
                        }
                    }
                    // 同时为下周安排闹钟
                    alarmTime = calculateAlarmTimeMillis(course, currentWeek + 1, course.alarmMinutesBefore)
                    if (alarmTime <= System.currentTimeMillis()) continue
                } else {
                    continue
                }
            }

            result.add(alarmTime to week)
        }
        return result
    }

    /**
     * 取消所有课程的提醒
     */
    fun cancelAllReminders() {
        val allCourses = CourseDataManager.getInstance(context).getAllCourses()
        allCourses.forEach { course ->
            cancelCourseAlarm(course)
        }
        // 取消定期检查Worker
        CourseReminderWorker.cancelPeriodicCheck(context)
        // 取消每日刷新闹钟
        cancelDailyAlarmRefreshes(context)
        // 取消自动关闭定时器
        AutoStartReceiver.cancelShutdown(context)
        // 停止前台服务
        stopForegroundService()
        Log.d("AlarmService", "Cancelled all reminders")
    }

    /**
     * 如果需要，启动前台服务
     */
    private fun scheduleForegroundServiceIfNeeded() {
        if (CourseReminderForegroundService.isRunning(context)) {
            return
        }
        CourseReminderForegroundService.start(context)
    }

    /**
     * 停止前台服务
     */
    private fun stopForegroundService() {
        CourseReminderForegroundService.stop(context)
    }

    /**
     * 获取当前周数
     * 根据学期开始日期计算
     *
     * @return 当前周数（1-20）
     */
    private fun getCurrentWeek(): Int {
        val settingsManager = SettingsManager(context)
        val semesterStartDate = settingsManager.getSemesterStartDate()

        // 如果未设置学期开始日期，返回默认值1
        if (semesterStartDate == 0L) {
            return 1
        }

        // 根据时间差计算周数
        val now = System.currentTimeMillis()
        val diffMillis = now - semesterStartDate
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        val week = (diffDays / 7) + 1

        return week.coerceIn(1, settingsManager.getTotalWeeks())
    }

    /**
     * 检查课程是否在当前单双周类型下应该显示
     *
     * @param course 课程
     * @param week 周数
     * @return true表示匹配
     */
    private fun isWeekTypeMatched(course: Course, week: Int): Boolean {
        return when (course.weekType) {
            1 -> week % 2 == 1   // 单周
            2 -> week % 2 == 0   // 双周
            else -> true          // 每周
        }
    }

    /**
     * 使用学期开始日期精确计算闹钟触发时间
     * 用学期开始日期 + 周偏移 + 星期偏移 来计算，避免 Calendar.set(DAY_OF_WEEK) 的解析不确定性
     *
     * @param course 课程
     * @param targetWeek 目标周数（1~20）
     * @param alarmMinutesBefore 提前提醒分钟数
     * @return 闹钟触发时间的毫秒时间戳，如果学期未设置则返回0
     */
    private fun calculateAlarmTimeMillis(course: Course, targetWeek: Int, alarmMinutesBefore: Int): Long {
        val settingsManager = SettingsManager(context)
        val semesterStart = settingsManager.getSemesterStartDate()
        if (semesterStart == 0L) return 0L

        // 学期开始日期是 Monday（周1），构造基准 Calendar
        val calendar = Calendar.getInstance().apply {
            timeInMillis = semesterStart
            // 清零时间部分
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 移动到目标周：semesterStart + (targetWeek - 1) * 7 天
        calendar.add(Calendar.DAY_OF_YEAR, (targetWeek - 1) * 7)
        // 移动到目标星期 dayOfWeek：1=周一, ..., 7=周日，偏移量为 dayOfWeek-1
        calendar.add(Calendar.DAY_OF_YEAR, course.dayOfWeek - 1)

        // 设置上课开始时间
        val timeSlots = timeTableManager.getTimeSlots()
        val timeSlot = timeSlots.find { it.node == course.startTime }
        if (timeSlot != null) {
            val timeParts = timeSlot.startTime.split(":")
            if (timeParts.size == 2) {
                calendar.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                calendar.set(Calendar.MINUTE, timeParts[1].toInt())
            }
        } else {
            // 默认计算：每节课45分钟，从第1节8:00开始
            calendar.set(Calendar.HOUR_OF_DAY, 8 + (course.startTime - 1) * 45 / 60)
            calendar.set(Calendar.MINUTE, (course.startTime - 1) * 45 % 60)
        }

        // 减去提前提醒分钟数
        calendar.add(Calendar.MINUTE, -alarmMinutesBefore)

        return calendar.timeInMillis
    }
}

/**
 * 闹钟广播接收器
 * 当闹钟触发时接收广播并显示课程提醒通知
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        // 防重复通知：记录最近通知时间戳（课程名 -> 最后通知毫秒时间戳）
        private val lastNotificationTime = mutableMapOf<String, Long>()
        private const val DEBOUNCE_MS = 5000L // 5秒内同一课程不再弹通知
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let { DebugLogger.init(it) }
        val ctx = context ?: return
        val it = intent ?: return

        val courseName = it.getStringExtra("course_name") ?: ""
        val teacher = it.getStringExtra("course_teacher") ?: ""
        val location = it.getStringExtra("course_location") ?: ""

        if (courseName.isEmpty()) {
            Log.w(TAG, "收到空课程名的闹钟广播，跳过")
            return
        }

        // 持有 WakeLock 10秒，确保通知展示 / 数据库读取 / 闹钟重注册等操作不被系统休眠打断
        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ScheduleApp::AlarmReceiver"
        )?.apply {
            setReferenceCounted(false)
            acquire(10_000L)
        }

        try {
            // 防重复通知：5秒内同一课程不重复弹通知
            val now = System.currentTimeMillis()
            lastNotificationTime[courseName]?.let { last ->
                if (now - last < DEBOUNCE_MS) {
                    Log.d(TAG, "$courseName 防抖，跳过重复通知")
                    return
                }
            }
            lastNotificationTime[courseName] = now
            // 清理超过1分钟的旧记录，防止 map 无限增长
            lastNotificationTime.entries.removeAll { now - it.value > 60000 }

            // 创建通知渠道
            val notificationHelper = NotificationHelper(ctx)
            notificationHelper.createNotificationChannels()

            // 获取课程对象
            val course = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getSerializableExtra("course", Course::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getSerializableExtra("course") as? Course
            }

            // 使用课程ID+周次生成统一通知ID
            val week = it.getIntExtra("notification_week", 0)
            val uniqueId = if (week > 0) {
                NotificationHelper.generateNotificationId(course?.id ?: 0, week)
            } else {
                NotificationHelper.generateNotificationId(course?.id ?: 0, 0)
            }
            notificationHelper.cancelNotification(uniqueId)

            // 构建并显示通知
            val notification = notificationHelper.buildCourseReminderNotification(
                courseName = courseName,
                teacher = teacher,
                location = location,
                minutesBefore = course?.alarmMinutesBefore ?: 15,
                notificationId = uniqueId
            )

            notificationHelper.showNotification(uniqueId, notification, courseName)
            Log.d(TAG, "已弹出课前通知: $courseName week=$week")

            // 为下一周安排闹钟（持续性提醒）+ 同时刷新所有课程闹钟，
            // 避免周期应用被杀后下节课闹钟被清除
            // 重要：先校验 course 是否仍然存在于数据库中，防止已删除课程的幽灵闹钟
            if (course != null) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    try {
                        // 检查课程是否仍然存在于数据库中
                        val allCourses = CourseDataManager.getInstance(ctx).getAllCourses()
                        val stillExists = allCourses.any { it.id == course.id }
                        if (stillExists) {
                            AlarmService(ctx).setCourseAlarm(course)
                            AlarmService(ctx).registerAllCourseNotifications()
                        } else {
                            Log.w(TAG, "课程 ${course.name} (id=${course.id}) 已不存在，跳过闹钟重注册")
                            // 清理可能残留的通知和闹钟
                            NotificationHelper(ctx).cancelCourseNotifications(course.id, course.name)
                            AlarmSlotManager.getInstance(ctx).freeAllSlotsForCourse(course.id)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "重新注册闹钟失败", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理闹钟广播失败", e)
        } finally {
            try {
                wakeLock?.takeIf { it.isHeld }?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放 WakeLock 失败", e)
            }
        }
    }
}

/**
 * 开机广播接收器
 * 设备启动后恢复所有课程的闹钟
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DebugLogger.init(context)
        // 只处理开机完成广播
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // 在IO线程中恢复闹钟（使用 GlobalScope 避免 BroadcastReceiver 销毁时协程被取消）
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                // 如果闹钟启用，则恢复所有闹钟并设置每日刷新
                if (SettingsManager(context).isAlarmEnabled()) {
                    AlarmService(context).registerAllCourseNotifications()
                }
            } catch (e: Exception) {
                Log.e("BootCompletedReceiver", "Failed to restore alarms after boot", e)
            }
        }
    }
}

/**
 * 每日凌晨闹钟刷新接收器
 * 每天凌晨 4:00 自动重新注册所有课程闹钟，
 * 解决国产手机（vivo/OPPO/小米）杀进程后 AlarmManager 闹钟被清除的问题。
 * 用户当天无需手动打开应用即可确保课前通知正常工作。
 */
class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DebugLogger.init(context)
        if (intent?.action != AlarmService.DAILY_REFRESH_ACTION) return

        val refreshHour = intent.getIntExtra("refresh_hour", 4)
        val refreshRequestCode = intent.getIntExtra("refresh_request_code", 99990)

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val settingsManager = SettingsManager(context)
                if (settingsManager.isAlarmEnabled()) {
                    val alarmService = AlarmService(context)
                    alarmService.registerAllCourseNotifications()
                }
                // 重新调度同一时段的下一次刷新
                AlarmService.scheduleDailyAlarmRefreshAtHour(context, refreshHour, refreshRequestCode)
            } catch (e: Exception) {
                Log.e("DailyAlarmReceiver", "Failed to refresh daily alarms", e)
            }
        }
    }
}
