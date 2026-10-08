package com.cherry.wakeupschedule.widget

import android.os.Build

/**
 * 应用内「一键添加小组件」（`AppWidgetManager#requestPinAppWidget`）的机型兼容性说明。
 *
 * 官方方法的返回值只表示**桌面声称支持主动添加**，并不表示组件真的加上了。
 * 国内 ROM 上的实测表现（社区汇总，见 docs/踩坑-小组件一键添加无反应.md）：
 *
 * | 机型 | 是否弹确认框 | 其它 |
 * |---|---|---|
 * | 小米 / 红米 | 否 | 未授予「桌面快捷方式」权限时**静默不添加** |
 * | vivo / iQOO | 否 | 未接入原子组件平台时该方法**无任何效果**（第三方桌面如 Nova 正常） |
 * | 华为 / 荣耀 | 是 | 无论成败都**不会回调**传入的 PendingIntent |
 *
 * 所以调用方必须以「桌面上组件个数有没有变多」为准做核验（见 `WidgetCenterActivity`），
 * 本对象只负责给出按机型定制的手动添加提示，不参与成败判定。
 */
object WidgetPinSupport {

    /**
     * 手动添加引导里附带的机型提示。
     * 返回 null 表示该机型没有已知的一键添加特有问题。
     */
    fun manualHint(): String? = when {
        isBrand("xiaomi", "redmi", "poco") ->
            "小米 / 红米：一键添加还需要「桌面快捷方式」权限 —— 设置 → 应用管理 → 西大课栈 → 权限管理 → 其他权限 → 桌面快捷方式，允许后再试。"

        isBrand("vivo", "iqoo") ->
            "vivo / iQOO：系统桌面不响应应用发起的一键添加，只能按上面的步骤在桌面组件库里手动拖出。"

        isBrand("huawei", "honor", "hihonor") ->
            "华为 / 荣耀：一键添加的确认框不会返回结果，若桌面上已经出现小组件，忽略本提示即可。"

        else -> null
    }

    private fun isBrand(vararg names: String): Boolean {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return names.any { it in brand }
    }
}
