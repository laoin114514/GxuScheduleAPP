package com.cherry.wakeupschedule.ui.screen.export

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.ui.theme.ThemeManager

/**
 * 课表导出预览弹窗。
 *
 * 交互流程：工具页点「课表导出」→ 生成整页课表 PNG（[ScheduleExportRenderer]）→ 本弹窗弹出预览。
 * 弹窗里提供两个操作：
 * - **保存到相册**：由调用方传入的 [onSave] 完成（含 API 26-28 的存储权限申请），本类不直接落盘；
 * - **分享**：由调用方传入的 [onShare] 拉起系统分享面板（见 [ScheduleShare]），本类只负责转发点击。
 *
 * 预览图按弹窗宽度等比缩放，长图可在弹窗内上下滑动查看；弹窗高度约为屏高的 88%。
 * 用 [Dialog] 而不是 BottomSheet，是为了让整页课表有更大的展示面积。
 */
class ScheduleExportDialog private constructor(
    context: Context,
    private val bitmap: Bitmap,
    private val week: Int,
    private val onSave: () -> Unit,
    private val onShare: () -> Unit,
) : Dialog(context, R.style.RoundedDialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 弹窗自己的主题会覆盖 M3 基线色板，补一次调色板 overlay 才能跟随当前配色/深浅色
        ThemeManager.applyPaletteOverlay(this)
        // 登记为提示宿主：本弹窗显示期间的 AppToast 渲染在弹窗窗口之上，不被遮罩压暗
        AppToast.registerDialogHost(this)
        setContentView(R.layout.dialog_schedule_export_preview)

        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val metrics = context.resources.displayMetrics
            val horizontalMargin = (16 * metrics.density).toInt()
            setLayout(
                metrics.widthPixels - horizontalMargin * 2,
                (metrics.heightPixels * HEIGHT_RATIO).toInt()
            )
        }

        findViewById<ImageView>(R.id.iv_export_preview).setImageBitmap(bitmap)
        findViewById<TextView>(R.id.tv_export_week_caption).text =
            context.getString(R.string.export_preview_caption, week)

        findViewById<View>(R.id.btn_export_close).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_export_save).setOnClickListener { onSave() }
        findViewById<View>(R.id.btn_export_share).setOnClickListener { onShare() }
    }

    companion object {

        /** 弹窗高度占屏高比例：留出上下边距，长图预览区尽量大 */
        private const val HEIGHT_RATIO = 0.88f

        /**
         * 弹出课表导出预览。
         *
         * @param bitmap 已经渲染好的整页课表图片（由 [ScheduleExportRenderer.renderWeek] 生成）
         * @param week 图片对应的周次，用于标题下方「第X周」说明
         * @param onSave 点击「保存到相册」时回调；权限申请与落盘由调用方处理
         * @param onShare 点击「分享」时回调；缓存落盘与分享面板由调用方处理
         */
        fun show(
            context: Context,
            bitmap: Bitmap,
            week: Int,
            onSave: () -> Unit,
            onShare: () -> Unit,
        ): ScheduleExportDialog {
            return ScheduleExportDialog(context, bitmap, week, onSave, onShare).also { it.show() }
        }
    }
}
