# Material 3 深度重设计 — 设计文档

> 日期：2026-07-28 | 版本：1.0

## 一、目标

将 Schedule 课表应用从碎片化的多套主题/背景/配色系统，重构为统一的 Material 3 设计体系，同时优化导航架构、课表交互体验和设置页面排版。

## 二、现状问题

| 问题 | 详情 |
|------|------|
| 主题碎片化 | 3 套 Theme + 14 种背景色 + 6 套卡片配色，三套独立的颜色系统，用户需分别配置 |
| 背景选择过多 | 14 种纯色 + 磨砂 + 图片，代码中 4 套文字颜色适配方法，维护成本高 |
| 设置页面粗糙 | 无图标纯文字列表、固定暗色背景、信息层次弱、不支持浅色模式 |
| 图标简陋 | 手绘 XML vector drawable，风格不统一，数量不足 |
| 课表滑动体验差 | 手动 touch 处理，不跟手、翻页动画不自然（先滑出再滑入） |
| 日期显示模糊 | 表头只显示"日"数字，不显示月份 |
| 底部导航利用率低 | 仅 2 个 tab（课表 + 设置），缺少扩展空间 |

## 三、方案总览

```
┌──────────────────────────────────────────────────────┐
│                    M3 Theme (1 套)                    │
│  ┌────────────────────────────────────────────────┐  │
│  │          5 套色板 (用户可选)                     │  │
│  │  ┌──────────────────────────────────────────┐  │  │
│  │  │     课程颜色自动从色板采样 (16色)          │  │  │
│  │  └──────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────┘  │
│                                                      │
│  ┌─ NavigationBar ─────────────────────────────────┐  │
│  │  课表 (calendar_month)  工具 (grid_view)  我的 (person) │
│  └─────────────────────────────────────────────────┘  │
│                                                      │
│  ┌─ 课表页 ────────────────────────────────────────┐  │
│  │  AppBar (周次 + 日期)  +  FAB (视图切换)        │  │
│  │  ViewPager2 (跟手拖动周切换)                     │  │
│  │  日期表头: "7/28 周一" ... "8/3 周日"            │  │
│  └─────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

## 四、主题系统

### 4.1 架构

- 使用 Material 3 (`MaterialComponents.Material3.*`) 作为基础 Theme
- 浅色/深色模式跟随系统设置自动切换
- 5 套预定义色板，用户在「我的」页面选择
- 课程卡片颜色从当前色板自动采样生成，无需手动配置
- 删除所有背景颜色选择功能，背景色由 M3 `surface` 自动派生

### 4.2 5 套 M3 色板

M3 以 Seed Color 为起点，自动生成 Primary / Secondary / Tertiary / Error 四组色阶，以及 Surface / SurfaceContainer / Background 等中性色阶。

| # | 名称 | Seed Color | 风格描述 |
|---|------|-----------|----------|
| 1 | 经典紫 | `#6750A4` | Material 3 官方默认，稳重大气 |
| 2 | 海洋蓝 | `#1565C0` | 清爽理性，适合校园场景 |
| 3 | 青绿 | `#006A60` | 自然舒适，护眼 |
| 4 | 暖棕 | `#8D5000` | 温暖沉稳，秋冬感 |
| 5 | 玫红 | `#B0005C` | 活力时尚，有辨识度 |

### 4.3 课程颜色自动生成

从当前色板的 `tertiaryContainer` 和 `primaryContainer` 色阶中均匀采样 16 个颜色：
- 使用 M3 `Hct` (色相-彩度-色调) 算法间隔采样，保证色彩区分度
- 文字颜色自动使用 `onTertiaryContainer` / `onPrimaryContainer` 确保可读性
- 不再提供手动选择 6 套配色方案的入口

### 4.4 删除项

