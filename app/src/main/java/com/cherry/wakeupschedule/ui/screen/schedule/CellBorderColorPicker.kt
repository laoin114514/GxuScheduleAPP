package com.cherry.wakeupschedule.ui.screen.schedule

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 课程格子边框颜色取色器（底部弹层，自绘——Android 无系统取色组件）。
 *
 * 结构：色相条（横向 0-360°）+ 饱和度/亮度二维面板 + 不透明度条 + hex 输入 + 预览色块。
 * 「应用」「恢复默认」经 [onApplied] 把 ARGB 交回调用方（写设置 + 实时生效都在那边），
 * 「取消」不落库。默认色为 50% 半透明白，与格子边框的历史默认一致。
 *
 * 深浅模式共用一个颜色（工单边界：不做自动适配），弹层本身随主题换肤。
 */
object CellBorderColorPicker {

    /** 恢复默认的目标色：50% 半透明白（十六进制超出 Int 范围需 toInt） */
    private val DEFAULT_COLOR = 0x80FFFFFF.toInt()

    private var currentDialog: Dialog? = null

    fun show(context: Context, initialColor: Int, onApplied: (Int) -> Unit) {
        // 防止连点打开多个（旧弹窗可能已随 Activity 销毁，安全关闭）
        dismissCurrent()
        val dialog = Dialog(context, R.style.BottomSheetDialog)
        currentDialog = dialog
        dialog.setOnDismissListener { currentDialog = null }

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // ── 颜色状态：HSV 三元组 + 不透明度（alpha 单独存，因为默认色本身是半透明的） ──
        val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }
        var alphaFrac = ((initialColor ushr 24) and 0xFF) / 255f
        fun currentColor(): Int = Color.HSVToColor((alphaFrac * 255).roundToInt(), hsv)

        // ── 自绘控件 ──
        val svPanel = SvPanel(context)
        val hueBar = HueBar(context)
        val alphaBar = AlphaBar(context)
        svPanel.hue = hsv[0]
        svPanel.sat = hsv[1]
        svPanel.value = hsv[2]
        hueBar.hue = hsv[0]
        alphaBar.alphaFrac = alphaFrac

