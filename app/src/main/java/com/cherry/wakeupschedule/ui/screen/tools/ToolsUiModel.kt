package com.cherry.wakeupschedule.ui.screen.tools

import android.content.Context
import androidx.annotation.DrawableRes

/**
 * 工具页数据模型。后续新增工具只需在 ToolsFragment 的 [ToolsFragment.buildSections]
 * 里追加条目；onClick 为 null 时视为「开发中」，点击提示敬请期待。
 */
data class ToolItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    @DrawableRes val icon: Int,
    /** 推荐卡角标文案，如「推荐」/「NEW」 */
    val badge: String? = null,
    /** 大卡分组里是否通栏（横排布局） */
    val wide: Boolean = false,
    /** 非空时点击执行真实跳转；空则占位提示 */
    val onClick: ((Context) -> Unit)? = null,
) {
    fun matches(query: String): Boolean =
        title.contains(query, ignoreCase = true) ||
                (subtitle?.contains(query, ignoreCase = true) ?: false)
}

/** 工具分组：图标网格（4 列）或大卡（双列） */
data class ToolSection(
    val title: String,
    val style: Style,
    val items: List<ToolItem>,
    /** 图标盒底色方案：学业服务用主色系，校园生活用次色系 */
    val tint: Tint = Tint.PRIMARY,
) {
    enum class Style { ICON_GRID, CARDS }
    enum class Tint { PRIMARY, SECONDARY }
}

/** 工具页内容行（扁平化后的 RecyclerView 行） */
sealed class ToolsRow {
    data class Featured(val item: ToolItem) : ToolsRow()
    data class SectionTitle(val title: String) : ToolsRow()
    data class IconGrid(val items: List<ToolItem>, val tint: ToolSection.Tint) : ToolsRow()
    data class CardGrid(val items: List<ToolItem>) : ToolsRow()
}
