package com.cherry.wakeupschedule

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.service.UiScaleManager
import com.cherry.wakeupschedule.service.UpdateService

/**
 * 所有页面的基类，负责应用全局 UI 缩放档位。
 *
 * [attachBaseContext] 里按当前档位改写 densityDpi（dp 与 sp 同步等比缩放）；
 * [onResume] 比对倍率，若档位在别处被改过就重建自己 —— 这样从字体大小页返回时，
 * 后台栈中的页面会以新倍率重新渲染，无需维护全局 Activity 注册表。
 * 同时兜底"从安装未知应用设置页返回后继续引导安装更新包"（无待安装记录时开销极小）。
 */
abstract class BaseActivity : AppCompatActivity() {

    /** 本实例创建时生效的倍率，用于检测档位变更 */
    private var appliedScale: Float = 1f

    /**
     * 是否在 onResume 时续接"待安装更新包"引导。
     * 闪屏页这类立刻退场的过渡页面关掉，避免安装弹窗一闪即逝。
     */
    protected open val resumesPendingInstall: Boolean = true

    override fun attachBaseContext(newBase: Context) {
        appliedScale = UiScaleManager.currentScale(newBase)
        super.attachBaseContext(UiScaleManager.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (appliedScale != UiScaleManager.currentScale(this)) {
            recreate()
            return
        }
        // 授权设置页返回时用户可能落在任意页面，这里接着引导安装，避免安装入口凭空消失
        if (resumesPendingInstall) UpdateService(this).retryPendingInstall(this)
    }
}
