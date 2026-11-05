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

    /**
     * 时间槽缓存锁。
     *
     * 旧实现每次 getTimeSlots() 都从 SharedPreferences 读 JSON 再用 Gson 解析一遍，
     * 而课表每页 bind 会调一次、倒计时每秒的比较器里会调几十次，低端机上纯属白烧 CPU。
     * 这里缓存成不可变快照：读走 @Volatile 无锁快路径，写（保存/重置）在同一把锁里
     * 同步更新 SharedPreferences 内存镜像 + 缓存，保证「保存后所有线程立即看到新值」。
     */
    private val cacheLock = Any()

    /** 时间槽缓存；null = 尚未从 SharedPreferences 加载 */
    @Volatile
    private var cachedTimeSlots: List<TimeSlot>? = null

    /** 最大节次数缓存；[MAX_NODES_UNSET] = 尚未从 SharedPreferences 加载 */
    @Volatile
    private var cachedMaxNodes: Int = MAX_NODES_UNSET

    companion object {
        private const val PREFS_NAME = "time_table_prefs"
        private const val KEY_TIME_SLOTS = "time_slots"
        private const val KEY_MAX_NODES = "max_nodes"

        /** 最大节次数缓存未命中标记（合法的节次数不可能取到这个值） */
        private const val MAX_NODES_UNSET = Int.MIN_VALUE

        /** getMaxNodes() 未设置时的默认节次数 */
        private const val DEFAULT_MAX_NODES = 13

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
     *
     * 返回的是缓存的不可变快照（语义与旧实现一致：按存储顺序、无效/空数据回落 [defaultTimeSlots]）。
     * 调用方只读不写（全项目都是 find/sortedBy/filter 这类只读用法），不要强转成可变列表修改它。
     */
    fun getTimeSlots(): List<TimeSlot> {
        // 快路径：命中缓存只有一次 volatile 读，不碰 SharedPreferences，也不解析 Gson
        cachedTimeSlots?.let { return it }
        synchronized(cacheLock) {
            cachedTimeSlots?.let { return it }
            val loaded = loadTimeSlotsFromPrefs()
            cachedTimeSlots = loaded
            return loaded
        }
    }

    /** 从 SharedPreferences 读并解析时间槽（只在缓存未命中时执行一次） */
    private fun loadTimeSlotsFromPrefs(): List<TimeSlot> {
        val json = prefs.getString(KEY_TIME_SLOTS, null)
        return if (json != null) {
            try {
                // 用 getParameterized 而不是匿名 TypeToken 子类：R8 会合并匿名子类并丢泛型签名，
                // 导致 TypeToken 构造时抛 "Missing type parameter."（release 包闪退）
                val type = TypeToken.getParameterized(List::class.java, TimeSlot::class.java).type
                val slots = gson.fromJson<List<TimeSlot>>(json, type)
                // 检查时间槽数据是否有效
                if (slots.isNotEmpty()) {
                    slots.toList()
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
     *
     * 保存后缓存同步替换，其他线程下一次 getTimeSlots() 立即拿到新值（不需要等磁盘落盘）。
     */
    fun saveTimeSlots(timeSlots: List<TimeSlot>) {
        synchronized(cacheLock) {
            val snapshot = timeSlots.toList()
            val json = gson.toJson(snapshot)
            prefs.edit().putString(KEY_TIME_SLOTS, json).apply()
            cachedTimeSlots = snapshot
        }
    }

    /**
     * 获取最大节次数
     */
    fun getMaxNodes(): Int {
        val cached = cachedMaxNodes
        if (cached != MAX_NODES_UNSET) return cached
        synchronized(cacheLock) {
            if (cachedMaxNodes == MAX_NODES_UNSET) {
                cachedMaxNodes = prefs.getInt(KEY_MAX_NODES, DEFAULT_MAX_NODES)
            }
            return cachedMaxNodes
        }
    }

    /**
     * 设置最大节次数
     */
    fun setMaxNodes(maxNodes: Int) {
        synchronized(cacheLock) {
            prefs.edit().putInt(KEY_MAX_NODES, maxNodes).apply()
            cachedMaxNodes = maxNodes
        }
    }

    /**
     * 重置为默认时间表
     */
    fun resetToDefault() {
        // 两次写入都放进同一把锁：避免别的线程在「槽位已重置、节次数还没重置」的中间态读到混搭值
        synchronized(cacheLock) {
            prefs.edit()
                .remove(KEY_TIME_SLOTS)
                .putInt(KEY_MAX_NODES, DEFAULT_MAX_NODES)
                .apply()
            // 保存默认时间槽（内部会同步刷新 cachedTimeSlots）
            saveTimeSlots(defaultTimeSlots)
            cachedMaxNodes = DEFAULT_MAX_NODES
        }
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
