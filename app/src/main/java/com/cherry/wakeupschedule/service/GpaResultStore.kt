package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences

/**
 * 一次绩点计算的持久化结果：汇总指标 + 计算时间。
 *
 * 字段是平铺的而不是直接存 [com.cherry.wakeupschedule.ui.screen.grade.GradeSummary]，
 * 避免 service 层反向依赖 UI 层；页面侧用两个小转换函数对接。
 */
data class GpaResult(
    val averageGpa: Double?,
    val weightedAverageScore: Double?,
    val totalCredits: Double,
    val courseCount: Int,
    val failedCount: Int,
    val calculatedAt: Long
)

/**
 * 绩点计算结果的本地存储（SharedPreferences）。
 *
 * 只保留最近一次「计算」的结果，页面下次打开时直接展示，并显示「上次计算时间」，
 * 不必重复联网；成绩明细仍以 grades 表为准。
 *
 * 解绑教务会清空（见 [JwxtAuthManager.unbind]），避免换账号后展示上一个账号的绩点。
 */
class GpaResultStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 从未计算过时返回 null */
    fun load(): GpaResult? {
        if (!prefs.contains(KEY_CALCULATED_AT)) return null
        return GpaResult(
            averageGpa = prefs.optionalFloat(KEY_GPA),
            weightedAverageScore = prefs.optionalFloat(KEY_WEIGHTED_SCORE),
            totalCredits = prefs.getFloat(KEY_CREDITS, 0f).toDouble(),
            courseCount = prefs.getInt(KEY_COURSES, 0),
            failedCount = prefs.getInt(KEY_FAILED, 0),
            calculatedAt = prefs.getLong(KEY_CALCULATED_AT, 0L)
        )
    }

    fun save(result: GpaResult) {
        val editor = prefs.edit()
            .putFloat(KEY_CREDITS, result.totalCredits.toFloat())
            .putInt(KEY_COURSES, result.courseCount)
            .putInt(KEY_FAILED, result.failedCount)
            .putLong(KEY_CALCULATED_AT, result.calculatedAt)
        editor.putOptionalFloat(KEY_GPA, result.averageGpa)
        editor.putOptionalFloat(KEY_WEIGHTED_SCORE, result.weightedAverageScore)
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun SharedPreferences.optionalFloat(key: String): Double? =
        if (contains(key)) getFloat(key, 0f).toDouble() else null

    private fun SharedPreferences.Editor.putOptionalFloat(key: String, value: Double?) {
        if (value == null) remove(key) else putFloat(key, value.toFloat())
    }

    companion object {
        private const val PREFS_NAME = "gpa_result"
        private const val KEY_GPA = "average_gpa"
        private const val KEY_WEIGHTED_SCORE = "weighted_average_score"
        private const val KEY_CREDITS = "total_credits"
        private const val KEY_COURSES = "course_count"
        private const val KEY_FAILED = "failed_count"
        private const val KEY_CALCULATED_AT = "calculated_at"
    }
}
