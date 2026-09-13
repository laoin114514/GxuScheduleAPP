package com.cherry.wakeupschedule

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.cherry.wakeupschedule.databinding.ActivityFontSizeBinding
import com.cherry.wakeupschedule.service.UiScaleManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * 字体大小设置页（整体 UI 缩放档位）。
 *
 * 顶部预览按当前档位实时渲染，下方滑块在四档预设间切换 —— 实现见 [UiScaleManager]，
 * 通过改写 densityDpi 让 dp 与 sp 同步变化，因此字体与界面尺寸一起缩放、版面比例保持不变。
 *
 * 拖动过程中只刷新预览与档位文字；松手才写入设置并重建本页，避免拖动时页面反复重建。
 */
class FontSizeActivity : BaseActivity() {

    private lateinit var binding: ActivityFontSizeBinding

    /** 刻度名称，索引与 [UiScaleManager.PRESETS] 对应 */
    private val tickViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFontSizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupPageHeader(binding.toolbar, "字体大小")
        buildScaleTicks()
        setupScaleSlider()
    }

    private fun setupScaleSlider() {
        val presets = UiScaleManager.PRESETS
        val lastIndex = presets.lastIndex
        val currentIndex = presets.indexOfFirst { it.key == UiScaleManager.currentKey(this) }
            .coerceAtLeast(0)

        // 先设初值再挂监听，避免初始化触发一次多余的预览重建
        with(binding.sliderScale) {
            valueFrom = 0f
            valueTo = lastIndex.toFloat()
            stepSize = 1f
            value = currentIndex.toFloat()
            // 胶囊轨道较粗会埋住刻度，关掉刻度、由下方档位名标注（与课表页周数滑块一致）
            isTickVisible = false
            // 拖动气泡直接显示档位名，而不是数字
            setLabelFormatter { value -> presets[value.roundToInt().coerceIn(0, lastIndex)].label }
        }

        binding.sliderScale.addOnChangeListener { _, value, _ ->
            showPreset(value.roundToInt().coerceIn(0, lastIndex))
        }
        binding.sliderScale.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                val index = slider.value.roundToInt().coerceIn(0, lastIndex)
                val preset = presets[index]
                if (preset.key == UiScaleManager.currentKey(this@FontSizeActivity)) return
                UiScaleManager.setPreset(this@FontSizeActivity, preset.key)
                // 以新档位重建本页：页面自身尺寸与顶部预览一并刷新
                recreate()
            }
        })

        showPreset(currentIndex)
    }

    /** 同步档位文字、倍率、顶部预览与刻度高亮 */
    private fun showPreset(index: Int) {
        val preset = UiScaleManager.PRESETS[index]
        binding.tvScaleValue.text = preset.label
        binding.tvScaleFactor.text = "${preset.scale}×"
        highlightTick(index)
        buildSample(preset.scale)
    }

    /** 刻度名称：首尾贴边与滑块两端对齐，中间两档居中 */
    private fun buildScaleTicks() {
        val presets = UiScaleManager.PRESETS
        tickViews.clear()
        binding.llScaleLabels.removeAllViews()

        presets.forEachIndexed { index, preset ->
            val tick = TextView(this).apply {
                gravity = when (index) {
                    0 -> Gravity.START
                    presets.lastIndex -> Gravity.END
                    else -> Gravity.CENTER
                }
                setTextSizeRes(R.dimen.text_caption)
                text = preset.label
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            tickViews += tick
            binding.llScaleLabels.addView(tick)
        }
    }

    private fun highlightTick(index: Int) {
        val primary = resolveColor(com.google.android.material.R.attr.colorPrimary)
        val muted = resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        tickViews.forEachIndexed { i, tick ->
            val selected = i == index
            tick.setTextColor(if (selected) primary else muted)
            tick.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * 在预览区里画一张迷你课程卡片。
     *
     * [factor] 是档位的**绝对**系数：样例的文字与内边距按「系统基准密度 × factor」换算，
     * 而不是在当前页面密度上再乘一次。否则本页本身处于大档位时样例会被重复放大
     * （如 1.3 × 1.3 = 1.69），既失真又会溢出预览区 —— 所见即所得就没了。
     */
    private fun buildSample(factor: Float) {
        val container = binding.previewContainer
        container.removeAllViews()
        val systemDensity = Resources.getSystem().displayMetrics.density
        val fontScale = resources.configuration.fontScale
        fun dp(value: Float) = value * factor * systemDensity
        fun textPx(dimenRes: Int) = dimenSp(dimenRes) * factor * systemDensity * fontScale

        val onContainer = resolveColor(
            com.google.android.material.R.attr.colorOnPrimaryContainer
        )

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            setPadding(dp(12f).toInt(), dp(9f).toInt(), dp(12f).toInt(), dp(9f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(12f)
                setColor(
                    resolveColor(com.google.android.material.R.attr.colorPrimaryContainer)
                )
            }
        }

        block.addView(TextView(this).apply {
            text = "高等数学"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(R.dimen.text_subtitle))
            setTypeface(null, Typeface.BOLD)
            setTextColor(onContainer)
        })
        block.addView(TextView(this).apply {
            text = "张三 · 教学楼A101"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(R.dimen.text_body_small))
            setTextColor(onContainer)
        })
        block.addView(TextView(this).apply {
            text = "第1-16周"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(R.dimen.text_label))
            setTextColor(onContainer)
        })

        container.addView(block)
    }

    /** 取回 dimens 里写的 sp 数值（与当前页面密度无关，供绝对尺寸换算用） */
    private fun dimenSp(dimenRes: Int): Float {
        val value = TypedValue()
        resources.getValue(dimenRes, value, true)
        return TypedValue.complexToFloat(value.data)
    }

    private fun resolveColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
