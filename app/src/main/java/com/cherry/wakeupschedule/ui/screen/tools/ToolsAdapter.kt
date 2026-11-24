package com.cherry.wakeupschedule.ui.screen.tools

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cherry.wakeupschedule.ui.feedback.AppToast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.ui.motion.Motion
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes

/**
 * 工具页列表适配器：推荐大卡 + 分组标题 + 4 列图标网格 / 双列大卡。
 * 行视图全部代码构建（与 WeekPagerAdapter 同一风格），颜色走主题属性。
 */
class ToolsAdapter : RecyclerView.Adapter<ToolsAdapter.RowViewHolder>() {

    private var rows: List<ToolsRow> = emptyList()

    fun render(rows: List<ToolsRow>) {
        this.rows = rows
        notifyDataSetChanged()
    }

    /**
     * 首次进入工具页时的卡片入场：卡片行按 34ms 步进上浮淡入（封顶 10 项），
     * 卡内图标随后轻微 pop；分组标题行不参与入场。
     *
     * 由页面在数据落位、RecyclerView 完成布局后调用；同一屏只播一次，
     * 滚动复用/返回不会重播（onBind 里会复位 transform 兜底）。
     */
    fun playEntrance(recyclerView: RecyclerView) {
        if (!Motion.enabled()) return
        recyclerView.post {
            if (!recyclerView.isAttachedToWindow) return@post
            var cardIndex = 0
            for (index in 0 until recyclerView.childCount) {
                val rowView = recyclerView.getChildAt(index) ?: continue
                val row = rows.getOrNull(recyclerView.getChildAdapterPosition(rowView)) ?: continue
                // 只对卡片 item（推荐大卡 / 图标网格卡 / 双列大卡组）做入场，分组标题保持静态
                if (row is ToolsRow.SectionTitle) continue
                val delay = cardIndex.coerceAtMost(Motion.STAGGER_MAX_ITEMS) * Motion.STAGGER_STEP
                cardIndex++
                Motion.enter(
                    rowView,
                    delay = delay,
                    duration = Motion.ENTRANCE,
                    fromY = 10f
                )
                // 图标随卡片轻微 pop：只动缩放，保留「占位项」原本的低透明度
                iconViews(rowView).forEachIndexed { iconIndex, icon ->
                    popIcon(icon, delay + 60L + iconIndex * 18L)
                }
            }
        }
    }

