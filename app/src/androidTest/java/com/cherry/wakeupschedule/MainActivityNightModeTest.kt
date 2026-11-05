package com.cherry.wakeupschedule

import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 深色模式启动冒烟测试（防回归：API <= 29 的「颜色状态列表当 Drawable」闪退）。
 *
 * ## 背景（2026-11-05 真实事故）
 * 官方 v0.5.9 在 Android 8.0 ~ 10（API 26~29）+ 深色模式下 **启动必崩**：
 * `activity_main.xml` 的 `PillBottomNavView` 写了 `android:background="@color/bottom_nav_bg"`，
 * 该资源是颜色状态列表（`<selector><item android:color>`）；API <= 29 的 `ResourcesImpl`
 * 用**字面前缀 `res/color/`** 判断颜色资源，夜间变体编译路径是 `res/color-night-v8/`，
 * 不匹配 → 被当普通 Drawable XML → `StateListDrawable` 要求 `<item android:drawable>` →
 * `Resources$NotFoundException`，在 Activity inflate 阶段直接崩掉进程。
 * API 30+ 改为按资源类型判断，所以 Android 15 开发机/模拟器完全复现不出来。
 *
 * 本测试强制夜间模式后真正拉起 [MainActivity]（会 inflate `activity_main` 并加载
 * 起始页 ScheduleFragment 的 `fragment_schedule`），任何资源/布局在深色配置下的加载异常
 * 都会让测试失败。CI 在 API 35 + API 29 两台模拟器上都会执行（android-ci.yml 的 androidTest job）。
 *
 * 维护约定：以后新增「随深色模式变化的资源」时，至少保证本测试在 API 29 上通过；
 * 布局 Drawable 属性禁止引用 res/color 与 color-night 等目录下的状态列表（见 ColorStateListAsDrawableTest）。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNightModeTest {

    @After
    fun restoreDefaultNightMode() {
        // 不把夜间模式留给后续测试类
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun mainActivity_launchesInNightMode_withoutInflateCrash() {
        // 必须在 Activity 创建之前设置：AppCompatDelegate 会应用到之后创建的 Activity/Context
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("MainActivity 在夜间模式下未完成创建", !activity.isFinishing)
            }
        }
    }
}
