package com.cherry.wakeupschedule.ui.screen.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.cherry.wakeupschedule.BindJwxtActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.databinding.FragmentToolsBinding

/**
 * 工具页：顶栏 + 搜索 + 推荐大卡 + 分组工具（图标网格 / 双列大卡）。
 * 工具以 [ToolItem] 列表驱动，后续接入新功能只需在 [buildSections] 追加条目，
 * 并给条目配上 [ToolItem.onClick]；未配置的条目自动以「敬请期待」占位。
 */
class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private val adapter = ToolsAdapter()

    /** 推荐位：教务一键导入（真实入口） */
    private val featured = ToolItem(
        id = "jwxt_import",
        title = "教务一键导入",
        subtitle = "绑定教务系统，自动同步课表",
        icon = R.drawable.ic_mtrl_school,
        badge = "推荐",
        onClick = { ctx -> ctx.startActivity(Intent(ctx, BindJwxtActivity::class.java)) },
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerTools.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerTools.adapter = adapter
        refresh("")

        // 搜索：按标题/副标题实时过滤分组
        binding.etToolsSearch.addTextChangedListener { text ->
            refresh(text?.toString().orEmpty())
        }

        // 顶栏搜索按钮：聚焦搜索框并弹起键盘
        binding.btnToolsSearch.setOnClickListener {
            binding.etToolsSearch.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
            imm.showSoftInput(binding.etToolsSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        // 顶栏头像：跳到「我的」页签
        binding.btnToolsAvatar.setOnClickListener { goTab(R.id.nav_profile) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── 数据 ────────────────────────────────────────────────────

    private fun buildSections(): List<ToolSection> = listOf(
        ToolSection("学业服务", ToolSection.Style.ICON_GRID, listOf(
            ToolItem("grades", "成绩查询", icon = R.drawable.ic_mtrl_assignment),
            ToolItem("exams", "考试安排", icon = R.drawable.ic_mtrl_event_note),
            ToolItem("classroom", "教室查询", icon = R.drawable.ic_mtrl_school),
            ToolItem("gpa", "绩点计算", icon = R.drawable.ic_mtrl_chart),
        )),
        ToolSection("校园生活", ToolSection.Style.ICON_GRID, listOf(
            ToolItem("library", "图书馆", icon = R.drawable.ic_mtrl_book),
            ToolItem("campus_card", "校园卡", icon = R.drawable.ic_mtrl_credit_card),
            ToolItem("map", "校园地图", icon = R.drawable.ic_mtrl_map),
            ToolItem("dining", "食堂菜谱", icon = R.drawable.ic_mtrl_restaurant),
        ), tint = ToolSection.Tint.SECONDARY),
        ToolSection("学习工具", ToolSection.Style.CARDS, listOf(
            ToolItem("pomodoro", "番茄钟", subtitle = "专注计时", icon = R.drawable.ic_mtrl_timer),
            ToolItem("notes", "快速笔记", subtitle = "灵感随手记", icon = R.drawable.ic_mtrl_note),
            ToolItem("export", "课表导出", subtitle = "生成课表图片，轻松分享",
                icon = R.drawable.ic_mtrl_file_upload, wide = true),
        )),
    )

    private fun refresh(query: String) {
        val q = query.trim()
        val feat = featured.takeIf { q.isEmpty() || it.matches(q) }
        val secs = buildSections()
            .map { if (q.isEmpty()) it else it.copy(items = it.items.filter { item -> item.matches(q) }) }
            .filter { it.items.isNotEmpty() }

        val rows = mutableListOf<ToolsRow>()
        feat?.let { rows += ToolsRow.Featured(it) }
        secs.forEach {
            rows += ToolsRow.SectionTitle(it.title)
            when (it.style) {
                ToolSection.Style.ICON_GRID -> rows += ToolsRow.IconGrid(it.items, it.tint)
                ToolSection.Style.CARDS -> rows += ToolsRow.CardGrid(it.items)
            }
        }
        adapter.render(rows)

        val empty = feat == null && secs.isEmpty()
        binding.layoutToolsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
    }

    /** 顶层页签式跳转（与底部导航一致的栈行为） */
    private fun goTab(id: Int) {
        val nav = findNavController()
        nav.navigate(id) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}
