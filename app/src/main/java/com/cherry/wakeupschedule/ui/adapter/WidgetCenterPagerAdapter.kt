package com.cherry.wakeupschedule.ui.adapter

import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.databinding.PageWidgetCenterIntroBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterMinimalBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterNextBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterTodayBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterTroubleshootBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterWeekBinding
import com.cherry.wakeupschedule.databinding.WidgetCenterAddFooterBinding
import com.cherry.wakeupschedule.ui.widget.VerticalScrollView
import com.cherry.wakeupschedule.widget.MinimalWidgetProvider
import com.cherry.wakeupschedule.widget.NextCourseWidgetProvider
import com.cherry.wakeupschedule.widget.ScheduleWidgetProvider
import com.cherry.wakeupschedule.widget.WeekViewWidgetProvider

/**
 * 小组件中心里的一页小组件。
 *
 * [pickerLabel] 与 AndroidManifest 中 receiver 的 label 一致（系统小组件列表里显示的名字），
 * 手动添加引导按它指路；[title] 是页内与 chip 上更完整的名字。
 */
data class WidgetPage(
    val title: String,
    val pickerLabel: String,
    val providerClass: Class<*>,
    @LayoutRes val previewLayoutRes: Int
)

/**
 * 小组件中心的分页适配器：使用说明 → 4 个小组件 → 找不到小组件排查，共 6 页。
 *
 * 每页一个独立布局，viewType 直接取页序 —— 不同页永不共用 ViewHolder，
 * 从机制上排除预览图与状态错位。
 */
class WidgetCenterPagerAdapter(
    private val onAddClick: (WidgetPage) -> Unit
) : RecyclerView.Adapter<WidgetCenterPagerAdapter.PageHolder>() {

    /** 4 个小组件页，顺序即页面顺序 */
    val widgetPages: List<WidgetPage> = listOf(
        WidgetPage(
            "下课倒计时", "下课倒计时",
            MinimalWidgetProvider::class.java, R.layout.widget_minimal_preview
        ),
        WidgetPage(
            "今日课程概览", "今日课程",
            ScheduleWidgetProvider::class.java, R.layout.widget_today_preview
        ),
        WidgetPage(
            "一周课程", "一周课程",
            WeekViewWidgetProvider::class.java, R.layout.widget_week_view_preview
        ),
        WidgetPage(
            "下一门课提醒", "下一门课",
            NextCourseWidgetProvider::class.java, R.layout.widget_next_course_preview
        ),
    )

    /** Provider → 桌面上已添加的个数 */
    private var addedCounts: Map<Class<*>, Int> = emptyMap()

    /** 各页离屏前的纵向滚动位置，回到该页时恢复 */
    private val scrollPositions = SparseIntArray()

    /** 刷新 4 个小组件页的「已添加 N 个 / 未添加」 */
    fun submitAddedCounts(counts: Map<Class<*>, Int>) {
        addedCounts = counts
        notifyItemRangeChanged(WIDGET_PAGE_START, widgetPages.size)
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            PAGE_INTRO ->
                PageHolder(PageWidgetCenterIntroBinding.inflate(inflater, parent, false))

            PAGE_MINIMAL -> PageWidgetCenterMinimalBinding.inflate(inflater, parent, false)
                .let { PageHolder(it, it.footerAdd) }

            PAGE_TODAY -> PageWidgetCenterTodayBinding.inflate(inflater, parent, false)
                .let { PageHolder(it, it.footerAdd) }

            PAGE_WEEK -> PageWidgetCenterWeekBinding.inflate(inflater, parent, false)
                .let { PageHolder(it, it.footerAdd) }

            PAGE_NEXT -> PageWidgetCenterNextBinding.inflate(inflater, parent, false)
                .let { PageHolder(it, it.footerAdd) }

            else ->
                PageHolder(PageWidgetCenterTroubleshootBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.pageIndex = position

        holder.footer?.let { footer ->
            val page = widgetPages[position - WIDGET_PAGE_START]
            val count = addedCounts[page.providerClass] ?: 0
            footer.tvWidgetStatus.text = if (count > 0) "已添加 $count 个" else "未添加"
            footer.btnWidgetAdd.setOnClickListener { onAddClick(page) }
        }

        val savedScroll = scrollPositions[position]
        if (savedScroll != 0) {
            holder.scroll.post {
                // 异步执行期间 holder 可能已被复用给别的页，位置对上才恢复
                if (holder.pageIndex == position) holder.scroll.scrollTo(0, savedScroll)
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: PageHolder) {
        if (holder.pageIndex != RecyclerView.NO_POSITION) {
            scrollPositions.put(holder.pageIndex, holder.scroll.scrollY)
        }
    }

    /** 页面骨架 + 「已添加状态 / 一键添加」出口，两者都是 ViewBinding，无 findViewById */
    class PageHolder(
        val binding: ViewBinding,
        val footer: WidgetCenterAddFooterBinding? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 页内竖滚容器（6 个页面布局的根都是它） */
        val scroll: VerticalScrollView = binding.root as VerticalScrollView

        /** 当前绑定的页序，离屏时用来记录滚动位置 */
        var pageIndex = RecyclerView.NO_POSITION
    }

    companion object {
        /** 第 1 页：使用说明 */
        const val PAGE_INTRO = 0

        /** 4 个小组件页的起始页序 */
        const val WIDGET_PAGE_START = 1

        /** 总页数：使用说明 + 4 个小组件 + 排查 */
        const val PAGE_COUNT = 6

        private const val PAGE_MINIMAL = 1
        private const val PAGE_TODAY = 2
        private const val PAGE_WEEK = 3
        private const val PAGE_NEXT = 4
        private const val PAGE_TROUBLESHOOT = 5
    }
}
