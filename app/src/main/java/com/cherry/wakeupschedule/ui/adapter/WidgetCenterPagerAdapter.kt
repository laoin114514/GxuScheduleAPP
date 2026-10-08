package com.cherry.wakeupschedule.ui.adapter

import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.databinding.PageWidgetCenterMinimalBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterNextBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterTodayBinding
import com.cherry.wakeupschedule.databinding.PageWidgetCenterWeekBinding
import com.cherry.wakeupschedule.databinding.WidgetCenterAddFooterBinding
import com.cherry.wakeupschedule.ui.widget.VerticalScrollView
import com.cherry.wakeupschedule.widget.MinimalWidgetProvider
import com.cherry.wakeupschedule.widget.NextCourseWidgetProvider
import com.cherry.wakeupschedule.widget.ScheduleWideWidgetProvider
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
    @LayoutRes val previewLayoutRes: Int,
    /** 尺寸徽标（如 "4×2"），拼进「已添加到桌面」卡的展示名 */
    val sizeLabel: String = "",
    /** 同页附带的次变体（如今日课程概览页同时提供 4×2 主推与 2×2 紧凑版两个添加入口） */
    val smallVariant: WidgetPage? = null
) {
    /** 出提示卡用的展示名：如「今日课程（4×2）」 */
    val displayName: String
        get() = if (sizeLabel.isBlank()) pickerLabel else "$pickerLabel（$sizeLabel）"
}

/**
 * 小组件中心的分页适配器：4 个小组件各占一页，进入即落在第 1 页「下课倒计时」。
 *
 * 使用说明与「找不到小组件」的排查已收进页面右下角「?」的弹窗（见 WidgetCenterActivity）。
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
            MinimalWidgetProvider::class.java, R.layout.widget_minimal_preview,
            sizeLabel = "2×2"
        ),
        WidgetPage(
            "今日课程概览", "今日课程",
            ScheduleWideWidgetProvider::class.java, R.layout.widget_today_wide_preview,
            sizeLabel = "4×2",
            smallVariant = WidgetPage(
                "今日课程概览", "今日课程",
                ScheduleWidgetProvider::class.java, R.layout.widget_today_preview,
                sizeLabel = "2×2"
            )
        ),
        WidgetPage(
            "一周课程", "一周课程",
            WeekViewWidgetProvider::class.java, R.layout.widget_week_view_preview,
            sizeLabel = "4×4"
        ),
        WidgetPage(
            "下一门课提醒", "下一门课",
            NextCourseWidgetProvider::class.java, R.layout.widget_next_course_preview,
            sizeLabel = "4×2"
        ),
    )

    /** Provider → 桌面上已添加的个数 */
    private var addedCounts: Map<Class<*>, Int> = emptyMap()

    /** 正在等待落地结果的一键添加：该行显示「正在添加到桌面…」并禁用按钮 */
    private var pendingProvider: Class<*>? = null

    /** 各页离屏前的纵向滚动位置，回到该页时恢复 */
    private val scrollPositions = SparseIntArray()

    /**
     * 刷新各页的「已添加 N 个 / 未添加」。
     * [pendingProvider] 为正在请求添加的组件 —— 部分 ROM 的桌面既不弹确认框也不回调，
     * 状态行是用户按下按钮后唯一的即时反馈，核验出结果后由调用方传 null 收起。
     */
    fun submitAddedCounts(counts: Map<Class<*>, Int>, pendingProvider: Class<*>? = null) {
        addedCounts = counts
        this.pendingProvider = pendingProvider
        notifyItemRangeChanged(0, widgetPages.size)
    }

    override fun getItemCount(): Int = widgetPages.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            PAGE_MINIMAL -> PageWidgetCenterMinimalBinding.inflate(inflater, parent, false)
                .let { PageHolder(it, listOf(it.footerAdd to widgetPages[PAGE_MINIMAL])) }

            PAGE_TODAY -> PageWidgetCenterTodayBinding.inflate(inflater, parent, false)
                .let { binding ->
                    PageHolder(
                        binding,
                        listOf(
                            binding.footerAdd to widgetPages[PAGE_TODAY],
                            binding.footerAddSmall to requireNotNull(
                                widgetPages[PAGE_TODAY].smallVariant
                            ) { "今日课程页缺少 2×2 次变体定义" }
                        )
                    )
                }

            PAGE_WEEK -> PageWidgetCenterWeekBinding.inflate(inflater, parent, false)
                .let { PageHolder(it, listOf(it.footerAdd to widgetPages[PAGE_WEEK])) }

            PAGE_NEXT -> PageWidgetCenterNextBinding.inflate(inflater, parent, false)
                .let { PageHolder(it, listOf(it.footerAdd to widgetPages[PAGE_NEXT])) }

            // 页序即 viewType，与 widgetPages 一一对应；走到这里说明加了页却漏了布局
            else -> throw IllegalArgumentException("未知的小组件页序：$viewType")
        }
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.pageIndex = position

        // 今日课程页有两个变体出口（4×2 主推 + 2×2 紧凑版），其余页只有一个
        holder.footers.forEach { (footer, targetPage) ->
            val count = addedCounts[targetPage.providerClass] ?: 0
            val pending = targetPage.providerClass == pendingProvider
            footer.tvWidgetStatus.text = when {
                pending -> "正在添加到桌面…"
                count > 0 -> "已添加 $count 个"
                else -> "未添加"
            }
            footer.btnWidgetAdd.isEnabled = !pending
            footer.btnWidgetAdd.setOnClickListener { onAddClick(targetPage) }
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

    /** 页面骨架 + 「已添加状态 / 一键添加」出口，两者都是 ViewBinding，无 findViewById；
     *  footers 为「footer ↔ 点击后要添加的变体」配对（今日课程页有两个） */
    class PageHolder(
        val binding: ViewBinding,
        val footers: List<Pair<WidgetCenterAddFooterBinding, WidgetPage>>
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 页内竖滚容器（4 个页面布局的根都是它） */
        val scroll: VerticalScrollView = binding.root as VerticalScrollView

        /** 当前绑定的页序，离屏时用来记录滚动位置 */
        var pageIndex = RecyclerView.NO_POSITION
    }

    companion object {
        private const val PAGE_MINIMAL = 0
        private const val PAGE_TODAY = 1
        private const val PAGE_WEEK = 2
        private const val PAGE_NEXT = 3
    }
}
