package com.cherry.wakeupschedule.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cherry.wakeupschedule.model.Course
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 课程提醒定期检查Worker
 * 定时检查即将开始的课程并设置提醒
 */
class CourseReminderWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val courseDataManager = CourseDataManager.getInstance(context)
    private val timeTableManager = TimeTableManager.getInstance(context)
    private val alarmService = AlarmService(context)

    companion object {
        private const val TAG = "CourseReminderWorker"
        private const val WORK_NAME = "course_reminder_check"
        private val REPEAT_INTERVAL_MINUTES = 15L

        /**
         * 调度定期检查工作
         */
        fun schedulePeriodicCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<CourseReminderWorker>(
                REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "已调度定期课程检查工作，每 ${REPEAT_INTERVAL_MINUTES} 分钟执行一次")
        }

        /**
         * 取消定期检查工作
         */
        fun cancelPeriodicCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "已取消定期课程检查工作")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始定期检查课程提醒...")

            if (!SettingsManager(applicationContext).isAlarmEnabled()) {
                Log.d(TAG, "课前提醒已关闭，跳过检查")
                return@withContext Result.success()
            }

            // 清理已过期的闹钟槽位（闹钟时间已过1小时以上的）
            cleanStaleSlots()

            val currentWeek = getCurrentWeek()
            val currentDayOfWeek = getCurrentDayOfWeek()
            val currentTime = getCurrentTimeSlot()

            val allCourses = courseDataManager.getAllCourses()
            val todayCourses = allCourses.filter { course ->
                course.alarmEnabled &&
                course.dayOfWeek == currentDayOfWeek &&
                currentWeek in course.startWeek..course.endWeek &&
                isWeekTypeMatched(course, currentWeek)
            }

            todayCourses.forEach { course ->
                val timeSlots = timeTableManager.getTimeSlots()
                val timeSlot = timeSlots.find { it.node == course.startTime }

                if (timeSlot != null) {
                    val courseStartMinutes = timeSlot.startTime.split(":").let {
                        it[0].toInt() * 60 + it[1].toInt()
                    }
                    val currentMinutes = currentTime.first * 60 + currentTime.second
                    val minutesUntilClass = courseStartMinutes - currentMinutes - course.alarmMinutesBefore

                    when {
                        minutesUntilClass in 0..REPEAT_INTERVAL_MINUTES.toInt() -> {
                            Log.d(TAG, "课程 ${course.name} 将在 ${minutesUntilClass} 分钟后开始，调度精确提醒")
                            alarmService.scheduleExactReminder(course)
                        }
                        minutesUntilClass > REPEAT_INTERVAL_MINUTES -> {
                            Log.d(TAG, "课程 ${course.name} 还有 ${minutesUntilClass} 分钟，暂时不处理")
                        }
                        else -> {
                            // 已错过提醒时间但课程尚未开始 → 补救
                            if (currentMinutes < courseStartMinutes) {
                                Log.w(TAG, "补救：课程 ${course.name} 提醒已错过但尚未上课，立即通知")
                                ExactAlarmWorker.scheduleImmediate(applicationContext, course)
                            } else {
                                Log.d(TAG, "课程 ${course.name} 已开始，跳过")
                            }
                        }
                    }
                }
            }

            // 检查是否有课程在未来30分钟内开始，预防性刷新对应课程的闹钟
            // 避免因系统杀进程导致闹钟丢失
            refreshUpcomingAlarms(currentWeek, currentDayOfWeek, currentTime)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "检查课程提醒失败", e)
            Result.retry()
        }
    }

    /**
     * 清理已过期的闹钟槽位（触发时间已超过 1 小时的）
     */
    private fun cleanStaleSlots() {
        try {
            val slotManager = AlarmSlotManager.getInstance(applicationContext)
            val now = System.currentTimeMillis()
            val staleThreshold = 60 * 60 * 1000L  // 1 小时
            slotManager.getActiveSlotDataList().forEach { slot ->
                if (now - slot.alarmTimeMillis > staleThreshold) {
                    Log.d(TAG, "清理过期槽位 ${slot.slotId}: ${slot.courseName} week=${slot.week}")
                    slotManager.freeSlot(slot.slotId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理过期槽位失败", e)
        }
    }

    /**
     * 预防性刷新即将开始的课程闹钟（30分钟内）
     * 如果闹钟被系统清除，通过 exactReminder 兜底
     */
    private fun refreshUpcomingAlarms(currentWeek: Int, currentDayOfWeek: Int, currentTime: Pair<Int, Int>) {
        val currentMinutes = currentTime.first * 60 + currentTime.second
        val allCourses = courseDataManager.getAllCourses()
        val upcomingCourses = allCourses.filter { course ->
            course.alarmEnabled &&
            course.dayOfWeek == currentDayOfWeek &&
            currentWeek in course.startWeek..course.endWeek &&
            isWeekTypeMatched(course, currentWeek)
        }

        upcomingCourses.forEach { course ->
            val timeSlots = timeTableManager.getTimeSlots()
            val timeSlot = timeSlots.find { it.node == course.startTime }
            if (timeSlot != null) {
                val courseStartMinutes = timeSlot.startTime.split(":").let {
                    it[0].toInt() * 60 + it[1].toInt()
                }
                val minutesUntilAlarm = courseStartMinutes - course.alarmMinutesBefore - currentMinutes
                if (minutesUntilAlarm in 1..30) {
                    // 在闹钟窗口内，确保有 WorkManager 兜底
                    val delayMinutes = minutesUntilAlarm.coerceAtLeast(1).toLong()
                    ExactAlarmWorker.scheduleReminder(applicationContext, course, delayMinutes)
                    Log.d(TAG, "预防性刷新: ${course.name} 推迟 ${delayMinutes} 分钟")
                }
            }
        }
    }

    /**
     * 获取当前周数
     */
    private fun getCurrentWeek(): Int {
        val settingsManager = SettingsManager(applicationContext)
        val semesterStartDate = settingsManager.getSemesterStartDate()

        if (semesterStartDate == 0L) {
            return 1
        }

        val now = System.currentTimeMillis()
        val diffMillis = now - semesterStartDate
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        val week = (diffDays / 7) + 1

        return week.coerceIn(1, settingsManager.getTotalWeeks())
    }

    /**
     * 获取当前星期几（1=周一, 7=周日，与 Course.dayOfWeek 对齐）
     */
    private fun getCurrentDayOfWeek(): Int {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        return if (dayOfWeek == 0) 7 else dayOfWeek
    }

    /**
     * 获取当前时间（小时和分钟）
     */
    private fun getCurrentTimeSlot(): Pair<Int, Int> {
        val calendar = Calendar.getInstance()
        return Pair(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
    }

    /**
     * 检查课程的单双周类型是否匹配
     */
    private fun isWeekTypeMatched(course: Course, week: Int): Boolean {
        return when (course.weekType) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }
}