- `SettingsManager.BackgroundType` / `BackgroundTheme` / `backgroundThemes` 及所有相关方法
- `SettingsManager.ColorTheme` / `colorThemes` 及所有相关方法
- `ColorThemePickerActivity` 整个文件
- `MainActivity` 中 4 个文字颜色适配方法
- `bg_flower_pattern.xml`（设置页花朵背景）
- 设置页「背景设置」「清空图片背景」「卡片配色」3 个入口
- 自定义图片背景功能：保留后端（文件存储/Glide加载），但入口移至「我的」页作为单一选项

## 五、导航架构

### 5.1 底部导航栏

使用 Material 3 `NavigationBar`，3 个目的地：

| 序号 | 标签 | 图标 (Material Icon) | 功能 |
|------|------|---------------------|------|
| 1 | 课表 | `calendar_month` | 课表主体（周/日/总览视图） |
| 2 | 工具 | `grid_view` | 预留：常用工具入口（暂时显示空状态） |
| 3 | 我的 | `person` | 用户设置、账号、关于 |

### 5.2 页面结构

```
MainActivity
├── AppBar (M3 TopAppBar)
│   ├── 标题: "第5周 (本周)"
│   └── 右侧: 刷新按钮
├── FragmentContainerView (NavHostFragment)
│   ├── ScheduleFragment    ← 课表页
│   ├── ToolsFragment       ← 工具页（占位）
│   └── ProfileFragment     ← 我的页（替代原 SettingsFragment）
└── NavigationBar
```

### 5.3 悬浮 FAB（课表视图切换）

- 位置：课表页右下角，覆盖在 ViewPager2 之上
- 外观：M3 `FloatingActionButton`，圆角，带 Material Tooltip
- Tooltip 文字：当前视图名称（"周视图" / "今日" / "总览"）
- 交互：
  - **点击**：周 → 今 → 总 三态循环
  - **长按**：弹出 PopupMenu 三个选项，可直达任意视图
  - **拖拽**：保持可拖拽 + 松手吸附屏幕左右边缘（沿用现有拖动逻辑，FAB 样式更新）
- 图标：`swap_horiz`（切换）或动态变化

## 六、课表交互重设计

### 6.1 周切换（ViewPager2）

替换当前手动 `setOnTouchListener` 方案，改用 `ViewPager2`：

- 每页 = 一周的完整课表（7 列 GridLayout）
- 总页数 = 学期总周数（默认 20 周）
- 当前周 = 默认定位页
- **跟手拖动**：手指移动时页面实时跟随平移
- **松手吸附**：偏移超过 50% → 吸附到上一页/下一页并切换数据；不足 50% → 弹回当前页
- **惯性 fling**：快速滑动即使不到 50% 偏移也触发翻页
- **边界处理**：ViewPager2 原生处理首尾页边界
- **周次同步**：页面变化回调 → 更新 AppBar 周次标题 + 日期表头 + 悬浮按钮状态

### 6.2 日期表头

从只显示「日」数字，改为显示完整日期：

```
现状:  [一] [二] [三] [四] [五] [六] [日]
       [28] [29] [30] [31] [1]  [2]  [3]

改进:  周一   周二   周三   周四   周五   周六   周日
       7/28   7/29   7/30   7/31   8/1    8/2    8/3
```

- 第一行：周X 中文（周一/周二/.../周日）
- 第二行：`M/d` 格式（跨月时自动显示月份变化）
- 当天日期高亮（M3 `primaryContainer` 背景 + `onPrimaryContainer` 文字）
- 跟随 ViewPager2 页面切换联动更新

### 6.3 今日视图（保留）

- 从「悬浮按钮循环」中的一态变为可选视图
- 今日视图显示逻辑不变：过滤当天课程 → 卡片列表 → 空状态提示
- 视图中无课程时，显示 M3 空状态组件（图标 + 文案）

### 6.4 课程全览（保留）

- RecyclerView 列表，显示所有课程
- 改为 M3 卡片样式

## 七、图标系统

