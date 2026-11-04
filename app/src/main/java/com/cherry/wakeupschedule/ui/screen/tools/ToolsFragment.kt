package com.cherry.wakeupschedule.ui.screen.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.cherry.wakeupschedule.ui.theme.PageBackground
import com.cherry.wakeupschedule.BindJwxtActivity
import com.cherry.wakeupschedule.ExamScheduleActivity
import com.cherry.wakeupschedule.GpaActivity
import com.cherry.wakeupschedule.GradeQueryActivity
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.databinding.FragmentToolsBinding
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.feedback.AppToast
import com.cherry.wakeupschedule.ui.screen.export.ScheduleExportDialog
import com.cherry.wakeupschedule.ui.screen.export.ScheduleExportRenderer
import com.cherry.wakeupschedule.ui.screen.export.ScheduleImageSaver
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import kotlinx.coroutines.launch

/**
 * 工具页：顶栏 + 搜索 + 推荐大卡 + 分组工具（图标网格 / 双列大卡）。
 * 工具以 [ToolItem] 列表驱动，后续接入新功能只需在 [buildSections] 追加条目，
 * 并给条目配上 [ToolItem.onClick]；未配置的条目自动以「敬请期待」占位。
 */
class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private val adapter = ToolsAdapter()

    /** 当前打开的课表导出预览弹窗（View 销毁时关掉，避免窗口泄漏） */
    private var exportDialog: ScheduleExportDialog? = null

    /** Android 9 及以下保存相册要先申请存储权限：暂存待保存的图片与周次 */
    private var pendingSaveBitmap: Bitmap? = null
    private var pendingSaveWeek: Int = 1

    /** 存储权限结果：授权后继续保存刚才的导出图，拒绝则提示 */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val bitmap = pendingSaveBitmap
        val week = pendingSaveWeek
        pendingSaveBitmap = null
        if (!isAdded) return@registerForActivityResult
        if (granted && bitmap != null) {
            saveBitmapToGallery(bitmap, week)
        } else {
            AppToast.warn(requireContext(), R.string.export_storage_permission_denied)
        }
    }

    /** 推荐位：教务一键导入（绑定引导，只在未绑定教务账号时展示） */
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
        // 页面底渐变按当前调色板派生（换配色时页面底色跟着变，而不是永远是蓝灰）
        PageBackground.apply(view)

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

    override fun onResume() {
        super.onResume()
        // 绑定/解绑后会原地回到本页（View 不重建），登录态可能已变，重算一次列表。
        // 传当前搜索词：否则正在搜索时切出去回来，过滤会被静默清掉。
        val b = _binding ?: return
        refresh(b.etToolsSearch.text.toString())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 弹窗挂在 Activity 窗口上，Fragment 的 View 没了就要关掉，否则旋转/切页后窗口泄漏
        exportDialog?.dismiss()
        exportDialog = null
        pendingSaveBitmap = null
        _binding = null
    }

    // ── 数据 ────────────────────────────────────────────────────

    private fun buildSections(): List<ToolSection> {
        // 文案统一走 strings.xml（第一批：工具页分组与条目）
        val ctx = requireContext()
        return listOf(
        ToolSection(ctx.getString(R.string.tools_section_academic), ToolSection.Style.ICON_GRID, listOf(
            ToolItem("grades", ctx.getString(R.string.tools_grades), icon = R.drawable.ic_mtrl_assignment,
                onClick = { ctx -> ctx.startActivity(Intent(ctx, GradeQueryActivity::class.java)) }),
            ToolItem("exams", ctx.getString(R.string.tools_exams), icon = R.drawable.ic_mtrl_event_note,
                onClick = { ctx -> ctx.startActivity(Intent(ctx, ExamScheduleActivity::class.java)) }),
            ToolItem("classroom", ctx.getString(R.string.tools_classroom), icon = R.drawable.ic_mtrl_meeting_room),
            ToolItem("gpa", ctx.getString(R.string.tools_gpa), icon = R.drawable.ic_mtrl_chart,
                onClick = { ctx -> ctx.startActivity(Intent(ctx, GpaActivity::class.java)) }),
        )),
        ToolSection(ctx.getString(R.string.tools_section_campus), ToolSection.Style.ICON_GRID, listOf(
            ToolItem("library", ctx.getString(R.string.tools_library), icon = R.drawable.ic_mtrl_book),
            ToolItem("campus_card", ctx.getString(R.string.tools_campus_card), icon = R.drawable.ic_mtrl_credit_card),
            ToolItem("map", ctx.getString(R.string.tools_campus_map), icon = R.drawable.ic_mtrl_map),
            ToolItem("dining", ctx.getString(R.string.tools_dining), icon = R.drawable.ic_mtrl_restaurant),
        ), tint = ToolSection.Tint.SECONDARY),
        ToolSection(ctx.getString(R.string.tools_section_study), ToolSection.Style.CARDS, listOf(
            ToolItem("pomodoro", ctx.getString(R.string.tools_pomodoro), subtitle = ctx.getString(R.string.tools_pomodoro_sub), icon = R.drawable.ic_mtrl_timer),
            ToolItem("notes", ctx.getString(R.string.tools_notes), subtitle = ctx.getString(R.string.tools_notes_sub), icon = R.drawable.ic_mtrl_note),
            ToolItem("export", ctx.getString(R.string.tools_export), subtitle = ctx.getString(R.string.tools_export_sub),
                icon = R.drawable.ic_mtrl_file_upload, wide = true,
                onClick = { exportSchedule() }),
        )),
        )
    }

    private fun refresh(query: String) {
        val q = query.trim()
        // 教务一键导入：只在未绑定教务账号时作为引导位展示，绑定后整块移除
        // （它不属于任何 ToolSection，所以移除后不会留下孤立的分组标题）
        val feat = featured.takeIf {
            !JwxtAuthManager.isBound() && (q.isEmpty() || it.matches(q))
        }
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

    // ── 课表导出 ────────────────────────────────────────────────

    /**
     * 工具页「课表导出」入口：把当前显示的周课表按屏幕宽度离屏排版成整页 Bitmap
     * （复用课表页自身的布局构建代码，见 [ScheduleExportRenderer]），
     * 然后弹出预览，由用户选择「保存到相册」或「分享」。
     */
    private fun exportSchedule() {
        val ctx = requireContext()
        val viewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]
        // 导出用户正在看的那一周：优先课表页记录的显示周，兜底当前周
        val displayWeek = viewModel.displayWeek.takeIf { it > 0 }
            ?: viewModel.currentWeek.takeIf { it > 0 }
            ?: 1
        val totalWeeks = SettingsManager(ctx).getTotalWeeks().coerceAtLeast(1)
        val week = displayWeek.coerceIn(1, totalWeeks)

        val bitmap = try {
            ScheduleExportRenderer.renderWeek(ctx, week, viewModel.currentWeek)
        } catch (t: Throwable) {
            Log.e(TAG, "render schedule export bitmap failed", t)
            AppToast.error(ctx, R.string.export_generate_failed)
            return
        }

        exportDialog?.dismiss()
        exportDialog = ScheduleExportDialog.show(ctx, bitmap, week) {
            saveBitmapToGallery(bitmap, week)
        }
    }

    /**
     * 保存到相册前先过权限：Android 10+ 走 MediaStore 分区存储无需权限；
     * Android 9 及以下写公共 Pictures 目录需要 WRITE_EXTERNAL_STORAGE。
     */
    private fun saveBitmapToGallery(bitmap: Bitmap, week: Int) {
        val ctx = context ?: return
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSaveBitmap = bitmap
            pendingSaveWeek = week
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSaveBitmap(bitmap, week)
    }

    /** 落盘（IO 线程）+ 结果提示；保存期间弹窗保持打开，用户可继续查看预览 */
    private fun doSaveBitmap(bitmap: Bitmap, week: Int) {
        val appContext = requireContext().applicationContext
        val fileName = ScheduleExportRenderer.buildFileName(week)
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = ScheduleImageSaver.saveToGallery(appContext, bitmap, fileName)
            if (!isAdded) return@launch
            if (saved) {
                AppToast.success(requireContext(), R.string.export_saved_success)
            } else {
                AppToast.error(requireContext(), R.string.export_saved_failed)
            }
        }
    }

    /** 顶层页签式跳转（与底部导航一致的栈行为） */
    private fun goTab(id: Int) {
        val nav = findNavController()
        nav.navigate(id, null, navOptions {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        })
    }

    private companion object {
        private const val TAG = "ToolsFragment"
    }
}
