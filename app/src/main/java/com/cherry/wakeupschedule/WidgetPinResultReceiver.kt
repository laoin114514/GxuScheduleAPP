package com.cherry.wakeupschedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 小组件一键添加的成功回调（requestPinAppWidget 的 successCallback）。
 *
 * 这里只通知前台中的「小组件中心」去核验刚发起的那笔请求，不直接判定成败、也不出提示卡：
 * - 华为 / 荣耀的桌面无论添加成功与否都不会发出这个回调；
 * - 反过来，回调到达也不等于组件真的落在桌面上（见 WidgetCenterActivity 的核验逻辑）。
 * 用户在系统确认框点取消时同样没有任何回调 —— 无从感知，由核验超时兜底。
 */
class WidgetPinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val provider = intent?.getStringExtra(EXTRA_PROVIDER) ?: return
        WidgetCenterActivity.notifyPinSuccess(provider)
    }

    companion object {
        const val EXTRA_PROVIDER = "extra_pin_provider"
    }
}