全面替换为 [Material Symbols](https://fonts.google.com/icons)，通过 `material-icons` 依赖引入。

### 7.1 关键图标映射

| 使用场景 | Material Icon | 尺寸 |
|----------|--------------|------|
| 底部导航 - 课表 | `calendar_month` | 24dp |
| 底部导航 - 工具 | `grid_view` | 24dp |
| 底部导航 - 我的 | `person` | 24dp |
| FAB - 视图切换 | `swap_horiz` | 24dp |
| 我的页 - 账号分组 | `account_circle` | 24dp |
| 绑定教务 | `link` | 24dp |
| 个人信息 | `badge` | 24dp |
| 学期 | `school` | 24dp |
| 日期范围 | `date_range` | 24dp |
| 周次 | `today` | 24dp |
| 主题 | `palette` | 24dp |
| 提醒 | `notifications` | 24dp |
| 时间表 | `schedule` | 24dp |
| 透明度 | `opacity` | 24dp |
| 节假日 | `beach_access` | 24dp |
| 导入 | `file_download` | 24dp |
| 导出 | `file_upload` | 24dp |
| 删除 | `delete` | 24dp |
| 关于 | `info` | 24dp |
| 反馈 | `feedback` | 24dp |
| 权限引导 | `admin_panel_settings` | 24dp |
| 刷新 | `refresh` | 24dp |
| 返回 | `arrow_back` | 24dp |

### 7.2 实现方式

```kotlin
// 使用 Material Icons Compose-compatible 或 standalone
// 方案: com.google.android.material:material 自带 icon 资源
// 在 XML 中: app:icon="@drawable/ic_mtrl_calendar_month"
// 在代码中: MaterialColors.getIcons()
```

## 八、「我的」页面重设计

### 8.1 布局结构

```
┌──────────────────────────────────────┐
│  ← 我的                              │  ← M3 TopAppBar
├──────────────────────────────────────┤
│                                      │
│  ┌─ 账号 ──────────────────────────┐  │
│  │ 🔑  绑定教务                  → │  │  ← M3 ListItem
│  │ 👤  个人信息                  → │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ 学期 ──────────────────────────┐  │
│  │ 📅  当前学期    2024-2025 一学期→│  │
│  │ 📆  学期日期    09/01 ~ 01/15  →│  │
│  │ 🔢  默认周次    第 5 周        →│  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ 课表 ──────────────────────────┐  │
│  │ 🎨  主题色板    海洋蓝        → │  │
│  │ 🔔  课前提醒    提前15分钟    → │  │
│  │ 🕐  时间表      12节/天      →  │  │
│  │ 🎯  卡片透明度  85%          →  │  │
│  │ 🏖  节假日隐藏        [Switch] │  │
│  │ 🖼  图片背景     (可选入口)   → │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ 数据 ──────────────────────────┐  │
│  │ 📥  导入课程                  → │  │
│  │ 📤  导出课程                  → │  │
│  │ 🗑  清除所有数据    (红色)    → │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ 关于 ──────────────────────────┐  │
│  │ ℹ️  关于应用                  → │  │
│  │ 🔍  检测更新                  → │  │
│  │ 🔔  更新提醒          [Switch] │  │
│  │ ✉️  反馈建议                  → │  │
│  └────────────────────────────────┘  │
│                                      │
└──────────────────────────────────────┘
```

### 8.2 交互细节

- 每个分组 = M3 Card（圆角 12dp，轻微 elevation）
- 每行 = 带 leading icon 的列表项
- 可导航项 → 右侧 chevron 或当前值 + chevron
- Switch 项 → 内联在行尾
- 点击弹出 dialog 或跳转子页面
- 页面背景 = M3 `surface`，文字颜色 = `onSurface`

## 九、文件变更清单

### 9.1 新增文件

| 文件 | 说明 |
|------|------|
| `ui/theme/ColorScheme.kt` | 5 套 M3 色板定义 + 课程颜色采样算法 |
| `ui/theme/Theme.kt` | M3 主题配置，浅色/深色/动态色板切换 |
| `ui/theme/Type.kt` | M3 排版系统 |
| `ui/navigation/NavGraph.kt` | Navigation 路由图 |
| `ui/screen/schedule/ScheduleFragment.kt` | 课表页 Fragment（从 MainActivity 拆分） |
| `ui/screen/tools/ToolsFragment.kt` | 工具页 Fragment（占位） |
| `ui/screen/profile/ProfileFragment.kt` | 我的页 Fragment（替代 SettingsFragment） |
| `ui/screen/profile/ProfileAdapter.kt` | 设置列表适配器 |
| `ui/widget/CourseColorSampler.kt` | 课程颜色采样工具 |

### 9.2 修改文件

| 文件 | 变更 |
|------|------|
| `MainActivity.kt` | 重构：移除课表主体逻辑，改为 NavigationBar + NavHost |
| `App.kt` | M3 主题初始化，删除旧 Theme 选择逻辑 |
| `WeekPagerAdapter.kt` | 重写为 ViewPager2 FragmentStateAdapter |
| `SettingsManager.kt` | 删除 ~200 行背景/颜色相关代码，新增色板索引 |
| `SettingsFragment.kt` | 不再在 MainActivity 内嵌使用 |
| `themes.xml` | 简化为 M3 基础主题 |
| `styles.xml` | 删除 RoundedDialog 等冗余样式 |
| `colors.xml` | 删除旧 purple/teal 色值 |
| `dimens.xml` | 更新为 M3 间距系统 |
| `build.gradle` | 添加 material-icons 依赖 |
| `activity_settings.xml` | 更新布局 |
| `fragment_settings.xml` | 重新设计（或废弃） |

### 9.3 删除文件

| 文件 | 原因 |
|------|------|
| `ColorThemePickerActivity.kt` | 卡片配色改为自动生成 |
| `bg_flower_pattern.xml` | 不再使用固定背景图案 |
| `bg_card_transparent.xml` | 替换为 M3 Card |
| `bg_button_outline.xml` | 替换为 M3 Button |
| `bg_button_outline_red.xml` | 替换为 M3 tonalButton |
| `bg_button_primary.xml` | 替换为 M3 filledButton |
| 旧版 ic_*.xml 自制图标 | 替换为 Material Icons |
| `SettingsActivity.kt` | 设置不再需要独立 Activity，「我的」页内嵌在 MainActivity 中 |
| `apply_background_adapter.xml` (如存在) | 不再需要 |

### 9.4 不受影响

- **课表逻辑层**：CourseDataManager, CourseViewModel, AlarmService, CourseValidator 等
- **桌面小组件**：所有 widget/ 包下文件（RemoteViews 独立于应用内主题）
- **教务系统**：JwxtImportService, JwxtAuthManager, JwxtAccountManager, gxuJwxtJavaClient
- **导入导出**：ImportService, ics/backup 逻辑
- **通知系统**：NotificationHelper, CourseReminderWorker 等
- **数据层**：Course, CourseDao, AppDatabase, BackupData

## 十、兼容性

- **最低 SDK**：API 26（Android 8.0），M3 组件良好支持
- **目标 SDK**：API 35，M3 最优化体验
- **深色模式**：M3 原生 DayNight 自动切换
- **小组件**：不受影响（RemoteViews 独立主题系统）
- **动态取色 API 31+**：不使用 `DynamicColors`，固定 5 套色板兼容所有版本

## 十一、风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| ViewPager2 替代手动滑动 | 可能影响现有 scrollView 嵌套 | schedule 内部用 `NestedScrollableHost` |
| M3 组件迁移 | 布局文件大面积重写 | 分步骤：先导航，再主题，再逐页 |
| 课表主体从 Activity 拆到 Fragment | 生命周期差异 | ViewModel + LiveData 已在用，影响可控 |
| 删除背景选择入口 | 用户可能不满 | 保留自定义图片入口，5 套色板覆盖主流偏好 |
