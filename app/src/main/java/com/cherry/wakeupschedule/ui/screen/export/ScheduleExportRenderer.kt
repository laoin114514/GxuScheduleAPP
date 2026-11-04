package com.cherry.wakeupschedule.ui.screen.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.View.MeasureSpec
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.adapter.WeekPagerAdapter
import com.cherry.wakeupschedule.ui.theme.PageBackground
import java.util.Calendar

/**
 * 课表导出渲染器：把**周课表页面本身**离屏排版后直接绘制成 Bitmap。
 *
 * 设计要点（与「重新画一张课表」的做法区分开）：
 * - 结构、样式、数据全部复用屏幕上的那一套：[WeekPagerAdapter] 的页面构建 + 绑定逻辑，
 *   导出图里没有任何一处是二次实现的手绘（时间轴、日期栏、课程卡片、调课角标、+N 重叠角标、
 *   边框颜色/透明度/深浅色都跟着当前设置走）。
 * - 页面在屏幕内是「固定高度 + 内部滚动」，导出时通过
 *   [WeekPagerAdapter.createExportPage] 换成「宽度不变、高度按内容展开」，
 *   再手动 measure/layout/draw 到一张整页大小的 Bitmap 上，所以图片能看到一周的全部节次，
 *   而不是只截到当前屏幕可见的部分。
 * - 背景沿用页面渐变（[PageBackground]），与 App 内课表页同一组颜色。
 */
object ScheduleExportRenderer {

    /** 导出图宽度上限保护：极端宽屏（平板/折叠屏展开）下也限制在合理体积 */
    private const val MAX_EXPORT_WIDTH_PX = 2160

    /**
     * 渲染第 [week] 周的整页课表。
     *
     * @param week 要导出的周次（会被收敛到 1..总周数）
     * @param currentWeek 当前周（决定日期栏「今天」高亮与「高亮当日」列；传 0 时按学期开始日期推算）
     * @return 一张宽 = 屏幕宽度、高 = 日期栏 + 全部节次的 PNG 可用 Bitmap（ARGB_8888）
     */
    fun renderWeek(context: Context, week: Int, currentWeek: Int): Bitmap {
        val settings = SettingsManager(context)
        val totalWeeks = settings.getTotalWeeks().coerceAtLeast(1)
        val safeWeek = week.coerceIn(1, totalWeeks)
        val safeCurrentWeek = (if (currentWeek > 0) currentWeek else calculateCurrentWeek(context, settings))
            .coerceIn(1, totalWeeks)

        // 与 ScheduleFragment.setupViewPager 同样的外观参数来源；数据用当前学期的课程缓存
        val adapter = WeekPagerAdapter(totalWeeks, settings.getCourseCellHeight()).apply {
            setContentVisibility(settings.isShowClassroom(), settings.isShowTeacher())
            setBorderColor(settings.getCellBorderColor())
            setBorderFollowCourse(settings.isBorderFollowCourse())
            setHighlightToday(settings.isHighlightToday())
            updateData(CourseDataManager.getInstance(context).getAllCourses())
            setWeekContext(settings.getSemesterStartDate(), safeCurrentWeek)
        }

        val page: View = adapter.createExportPage(context, safeWeek)
        val width = context.resources.displayMetrics.widthPixels.coerceAtMost(MAX_EXPORT_WIDTH_PX)

        // 高度按内容自适应：先以 UNSPECIFIED 量出整页高度，再按该高度正式布局。
        // 只有一次测量就够（内容区已由 prepareForExport 固定为「节次数 × 格子高度」），
        // 这里补第二次 EXACTLY 测量是为了让 MATCH_PARENT 的子 View 拿到最终尺寸。
        page.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val height = page.measuredHeight.coerceAtLeast(1)
        page.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        page.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 页面渐变底：与课表页一致（根布局背景透明，渐变由页面容器提供）
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(PageBackground.topColor(context), PageBackground.bottomColor(context))
        ).apply {
            setBounds(0, 0, width, height)
            draw(canvas)
        }

        page.draw(canvas)
        return bitmap
    }

    /**
     * 与 [com.cherry.wakeupschedule.viewmodel.CourseViewModel] 的当前周算法保持一致：
     * 学期未设置开始日期时回落到「默认周」，否则按 (今天 - 开学日) / 7 + 1 计算。
     * 导出入口在工具页，拿不到课表页缓存的 currentWeek 时用它兜底。
     */
    fun calculateCurrentWeek(context: Context, settings: SettingsManager): Int {
        val totalWeeks = settings.getTotalWeeks().coerceAtLeast(1)
        val startDate = settings.getSemesterStartDate()
        if (startDate <= 0L) return settings.getDefaultWeek().coerceIn(1, totalWeeks)
        val diffMillis = System.currentTimeMillis() - startDate
        val diffDays = (diffMillis / (24 * 60 * 60 * 1000L)).toInt()
        val week = (diffDays / 7) + 1
        // 学期开始日期可能被设置到未来（diffDays 为负），同样收敛到 1..总周数
        return week.coerceIn(1, totalWeeks)
    }

    /** 导出文件名：课表_第X周_yyyyMMdd_HHmmss.png（相册里可读、同秒重复导出不覆盖） */
    fun buildFileName(week: Int, timeMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val stamp = String.format(
            java.util.Locale.US, "%04d%02d%02d_%02d%02d%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
        return "课表_第${week}周_$stamp.png"
    }
}
