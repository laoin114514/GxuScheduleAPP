package com.cherry.wakeupschedule.ui.theme

import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.DimenRes
import com.cherry.wakeupschedule.R

/**
 * 按全局字号刻度（[R.dimen] 里的 text_* 系列）设置文字大小。
 *
 * 取 px 后以 COMPLEX_UNIT_PX 传入：dimens 中的 sp 值会先按当前 density 与 fontScale
 * 换算成 px，因此文字能正确跟随系统字体缩放与 App 内 UI 缩放档位。
 * 直接写 `textSize = 15f` 会绕过全局档位，不要再用。
 */
fun TextView.setTextSizeRes(@DimenRes dimenRes: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(dimenRes))
}
