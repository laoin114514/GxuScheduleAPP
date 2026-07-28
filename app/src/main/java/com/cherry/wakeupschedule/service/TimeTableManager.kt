package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 时间表管理器
 * 负责课程时间段的读取和保存
 */
class TimeTableManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "time_table_prefs"
        private const val KEY_TIME_SLOTS = "time_slots"
        private const val KEY_MAX_NODES = "max_nodes"

        @Volatile
        private var instance: TimeTableManager? = null

        fun getInstance(context: Context): TimeTableManager {
            return instance ?: synchronized(this) {
                instance ?: TimeTableManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        // 默认的课程时间段（广西大学 13 节，2022年2月14日起执行）
        val defaultTimeSlots = listOf(
            TimeSlot(1, "08:00", "08:45"),
            TimeSlot(2, "08:55", "09:40"),
            TimeSlot(3, "10:00", "10:45"),
            TimeSlot(4, "10:55", "11:40"),
            TimeSlot(5, "14:30", "15:15"),
            TimeSlot(6, "15:20", "16:05"),
            TimeSlot(7, "16:25", "17:10"),
            TimeSlot(8, "17:15", "18:00"),
            TimeSlot(9, "", ""),
            TimeSlot(10, "", ""),
            TimeSlot(11, "19:40", "20:25"),
            TimeSlot(12, "20:30", "21:15"),
            TimeSlot(13, "21:20", "22:05")
        )

        fun getTimeSlot(startNode: Int): TimeSlot? {
            return defaultTimeSlots.find { it.node == startNode }
        }

        fun getTimeRange(startNode: Int, endNode: Int): String {
            val startSlot = getTimeSlot(startNode)
            val endSlot = getTimeSlot(endNode)
            return if (startSlot != null && endSlot != null) {
                "${startSlot.startTime}-${endSlot.endTime}"
            } else {
                "第${startNode}-${endNode}节"
            }
        }
    }

    /**
     * 获取自定义的时间表
     */
    fun getTimeSlots(): List<TimeSlot> {
        val json = prefs.getString(KEY_TIME_SLOTS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<TimeSlot>>() {}.type
                val slots = gson.fromJson<List<TimeSlot>>(json, type)
                // 检查时间槽数据是否有效
                if (slots.isNotEmpty()) {
                    slots
                } else {
                    defaultTimeSlots
                }
            } catch (e: Exception) {
                android.util.Log.e("TimeTableManager", "解析时间槽失败", e)
                defaultTimeSlots
            }
        } else {
            defaultTimeSlots
        }
    }

    /**
     * 保存自定义的时间表
     */
    fun saveTimeSlots(timeSlots: List<TimeSlot>) {
        val json = gson.toJson(timeSlots)
        prefs.edit().putString(KEY_TIME_SLOTS, json).apply()
    }

    /**
     * 获取最大节次数
     */
    fun getMaxNodes(): Int {
        return prefs.getInt(KEY_MAX_NODES, 13)
    }

    /**
     * 设置最大节次数
     */
    fun setMaxNodes(maxNodes: Int) {
        prefs.edit().putInt(KEY_MAX_NODES, maxNodes).apply()
    }

    /**
     * 重置为默认时间表
     */
    fun resetToDefault() {
        prefs.edit()
            .remove(KEY_TIME_SLOTS)
            .putInt(KEY_MAX_NODES, 13)
            .apply()
        // 保存默认时间槽
        saveTimeSlots(defaultTimeSlots)
    }

    /**
     * 将当前设置的时间表设为默认
     */
    fun setCurrentAsDefault() {
        val currentSlots = getTimeSlots()
        val currentMaxNodes = getMaxNodes()
        // 保存当前设置为默认（这里只是确保当前设置被正确保存）
        saveTimeSlots(currentSlots)
        setMaxNodes(currentMaxNodes)
    }

    /**
     * 添加一个时间段
     */
    fun addTimeSlot(node: Int, startTime: String, endTime: String) {
        val currentSlots = getTimeSlots().toMutableList()
        // 移除相同节次的旧数据
        currentSlots.removeAll { it.node == node }
        // 添加新的
        currentSlots.add(TimeSlot(node, startTime, endTime))
        // 按节次排序
        currentSlots.sortBy { it.node }
        saveTimeSlots(currentSlots)
    }

    /**
     * 删除一个时间段
     */
    fun removeTimeSlot(node: Int) {
        val currentSlots = getTimeSlots().toMutableList()
        currentSlots.removeAll { it.node == node }
        saveTimeSlots(currentSlots)
    }

    /**
     * 更新时间段
     */
    fun updateTimeSlot(node: Int, startTime: String, endTime: String) {
        addTimeSlot(node, startTime, endTime)
    }

    data class TimeSlot(
        val node: Int,
        val startTime: String,
        val endTime: String
    )
}
