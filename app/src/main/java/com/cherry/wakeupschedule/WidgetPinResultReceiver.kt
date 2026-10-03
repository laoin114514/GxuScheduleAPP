package com.cherry.wakeupschedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cherry.wakeupschedule.ui.feedback.AppToast

/**
 * 小组件一键添加的成功回调（requestPinAppWidget 的 successCallback）。
 *
 * 桌面放置完成后由系统发出：前台中的小组件中心直接出「已添加」卡；
 * 已退后台则交给 AppToast 暂存 8s，回到应用任意页面补发。
 * 用户在系统确认框点取消时不会有任何回调 —— 无从感知，也不出「失败」卡。
 */
class WidgetPinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: return
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: return
        val appContext = context?.applicationContext ?: return
        WidgetCenterActivity.notifyPinSuccess(appContext, message, provider)
    }

    companion object {
        const val EXTRA_PROVIDER = "extra_pin_provider"
        const val EXTRA_MESSAGE = "extra_pin_message"
    }
}
