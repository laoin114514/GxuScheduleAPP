package com.cherry.wakeupschedule

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.service.UiScaleManager

/**
 * 所有页面的基类，负责应用全局 UI 缩放档位。
 *
 * [attachBaseContext] 里按当前档位改写 densityDpi（dp 与 sp 同步等比缩放）；
 * [onResume] 比对倍率，若档位在别处被改过就重建自己 —— 这样从字体大小页返回时，
 * 后台栈中的页面会以新倍率重新渲染，无需维护全局 Activity 注册表。
 */
abstract class BaseActivity : AppCompatActivity() {

    /** 本实例创建时生效的倍率，用于检测档位变更 */
    private var appliedScale: Float = 1f

    override fun attachBaseContext(newBase: Context) {
        appliedScale = UiScaleManager.currentScale(newBase)
        super.attachBaseContext(UiScaleManager.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (appliedScale != UiScaleManager.currentScale(this)) {
            recreate()
        }
    }
}
