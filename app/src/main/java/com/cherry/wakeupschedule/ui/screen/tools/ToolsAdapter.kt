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
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R

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
        val ctx = holder.itemView.context
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
            textSize = 18f
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

    private fun buildCardGrid(ctx: Context): android.widget.GridLayout {
        return android.widget.GridLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(ctx, 16), 0, dp(ctx, 16), 0) }
            columnCount = 2
        }
    }

    // ── 行内容绑定 ──────────────────────────────────────────────

    private fun bindHero(hero: FrameLayout, item: ToolItem) {
        val ctx = hero.context
        hero.removeAllViews()

        val primary = attr(ctx, com.google.android.material.R.attr.colorPrimary)
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
                    textSize = 10f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 99).toFloat()
                        setColor(0x33FFFFFF)
                    }
                    setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 4))
                })
            }
            addView(TextView(ctx).apply {
                text = item.title
                textSize = 19f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) })
            addView(TextView(ctx).apply {
                text = item.subtitle ?: ""
                textSize = 12f
                setTextColor(0xCCFFFFFF.toInt())
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
                setColorFilter(Color.WHITE)
            })
        }
        hero.addView(arrow)

        pressScale(hero)
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
                    })
                }
                addView(box)
                addView(TextView(ctx).apply {
                    text = item.title
                    textSize = 10f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(attr(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 7) })

                pressScale(this)
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

        items.forEach { item ->
            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = dp(ctx, 20).toFloat()
                cardElevation = dp(ctx, 1).toFloat()
                strokeWidth = 0
                setCardBackgroundColor(attr(ctx, com.google.android.material.R.attr.colorSurfaceContainerLowest))
                layoutParams = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, if (item.wide) 2 else 1),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                ).apply { setMargins(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6)) }
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
                })
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = item.title
                    textSize = 15f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(attr(ctx, com.google.android.material.R.attr.colorOnSurface))
                })
                if (!item.subtitle.isNullOrBlank()) {
                    addView(TextView(ctx).apply {
                        text = item.subtitle
                        textSize = 10f
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

            pressScale(card)
            card.setOnClickListener { handleClick(it, item) }
            grid.addView(card)
        }
    }

    // ── 工具方法 ────────────────────────────────────────────────

    /** 已配置跳转的工具直接执行；未配置的以「敬请期待」占位 */
    private fun handleClick(v: View, item: ToolItem) {
        val action = item.onClick
        if (action != null) {
            action(v.context)
        } else {
            Toast.makeText(v.context, "功能开发中，敬请期待", Toast.LENGTH_SHORT).show()
        }
    }

    /** 按压缩放反馈（与课表卡片一致） */
    private fun pressScale(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
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
        private const val TYPE_FEATURED = 0
        private const val TYPE_TITLE = 1
        private const val TYPE_ICON_GRID = 2
        private const val TYPE_CARD_GRID = 3
    }
}
