package com.cherry.wakeupschedule.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cherry.wakeupschedule.model.Course
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 精确提醒Worker
 * 使用WorkManager实现课程提醒，作为AlarmManager的备份方案
 */
class ExactAlarmWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val notificationHelper = NotificationHelper(context)
    private val courseDataManager = CourseDataManager.getInstance(context)

    companion object {
        private const val TAG = "ExactAlarmWorker"
        private const val KEY_COURSE_JSON = "course_json"
        private const val KEY_COURSE_ID = "course_id"
        private const val WORK_NAME_PREFIX = "course_exact_reminder"
        private const val CATCHUP_WORK_NAME_PREFIX = "course_catchup"

        /**
         * 调度课程提醒（标准备份）
         */
        fun scheduleReminder(context: Context, course: Course, delayMinutes: Long) {
            val courseJson = Gson().toJson(course)

            val inputData = Data.Builder()
                .putString(KEY_COURSE_JSON, courseJson)
                .putLong(KEY_COURSE_ID, course.id)
                .build()

            val workName = "${WORK_NAME_PREFIX}_${course.id}"

            val workRequest = OneTimeWorkRequestBuilder<ExactAlarmWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(inputData)
                .addTag("course_reminder_${course.id}")
                .build()

            // 使用 REPLACE 策略确保同一课程的 ExactAlarm 备份只有一个待执行任务
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "已调度课程 ${course.name} 的精确提醒，延迟 ${delayMinutes} 分钟")
        }

        /**
         * 调度立即补救提醒（独立 workName，不与标准备份互相覆盖）
         *
         * 关键设计：使用独立的 workName="course_catchup_{id}"，
         * 避免被 registerCourseNotificationsForSemester 的备份代码
         * 或 scheduleExactReminder 的 REPLACE 策略覆盖。
         */
        fun scheduleImmediate(context: Context, course: Course) {
            val courseJson = Gson().toJson(course)

            val inputData = Data.Builder()
                .putString(KEY_COURSE_JSON, courseJson)
                .putLong(KEY_COURSE_ID, course.id)
                .build()

            val workName = "${CATCHUP_WORK_NAME_PREFIX}_${course.id}"

            val workRequest = OneTimeWorkRequestBuilder<ExactAlarmWorker>()
                .setInitialDelay(0, TimeUnit.MINUTES)
                .setInputData(inputData)
                .addTag("course_catchup_${course.id}")
                .build()

            // REPLACE 确保同一课程的补救任务不堆积
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "已调度课程 ${course.name} 的立即补救提醒")
        }

        /**
         * 取消课程提醒（同时取消标准备份和补救任务）
         */
        fun cancelReminder(context: Context, courseId: Long) {
            val workName = "${WORK_NAME_PREFIX}_$courseId"
            val catchupWorkName = "${CATCHUP_WORK_NAME_PREFIX}_$courseId"
            WorkManager.getInstance(context).cancelUniqueWork(workName)
            WorkManager.getInstance(context).cancelUniqueWork(catchupWorkName)
            WorkManager.getInstance(context).cancelAllWorkByTag("course_reminder_$courseId")
            WorkManager.getInstance(context).cancelAllWorkByTag("course_catchup_$courseId")
            Log.d(TAG, "已取消课程 ID=$courseId 的精确提醒（含补救任务）")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val courseJson = inputData.getString(KEY_COURSE_JSON)
            @Suppress("UNUSED_VARIABLE")
            val courseId = inputData.getLong(KEY_COURSE_ID, -1)

            if (courseJson.isNullOrEmpty()) {
                Log.e(TAG, "课程数据为空")
                return@withContext Result.failure()
            }

            val course = Gson().fromJson(courseJson, Course::class.java)

            Log.d(TAG, "触发课程提醒：${course.name}")

            val notification = notificationHelper.buildCourseReminderNotification(
                courseName = course.name,
                teacher = course.teacher,
                location = course.classroom,
                minutesBefore = course.alarmMinutesBefore,
                notificationId = NotificationHelper.generateNotificationId(course.id, 0)
            )

            notificationHelper.showNotification(
                NotificationHelper.generateNotificationId(course.id, 0),
                notification,
                course.name
            )

            if (shouldReschedule(course)) {
                rescheduleNextWeek(course)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "处理精确提醒失败", e)
            Result.retry()
        }
    }

    /**
     * 检查是否需要重新调度下周的提醒
     */
    private fun shouldReschedule(course: Course): Boolean {
        val currentWeek = getCurrentWeek()
        // 只在本学期周范围内且未超过结束周时重新调度
        return currentWeek in course.startWeek..course.endWeek
    }

    /**
     * 重新调度下周的课程提醒
     * 使用 AlarmService 内统一的算法，避免 Calendar.set(DAY_OF_WEEK) 的解析不确定性问题
     */
    private fun rescheduleNextWeek(course: Course) {
        try {
            val settingsManager = SettingsManager(applicationContext)
            val semesterStart = settingsManager.getSemesterStartDate()
            if (semesterStart == 0L) {
                Log.w(TAG, "学期开始日期未设置，跳过下周重调度")
                return
            }

            val currentWeek = getCurrentWeek()
            val targetWeek = currentWeek + 1
            if (targetWeek !in course.startWeek..course.endWeek) {
                Log.d(TAG, "下周已超出课程范围，跳过重调度")
                return
            }
            if (!isWeekTypeMatched(course, targetWeek)) {
                Log.d(TAG, "下周单双周不匹配，跳过重调度")
                return
            }

            val timeTableManager = TimeTableManager.getInstance(applicationContext)
            val timeSlots = timeTableManager.getTimeSlots()
            val timeSlot = timeSlots.find { it.node == course.startTime }
            val (hour, minute) = if (timeSlot != null) {
                val parts = timeSlot.startTime.split(":")
                if (parts.size == 2) parts[0].toInt() to parts[1].toInt() else 8 to 0
            } else {
                (8 + (course.startTime - 1) * 45 / 60) to ((course.startTime - 1) * 45 % 60)
            }

            val calendar = Calendar.getInstance().apply {
                timeInMillis = semesterStart
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, (targetWeek - 1) * 7)
                add(Calendar.DAY_OF_YEAR, course.dayOfWeek - 1)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                add(Calendar.MINUTE, -course.alarmMinutesBefore)
            }

            val now = System.currentTimeMillis()
            val delayMillis = calendar.timeInMillis - now
            if (delayMillis <= 0) return
            val delayMinutes = (delayMillis / (1000 * 60)).coerceAtLeast(1)

            scheduleReminder(applicationContext, course, delayMinutes)
            Log.d(TAG, "已为课程 ${course.name} 调度下周提醒，延迟 ${delayMinutes} 分钟")
        } catch (e: Exception) {
            Log.e(TAG, "调度下周提醒失败", e)
        }
    }

    private fun isWeekTypeMatched(course: Course, week: Int): Boolean {
        return when (course.weekType) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
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
}
