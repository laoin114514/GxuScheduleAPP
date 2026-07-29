package com.cherry.wakeupschedule.ui.component

/**
 * 统一选择器选项数据模型。
 *
 * @param label       主文字（必填）
 * @param subtitle    辅助文字，如学期的 "← 当前" 标签（可选）
 * @param leadingColor 前置色块颜色，用于主题色板选择器展示颜色预览（可选）
 * @param leadingIcon  前置图标资源 ID（可选，留作扩展）
 */
data class SelectOption(
    val label: String,
    val subtitle: String? = null,
    val leadingColor: Int? = null,
    val leadingIcon: Int? = null
)
