package com.cherry.wakeupschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import com.cherry.wakeupschedule.MainActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.service.TimeTableManager
import java.util.Calendar
import kotlin.math.abs

/**
 * 下课倒计时小组件提供者（2×2）。
 *
 * 布局：顶部状态（上课中 / 当前无课）贴顶，其余 5 行竖向居中 ——
 * 时间+说明 / 倒计时 / 课名 / 教室 / 教师。
 * 有进行中的课 → 距下课倒计时；今天还有下一节 → 距上课倒计时。
 * 倒计时走系统 Chronometer（进程被杀也能继续走秒），格式由 App 指定以保证恒为两位时分秒；
 * 归零时由安全闹钟刷新翻状态，跨整点时由格式闹钟回来重设格式。
 *
 * 性能口径（工作流 C）：
 * - 这是全 App 唯一被 App 每秒调用的组件入口。无实例时 [triggerUpdate] 纯内存短路（不 IPC/不广播）；
 * - 有实例时进程内直接刷新（不再 sendBroadcast 走 AMS）；
 * - 「按秒去重提交」：每秒只重算一份内容快照并比签名，内容没变就**不重建 RemoteViews、不发 IPC**
 *   （Chronometer 在宿主里自己逐秒走字，基准不变时无需每秒重设；状态/文案变化的那一秒照常提交）；
 * - 归零/跨整点/下课三类精确闹钟按「目标时刻」去重，逐秒 tick 不再重复 cancel+setExact；
 * - 15 分钟兜底刷新并入「所有小组件共用的一条闹钟」，不再每款组件各挂一条；
 * - PendingIntent 全部进程内复用，避免每次刷新都跨进程申请一遍。
 */
class MinimalWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.cherry.wakeupschedule.widget.minimal.ACTION_REFRESH"
        private const val WIDGET_MINIMAL_COURSE_END_REQUEST_CODE = 10006
        private const val WIDGET_MINIMAL_TICK_REQUEST_CODE = 10007
        private const val WIDGET_MINIMAL_SAFETY_REQUEST_CODE = 10008

        /** 跨整点重设 Chronometer 格式（与到点翻状态的安全闹钟并存，互不覆盖） */
        private const val WIDGET_MINIMAL_FORMAT_REQUEST_CODE = 10011

        /** 内容每秒刷新一次，已被取消/无需重设的闹钟目标用一个宽一点的容差判定「还是同一个目标」 */
        private const val ALARM_TARGET_TOLERANCE_MS = 1500L

        /** 内容签名里用的分隔符（NUL，正常课程文案不会出现，避免字段拼接歧义） */
        private const val SIGNATURE_SEPARATOR = "\u0000"

        // ── PendingIntent 进程内缓存（内容每秒刷新，逐秒重新申请都是跨进程 IPC） ──

        /** 点击整块组件 → 打开 App（所有实例共用同一份） */
        @Volatile
        private var clickPendingIntent: PendingIntent? = null

        @Volatile
        private var courseEndPendingIntent: PendingIntent? = null

        @Volatile
        private var safetyPendingIntent: PendingIntent? = null

        @Volatile
        private var formatPendingIntent: PendingIntent? = null

        @Volatile
        private var tickPendingIntent: PendingIntent? = null

        // ── 闹钟去重状态（本进程口径；闹钟本身跨进程存活，进程重启后按新目标重挂即可） ──

        /** 已挂的下课精确闹钟目标时刻（0 = 当前没挂） */
        @Volatile
        private var courseEndAlarmTarget = 0L

        /** 已挂的倒计时归零安全闹钟目标时刻（0 = 没挂）。每秒 tick 靠它避免重复 setExact */
        @Volatile
        private var safetyAlarmTarget = 0L

        /** 已挂的跨整点格式闹钟目标时刻（0 = 没挂） */
        @Volatile
        private var formatAlarmTarget = 0L

        /** 旧版本短周期 tick 闹钟只取消一次（minSdk 26 起倒计时已交给 Chronometer，不再调度它） */
        @Volatile
        private var legacyTickCanceled = false

        /** 本进程是否已为高频 tick 补挂过一次周期/下课闹钟（之后交给闹钟链自身维持） */
        @Volatile
        private var alarmsPrimed = false

        // ── 按秒去重提交用的内容签名（进程级缓存；进程重启后为空 → 先提交一次，无视觉差异） ──

        private val lastSubmittedSignatures = HashMap<Int, String>()
        private val signatureLock = Any()

        private fun lastSignatureOf(appWidgetId: Int): String? =
            synchronized(signatureLock) { lastSubmittedSignatures[appWidgetId] }

        private fun rememberSignatures(appWidgetIds: IntArray, signature: String) {
            synchronized(signatureLock) {
                for (appWidgetId in appWidgetIds) lastSubmittedSignatures[appWidgetId] = signature
            }
        }

        private fun forgetSignatures(appWidgetIds: IntArray) {
            synchronized(signatureLock) {
                for (appWidgetId in appWidgetIds) lastSubmittedSignatures.remove(appWidgetId)
            }
        }

        private fun forgetAllSignatures() {
            synchronized(signatureLock) { lastSubmittedSignatures.clear() }
        }

        // ── 每秒内容计算用的「今日课程 + 起止分钟」缓存 ──
        //
        // 每秒 tick 即使不提交也要先算出内容快照做签名比对；旧路径每秒都会
        // filter 全部课程 + sortedBy（比较器里逐个按节次查时间表、split 解析 HH:mm），
        // 一天里这些结果只有「跨天/跨周/改时间表/课程数据变化」时才变。
        // 这里按 (课程列表引用, 星期, 周次, 时间表引用) 缓存，稳定态每秒只剩 n 次 int 比较。
        private val todayCacheLock = Any()

        private class TodayCourses(
            val courses: List<com.cherry.wakeupschedule.model.Course>,
            val startMinutes: IntArray,
            val endMinutes: IntArray
        )

        @Volatile
        private var cachedTodayCourses: TodayCourses = TodayCourses(emptyList(), IntArray(0), IntArray(0))

        @Volatile
        private var cachedTodaySource: List<com.cherry.wakeupschedule.model.Course>? = null

        @Volatile
        private var cachedTodayDay: Int = Int.MIN_VALUE

        @Volatile
        private var cachedTodayWeek: Int = Int.MIN_VALUE

        @Volatile
        private var cachedTodaySlots: List<TimeTableManager.TimeSlot>? = null

        /**
         * 取指定星期/周次的今日课程（已按开始分钟升序）+ 对应起止分钟数组。
         * 语义与旧实现逐字段一致：filter(dayOfWeek, isActiveInWeek) → sortedBy(开始分钟)；
         * 开始分钟相同的课程保持数据源里的相对顺序（稳定排序）。
         */
        private fun todayCoursesSnapshot(
            context: Context,
            adjustedDayOfWeek: Int,
            currentWeek: Int
        ): TodayCourses {
            val allCourses = CourseDataManager.getAllCoursesSafely(context)
            val slots = TimeTableManager.getInstance(context).getTimeSlots()
            if (allCourses === cachedTodaySource &&
                adjustedDayOfWeek == cachedTodayDay &&
                currentWeek == cachedTodayWeek &&
                slots === cachedTodaySlots
            ) {
                return cachedTodayCourses
            }
            synchronized(todayCacheLock) {
                if (allCourses === cachedTodaySource &&
                    adjustedDayOfWeek == cachedTodayDay &&
                    currentWeek == cachedTodayWeek &&
                    slots === cachedTodaySlots
                ) {
                    return cachedTodayCourses
                }
                val filtered = allCourses.filter { course ->
                    course.dayOfWeek == adjustedDayOfWeek &&
                        course.isActiveInWeek(currentWeek)
                }
                val starts = IntArray(filtered.size)
                val ends = IntArray(filtered.size)
                filtered.forEachIndexed { i, course ->
                    starts[i] = CourseTimeUtils.getStartMinutes(context, course)
                    ends[i] = CourseTimeUtils.getEndMinutes(context, course)
                }
                val order = filtered.indices.sortedBy { starts[it] }
                val snapshot = TodayCourses(
                    courses = order.map { filtered[it] },
                    startMinutes = IntArray(order.size) { starts[order[it]] },
                    endMinutes = IntArray(order.size) { ends[order[it]] }
                )
                cachedTodayCourses = snapshot
                cachedTodaySource = allCourses
                cachedTodayDay = adjustedDayOfWeek
                cachedTodayWeek = currentWeek
                cachedTodaySlots = slots
                return snapshot
            }
        }

        /**
         * 触发小组件更新（App 的每秒 tick 入口）。
         *
         * 旧实现是 sendBroadcast → onReceive → getAppWidgetIds；App 每秒调一次就等于每秒
         * 一次 AMS 广播 + 一次 IPC。现在：无实例纯内存返回；有实例直接进程内刷新（同一进程，
         * 视觉等价，只是提前几毫秒），并且内容签名没变时连 RemoteViews 都不重建。
         */
        fun triggerUpdate(context: Context) {
            if (!isPlaced(context)) return
            val appWidgetIds = WidgetPresence.widgetIds(context, MinimalWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            MinimalWidgetProvider().refresh(context, appWidgetIds, forceSubmit = false)
            // 进程启动后第一次 tick 补挂一次周期/下课闹钟（内部按已挂状态/目标时刻去重，只做一次）
            primeAlarmsOnce(context)
        }

        /**
         * 事件驱动的强制刷新（系统改时间/改时区/跨天等）。
         *
         * 这些事件会让「按秒去重提交」的前提失效：墙上时钟跳变后，即使可见文案没变，
         * Chronometer 的倒计时基准也可能已经偏了，必须无条件重提交一次。
         */
        internal fun triggerUpdateNow(context: Context) {
            if (!isPlaced(context)) return
            val appWidgetIds = WidgetPresence.widgetIds(context, MinimalWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            MinimalWidgetProvider().refresh(context, appWidgetIds, forceSubmit = true)
            primeAlarmsOnce(context)
        }

        /**
         * 「下课倒计时」是否至少有一个实例在桌面。
         *
         * 供 App 的每秒 tick 在调用 [triggerUpdate] 前零成本短路：进程内缓存，首次调用
         * 查一次 AppWidgetManager，之后由 onUpdate/onEnabled/onDeleted/onDisabled 维护，
         * 不会每次 IPC。线程安全，不持有 Context。
         */
        fun isPlaced(context: Context): Boolean =
            WidgetPresence.isPresent(context, MinimalWidgetProvider::class.java)

        /** 点击组件打开 App 的 PendingIntent（进程内复用，避免每秒一次跨进程申请） */
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

        /**
         * 刷新所有实例（内容 + 闹钟，强制提交）。
         * 调用方已确认有实例；这里再取一次缓存的 id（纯内存，几乎零成本）。
         */
        private fun updateAllWidgets(context: Context) {
            val appWidgetIds = WidgetPresence.widgetIds(context, MinimalWidgetProvider::class.java)
            if (appWidgetIds.isEmpty()) return
            MinimalWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds)
        }

        /** 进程内只补挂一次闹钟（周期闹钟 / 下课闹钟内部都有去重，这里避免每秒做课程扫描） */
        private fun primeAlarmsOnce(context: Context) {
            if (alarmsPrimed) return
            synchronized(MinimalWidgetProvider::class.java) {
                if (alarmsPrimed) return
                alarmsPrimed = true
            }
            MinimalWidgetProvider().schedulePeriodicUpdate(context)
            MinimalWidgetProvider().scheduleNextCourseEndUpdate(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 系统回调带来当前完整的实例列表：顺手校准「是否有实例」的进程内缓存
        WidgetPresence.markUpdated(MinimalWidgetProvider::class.java, appWidgetIds)
        // 闹钟/系统驱动的刷新强制提交一次（顺带校正可能已漂移的宿主视图）
        if (appWidgetIds.isNotEmpty()) refresh(context, appWidgetIds, forceSubmit = true)
        schedulePeriodicUpdate(context)
        scheduleNextCourseEndUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
        schedulePeriodicUpdate(context)
        WidgetMidnightReceiver.scheduleMidnightUpdate(context)
        ScheduleWidgetUpdateService.triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.markAbsent(MinimalWidgetProvider::class.java)
        forgetAllSignatures()
        try {
            cancelMinimalCourseEndUpdate(context)
            cancelMinimalTick(context)
            cancelCountdownSafetyUpdate(context)
            cancelCountdownFormatUpdate(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 如果这是桌面上最后一个小组件，顺手停掉 30 分钟兜底链，避免空转唤醒
        ScheduleWidgetUpdateService.onProviderDisabled(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPresence.markDeleted(MinimalWidgetProvider::class.java, appWidgetIds)
        forgetSignatures(appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            "com.cherry.wakeupschedule.widget.ACTION_PERIODIC_UPDATE",
            "com.cherry.wakeupschedule.widget.minimal.ACTION_TICK" -> updateAllWidgets(context)
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
     * 安排下一个课程结束时的更新
     * 使用绝对时间调度（基于课程实际下课时刻），确保无论何时调用都能准确触发。
     * 目标时刻没变就不再重复 setExact：原先每次刷新（每秒一次）都会重设一遍。
     */
    private fun scheduleNextCourseEndUpdate(context: Context) {
        try {
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            val currentTimeMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val currentWeek = calculateCurrentWeek(SettingsManager(context))

            val todayEndCourses = CourseDataManager.getAllCoursesSafely(context)
                .filter { it.dayOfWeek == dayOfWeek && it.isActiveInWeek(currentWeek) }
                .mapNotNull { val end = getCourseEndMinutes(context, it); if (end > currentTimeMinutes) end to it else null }
                .sortedBy { it.first }

            if (todayEndCourses.isEmpty()) {
                cancelMinimalCourseEndUpdate(context)
                return
            }

            // 使用绝对时间：计算出今天课程结束的精确时刻，而非相对延迟
            // 这样即使上课中途刷新了小组件，闹钟触发时间也不会偏移
            val endMinutes = todayEndCourses[0].first
            val calendarEnd = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, endMinutes)
            }
            val targetTriggerMillis = calendarEnd.timeInMillis + 2000L // 下课后2秒触发，给系统一点处理时间

            if (targetTriggerMillis <= System.currentTimeMillis() + 3000L) {
                // 距离触发时刻已不足 3 秒：闹钟已无意义。本次刷新在调度之前就已经把内容算过一遍了，
                // 这里只需要撤掉旧闹钟；不要再触发一轮刷新（旧实现会刷新 → 再判断 → 再刷新地递归下去）。
                cancelMinimalCourseEndUpdate(context)
                return
            }

            if (targetTriggerMillis == courseEndAlarmTarget) return

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(
                context,
                WIDGET_MINIMAL_COURSE_END_REQUEST_CODE,
                MinimalWidgetCourseEndReceiver::class.java,
                courseEndPendingIntent
            )
            courseEndPendingIntent = pendingIntent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTriggerMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetTriggerMillis, pendingIntent)
            }
            // 只有真正 set 成功才记目标：Android 14+ 精确闹钟被拒（SecurityException）时不记，
            // 下次刷新仍会重试，保持原有失败回退语义
            courseEndAlarmTarget = targetTriggerMillis
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消课程结束时的更新
     */
    private fun cancelMinimalCourseEndUpdate(context: Context) {
        // 本进程没挂过就不白跑一次 IPC（闹钟不在的情况占多数）
        if (courseEndAlarmTarget == 0L) return
        courseEndAlarmTarget = 0L
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(
                alarmPendingIntent(
                    context,
                    WIDGET_MINIMAL_COURSE_END_REQUEST_CODE,
                    MinimalWidgetCourseEndReceiver::class.java,
                    courseEndPendingIntent
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── 内容快照 / 渲染 / 提交 ──

    /**
     * 一次渲染所需的全部内容。
     *
     * 刻意不包含 Chronometer 的基准时刻：Chronometer 在宿主进程里自己逐秒走字，
     * 只要「目标时刻」没变就不需要每秒重设，这正是「按秒去重提交」的前提。
     */
    private data class ContentSnapshot(
        /** 内容签名：任一可见字段变化都会变；不变时可安全跳过 RemoteViews 重建与提交 */
        val signature: String,
        /** 顶部状态行是否可见（异常态隐藏） */
        val classStatusVisible: Boolean,
        val inClass: Boolean,
        val courseLabel: String,
        /** 时间行文案；null = 隐藏 */
        val courseTimeText: String?,
        /** 教室；空串 = 隐藏整行 */
        val classroom: String,
        /** 教师；空串 = 隐藏整行 */
        val teacher: String,
        /** 倒计时目标当天分钟数（>=0 且 format != null 时走 Chronometer） */
        val countdownTargetMinutes: Int,
        /** Chronometer 格式；null 表示不走 Chronometer */
        val countdownFormat: String?,
        /** 静态倒计时文本（异常态 "--"）；与 format 都为 null 表示整块隐藏 */
        val countdownText: String?,
        /** 数据读取失败态：只改视图，不动已挂的闹钟（与旧实现一致） */
        val isError: Boolean
    )

    /**
     * 计算当前应展示的内容（纯数据，不碰 RemoteViews）。
     * 与旧实现逐字段等价：状态行文案/颜色由 inClass 决定，时间行取时间表 HH:mm（缺失退回「第N节」），
     * 教室/教师为空时整行隐藏，倒计时走 Chronometer（格式按剩余时长选，保证恒为 8 字符）。
     */
    private fun computeContent(context: Context, calendar: Calendar, currentTimeSeconds: Int): ContentSnapshot {
        val settingsManager = SettingsManager(context)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val adjustedDayOfWeek = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
        val currentTime = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val currentWeek = calculateCurrentWeek(settingsManager)

        // 今日课程与起止分钟按（课程列表, 星期, 周次, 时间表）缓存：稳定态每秒不再 filter/sort/解析时间表
        val today = todayCoursesSnapshot(context, adjustedDayOfWeek, currentWeek)
        val courses = today.courses
        val starts = today.startMinutes
        val ends = today.endMinutes

        // 当前课：旧实现 = 按开始分钟升序后第一个满足 start <= now < end 的课程（这里逐个比较，等价）
        for (i in courses.indices) {
            if (currentTime >= starts[i] && currentTime < ends[i]) {
                return courseSnapshot(
                    context, calendar, courses[i],
                    inClass = true, isEnd = true,
                    targetMinutes = ends[i], currentTimeSeconds = currentTimeSeconds
                )
            }
        }
        // 下一节课：旧实现 = 升序列表里第一个 start > now 的课程
        for (i in courses.indices) {
            if (starts[i] > currentTime) {
                return courseSnapshot(
                    context, calendar, courses[i],
                    inClass = false, isEnd = false,
                    targetMinutes = starts[i], currentTimeSeconds = currentTimeSeconds
                )
            }
        }
        // 无课 / 今日已结束：明细行全收起、倒计时块整体隐藏（与旧实现一致）
        return snapshot(
            context, calendar,
            classStatusVisible = true,
            inClass = false,
            courseLabel = if (courses.isEmpty()) "今天没课" else "今日已结束",
            courseTimeText = null,
            classroom = "",
            teacher = "",
            countdownTargetMinutes = -1,
            countdownFormat = null,
            countdownText = null,
            isError = false
        )
    }

    /** 有课状态（进行中 / 下一节）的快照：课名、时间行、教室、教师 + 倒计时目标 */
    private fun courseSnapshot(
        context: Context,
        calendar: Calendar,
        course: com.cherry.wakeupschedule.model.Course,
        inClass: Boolean,
        isEnd: Boolean,
        targetMinutes: Int,
        currentTimeSeconds: Int
    ): ContentSnapshot {
        // 格式按剩余时长选：<1h "00:%s"、1–9h "0%s"、≥10h "%s"，保证 Chronometer 渲染恒为 8 字符
        val remainingSeconds = (targetMinutes * 60 - currentTimeSeconds).coerceAtLeast(0)
        val hours = remainingSeconds / 3600
        val format = when {
            hours == 0 -> "00:%s"
            hours < 10 -> "0%s"
            else -> "%s"
        }
        return snapshot(
            context, calendar,
            classStatusVisible = true,
            inClass = inClass,
            courseLabel = course.name,
            courseTimeText = courseTimeText(context, course, isEnd),
            classroom = course.classroom,
            teacher = course.teacher,
            countdownTargetMinutes = targetMinutes,
            countdownFormat = format,
            countdownText = null,
            isError = false
        )
    }

    /** 数据读取失败态：顶部状态隐藏、课名显示"加载失败"、明细行收起、倒计时显示 "--" */
    private fun errorSnapshot(context: Context, calendar: Calendar): ContentSnapshot = snapshot(
        context, calendar,
        classStatusVisible = false,
        inClass = false,
        courseLabel = "加载失败",
        courseTimeText = null,
        classroom = "",
        teacher = "",
        countdownTargetMinutes = -1,
        countdownFormat = null,
        countdownText = "--",
        isError = true
    )

    /** 组装快照并算签名：签名覆盖渲染用到的全部输入 + 自然日 + 深浅色模式（跨天/换主题要强制提交） */
    private fun snapshot(
        context: Context,
        calendar: Calendar,
        classStatusVisible: Boolean,
        inClass: Boolean,
        courseLabel: String,
        courseTimeText: String?,
        classroom: String,
        teacher: String,
        countdownTargetMinutes: Int,
        countdownFormat: String?,
        countdownText: String?,
        isError: Boolean
    ): ContentSnapshot {
        // 自然日：即使两天课表完全一样，跨天也要提交一次
        val dayKey = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
        // 深浅色模式：状态行/文字颜色取自当前配置下的颜色资源，模式切换需要重新提交
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // 倒计时走 Chronometer 时按分钟重提交一次：系统校时（NTP）后墙上时钟若有跳变，
        // 最多 1 分钟就能把宿主里的倒计时基准纠回；其余时间（无倒计时）不产生这次提交。
        val countdownMinuteKey =
            if (countdownFormat != null) (calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)).toString()
            else ""
        val signature = listOf(
            dayKey.toString(), nightMode.toString(),
            classStatusVisible.toString(), inClass.toString(),
            courseLabel, courseTimeText ?: "", classroom, teacher,
            countdownTargetMinutes.toString(), countdownFormat ?: "", countdownText ?: "",
            countdownMinuteKey
        ).joinToString(SIGNATURE_SEPARATOR)
        return ContentSnapshot(
            signature = signature,
            classStatusVisible = classStatusVisible,
            inClass = inClass,
            courseLabel = courseLabel,
            courseTimeText = courseTimeText,
            classroom = classroom,
            teacher = teacher,
            countdownTargetMinutes = countdownTargetMinutes,
            countdownFormat = countdownFormat,
            countdownText = countdownText,
            isError = isError
        )
    }

    /** 时间行（倒计时上方）："{时间} 下课，距下课还有" / "{时间} 上课，距上课还有"（时间表缺失退回「第 N 节」） */
    private fun courseTimeText(
        context: Context,
        course: com.cherry.wakeupschedule.model.Course,
        isEnd: Boolean
    ): String {
        val node = if (isEnd) course.endTime else course.startTime
        val slot = try {
            TimeTableManager.getInstance(context).getTimeSlots().find { it.node == node }
        } catch (e: Exception) {
            null
        }
        val clock = if (isEnd) slot?.endTime else slot?.startTime
        val timeText = if (!clock.isNullOrBlank()) clock else "第${node}节"
        val action = if (isEnd) "下课" else "上课"
        val countdownHint = if (isEnd) "距下课还有" else "距上课还有"
        return "$timeText $action，$countdownHint"
    }

    /**
     * 计算内容 →（按需）提交 RemoteViews。
     *
     * 提交门控：内容签名与该实例上次成功提交的一致时，直接跳过（不 new RemoteViews、不走 updateAppWidget）。
     * 视觉等价性：RemoteViews 是幂等的「设置为这些值」，两次内容相同 → 跳过与提交效果相同；
     * Chronometer 的逐秒跳字由宿主进程里的 Chronometer 自己完成，不依赖每秒重设。
     * 强制提交（forceSubmit=true）用于系统/闹钟驱动的刷新与首帧，顺带校正宿主侧可能漂移的视图。
     */
    private fun refresh(context: Context, appWidgetIds: IntArray, forceSubmit: Boolean) {
        val calendar = Calendar.getInstance()
        val currentTimeSeconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)
        val content = try {
            computeContent(context, calendar, currentTimeSeconds)
        } catch (e: Exception) {
            e.printStackTrace()
            errorSnapshot(context, calendar)
        }
        // 倒计时相关闹钟：目标时刻不变时内部会去重，稳定态 0 次 IPC
        if (!content.isError) scheduleContentAlarms(context, content, currentTimeSeconds)

        val staleIds: IntArray = if (forceSubmit) {
            appWidgetIds
        } else {
            appWidgetIds.filter { lastSignatureOf(it) != content.signature }.toIntArray()
        }
        if (staleIds.isEmpty()) return

        try {
            val views = RemoteViews(context.packageName, R.layout.widget_minimal)
            views.setOnClickPendingIntent(R.id.widget_container, clickPendingIntent(context))
            renderContent(context, views, content, currentTimeSeconds)
            AppWidgetManager.getInstance(context).updateAppWidget(staleIds, views)
            rememberSignatures(staleIds, content.signature)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 把内容快照写进 RemoteViews（与旧实现逐字段等价） */
    private fun renderContent(
        context: Context,
        views: RemoteViews,
        content: ContentSnapshot,
        currentTimeSeconds: Int
    ) {
        if (content.classStatusVisible) {
            showClassStatus(context, views, content.inClass)
        } else {
            views.setViewVisibility(R.id.tv_class_status, android.view.View.GONE)
        }
        views.setTextViewText(R.id.tv_course_label, content.courseLabel)

        if (content.courseTimeText != null) {
            views.setTextViewText(R.id.tv_course_time, content.courseTimeText)
            views.setViewVisibility(R.id.tv_course_time, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_course_time, android.view.View.GONE)
        }

        if (content.classroom.isBlank()) {
            views.setViewVisibility(R.id.ll_location, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.ll_location, android.view.View.VISIBLE)
            views.setTextViewText(R.id.tv_course_location, content.classroom)
        }

        if (content.teacher.isBlank()) {
            views.setViewVisibility(R.id.tv_course_teacher, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.tv_course_teacher, android.view.View.VISIBLE)
            views.setTextViewText(R.id.tv_course_teacher, content.teacher)
        }

        val format = content.countdownFormat
        if (format != null && content.countdownTargetMinutes >= 0) {
            val remainingSeconds = (content.countdownTargetMinutes * 60 - currentTimeSeconds).coerceAtLeast(0)
            views.setViewVisibility(R.id.tv_countdown, android.view.View.GONE)
            views.setViewVisibility(R.id.chronometer_countdown, android.view.View.VISIBLE)
            views.setChronometerCountDown(R.id.chronometer_countdown, true)
            views.setChronometer(
                R.id.chronometer_countdown,
                SystemClock.elapsedRealtime() + remainingSeconds * 1000L,
                format,
                true
            )
        } else if (content.countdownText != null) {
            views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
            views.setViewVisibility(R.id.tv_countdown, android.view.View.VISIBLE)
            views.setTextViewText(R.id.tv_countdown, content.countdownText)
        } else {
            views.setViewVisibility(R.id.chronometer_countdown, android.view.View.GONE)
            views.setViewVisibility(R.id.tv_countdown, android.view.View.GONE)
        }
    }

    /**
     * 按当前内容安排倒计时相关闹钟（归零翻状态 / 跨整点换格式）。
     * 旧实现是渲染时顺手排；现在渲染可能被签名门控跳过，所以独立出来每次都跑（内部按目标去重，稳定态 0 IPC）。
     */
    private fun scheduleContentAlarms(context: Context, content: ContentSnapshot, currentTimeSeconds: Int) {
        val format = content.countdownFormat
        if (format != null && content.countdownTargetMinutes >= 0) {
            val remainingSeconds = (content.countdownTargetMinutes * 60 - currentTimeSeconds).coerceAtLeast(0)
            // 归零时刷新并按新状态重渲染（Chronometer 归零后会继续走成负数）
            scheduleCountdownSafetyUpdate(context, (remainingSeconds * 1000L).coerceAtLeast(1000L))
            // 跨过整点（1 小时 / 10 小时）时回来重设格式，否则会残留旧格式的数字
            val hours = remainingSeconds / 3600
            val boundarySeconds = when {
                hours in 1..9 -> remainingSeconds - 3600
                hours >= 10 -> remainingSeconds - 10 * 3600
                else -> -1
            }
            if (boundarySeconds >= 0) {
                scheduleCountdownFormatUpdate(context, (boundarySeconds + 1) * 1000L)
            } else {
                cancelCountdownFormatUpdate(context)
            }
        } else {
            cancelCountdownSafetyUpdate(context)
            cancelCountdownFormatUpdate(context)
        }
        cancelMinimalTick(context)
    }

    /** 顶部状态：上课中（主色）/ 当前无课（弱色），两个有课状态一眼区分 */
    private fun showClassStatus(context: Context, views: RemoteViews, inClass: Boolean) {
        views.setViewVisibility(R.id.tv_class_status, android.view.View.VISIBLE)
        views.setTextViewText(R.id.tv_class_status, if (inClass) "上课中" else "当前无课")
        views.setTextColor(
            R.id.tv_class_status,
            context.getColor(if (inClass) R.color.widget_accent else R.color.widget_text_weak)
        )
    }

    /**
     * 取消短周期刷新（旧版本遗留的闹钟，minSdk 26 起已不再调度；只取消一次，不每秒空跑 IPC）
     */
    private fun cancelMinimalTick(context: Context) {
        if (legacyTickCanceled) return
        legacyTickCanceled = true
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(
                context,
                WIDGET_MINIMAL_TICK_REQUEST_CODE,
                MinimalWidgetTickReceiver::class.java,
                tickPendingIntent
            )
            tickPendingIntent = pendingIntent
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 安排倒计时结束后的安全更新
     * 当剩余时间不足60秒时使用文本显示，并在倒计时结束后触发一次更新以避免负数时间。
     *
     * 目标时刻不变时直接跳过：这正是 App 每秒 tick 会重复走到的地方，原先每秒都要
     * cancel + setExact 一次（2 次 IPC），现在稳定态是 0 次。
     */
    private fun scheduleCountdownSafetyUpdate(context: Context, delayMillis: Long) {
        if (delayMillis <= 0) return
        try {
            val targetMillis = System.currentTimeMillis() + delayMillis
            if (safetyAlarmTarget != 0L && abs(targetMillis - safetyAlarmTarget) <= ALARM_TARGET_TOLERANCE_MS) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(
                context,
                WIDGET_MINIMAL_SAFETY_REQUEST_CODE,
                MinimalWidgetSafetyReceiver::class.java,
                safetyPendingIntent
            )
            safetyPendingIntent = pendingIntent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
            }
            // 只有 set 成功才记目标：精确闹钟被系统拒绝时不记，下次刷新继续重试（保持原失败回退语义）
            safetyAlarmTarget = targetMillis
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消倒计时安全更新（没挂过就不白跑 IPC）
     */
    private fun cancelCountdownSafetyUpdate(context: Context) {
        if (safetyAlarmTarget == 0L) return
        safetyAlarmTarget = 0L
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(
                alarmPendingIntent(
                    context,
                    WIDGET_MINIMAL_SAFETY_REQUEST_CODE,
                    MinimalWidgetSafetyReceiver::class.java,
                    safetyPendingIntent
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 跨整点重设 Chronometer 格式的闹钟（复用安全刷新接收器，靠 request code 与到点闹钟区分）。
     * 目标时刻不变时跳过，避免每秒重复 setExact。
     */
    private fun scheduleCountdownFormatUpdate(context: Context, delayMillis: Long) {
        if (delayMillis <= 0) return
        try {
            val targetMillis = System.currentTimeMillis() + delayMillis
            if (formatAlarmTarget != 0L && abs(targetMillis - formatAlarmTarget) <= ALARM_TARGET_TOLERANCE_MS) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(
                context,
                WIDGET_MINIMAL_FORMAT_REQUEST_CODE,
                MinimalWidgetSafetyReceiver::class.java,
                formatPendingIntent
            )
            formatPendingIntent = pendingIntent
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
            formatAlarmTarget = targetMillis
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消跨整点的格式更新（没挂过就不白跑 IPC）
     */
    private fun cancelCountdownFormatUpdate(context: Context) {
        if (formatAlarmTarget == 0L) return
        formatAlarmTarget = 0L
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(
                alarmPendingIntent(
                    context,
                    WIDGET_MINIMAL_FORMAT_REQUEST_CODE,
                    MinimalWidgetSafetyReceiver::class.java,
                    formatPendingIntent
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取课程开始时间（分钟）
     */
    private fun getCourseStartMinutes(context: Context, course: com.cherry.wakeupschedule.model.Course): Int =
        CourseTimeUtils.getStartMinutes(context, course)

    /**
     * 获取课程结束时间（分钟）
     */
    private fun getCourseEndMinutes(context: Context, course: com.cherry.wakeupschedule.model.Course): Int =
        CourseTimeUtils.getEndMinutes(context, course)

    /**
     * 计算当前周
     */
    private fun calculateCurrentWeek(settingsManager: SettingsManager): Int =
        CourseTimeUtils.getCurrentWeek(settingsManager)
}
