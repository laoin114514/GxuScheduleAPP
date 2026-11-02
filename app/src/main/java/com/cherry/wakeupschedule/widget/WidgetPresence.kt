package com.cherry.wakeupschedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * 小组件「桌面实例」的进程级缓存。
 *
 * 为什么需要：App 的每秒 tick 与每分钟 TIME_TICK 会反复询问「桌面上还有没有这个组件」，
 * 而 [AppWidgetManager.getAppWidgetIds] 是跨进程调用 —— 每秒来一次在低端机上纯属白烧电。
 * 组件增删一定会回调 provider 的 onUpdate（带当前完整的 id 列表）/ onDeleted / onDisabled，
 * 因此本进程内可以安全地缓存一份 id 列表，只有进程刚启动（尚不知道）时才查一次系统。
 *
 * 线程安全：全部状态由 [lock] 保护；对外返回的都是缓存快照，调用方改不到缓存。
 * 不持有 Context：只在查询时现取 AppWidgetManager，查完即弃，不会泄漏 Activity。
 */
internal object WidgetPresence {

    private val EMPTY = IntArray(0)

    /** provider 类 → 桌面实例 id（不可变快照，整体替换而非原地修改） */
    private val idsByProvider = HashMap<Class<*>, IntArray>()

    private val lock = Any()

    /**
     * 取某个 provider 当前的实例 id；首次调用查一次系统（IPC），之后纯内存。
     * 没有任何实例时返回空数组（调用方据此零成本短路）。
     */
    fun widgetIds(context: Context, providerClass: Class<*>): IntArray {
        synchronized(lock) {
            idsByProvider[providerClass]?.let { return it }
            // 首次（进程刚启动）才真的问系统。查询放在锁内：查询耗时极短且只发生一次，
            // 换来的是与组件增删回调串行，不会出现「旧查询结果覆盖更新的缓存」的竞态。
            val ids = query(context, providerClass)
            if (ids !== EMPTY) idsByProvider[providerClass] = ids
            return ids
        }
    }

    /** 是否至少有一个实例 */
    fun isPresent(context: Context, providerClass: Class<*>): Boolean =
        widgetIds(context, providerClass).isNotEmpty()

    /** onUpdate 带来的是当前完整的实例列表，直接替换缓存 */
    fun markUpdated(providerClass: Class<*>, ids: IntArray) {
        synchronized(lock) {
            idsByProvider[providerClass] = if (ids.isEmpty()) EMPTY else ids.copyOf()
        }
    }

    /** onDeleted 只删掉被移除的那几个 id，剩余的继续有效 */
    fun markDeleted(providerClass: Class<*>, deletedIds: IntArray) {
        if (deletedIds.isEmpty()) return
        synchronized(lock) {
            val current = idsByProvider[providerClass] ?: return
            idsByProvider[providerClass] = current.filterNot { it in deletedIds }.toIntArray()
        }
    }

    /** onDisabled：最后一个实例已被移除 */
    fun markAbsent(providerClass: Class<*>) {
        synchronized(lock) {
            idsByProvider[providerClass] = EMPTY
        }
    }

    /**
     * 向系统查询实例 id。查询失败不写缓存（下次还会重试），本次按「无实例」处理：
     * 宁可这一轮不刷新，也不每秒白跑一次会失败的 IPC。
     */
    private fun query(context: Context, providerClass: Class<*>): IntArray = try {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, providerClass)) ?: EMPTY
    } catch (e: Exception) {
        e.printStackTrace()
        EMPTY
    }
}
