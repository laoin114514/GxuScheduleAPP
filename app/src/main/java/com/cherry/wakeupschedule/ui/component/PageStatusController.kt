package com.cherry.wakeupschedule.ui.component

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * 页面状态层控制器：加载中 / 空态 / 失败 / 内容 四态的统一实现。
 *
 * 成绩查询页与考试安排页原来各有一份逐行相同的 showStatus / hideStatus /
 * setStatusHeight / loadingScrim（约 50 行 × 2），任意一页改动都会造成视觉漂移。
 * 这里收成一份，两个页面只负责提供视图与「查询按钮置灰」的回调。
 *
 * 视图约定见 `res/layout/view_empty_state.xml`：
 * ll_status 容器 + pb_loading / iv_status_icon / tv_status / tv_status_action。
 */
class PageStatusController(
    private val container: View,
    private val textView: TextView,
    private val loadingView: View,
    private val iconView: ImageView,
    private val actionView: TextView,
    private val listView: RecyclerView,
    /** 加载态变化回调（用于把「查询」按钮置灰 / 恢复） */
    private val onLoadingChanged: (Boolean) -> Unit = {}
) {

    /** 盖住列表的 loading 遮罩：与列表卡片同色的圆角块 */
    private val scrim: GradientDrawable by lazy {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * container.resources.displayMetrics.density
            setColor(
                ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(
                        container,
                        com.google.android.material.R.attr.colorSurfaceContainerLowest
                    ),
                    0xF5
                )
            )
        }
    }

    /**
     * 显示状态层。
     *
     * @param loading true = 转圈并盖住旧列表；false = 空态/失败态
     * @param iconRes 非加载态显示的状态图标（空态用语义图标，失败用 error 图标）
     * @param actionText / action 主动作（去绑定 / 重试），没有就不显示
     */
    fun show(
        text: String,
        loading: Boolean,
        @DrawableRes iconRes: Int? = null,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        container.isVisible = true
        loadingView.isVisible = loading
        iconView.isVisible = !loading && iconRes != null
        iconRes?.let { iconView.setImageResource(it) }
        actionView.isVisible = !loading && actionText != null && action != null
        actionView.text = actionText
        actionView.setOnClickListener { action?.invoke() }
        textView.text = text

        // 加载中铺满列表区：盖住旧数据，避免"查询中"被当成新结果；
        // 高度必须显式给——单个 match_parent 子项在 wrap_content 容器里量不出列表高度
        setHeight(if (loading && listView.height > 0) listView.height else null)
        container.isClickable = loading
        container.background = if (loading) scrim else null
        onLoadingChanged(loading)
    }

    fun hide() {
        container.isVisible = false
        container.isClickable = false
        container.background = null
        setHeight(null)
        onLoadingChanged(false)
    }

    private fun setHeight(height: Int?) {
        val lp = container.layoutParams
        val target = height ?: android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height == target) return
        lp.height = target
        container.layoutParams = lp
    }
}