        // ── 预览色块 + hex 输入 ──
        val outlineValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOutline, outlineValue, true
        )
        val swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(28))
        }
        val etHex = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(9))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        fun refreshSwatchAndAlphaBar(color: Int) {
            swatch.background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(color)
                setStroke(dp(1), outlineValue.data)
            }
            // 不透明度条的底色跟随当前 RGB（透明 → 不透明渐变），不含 alpha
            alphaBar.rgb = color or 0xFF000000.toInt()
        }

        /** 由当前状态刷新预览/hex/各条；hex 输入中不回写，避免打断打字 */
        fun refresh(updateHex: Boolean) {
            val color = currentColor()
            refreshSwatchAndAlphaBar(color)
            if (updateHex && !etHex.hasFocus()) {
                etHex.setText(String.format(Locale.US, "#%08X", color))
            }
        }

        /** 解析 hex：6 位只改 RGB（保留当前不透明度），8 位连 alpha 一起改 */
        fun commitHex() {
            val raw = etHex.text.toString().trim().removePrefix("#")
            val parsed = when (raw.length) {
                6 -> raw.toLongOrNull(16)?.let {
                    (currentColor() and 0xFF000000.toInt()) or it.toInt()
                }
                8 -> raw.toLongOrNull(16)?.toInt()
                else -> null
            }
            if (parsed == null) {
                AppToast.warn(context, "hex 格式不正确")
                etHex.setText(String.format(Locale.US, "#%08X", currentColor()))
                return
            }
            Color.colorToHSV(parsed, hsv)
            alphaFrac = ((parsed ushr 24) and 0xFF) / 255f
            svPanel.hue = hsv[0]
            svPanel.sat = hsv[1]
            svPanel.value = hsv[2]
            hueBar.hue = hsv[0]
            alphaBar.alphaFrac = alphaFrac
            refresh(true)
        }

        // 控件自身字段只是回显，唯一事实源是 hsv/alphaFrac——回调必须回写，否则 hex/预览永远不变
        svPanel.onChanged = {
            hsv[1] = svPanel.sat
            hsv[2] = svPanel.value
            refresh(true)
        }
        hueBar.onHueChanged = { h ->
            hsv[0] = h
            svPanel.hue = h
            refresh(true)
        }
        alphaBar.onChanged = {
            alphaFrac = alphaBar.alphaFrac
            refresh(true)
        }
        etHex.setOnEditorActionListener { _, _, _ ->
            commitHex()
            true
        }
        etHex.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitHex() }

        // ── 组装布局 ──
        fun label(text: String, colorAttr: Int, bold: Boolean = false) = TextView(context).apply {
            this.text = text
            setTextSizeRes(if (bold) R.dimen.text_title else R.dimen.text_body_small)
            setTextColor(MaterialColors.getColor(this, colorAttr))
            if (bold) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }

        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch)
            addView(etHex)
        }

        svPanel.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(180)
        ).apply { topMargin = dp(16) }
        hueBar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
        ).apply { topMargin = dp(12) }
        alphaBar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
        ).apply { topMargin = dp(12) }
        previewRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        /** 文字按钮（无底色，跟随主题色） */
        fun textButton(text: String, colorAttr: Int, onClick: () -> Unit) =
            TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(MaterialColors.getColor(this, colorAttr))
                val bg = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
                setBackgroundResource(bg.resourceId)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener { onClick() }
            }

        val btnApply = MaterialButton(context).apply {
            text = "应用"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        val btnCancel = textButton("取消", com.google.android.material.R.attr.colorOnSurfaceVariant) {
            dialog.dismiss()
        }
        val btnReset = textButton("恢复默认", com.google.android.material.R.attr.colorOnSurfaceVariant) {
            // 一键回默认：直接落库生效并关闭（工单口径）
            onApplied(DEFAULT_COLOR)
            dialog.dismiss()
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            addView(FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(btnReset)
            })
            addView(btnCancel)
            addView(btnApply)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(label("边框颜色", com.google.android.material.R.attr.colorOnSurface, bold = true))
            addView(previewRow)
            addView(svPanel)
            addView(hueBar)
            addView(alphaBar)
            addView(buttonRow)
        }

        btnApply.setOnClickListener {
            onApplied(currentColor())
            dialog.dismiss()
        }

        // ── 弹窗骨架：24dp 顶部圆角 + 上半屏空白点击关闭（与应用内其他弹层同一配方） ──
        val sheetBg = GradientDrawable().apply {
            val r = 24 * density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        val surfaceValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceContainer, surfaceValue, true
        )
        sheetBg.setColor(surfaceValue.data)
        root.background = sheetBg

        dialog.setContentView(root)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (root.parent as? ViewGroup)?.removeView(root)
        root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnClickListener { dialog.dismiss() }
        })
        container.addView(root)
        dialog.setContentView(container)

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.BottomSheetAnimation)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setDimAmount(0.5f)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        refresh(true)
        dialog.show()
    }

    private fun dismissCurrent() {
        val d = currentDialog ?: return
        if (!d.isShowing) {
            currentDialog = null
            return
        }
        try {
            d.dismiss()
        } catch (_: Exception) {
            currentDialog = null
        }
    }

    // ==================== 自绘控件 ====================

    /** 色相条：0-360° 彩虹渐变，拖动改色相（SV 面板底色联动） */
    private class HueBar(context: Context) : View(context) {
        var hue: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 360f)
                invalidate()
            }
        var onHueChanged: ((Float) -> Unit)? = null

        private val paint = Paint()
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: LinearGradient? = null

        init {
            // 圆角裁剪：背景描个透明圆角矩形 + clipToOutline，onDraw 内容就被裁成胶囊形
            background = GradientDrawable().apply { cornerRadius = 14f * resources.displayMetrics.density }
            clipToOutline = true
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w <= 0) return
            val colors = IntArray(7) { i ->
                Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f))
            }
            shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f, colors,
                floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            shader?.let { paint.shader = it }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val d = resources.displayMetrics.density
            val cx = hue / 360f * width
            val cy = height / 2f
            ringPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 11 * d, ringPaint)
            corePaint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            canvas.drawCircle(cx, cy, 7 * d, corePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    hue = event.x / width.coerceAtLeast(1) * 360f
                    onHueChanged?.invoke(hue)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /** 饱和度/亮度面板：横向饱和度（左灰右纯色）、纵向亮度（上亮下暗） */
    private class SvPanel(context: Context) : View(context) {
        var hue: Float = 0f
            set(value) {
                field = value
                invalidate()
            }
        var sat: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        var value: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        var onChanged: (() -> Unit)? = null

        private val huePaint = Paint()
        private val satPaint = Paint()
        private val valuePaint = Paint()
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var satShader: LinearGradient? = null
        private var shaderHue = -1f
        private var valueShader: LinearGradient? = null

        init {
            background = GradientDrawable().apply { cornerRadius = 14f * resources.displayMetrics.density }
            clipToOutline = true
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (h <= 0) return
            valueShader = LinearGradient(
                0f, 0f, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            if (satShader == null || shaderHue != hue) {
                satShader = LinearGradient(
                    0f, 0f, w, 0f,
                    Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)),
                    Shader.TileMode.CLAMP
                )
                shaderHue = hue
            }
            huePaint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            canvas.drawRect(0f, 0f, w, h, huePaint)
            satPaint.shader = satShader
            canvas.drawRect(0f, 0f, w, h, satPaint)
            valuePaint.shader = valueShader
            canvas.drawRect(0f, 0f, w, h, valuePaint)

            val d = resources.displayMetrics.density
            val cx = sat * w
            val cy = (1f - value) * h
            ringPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 12 * d, ringPaint)
            // 圆点用不透明色，保证在暗端也可见（实际颜色带不带 alpha 看不透明度条）
            corePaint.color = Color.HSVToColor(255, floatArrayOf(hue, sat, value))
            canvas.drawCircle(cx, cy, 8 * d, corePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    sat = event.x / width.coerceAtLeast(1)
                    value = 1f - event.y / height.coerceAtLeast(1)
                    onChanged?.invoke()
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /** 不透明度条：左透明右不透明，渐变底色随当前 RGB 变化 */
    private class AlphaBar(context: Context) : View(context) {
        var alphaFrac: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        /** 当前 RGB（不含 alpha），决定渐变终点色 */
        var rgb: Int = Color.WHITE
            set(value) {
                field = value
                invalidate()
            }
        var onChanged: (() -> Unit)? = null

        private val paint = Paint()
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: LinearGradient? = null
        private var shaderRgb = 0

        init {
            background = GradientDrawable().apply { cornerRadius = 14f * resources.displayMetrics.density }
            clipToOutline = true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            if (shader == null || shaderRgb != rgb) {
                // 棋盘格省略：渐变左端 alpha 0 直接透出卡片底色，足以表达"透明"
                shader = LinearGradient(
                    0f, 0f, w, 0f,
                    Color.TRANSPARENT, rgb, Shader.TileMode.CLAMP
                )
                shaderRgb = rgb
            }
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
            val d = resources.displayMetrics.density
            val cx = alphaFrac * w
            val cy = h / 2f
            ringPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 11 * d, ringPaint)
            corePaint.color = rgb
            canvas.drawCircle(cx, cy, 7 * d, corePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    alphaFrac = event.x / width.coerceAtLeast(1)
                    onChanged?.invoke()
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
