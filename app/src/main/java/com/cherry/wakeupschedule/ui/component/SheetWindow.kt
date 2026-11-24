package com.cherry.wakeupschedule.ui.component

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 全屏底部弹层（课表菜单 / 课程详情）的窗口统一配置。
 *
 * 遮罩方案：窗口自己开系统 dim 层（FLAG_DIM_BEHIND），由 [setDim] 逐帧调节强度。
 * - dim 层覆盖整屏，状态栏 / 手势条区域与页面一起变暗，不会出现「状态栏一块没遮住」；
 * - 颜色/强度（50% 黑）与全 App 其它弹窗一致；
 * - 窗口自身铺到整屏且系统栏透明，弹层内容可以延伸到手势条下面（由调用方补内边距），
 *   避免窗口被系统栏裁出黑边。
 *
 * 入场/退场时调用方一边滑入内容、一边把 dim 从 0 渐变到 [DIM_AMOUNT]（退出反向），
 * 这样既有渐变、又不用在内容层叠一层会拦触摸的自绘遮罩。
 */
object SheetWindow {

    /** 遮罩强度：与 Theme 里 backgroundDimAmount / 其它弹窗保持一致 */
    const val DIM_AMOUNT = 0.5f

    /**
     * 把 [dialog] 配置成铺满整屏的底部弹层窗口。需在 [Dialog.show] 之前调用。
     */
    fun configure(dialog: Dialog) {
        val window = dialog.window ?: return
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        window.setGravity(Gravity.BOTTOM)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // 内容滑入/滑出全部由内容层自己动画：系统 window 动画会和内容动画打架
        window.setWindowAnimations(0)

        // 遮罩用系统 dim 层：整屏生效（含状态栏 / 手势条），且强度可逐帧渐变
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)

        // 系统栏透明 + 窗口铺到系统栏下面，避免状态栏/导航条被窗口裁出黑边
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /** 设置窗口 dim 强度（0f..1f）。供入场/退场动画逐帧调用。 */
    fun setDim(dialog: Dialog, amount: Float) {
        // 弹窗已关闭时窗口已 detach，继续写 attributes 会抛异常（入场/退场动画与 dismiss 有竞态）
        if (!dialog.isShowing) return
        val window = dialog.window ?: return
        val lp = window.attributes
        if (lp.dimAmount == amount) return
        lp.dimAmount = amount
        window.attributes = lp
    }

    /** 当前窗口 dim 强度（退场动画从当前值开始渐隐） */
    fun currentDim(dialog: Dialog): Float = dialog.window?.attributes?.dimAmount ?: 0f

    /**
     * 给弹层内容补上系统导航条（手势条）内边距。
     *
     * 窗口铺到整屏后，弹层底部会延伸到手势条下面；内容区必须自己避让，
     * 否则最后一排控件会被手势条压住。
     */
    fun applyNavigationBarInset(view: View, context: Context) {
        val activity = findActivity(context) ?: return
        val inset = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        if (inset <= 0) return
        view.setPadding(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            view.paddingBottom + inset
        )
    }

    /** 从可能被 ContextWrapper 包了一层的 context 里找出 Activity */
    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
