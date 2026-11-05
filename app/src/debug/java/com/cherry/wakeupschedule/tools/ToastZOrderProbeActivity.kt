package com.cherry.wakeupschedule.tools

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.feedback.AppToast

/**
 * 仅编入 debug 变体：验证 [AppToast] 在弹层打开时的窗口层级
 * （对应 docs/UI-优化方案.md「Toast 暗区」）。
 *
 * ```bash
 * adb shell am start -n com.cherry.wakeupschedule/.tools.ToastZOrderProbeActivity --es mode dialog
 * # dialog：先弹居中 StyledDialog（生产代码自动登记为提示宿主），再连发两条提示
 * adb shell am start -n com.cherry.wakeupschedule/.tools.ToastZOrderProbeActivity --es mode sheet
 * # sheet：模拟全屏底部弹层，提示显示在遮罩之上；1.6s 后自动关弹层，验证容器搬回页面
 * adb shell am start -n com.cherry.wakeupschedule/.tools.ToastZOrderProbeActivity --es mode plain
 * # plain：只发提示，作为普通场景基线
 * ```
 */
class ToastZOrderProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var sheetDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DIALOG

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#12161C")) }
        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            TextView(this).apply {
                text = "ToastZOrderProbe · mode=$mode"
                setTextColor(Color.WHITE)
                textSize = 18f
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        when (mode) {
            MODE_DIALOG -> handler.postDelayed({
                StyledDialog.Builder(this)
                    .title("弹层宿主验证")
                    .message("弹窗打开期间触发 AppToast：提示卡应显示在弹窗窗口之上，而不是被遮罩压暗。")
                    .positiveButton("知道了")
                    .show()
                handler.postDelayed({ shootToastA() }, 500)
            }, 700)

            MODE_SHEET -> handler.postDelayed({
                showSheetLikeDialog()
                handler.postDelayed({ shootToastA() }, 400)
                // 提示还在展示时关掉弹层：容器应搬回页面，卡片继续可见
                handler.postDelayed({ sheetDialog?.dismiss() }, 1600)
            }, 500)

            else -> handler.postDelayed({ shootToastA() }, 700)
        }
    }

    /** 模拟生产里的全屏底部弹层（Dialog + MATCH_PARENT 窗口 + 底部内容 + 0.5 遮罩） */
    private fun showSheetLikeDialog() {
        val dialog = Dialog(this, R.style.BottomSheetDialog)
        sheetDialog = dialog
        val sheetContent = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#20262E"))
        }
        sheetContent.addView(
            TextView(this).apply {
                text = "模拟底部弹层（全屏窗口 + 底部内容）"
                setTextColor(Color.WHITE)
                textSize = 16f
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                View(this@ToastZOrderProbeActivity),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(
                sheetContent,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700),
            )
        }
        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.BottomSheetAnimation)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
        }
        // 探针内部手动登记（生产里的 12 处弹层都在创建处自动登记）
        AppToast.registerDialogHost(dialog)
        dialog.show()
    }

    private fun shootToastA() {
        android.util.Log.i(TAG, "submit toast A")
        AppToast.info(this, "测试提示 A：应在最上层", AppToast.Duration.LONG)
        handler.postDelayed({
            android.util.Log.i(TAG, "submit toast B")
            AppToast.error(this, "测试提示 B：第二张卡片", AppToast.Duration.LONG)
        }, 600)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ToastZOrderProbe"
        const val EXTRA_MODE = "mode"
        const val MODE_DIALOG = "dialog"
        const val MODE_SHEET = "sheet"
        const val MODE_PLAIN = "plain"
    }
}
