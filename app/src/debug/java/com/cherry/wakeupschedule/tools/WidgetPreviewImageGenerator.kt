package com.cherry.wakeupschedule.tools

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import com.cherry.wakeupschedule.R
import java.io.File
import java.io.FileOutputStream

/**
 * 调试专用工具（**只编入 debug 变体，release APK 不含**）。
 *
 * 作用：把「小组件中心」在用的那套静态假数据预览布局（`widget_*_preview.xml`）
 * 离屏渲染成**无损 WebP**，作为 AppWidgetProviderInfo 的 `android:previewImage` 素材。
 *
 * 为什么需要它：`android:previewLayout` 只在**支持该特性的桌面宿主**上生效
 * （Android 12+ 的 Pixel Launcher 等）；不支持的宿主（Android 11 及以下、
 * 以及 MIUI / HyperOS / ColorOS / OriginOS 等大量 OEM 桌面）会回落到
 * `android:previewImage`——该属性缺省时系统直接拿**应用图标**当预览图。
 * 所以 `previewImage` 是必需的兜底，而它只能是一张静态位图资源。
 *
 * 为什么用 WebP 而不是 PNG：仓库 .gitignore 有一条 `*.png`（本意是别提交调试截图），
 * 新增 PNG 会被静默忽略、根本进不了版本库；WebP 不受影响且体积小得多。
 *
 * 用法（素材随布局变更需要重新生成）：
 * ```
 * ./gradlew :app:assembleDebug
 * adb install -r app/build/outputs/apk/debug/GxuScheduleAPP-debug-<...>.apk
 * adb shell am start -n com.cherry.wakeupschedule/.tools.WidgetPreviewImageGenerator
 * # 产物在 App 内部存储 files/widget_previews/{light,night}/
 * adb shell "run-as com.cherry.wakeupschedule ls files/widget_previews/light"
 * ```
 * 再把 light/ 下的文件放进 `app/src/main/res/drawable-nodpi/`、
 * night/ 下的放进 `app/src/main/res/drawable-night-nodpi/`。
 */
class WidgetPreviewImageGenerator : Activity() {

    /** 一个待渲染的预览：输出文件名 + 预览布局 + 该小组件的规格尺寸（dp，取自对应的 widget_*_info.xml） */
    private data class Spec(
        val name: String,
        val layoutRes: Int,
        val widthDp: Int,
        val heightDp: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = File(filesDir, "widget_previews")
        val lightDir = File(root, "light").apply { mkdirs() }
        val nightDir = File(root, "night").apply { mkdirs() }

        var ok = 0
        for (spec in SPECS) {
            try {
                writeImage(render(spec, night = false), File(lightDir, spec.name))
                writeImage(render(spec, night = true), File(nightDir, spec.name))
                ok++
                Log.i(TAG, "rendered ${spec.name} (${spec.widthDp}x${spec.heightDp}dp) as .webp")
            } catch (t: Throwable) {
                Log.e(TAG, "failed to render ${spec.name}", t)
            }
        }

        Log.i(TAG, "done: $ok/${SPECS.size} specs -> ${root.absolutePath}")
        verifyProviderMetadata()
        finish()
    }

    /**
     * 顺带校验元数据：让系统自己解析一遍我们 6 个 provider 的 appwidget-provider，
     * 确认 `previewImage` / `previewLayout` 都拿到了有效资源 id（0 就是没配）。
     * 这是"桌面图标当预览图"那个 bug 的回归检查点。
     */
    private fun verifyProviderMetadata() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "metadata check skipped: previewLayout needs API 31+")
            return
        }
        val installed = AppWidgetManager.getInstance(this).installedProviders
        for (info in installed) {
            if (info.provider.packageName != packageName) continue
            val ok = info.previewImage != 0 && info.previewLayout != 0 && info.descriptionRes != 0
            Log.i(
                TAG,
                "provider ${info.provider.className} previewImage=0x${info.previewImage.toString(16)} " +
                    "previewLayout=0x${info.previewLayout.toString(16)} " +
                    "description=0x${info.descriptionRes.toString(16)} -> ${if (ok) "OK" else "MISSING!"}",
            )
        }
    }

    /**
     * 以「同样的 dp 尺寸、双倍像素密度」渲染，既保持与真身一致的排版比例，
     * 又能让桌面宿主任意缩放而不糊。
     */
    private fun render(spec: Spec, night: Boolean): Bitmap {
        val cfg = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            densityDpi = resources.displayMetrics.densityDpi * DENSITY_SCALE
        }
        val ctx = createConfigurationContext(cfg)
        val density = ctx.resources.displayMetrics.density
        val width = Math.round(spec.widthDp * density)
        val height = Math.round(spec.heightDp * density)

        val view = LayoutInflater.from(ctx).inflate(spec.layoutRes, null, false)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * 输出 WebP：仓库 .gitignore 有一条 `*.png`（本意是别提交调试截图），
     * 新增 PNG 会被静默忽略；WebP 不受影响，体积也只有 PNG 的零头。
     * 预览图最终只以 170~250dp 的缩略图出现在桌面选择器里，q92 的压缩痕迹不可见。
     */
    private fun writeImage(bitmap: Bitmap, outWithoutSuffix: File) {
        FileOutputStream(File(outWithoutSuffix.parentFile, "${outWithoutSuffix.name}.webp")).use {
            bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, it)
        }
        bitmap.recycle()
    }

    companion object {
        private const val TAG = "WidgetPreviewGen"
        private const val DENSITY_SCALE = 2
        private const val WEBP_QUALITY = 92

        /**
         * 尺寸取「小组件中心」里预览卡片的实际渲染尺寸（page_widget_center_*.xml 中
         * 承载 widget_*_preview 的 FrameLayout）——那是团队已经验收过的观感，
         * 也是这些静态预览布局真正被设计来适配的大小。
         *
         * 不能用 widget_*_info.xml 的 minWidth/minHeight：那只是"最小可用尺寸"
         * （2 格 = 110dp），真机上桌面会给到 ~170dp，按 110dp 渲染会内容溢出。
         */
        private val SPECS = listOf(
            // 今日课程 2×2 —— 小组件中心是 172dp × 172dp，但三行课表在 172dp 高度下会截掉最后一行；
            // 静态预览图不像真身那样能滚动，所以竖向多给 24dp 让三行完整可见
            Spec("widget_preview_today", R.layout.widget_today_preview, 172, 196),
            // 今日课程 4×2 —— 小组件中心 满宽 × 172dp
            Spec("widget_preview_today_wide", R.layout.widget_today_wide_preview, 335, 172),
            // 下课倒计时 2×2 —— 小组件中心 172dp × 172dp
            Spec("widget_preview_minimal", R.layout.widget_minimal_preview, 172, 172),
            // 近日课程 4×2 —— 小组件中心 满宽 × 172dp
            Spec("widget_preview_upcoming", R.layout.widget_upcoming_days_preview, 335, 172),
            // 一周课程 4×4 —— 小组件中心 满宽 × 344dp
            Spec("widget_preview_week", R.layout.widget_week_view_preview, 335, 344),
            // 下一门课 4×2 —— 小组件中心 满宽 × 172dp
            Spec("widget_preview_next", R.layout.widget_next_course_preview, 335, 172),
        )
    }
}
