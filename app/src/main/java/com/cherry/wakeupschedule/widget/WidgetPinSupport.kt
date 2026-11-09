package com.cherry.wakeupschedule.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 应用内「一键添加小组件」（`AppWidgetManager#requestPinAppWidget`）的机型兼容性说明。
 *
 * 官方方法的返回值只表示**桌面声称支持主动添加**，并不表示组件真的加上了。
 * 国内 ROM 上的实测表现（社区汇总，见 docs/踩坑-小组件一键添加无反应.md）：
 *
 * | 机型 | 是否弹确认框 | 其它 |
 * |---|---|---|
 * | 小米 / 红米 | 否 | 未开启「桌面快捷方式」权限时**静默不添加** |
 * | vivo / iQOO | 否 | 未接入原子组件平台时该方法**无任何效果**（第三方桌面如 Nova 正常） |
 * | 华为 / 荣耀 | 是 | 无论成败都**不会回调**传入的 PendingIntent |
 *
 * 所以调用方必须以「桌面上组件个数有没有变多」为准做核验（见 `WidgetCenterActivity`），
 * 本对象只负责给出按机型定制的手动添加提示，不参与成败判定。
 *
 * ### 小米「桌面快捷方式」实测（HyperOS / Android 15，Redmi K40）
 * 小米桌面收到 `CONFIRM_PIN_APPWIDGET` 后会进入 `AddItemActivity`，先查本应用的
 * MIUI AppOp `INSTALL_SHORTCUT`（op 10017，见桌面 Dex 的
 * `Utilities.hasAddShortcutPermission`）。op 未放行时它**只闪一下就退出**：
 * 不弹任何确认框、不添加组件，日志是 `add widget failed, <pkg> has no permission`。
 * 该 op 与 Manifest 里是否声明 `com.android.launcher.permission.INSTALL_SHORTCUT`
 * 无关（实测声明后 op 仍为 ignore），必须在
 * 设置 → 应用管理 → 本应用 → 权限管理 → 其他权限 → 桌面快捷方式 里手动选「始终允许」；
 * 开启后同样的请求会直接落地，且全程没有确认框。
 */
object WidgetPinSupport {

    /**
     * 该机型是否会在「请求被桌面静默拒绝」时呈现出「系统界面短暂出现过」的假象。
     *
     * 小米桌面失败时会短暂启动 `AddItemActivity` 再立刻退出，本页因此收到 onPause，
     * 老逻辑会把它当成「用户自己取消了确认框」。实际上小米在允许与拒绝两种情况下
     * 都不弹确认框，所以这种机型上「没加成功」只可能是桌面拒绝，应引导去开权限。
     */
    fun pinRejectedSilently(): Boolean = isBrand("xiaomi", "redmi", "poco")

    /**
     * 打开系统里本应用的「桌面快捷方式」权限页。
     * MIUI/HyperOS 优先直达「其他权限」编辑器，打不开时回落到应用详情页。
     */
    fun shortcutPermissionIntent(context: Context): Intent {
        val packageName = context.packageName
        val miuiEditor = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", packageName)
        }
        if (miuiEditor.resolveActivity(context.packageManager) != null) return miuiEditor
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
    }

    /**
     * 手动添加引导里附带的机型提示。
     * 返回 null 表示该机型没有已知的一键添加特有问题。
     */
    fun manualHint(): String? = when {
        pinRejectedSilently() ->
            "小米 / 红米：一键添加需要系统的「桌面快捷方式」权限。开启方法：设置 → 应用管理 → " +
                    "西大课栈 → 权限管理 → 其他权限 → 桌面快捷方式 → 始终允许，" +
                    "然后回到这里再点一次「添加到桌面」。"

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
