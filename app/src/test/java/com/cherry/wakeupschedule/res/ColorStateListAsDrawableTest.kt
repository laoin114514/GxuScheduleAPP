package com.cherry.wakeupschedule.res

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防回归：禁止在布局 XML 里把「颜色状态列表」（res/color/ 与 res/color-night/ 等 color 系列目录下的 <name>.xml）当作 Drawable 属性使用。
 *
 * ## 背景（真实事故，2026-11-05 定位 + 修复）
 *
 * Android API <= 29（Android 8.0 ~ 10）的 `ResourcesImpl.loadDrawableForCookie` 用**字面前缀**
 * 判断颜色资源：
 * ```
 * if (file.endsWith(".xml")) {
 *     if (file.startsWith("res/color/")) {
 *         dr = loadColorOrXmlDrawable(...)   // 状态色列表 → ColorStateListDrawable，正常
 *     } else {
 *         dr = loadXmlDrawable(...)          // 普通 Drawable XML 解析
 *     }
 * }
 * ```
 * 于是夜间/其他限定符变体的路径 `res/color-night-v8/xxx.xml` 不匹配 `res/color/` 前缀，
 * 会被 `StateListDrawable` 解析，而 `<item android:color="...">` 在 Drawable 语境下非法 →
 * `Resources$NotFoundException: <item> tag requires a 'drawable' attribute ...`，
 * 在 `View.<init>` 读 `android:background` 时直接崩掉整个 Activity（inflate 阶段无法捕获恢复）。
 *
 * 实测：官方 v0.5.9 在 Android 10、系统深色模式下**启动必崩**
 * （`res/color-night-v8/bottom_nav_bg.xml`）。而开发机/模拟器是 Android 15（该检查已改为按资源
 * 类型判断），所以一直复现不了。该问题从 v0.3.17 引入 `@color/bottom_nav_bg` 作为布局背景起就存在。
 *
 * ## 规则
 *
 * 布局里 Drawable 类属性（`android:background` / `src` / `foreground` / `drawableXxx`）
 * 不允许引用 `@color/<name>`，只要 color 系列目录下存在 <name>.xml 即判失败。
 * 需要「随深浅色变化的纯色背景」时：
 * - 在代码里用 `ColorDrawable(颜色)` 设置（本项目 `PageBackground.bottomColor` 即此做法）；或
 * - 使用真正的 res/drawable/ 或 res/drawable-night/ 下的形状/选择器 Drawable。
 *
 * 纯色值引用（`values/colors.xml` 里的 `<color>`）不受影响。
 */
class ColorStateListAsDrawableTest {

    /** Drawable 语义的属性白名单（这些属性按 Drawable 加载，不能指向状态色列表 XML）。 */
    private val drawableAttributes = listOf(
        "background", "src", "foreground",
        "drawableLeft", "drawableStart", "drawableTop", "drawableBottom",
        "drawableEnd", "drawableRight",
    )

    @Test
    fun `layouts must not reference color state list xml as drawable`() {
        val resDir = locateResDir()
        // 当前仓库没有 color 状态列表资源；collect 仍按目录动态收集，
        // 将来一新增 color*/X.xml，引用它的布局就会被这里拦下。
        val colorStateListNames = collectColorStateListNames(resDir)

        val layoutDir = File(resDir, "layout")
        assertTrue("找不到 $layoutDir", layoutDir.isDirectory)

        val pattern = Regex(
            "android:(${drawableAttributes.joinToString("|")})=\"@color/([A-Za-z0-9_]+)\""
        )
        val offenders = mutableListOf<String>()

        layoutDir.listFiles { file -> file.isFile && file.extension == "xml" }
            ?.sortedBy { it.name }
            ?.forEach { layout ->
                pattern.findAll(layout.readText()).forEach { match ->
                    val attribute = match.groupValues[1]
                    val name = match.groupValues[2]
                    if (name in colorStateListNames) {
                        offenders += "${layout.name}: android:$attribute=\"@color/$name\"" +
                            "（存在 res/color*/$name.xml，在 API<=29 深色模式会 inflate 崩溃）"
                    }
                }
            }

        assertTrue(
            buildString {
                appendLine("布局把颜色状态列表当作 Drawable 用了，这会在 Android 8~10 的深色模式下")
                appendLine("抛 Resources\$NotFoundException（res/color-night-*/ 路径不匹配 API<=29 的")
                appendLine("\"res/color/\" 前缀判断）。请改用代码设置 ColorDrawable 或真正的 drawable 资源：")
                offenders.forEach { appendLine("  - $it") }
            },
            offenders.isEmpty(),
        )
    }

    /** 收集 `res/color/`、`res/color-night/`、`res/color-v23/` 等所有颜色状态列表文件名。 */
    private fun collectColorStateListNames(resDir: File): Set<String> {
        val names = mutableSetOf<String>()
        resDir.listFiles { file -> file.isDirectory && file.name.startsWith("color") }
            ?.forEach { dir ->
                dir.listFiles { file -> file.isFile && file.extension == "xml" }
                    ?.forEach { names += it.nameWithoutExtension }
            }
        return names
    }

    /** 定位 `app/src/main/res`（单测工作目录可能是模块目录也可能是项目根，两种都兼容）。 */
    private fun locateResDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val current = dir ?: return@repeat
            File(current, "app/src/main/res")
                .takeIf { it.isDirectory }?.let { return it }
            File(current, "src/main/res")
                .takeIf { it.isDirectory }?.let { return it }
            dir = current.parentFile
        }
        return File("app/src/main/res")
    }
}