    /** 递归收集卡片里的图标（图标格 icon / 大卡圆底 icon / 推荐卡箭头） */
    private fun iconViews(view: View): List<View> {
        val result = mutableListOf<View>()
        fun collect(current: View) {
            if (current is ImageView) {
                result += current
                return
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    current.getChildAt(index)?.let(::collect)
                }
            }
        }
        collect(view)
        return result
    }

    private fun popIcon(icon: View, delay: Long) {
        if (!Motion.enabled()) return
        icon.animate().cancel()
        icon.scaleX = ICON_POP_FROM_SCALE
        icon.scaleY = ICON_POP_FROM_SCALE
        icon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(Motion.ENTRANCE)
            .setInterpolator(Motion.spring)
            .start()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ToolsRow.Featured -> TYPE_FEATURED
        is ToolsRow.SectionTitle -> TYPE_TITLE
        is ToolsRow.IconGrid -> TYPE_ICON_GRID
        is ToolsRow.CardGrid -> TYPE_CARD_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val ctx = parent.context
        return when (viewType) {
            TYPE_FEATURED -> RowViewHolder(buildHero(ctx), TYPE_FEATURED)
            TYPE_TITLE -> RowViewHolder(buildSectionTitle(ctx), TYPE_TITLE)
            TYPE_ICON_GRID -> RowViewHolder(buildGridCard(ctx), TYPE_ICON_GRID)
            else -> RowViewHolder(buildCardGrid(ctx), TYPE_CARD_GRID)
        }
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        // 列表复用兜底：入场动画被复用打断时，复位 transform，避免行视图带着半截动画出现
        Motion.resetScale(holder.itemView)
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
        when (val row = rows[position]) {
            is ToolsRow.Featured -> bindHero(holder.itemView as FrameLayout, row.item)
            is ToolsRow.SectionTitle -> (holder.itemView as TextView).text = row.title
            is ToolsRow.IconGrid -> bindIconGrid(holder.itemView as ViewGroup, row)
            is ToolsRow.CardGrid -> bindCardGrid(holder.itemView as ViewGroup, row.items)
        }
    }

    // ── 行容器构建 ──────────────────────────────────────────────

    private fun buildHero(ctx: Context): FrameLayout {
        return FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 150)
            ).apply {
                setMargins(dp(ctx, 20), dp(ctx, 6), dp(ctx, 20), dp(ctx, 4))
            }
            clipToOutline = true
        }
    }

    private fun buildSectionTitle(ctx: Context): TextView {
        return TextView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(ctx, 24), dp(ctx, 24), dp(ctx, 20), dp(ctx, 10)) }
            setTextSizeRes(R.dimen.text_title)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(attr(ctx, com.google.android.material.R.attr.colorOnSurface))
        }
    }

    private fun buildGridCard(ctx: Context): com.google.android.material.card.MaterialCardView {
        return com.google.android.material.card.MaterialCardView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
            radius = dp(ctx, 24).toFloat()
            cardElevation = dp(ctx, 1).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(attr(ctx, com.google.android.material.R.attr.colorSurfaceContainerLowest))
            // 内容在 bind 时按条目数重建
        }
    }

    /**
     * 双列卡容器。
     *
     * 原来用 android.widget.GridLayout + weight 分配列宽：GridLayout 算列宽时
     * **不含子项的 margin**，两张卡各带 4dp 外边距后就比父宽多出 16dp，
     * 右列被裁掉一截（截图里「快速笔记」右边永远缺一块）。
     * 换成竖向 LinearLayout + 每行两个 weight=1 的卡，宽度由父容器精确分配。
     */
    private fun buildCardGrid(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(ctx, 16), 0, dp(ctx, 16), 0) }
        }
    }

    // ── 行内容绑定 ──────────────────────────────────────────────

    private fun bindHero(hero: FrameLayout, item: ToolItem) {
        val ctx = hero.context
        hero.removeAllViews()

        val primary = attr(ctx, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = attr(ctx, com.google.android.material.R.attr.colorOnPrimary)
        hero.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.blendARGB(primary, Color.WHITE, 0.10f),
                primary,
                ColorUtils.blendARGB(primary, Color.BLACK, 0.30f)
            )
        ).apply { cornerRadius = dp(ctx, 24).toFloat() }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply { setMargins(dp(ctx, 20), 0, 0, dp(ctx, 18)) }

            if (!item.badge.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = item.badge
                    setTextSizeRes(R.dimen.text_label)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(onPrimary)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 99).toFloat()
                        setColor(0x33FFFFFF)
                    }
                    setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 4))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            addView(TextView(ctx).apply {
                text = item.title
                setTextSizeRes(R.dimen.text_headline)
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(onPrimary)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) })
            addView(TextView(ctx).apply {
                text = item.subtitle ?: ""
                setTextSizeRes(R.dimen.text_caption)
                setTextColor(ColorUtils.setAlphaComponent(onPrimary, 204))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 3) })
        }
        hero.addView(content)

        val arrow = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40),
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(0, 0, dp(ctx, 16), dp(ctx, 18)) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            addView(ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20), Gravity.CENTER)
                setImageResource(R.drawable.ic_mtrl_arrow_forward)
                setColorFilter(onPrimary)
            })
        }
        hero.addView(arrow)

        bindHeroPress(hero)
        hero.setOnClickListener { handleClick(it, item) }
    }

    private fun bindIconGrid(card: ViewGroup, row: ToolsRow.IconGrid) {
        val ctx = card.context
        card.removeAllViews()

        val (boxColor, iconColor) = when (row.tint) {
            ToolSection.Tint.PRIMARY ->
                attr(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh) to
                        attr(ctx, com.google.android.material.R.attr.colorPrimary)
            ToolSection.Tint.SECONDARY ->
                attr(ctx, com.google.android.material.R.attr.colorSecondaryContainer) to
                        attr(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer)
        }

        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 4
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(ctx, 16), 0, dp(ctx, 16)) }
        }

        row.items.forEach { item ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                ).apply { setMargins(0, dp(ctx, 6), 0, dp(ctx, 6)) }

                val box = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 14).toFloat()
                        setColor(boxColor)
                    }
                    addView(ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22), Gravity.CENTER)
                        setImageResource(item.icon)
                        setColorFilter(iconColor)
                        // 占位项（点击只弹"功能开发中"）图标降一档，和可用项区分开
                        if (item.isComingSoon) alpha = 0.5f
                    })
                }
                addView(box)
                addView(TextView(ctx).apply {
                    text = item.title
                    setTextSizeRes(R.dimen.text_label)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(attr(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 7) })

                Motion.press(this)
                setOnClickListener { handleClick(it, item) }
            }
            grid.addView(cell)
        }
        card.addView(grid)
    }

    private fun bindCardGrid(grid: ViewGroup, items: List<ToolItem>) {
        val ctx = grid.context
        grid.removeAllViews()

        val circleColor = attr(ctx, com.google.android.material.R.attr.colorSecondaryContainer)
        val onCircleColor = attr(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val outlineColor = attr(ctx, com.google.android.material.R.attr.colorOutline)

        var currentRow: LinearLayout? = null
        var cardsInRow = 0

        items.forEach { item ->
            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = dp(ctx, 20).toFloat()
                cardElevation = dp(ctx, 1).toFloat()
                strokeWidth = 0
                setCardBackgroundColor(attr(ctx, com.google.android.material.R.attr.colorSurfaceContainerLowest))
            }

            val iconCircle = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(circleColor)
                }
                addView(ImageView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20), Gravity.CENTER)
                    setImageResource(item.icon)
                    setColorFilter(onCircleColor)
                    // 占位项降一档不透明度：可用项"亮"，未上线项"淡"
                    if (item.isComingSoon) alpha = 0.55f
                })
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 标题行：标题 +（占位功能）「即将上线」小胶囊
                val titleRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(TextView(ctx).apply {
                    text = item.title
                    setTextSizeRes(R.dimen.text_body)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(
                        attr(ctx, com.google.android.material.R.attr.colorOnSurface)
                    )
                })
                if (item.isComingSoon) {
                    titleRow.addView(TextView(ctx).apply {
                        text = ctx.getString(R.string.tools_coming_soon)
                        setTextSizeRes(R.dimen.text_label)
                        setTextColor(attr(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(ctx, 99).toFloat()
                            setColor(attr(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh))
                        }
                        setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(ctx, 6) })
                }
                addView(titleRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                if (!item.subtitle.isNullOrBlank()) {
                    addView(TextView(ctx).apply {
                        text = item.subtitle
                        setTextSizeRes(R.dimen.text_label)
                        setTextColor(outlineColor)
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(ctx, 2) })
                }
            }

            if (item.wide) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
                    addView(iconCircle)
                    addView(textCol, LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(ctx, 12) })
                }
                card.addView(row)
            } else {
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
                    addView(iconCircle)
                    addView(textCol, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(ctx, 10) })
                }
                card.addView(col)
            }

            Motion.press(card)
            card.setOnClickListener { handleClick(it, item) }

            if (item.wide) {
                // 通栏卡：独占一行（前面若有半行，先把那半行封口）
                currentRow?.let { (grid as LinearLayout).addView(it) }
                currentRow = null
                cardsInRow = 0
                card.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6)) }
                (grid as LinearLayout).addView(card)
            } else {
                if (currentRow == null) {
                    currentRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                }
                card.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6)) }
                currentRow!!.addView(card)
                cardsInRow++
                if (cardsInRow == 2) {
                    (grid as LinearLayout).addView(currentRow)
                    currentRow = null
                    cardsInRow = 0
                }
            }
        }
        // 收尾：最后一行只有一张卡时也要加进去
        currentRow?.let { (grid as LinearLayout).addView(it) }
    }

    /**
     * 推荐大卡的按压反馈。
     *
     * 这张卡同时有入场动画与按压缩放，两者都走 ViewPropertyAnimator：
     * 入场没播完就被按下时，按压缩放会 cancel 掉入场动画，把透明度/位移卡在中间。
     * 这里在按下瞬间先把入场落到终态，只保留按压缩放。
     */
    private fun bindHeroPress(hero: View) {
        val press = Motion.press(hero)
        hero.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                view.alpha = 1f
                view.translationY = 0f
            }
            press.onTouch(view, event)
        }
    }

    // ── 工具方法 ────────────────────────────────────────────────

    /** 已配置跳转的工具直接执行；未配置的以「敬请期待」占位 */
    private fun handleClick(v: View, item: ToolItem) {
        val action = item.onClick
        if (action != null) {
            action(v.context)
        } else {
            AppToast.info(v.context, v.context.getString(R.string.tools_coming_soon_toast))
        }
    }

    private fun attr(ctx: Context, attrRes: Int): Int {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    class RowViewHolder(itemView: View, val viewType: Int) : RecyclerView.ViewHolder(itemView)

    companion object {
        /** 图标入场起始缩放（随卡片轻微 pop） */
        private const val ICON_POP_FROM_SCALE = 0.8f

        private const val TYPE_FEATURED = 0
        private const val TYPE_TITLE = 1
        private const val TYPE_ICON_GRID = 2
        private const val TYPE_CARD_GRID = 3
    }
}
