# 西大课栈 · UI 全面体检与优化方案

> 状态：**方案设计稿（未改一行代码）**
> 基线：`183ed47 docs: 补充 AGENT.md…`（工作区干净）
> 方法：全量通读 60 个 layout / 20 K 行 Kotlin，配合模拟器（1080×2400 / 420dpi，Android 15）实机截图，
> 关键结论都用**像素采样 + WCAG 对比度计算**坐实，不靠"看图感觉"。
> 截图证据：`/tmp/shots2/`（本轮实拍 25 张，含深色），历史对照：`develop/课表菜单截图/`、`develop/课表外观排版截图/`。

---

## 0. 一句话结论

**这个 App 的"设计意图"已经相当完整，缺的是"设计的最后一公里"：颜色在真实底色上不够读、文字在真实格子里塞不下、
控件在真实手势下点不中、体系在真实调色板下没闭环。**

视觉风格（渐变底 + 24dp 大圆角卡 + 胶囊导航 + 主色加粗标题）值得保留，不需要推倒重来。
本方案的核心不是"换个风格"，而是把已有的风格**做实**：
- 让每一处文字在当前底色上都 ≥ 4.5:1；
- 让每一块内容在 40–120dp 的容器里都有明确的分级与截断规则；
- 让每一个可点元素都有 ≥48dp 的热区和可见的按压反馈；
- 让"5 套调色板 / 浅色 / 深色 / 6 款小组件"共用一条颜色链路，而不是各抄一份 hex。

**最紧急的一件事**：浅色模式下课程格子是「半透明浅彩底 + 纯白粗体字」，实测对比度 **1.48–2.77:1**，
全部低于 WCAG AA(4.5) 甚至低于大字级 AA(3.0)。这是全 App 每天被看最久、当前最读不清的地方。

---

## 1. 现状盘点

### 1.1 页面地图（21 个界面 + 6 款桌面组件）

| 层级 | 页面 | 承载文件 |
| --- | --- | --- |
| 主框架 | 课表 / 工具 / 我的 | `activity_main.xml` + 3 个 Fragment + `PillBottomNavView` |
| 课表 | 周课表、总课表、课程详情、课表菜单、课表外观面板 | `WeekPagerAdapter`(722) / `SchedulePageDetailDialog` / `ScheduleAppearanceSheet` |
| 工具 | 工具列表、绑定教务、成绩查询、考试安排、绩点计算 | `ToolsAdapter` / `BindJwxtActivity` / `GradeQueryActivity` / `ExamScheduleActivity` / `GpaActivity` |
| 我的 | 我的、个人信息、主题、课表外观、字体大小、时间表、小组件、关于 | `ProfileFragment` / `ProfileActivity` / `AppearanceActivity` / `FontSizeActivity` / `TimeTableEditActivity` / `WidgetCenterActivity` / `AboutActivity` |
| 桌面 | 今日课程 2×2/4×2、下一门课、下课倒计时、一周课程、近日课程 | 6 个 `*WidgetProvider` + `widget/*.xml` |

### 1.2 设计令牌现状（已有什么、缺什么）

**已经有了（且做得不错）**
- 字号刻度：`dimens.xml` 里 `text_display(24) … text_micro(9)` 十档，全 App 引用率很高（layout 内 `@dimen/text_caption` 93 处、`text_body_small` 45 处）。
- 主题 overlay 骨架：`ThemeOverlay.WakeupSchedule.Palette0…4`（浅色 5 套 + 深色 5 套），运行时 `applyStyle`。
- 区块卡片样式：`AppSectionCard`（23 处）、`DetailSheetCard`（8 处）、`AppSectionHeader`（11 处）、`AppToolbarTitle`。
- 反馈组件：`AppToast`（合并/队列/生命周期重画齐全）、`AppChip`、`AppSlider`、`StyledDialog`、`SelectionDialog`。
- 小组件令牌：`widget_colors.xml` + `values-night` 变体、`bg_widget_card`（16dp 圆角）、`widget_row_*`、`widget_week_chip_*`。

**缺的（本方案的"地基"部分）**
| 缺什么 | 后果 | 证据 |
| --- | --- | --- |
| 无**间距**令牌 | 页面边距 16/20/24 三套并存；工具页内部 24/20/16 三条左边缘 | `ToolsAdapter.kt:80,91,104` |
| 无**圆角**令牌 | 实际圆角 2/4/5/8/12/14/16/18/20/24/28/999 共 12 档 | 35 个 drawable 里的 `radius=` |
| 无**语义色**（positive/negative/warning） | 红=挂科、红=绩点下降、红=危险操作、红=需补考，混用 | `GradeAdapter` / `GpaActivity` / `danger_color` |
| `colorSurfaceContainerLowest/Low/Highest` **未定义** | 28 处卡片落到 M3 基线默认色，深色下比页面更冷更暗 | `styles.xml:106` vs 5 套 overlay 只定义 2 档 |
| 页面渐变**写死 hex** | 5 套调色板对页面底色完全无效；同一 `#DDE4ED` 抄了 4 份 | `bg_page_gradient.xml:7-8` 等 |
| 调色板**没有入口** | 5 套 palette 是死代码，用户永远只能用海洋蓝 | `setPaletteIndex` 无 UI 调用 |
| 无 `windowLightStatusBar` 声明 | 状态栏条带与页面渐变之间有一条色带 | `themes.xml:6-7`，全仓 0 命中 |

### 1.3 实拍基线的 8 个"第一眼"问题

| # | 现象 | 截图 |
| --- | --- | --- |
| 1 | 浅色课表格子：白字压在浅粉/浅绿/浅紫上，几乎读不出 | `C01_schedule_light` |
| 2 | 1 节/2 节的短格子塞 4 行文字，第 4 行被硬裁（"东体育馆"只剩上半截） | `C01_schedule_light` |
| 3 | 时间轴贴屏幕最左 1.5dp，"08:00" 顶到边；未配置节次显示 `--:--` | `C01_schedule_light` |
| 4 | 课表菜单内容约 900dp，被 70% 屏高封顶 → "外观"整组在折叠线以下，且滚动条/渐变全关 | `F02_menu_light` |
| 5 | 学期色块：未导入学期是灰粉色空块，已导入是"太阳+山"插画（所有学期一模一样），右侧第 6 个被硬裁 | `F02_menu_light` |
| 6 | 底部导航「课表」标签后跟一个小 ⇄ 角标，语义不可发现 | `C01_schedule_light` |
| 7 | 顶部大日期恒为"今天"，翻到第 12 周时顶栏与页内日期栏互相矛盾 | 代码 `ScheduleFragment.kt:477` |
| 8 | 主题页只有"浅色/深色"两张卡 + 一个开关，页面 2/3 是空白，5 套配色无处可选 | `B02_theme_picker_dark` |

---

## 2. 全局问题总览（按"用户感知强度"排序）

### 🔴 A. 可读性（最高优先，几乎全在浅色模式）

1. **课程格子文字对比度不达标**（实测表见 §4.1）。浅色 9 色全部 1.48–2.77:1；深色 4.69–9.06 但绿色仅 2.66、橙色 3.71。
2. **第四行文字被裁**：`TextView` 无 `maxLines`/`ellipsize`，卡片高度 40–120dp 固定，文字只靠 `addView` 自然溢出后被 clip。
3. **9sp/8sp 字被当作正文**：`text_micro(9sp)`、`text_chip_room(8sp)` 只服务小组件，但小组件里它们承担课名与徽标，低于 M3 最小 `labelSmall(11sp)`。
4. **10sp 课名 + 10sp 网格标签**：`text_label` 被同时用于课程名（信息主体）与时间轴（辅助），同一刻度承担两种角色。
5. **数字不用等宽**：全 App 只有 2 个 widget 用了 `tnum`；成绩/绩点/学分/时间未对齐。

### 🟠 B. 排版与栅格

6. **左右边距三套**：一级页 20dp（工具/我的）、设置页 16dp、绑定页 24dp；工具页内部再分 24/20/16 三条线。
7. **顶栏四种语言**：工具/我的 52dp+20sp 居中主色；课表 wrap_content+12sp 周次+18sp 日期（左对齐）；二级页 56dp+18sp；`dialog_update` 20sp 左对齐。
8. **同一语义 4 套卡片样式**：`ProfileSection`(20dp/1dp elevation) / `ScheduleSectionCard`(16dp/描边/0 引用) / `AppSectionCard`(24dp/0 描边) / `DetailSheetCard`(20dp)；列表项又是 16dp+描边。
9. **同一弹层两种标题**：课表菜单标题居中，课表外观面板标题左对齐（同一个 `view_schedule_appearance_controls`）。
10. **关于页 4 行有分割线、我的页 11 行无分割线**；关于页行无图标容器，与我的页图标列不对齐。
11. **时间轴 32dp/年列 32dp 都卡在"刚好装下"**：`2026` 余 2.5dp，`08:00` 余 4dp，系统字体放大即折行；`setPadding(4,4,4,4)` 写的是 **px** 不是 dp。
12. **课程卡圆角是 `cornerRadius = 14f`（px）**≈5.3dp，与"14dp"的意图差 3 倍；同一 App 里色棒 2dp/课程块 5dp/行卡 12dp/容器 16dp/分组卡 24dp。

### 🟡 C. 信息架构与层级

13. **课表菜单 900dp 内容 vs 0.7×屏高封顶**：最需要调的"外观"永远在折叠线以下，且滚动无任何暗示（`scrollbars=none` + `overScrollMode=never`）。
14. **学期选择是"装饰驱动"**：迷你风景插画不携带信息；6 个学期里有 3 个是空色块；点空色块会静默切学期 + 联网导入 + 重置到第 1 周。
15. **课程详情最多 5 张同权重卡片**，其中 3 张只装 1 行（教室/周次/学分），主信息反而被稀释。
16. **成绩页信息缺口**：列表不显示绩点（核心字段），不及格条目名被 `match_parent` 挤出屏幕；"平均学分绩点"在成绩页算本学期、在绩点页算全学期却同名。
17. **空状态各写各的**：工具页 72dp 图标 + 主文案；成绩/考试/绩点页只有一行 14sp 灰字，无图标无 CTA。
18. **11 个工具里 7 个点了只弹"功能开发中"**，外观与可用项完全一致，没有"即将上线"标识。
19. **小组件中心一页只展示一款**，2/3 屏幕空白 + 底部三个点 + 右下"?"，标题还出现两遍（chip 与分段标题重复）。

### 🔵 D. 交互与反馈

20. **Toast 暗区（功能性缺陷）**：`AppToast` 挂在 Activity content 上，而所有弹层是独立 Dialog 窗口。
    → 在课表菜单里输 `999dp` 点应用，弹窗关闭 + 提示被遮罩压住 → 用户只看到"什么都没发生"。
21. **滑块可读性**：轨道 20dp 比拇指 18dp 还粗，拇指"埋在"轨道里；`labelBehavior=gone` + `isTickVisible=false`，拖 18 个离散周次时没有任何数值反馈（唯一的"第 N 周"被手指盖住）。
22. **周次滑块边界崩溃风险**：`valueTo = totalWeeks`（可低到 1）配 `valueFrom = 1`，M3 Slider 在 `valueTo <= valueFrom` 时抛异常。
23. **假 affordance 三连**：菜单/详情的拖拽条是纯装饰（不能拖），只有外观面板能拖；三处都没有关闭按钮；"本周"静态 chip 与"回到本周"按钮长得一样，一个能点一个不能。
24. **热区 <48dp 约 19 处**：刷新/菜单圆钮 32dp、头像 32dp、回到本周 40dp、chip ≈28dp、色块 28dp、解绑文字行 ≈41dp、关于页行 ≈45dp、"?" 40dp。
25. **开关用平台 `Switch`**（5 处）+ 关态拇指写死纯白，"关"在浅底上几乎看不出；不是 M3 `MaterialSwitch`。
26. **自动切换/时间选择行整行不可点**，只有开关/右侧箭头可点；开关也没有 `labelFor`，TalkBack 不播报关联标签。
27. **无障碍成组缺失**：chip 只用背景色表达选中（无 `isSelected`/`stateDescription`）、状态文本无 `liveRegion`、loading 遮罩未屏蔽读屏、装饰箭头未标 `no`。

### ⚪ E. 体系与工程化

28. **调色板链路断在最后一步**：5 套 palette 存在但无入口；渐变/导航栏/小组件卡面都写死 hex（同一 `#DDE4ED` 抄 4 份、`#0D1117` 抄 4 份）。
29. **死资源**：`activity_color_theme_picker.xml`（孤立的"卡片配色"页）、`ScheduleSectionCard`、`SectionTitle`、`WidgetTheme.kt`、6 个 `m3_*` 占位色、`dialog_color_picker.xml`、`dialog_background_preview.xml`、`item_week_page.xml`、`item_course.xml`。
30. **硬编码**：字面量文案 ≈300 处（无 `@string`）；`#20FFFFFF` 分割线（浅色下不可见）；3 处代码里写死 15sp 绕过字号刻度；`30sp` 倒计时写死在 widget。
31. **重复实现**：成绩页/考试页两份逐行相同的加载状态机；学期色块两套参数（58dp/3dp vs 54dp/2.5dp）；4 个小组件页布局 90% 相同。
32. **高亮当日/底部留白**等"增强显示"开关埋在外观卡片底部，默认关闭。

---

## 3. 设计语言 2.0 提案（Design Language）

> 原则：**保留现有风格，补齐规则**。以下 token 都是可直接落地到 `dimens.xml` / `colors.xml` / `themes.xml` 的值。

### 3.1 颜色：一条链 + 两套角色

**链路（解决"抄 hex"）**
```
调色板 palette(0..4, light|dark)
   └─→ surface 容器族 5 档（补全 Lowest/Low/Container/High/Highest）
   └─→ 页面渐变（从 surface/primary 派生，不再写死 hex）
   └─→ 导航条 / loading 遮罩 / 小组件卡面（引用同一 token）
   └─→ 课程色（9 色固定，派生 container + onContainer 成对）
```

**surface 容器族补齐（关键修复）**

| 角色 | 浅色（以海洋蓝为例） | 深色 |
| --- | --- | --- |
| surfaceContainerLowest | `#FFFFFF` | `#0F1216`（比页面略深，用于"我的/工具"卡片） |
| surfaceContainerLow | `#F4F6FB` | `#151A21` |
| surfaceContainer | `#F0F4FA` | `#1A1C1E` |
| surfaceContainerHigh | `#EAEEF4` | `#242629` |
| surfaceContainerHighest | `#E3E8F0` | `#2E3135` |

> 深色卡片与页面底色当前只差 ~10 灰阶（#0F0D13 vs #182230），靠"页面偏蓝"才勉强看出层次。
> 补齐后卡片用 `Lowest` 同时给 **1dp `colorOutlineVariant` 描边**（深色下唯一可靠的边界），浅色不加描边。

**语义色（解决"红=四种意思"）**
| token | 用途 | 浅色 | 深色 |
| --- | --- | --- | --- |
| `semantic_positive` | 通过、绩点上升、已导入 | `#1E8E3E` | `#7ED694` |
| `semantic_negative` | 挂科、绩点下降、危险操作 | `#B3261E` | `#FF5449` |
| `semantic_warning` | 需补考、时间待定、弱网 | `#B26A00` | `#FFB74D` |
| `semantic_info` | 进行中、提示 | `?attr/colorPrimary` | `?attr/colorPrimary` |

**课程色：从"半透明叠底 + 白字"改为"成对 container/onContainer"**

| 模式 | 填充 | 文字 | 描边 | 实测对比度 |
| --- | --- | --- | --- | --- |
| 浅色·现状 | 课程色 50% α | `#FFFFFF` bold | 白 50% | **1.48–2.77** ❌ |
| 浅色·方案A（推荐） | `18% 课程色 + 82% 白`（奶油底） | 课程色 × 0.50（同色系深字） | 课程色 12% α、1dp | **5.16–10.35** ✅ |
| 浅色·方案B（备选） | 课程色 100% | `#FFFFFF` | 无 | 3.1–5.6（绿/橙仍不达标）❌ |
| 深色·方案A | 课程色 × 0.45（压暗、保留色相） | `#FFFFFF` | 课程色 × 0.7、1dp | **6.65–14.68** ✅ |

> 方案 A 就是 Google Calendar / Notion Calendar 的做法：**浅底深字**。视觉上更"纸质课表"，
> 且彻底摆脱"浅色彩底 + 白字"这种天生不达标的组合。
> 实现只是 `ThemeManager` 里两个纯函数（小组件也能复用同一套）：
> ```kotlin
> fun containerOf(c: Int, dark: Boolean) =
>     if (dark) scaleColor(c, 0.45f) else blendARGB(c, Color.WHITE, 0.82f) // 18% 课程色
> fun onContainerOf(c: Int, dark: Boolean) =
>     if (dark) Color.WHITE else scaleColor(c, 0.50f)
> ```
> 上表数值已对 9 色逐一实算：最差一组（绿）浅色 5.16 / 深色 7.32，均满足 AA 4.5。

### 3.2 字体

| token | 现值 | 提案 | 用途 |
| --- | --- | --- | --- |
| text_display | 24sp | 24sp | 页面超大数字（绩点、倒计时） |
| text_headline | 20sp | 20sp | 一级页标题、Hero 标题 |
| text_title | 18sp | 18sp | 二级页标题、分组标题 |
| text_subtitle | 16sp | 16sp | 主要正文、按钮 |
| text_body | 15sp | 15sp | 设置项名、正文 |
| text_body_small | 14sp | 14sp | 次要说明 |
| text_caption | 12sp | 12sp | 值、辅助说明、chip |
| text_label | 10sp | **11sp** | 课程格内文字、角标（原 10sp 太小） |
| text_micro | 9sp | **10sp**（仅 widget） | 小组件课名 |
| text_chip_room | 8sp | **10sp**（并入 text_label） | 小组件教室行 |

规则：
- **App 内最小字号 11sp**；小组件最小 10sp。8/9sp 一律取消。
- 所有数值（成绩、绩点、学分、时间、倒计时、课程数）加 `fontFeatureSettings="'tnum'"` + 等宽。
- 课程格子内文字分级：名称 `text_label`+bold+行高 1.15；教室/教师 `10sp` + 80% onColor，名称与元信息之间留 1dp。
- 行高统一：正文 1.45，标题 1.25，格子内 1.15。

### 3.3 间距 / 圆角 / 阴影 / 描边

```
space_xs 4   space_sm 8   space_md 12   space_lg 16   space_xl 20   space_2xl 24   space_3xl 32
radius_xs 4（色棒/徽标）  radius_sm 8（输入框/格子）  radius_md 12（行卡）
radius_lg 16（组件卡）    radius_xl 24（页面分组卡/弹层顶角）   radius_full 999（胶囊）
elevation_0 0   elevation_1 1dp（卡片）   elevation_2 3dp（浮层）
stroke_card = 1dp colorOutlineVariant（深色必开，浅色可关）
```

- **页面横向边距统一 20dp**（一级页已 20dp，二级页从 16dp 收到 20dp；绑定页从 24dp 收到 20dp）。
- **卡片内边距统一 16dp**；行内垂直 12dp（≥ 48dp 行高）。
- 圆角收敛：`2/5/8/12/14/16/18/20/24/28` → 只留 `4/8/12/16/24/999`。
- 课程格子圆角 `14f px` → **8dp**；去掉浅色白描边，改用 onColor 8% 描边（可关）。

### 3.4 页面骨架（解决"顶栏四种语言"）

```
[ 状态栏透明 + 页面渐变延伸 ]
┌ 顶栏 52dp ────────────────────────────┐
│ 左：返回/搜索 40dp  中：标题 20sp 主色 bold  右：动作 40dp×N │
└──────────────────────────────────────┘
   内容区 padding 20dp，卡片间距 16dp
```
- 一级页：标题居中主色 20sp；二级页：返回 40dp + 标题居中主色 20sp（**当前 18sp → 20sp**）。
- 课表页特殊：顶栏左对齐（`第 12 周 · 大二下` 12sp 次级 + `11/3–11/9` 或页内当前周日期 18sp 主色），右侧刷新/菜单 40dp。
- 所有顶栏高度 `52dp`（`?attr/actionBarSize` 在部分 ROM/大字体下会变，用固定 52dp + `minHeight` 兜底）。

### 3.5 组件规范

| 组件 | 现状问题 | 规范 |
| --- | --- | --- |
| Chip | 28dp 高、selected 只用底色 | 视觉 32dp + 48dp 热区；selected=`primaryContainer`+`onPrimaryContainer`+`isSelected` |
| Switch | 平台 Switch、关态拇指死白 | `MaterialSwitch`；行整行可点 + `labelFor` |
| Slider | 轨道 20dp > 拇指 18dp，无刻度/气泡 | 轨道 4dp、拇指 20dp、`labelBehavior=withinBounds`、离散值开刻度 |
| 图标容器 | 48 方/40 圆/56 圆 三种底 3 色 | 统一 **40dp 圆 + `colorSurfaceContainerHigh` 底 + 24dp 描边图标 + 主色** |
| 空状态 | 4 个页面 4 套 | 统一 `EmptyStateView`：64dp 图标(α .35) + 标题 16sp + 说明 13sp + 主按钮（可选） |
| Toast | 顶部堆叠 3 张、无操作位、弹层内不可见 | 顶部最多 1 张（合并）+ 可选 action；弹层内改**内联 banner** |
| Sheet | 圆角/标题/关闭各写各的 | `SheetStyle`：24dp 顶角 + `surfaceContainer` + 居中 18sp 标题 + 48dp 关闭钮 + 拖拽条（统一可拖） |
| Dialog | 20dp + 写死 `#E6FFFFFF` | `DialogStyle`：20dp + `surfaceContainerHigh` + 18sp 标题 |

### 3.6 图标规范

- 统一 **24dp 描边式**（`Material Symbols Outlined` 风格），线宽一致；去掉实心人像（`ic_mtrl_person`/`ic_mtrl_badge`）。
- **一图一义**：`ic_mtrl_school` 现在同时表示"教室查询/当前学期/品牌"；`ic_mtrl_schedule` 同时表示"学期周数/时间表设置" → 拆开。
- 装饰性箭头/角标标 `importantForAccessibility="no"`。
- 未上线工具用"角标 + 降饱和"表达，不与可用项混淆。

---

## 4. 六个专题改版方案（含具体数值）

### 4.1 专题一：课程格子 2.0 —— 全 App 收益最高的一次改版

**问题重述（实测）**

浅色模式，白字压在"课程色 50% α 叠在 #DDE4ED 上"：

| 课程色 | 叠后底色 | 白字对比度 |
| --- | --- | --- |
| 绿 #1DE9B6 | rgb(125,231,209) | **1.48** |
| 橙 #FF9100 | rgb(238,186,118) | **1.76** |
| 粉 #FA6278 | rgb(236,163,178) | **2.01** |
| 紫 #A375FF | rgb(192,172,246) | **2.01** |
| 浅蓝 #2196F3 | rgb(127,189,240) | **2.01** |
| 蓝 #2979FF | rgb(131,174,246) | **2.25** |
| 深橙 #FF3D00 | rgb(238,144,118) | **2.36** |
| 红 #FF1744 | rgb(238,125,152) | **2.62** |
| 琉璃 #005CAF | rgb(110,160,206) | **2.77** |

AA 需 4.5（正文）/3.0（大字）。**9/9 全部不达标。**

**方案**

1. **颜色**：改为成对 `container/onContainer`（§3.1 方案 A 的两个纯函数）：
   浅色 `container = 18% 课程色 + 82% 白`、`onContainer = 课程色 × 0.50`；
   深色 `container = 课程色 × 0.45`、`onContainer = #FFFFFF`。9 色实算对比度 5.16–10.35（浅）/ 6.65–14.68（深），全部 ≥4.5。
   - 视觉效果：浅色是"奶油底 + 同色系深字"，深色是"压暗色块 + 白字"，与系统日历一致。
   - 描边：`onContainer` 12% α、1dp（替换现在的 `#80FFFFFF`，后者在浅色下就是"发光的白边"）。
2. **文字分级（按格子实际高度自适应）**：

   | 格子高度 | 显示内容 | 字号 |
   | --- | --- | --- |
   | < 56dp | 课名，2 行截断 | 11sp bold |
   | 56–88dp | 课名 2 行 + 教室 1 行 | 11sp + 10sp |
   | 88–120dp | 课名 3 行 + 教室 + 教师 | 11sp + 10sp + 10sp |
   | ≥ 120dp | 课名 3 行 + 教室 + 教师 + 周次 | 同上 |

   实现：`maxLines` + `ellipsize=end`，**必须**；名称与元信息用两个 `TextView`（现在是一个 `TextView` 拼 `\n`，无法差异化截断）。
3. **布局**：`padding 4dp→(6,4)`，名称居中对齐保留；角标 `+N` 从 20dp 提到 24dp 且热区 40dp。
4. **圆角**：`14f px` → `8dp`。
5. **非本周弱化**：非当前周的课程块整体 `alpha 0.86`（现状完全没有弱化）。

**收益**：一眼可读、长课名不再吃掉教室、任何格子高度都不再裁字。
**改动面**：`WeekPagerAdapter.buildCourseCells` 约 80 行 + `ThemeManager` 增加 `containerOf/onContainerOf`。

### 4.2 专题二：课表菜单 2.0 —— 从"一屏塞满"到"分层可发现"

**问题**：内容 ≈ 900dp，被 `0.7 × 屏高`（约 640dp）封顶 → 约 260dp（≈40%）藏在折叠线以下；
`scrollbars=none` + `overScrollMode=never` 把唯一的暗示也关掉了；外观 7 个控件同一权重。

**方案（三步，零重写）**

```
┌ 拖拽条（可拖，高度可 60%/85%/全屏 三档吸附）
│ 课表菜单            ✕  ← 右上 48dp 关闭（新增）
│ 大二下 · 2024-2025 学年 第二学期
├ 周次 ───────────────────────────
│ [第 8 周]              [回到本周]
│ ──────●─────────────────────  ← 轨道 4dp / 拇指 20dp / 拖动气泡"第 8 周"
│ 1  2  3 … 18  （细刻度点，当前周高亮）
├ 学期 ───────────────────────────
│ [▣ 大二下 11门] [▣ 大二上 12门] [▣ 大一上 14门] [⤓ 大一下] →
│   ↑ 色块内显示课程数 / 未导入显示下载图标（不再用无信息的风景插画）
├ 课表外观                    ▾  ← 默认折叠为一行摘要
│ 格子 68dp · 教室 教师 · 边框跟随课程
└────────────────────────────────
        ↑ 底部渐隐 24dp + 细滚动条 + "上滑查看更多"
```

- **高度**：上限 `0.7 → 0.85`，拖拽条接真实手势（复用 `ScheduleAppearanceSheet` 的现成实现），三档吸附。
- **外观折叠**：默认一行摘要（"格子 68dp · 教室 教师 · 边框跟随课程"），点开展开现有控件（`view_schedule_appearance_controls` 原样复用）。
- **卡内分组**：展开后在卡内补 3 个小标题（格子 / 边框 / 整张课表），把 4 条看不见的 0.5dp 发丝线换成有语义的分组。
- **学期块**：`SemesterSceneryView` 改为"课程数徽标 + 学期首字色块"；未导入用 `⤓` 图标 + `outline` 描边；点击前用内联提示"将联网导入该学期课表"。
- **滚动暗示**：`NestedScrollView` 底部渐隐 24dp + `scrollbars="vertical"`（细条）+ 内容末尾 12dp 呼吸。
- **反馈**：菜单内的错误不再走 `AppToast`，改为菜单顶部内联 banner（`colorErrorContainer` + 12sp + 可重试）。
- **标题**：`课表菜单` 居中 18sp + 右侧 40dp `✕`；外观面板从"我的"进入时复用同一 spec（去掉左对齐特例）。

**收益**：首屏同时看到"周次 + 学期 + 外观入口"；滚动可发现；两个入口视觉一致。

### 4.3 专题三：主题系统闭环 —— 让 5 套调色板真正"存在"

**现状**：`ThemeOverlay.Palette0..4` 已就绪，`ThemeManager.setPaletteIndex()` 无 UI 调用 → 永远是海洋蓝；
页面渐变写死 `#C4D4E4→#DDE4ED`，切到青绿/暖棕时页面仍是冷蓝灰。

**方案**

1. **入口**：`我的 → 主题` 改造为：
   ```
   浅色/深色 两张预览卡（保留，但换成"当前配色"渲染）
   自动切换（跟随系统 / 定时）  ← 保留，行整行可点
   配色方案  ● 紫  ● 蓝  ● 青绿  ● 暖棕  ● 玫红   ← 新增，5 个色点，选中打勾
   ```
   点选色点 → `ThemeManager.setPaletteIndex` + `recreate()`（预览卡实时用新配色重绘，`AppearanceActivity` 已有 `applyFixedCardTheme/buildPreview` 现成能力）。
2. **渐变派生**：删掉 `bg_page_gradient.xml` / `bg_schedule_gradient.xml` 两份写死资源，改为基类里用 `GradientDrawable` 按 palette 生成：
   `start = blend(primary, surface, 0.72)`，`end = surfaceContainerLowest`（浅色）/ `start = blend(primary, surface, 0.18)`，`end = surface`（深色）。
   导航条颜色、loading 遮罩、小组件卡面统一引用同一 token。
3. **系统栏**：显式 `windowLightStatusBar` / `windowLightNavigationBar`（浅 true / 深 false）+ `windowBackground = 渐变 end 色`，消除状态栏色带。
4. **补齐 surface 容器 5 档**（见 §3.1），并同步 `M3Palettes.kt` 字段，避免 XML 与代码取色分叉。
5. **CI 检查**：扫描所有 `?attr/colorXxx`，要求 5 套浅色 + 5 套深色 overlay 全部定义（防"只定义一半"再发生）。

### 4.4 专题四：工具页 / 我的页 2.0

**工具页**
- 顶栏：左右各 40dp 圆钮（搜索/头像）+ 8dp 外距，标题真正居中；去掉与搜索框重复的顶栏搜索钮（或让搜索框点击展开）。
- Hero 卡：高度 `150dp 固定 → wrap_content + minHeight 132dp`；标题/副标题加 `maxLines` 与右侧 76dp 避让；暗色渐变末端不再压黑（沿用 §3.1 派生）。
- 图标网格：抽 `ToolTile`（48dp 圆角方 14dp + 24dp 描边图标 + 13sp 标签），三类图标统一风格；未上线项加"即将上线"角标 + 图标降饱和。
- 双列卡：`GridLayout` weight+margin 溢出 → 换 `LinearLayout`（每行 2 个 `weight=1` + 12dp 间隔），消除右边缘裁切；左边缘统一到 20dp。
- 空搜索：保留 72dp 图标但 `alpha 0.3 → 0.5`，文案回显关键词，加"清除搜索"文字按钮。

**我的页**
- 卡片行统一：40dp 圆图标容器 + 主标题 15sp + 副标题 13sp；**5 行以上卡片加 1dp 分割线（缩进 40+16dp）**。
- 占位值与真实值区分：空值用 `colorOutline` + "去设置"轻量动作；无数据行（学期周期/周数）直接隐藏，有数据再出现。
- 危险操作：「解绑教务账号」从卡片内裸红字 → 复用已有 `DangerButton`，单列"危险操作"分组，与常规设置视觉隔离。
- 「课表外观」行尾 chevron 换成 `ic_arrow_forward`（它是跨页签动作，不是进入下一页）。
- 未绑定时隐藏/置灰「个人信息」行。
- 底部：修 fragment `fitsSystemWindows` 与导航条 inset 双计，去掉多出的一条系统栏高度空白。

**设置二级页**
- 顶栏统一 52dp + 20sp 居中主色；字体大小页删掉与 toolbar 重复的第二个"字体大小"标题。
- 主题页补分组标题（`AppSectionHeader`）+ 配色方案区（§4.3），把 2/3 空白利用起来。
- 时间表设置：13 个独立卡 → 1 张卡 13 行（分割线），"开始 08:00 - 08:45 结束" → `08:00 → 08:45` 两列对齐；空时间显示"—"。
- 关于页：`ScrollView` 高度 `match_parent → 0dp+weight` 修滚动裁切；4 行补 40dp 图标容器；`版本 0.0.0` 接 `BuildConfig.VERSION_NAME`；更新红点与 chevron 合并为一个元素。

### 4.5 专题五：弹层 / 对话框 / 反馈体系统一

1. **Toast 暗区（P0）**
   - 短期方案：`StyledDialog` 增加可拦截关闭的校验回调；非法输入时**不关窗**，在输入框下方显示内联错误（`colorError` 12sp）+ 输入框描边转错误色。
   - 中期方案：`ToastManager` 支持"弹层宿主"——弹层打开时提示渲染进弹层自己的容器（顶部 banner），而不是 Activity content。
   - 验收：菜单里输 `999` 点应用 → 能看到"已取范围 120dp"；断网切学期 → 能看到错误原因。
   - **落地（2026-11-05）**：中期方案已实现。`ToastManager` 维护"窗口宿主栈"（Activity content → 最上层弹层 DecorView），`AppToast.registerDialogHost(dialog)` 在弹层 `show()` 前登记；弹层打开时容器搬进弹层窗口，关闭后自动搬回。已接线 12 处弹层（课表菜单 / 课程详情 / 外观面板 / 取色器 / 学期滚轮 / StyledDialog / SelectionDialog / 成绩详情 / 导出预览 / 主题-自动切换面板 / 更新弹窗 / 重叠课程选择器），普通场景行为不变。debug 变体提供 `tools/ToastZOrderProbeActivity` 做层级自检（`--es mode dialog|sheet|plain`）。
2. **Sheet 规范**：`SheetStyle`（24dp 顶角 + `surfaceContainer` + 居中 18sp 标题 + 48dp 关闭 + 可拖拖拽条），三处底部弹层统一；删掉"假拖拽条"。
3. **Dialog 规范**：`DialogStyle`（20dp + `surfaceContainerHigh` + 18sp 标题）；分割线 `#20FFFFFF` → `colorOutlineVariant` 60%；按钮高度 ≥48dp；宽度规则统一（`min(屏宽×0.88, 400dp)`）。
4. **课程详情**：5 张卡 → 2 张卡（主信息卡吸收时间/教师/教室/周次/学分；动态信息卡可折叠默认 3 行）；标签列宽统一（`wrap_content + minWidth 64dp`）；QQ 行热区 ≥48dp。
5. **AppToast**：顶部最多 1 张（合并计数），加左侧 3dp 语义色竖条；错误类带"重试"action；`maxLines 3 → 4`（LONG 时 6）；修进度条两端被圆角切掉的问题。
6. **无障碍**：chip/开关/遮罩/状态文本按 §2-27 清单逐条补齐。

### 4.6 专题六：数据页排版（成绩 / 绩点 / 考试）

1. **成绩条目结构 bug**：`tv_course_name` 的 `match_parent` → `0dp + weight=1 + maxLines=1 + ellipsize`（P0）。
2. **补绩点**：右列改成「成绩 / 绩点 3.7」两行或"成绩大 + 绩点小"，并统一 `tnum`。
3. **文案口径**：成绩页标"本学期平均学分绩点"，绩点页标"全部学期平均学分绩点"；"需补考"从 XML 移到 Adapter（按 `examNature` 区分 需补考/需重修/不及格）。
4. **指标卡**：4 列横排 → 2×2 网格（大字号下不挤压），标签 10sp → 12sp，不及格数用 `semantic_negative`。
5. **筛选 chip**：排序从"被挤出的第 5 个 chip"改为列表头右侧图标按钮（↑↓ 切换 + 高亮）；chip 补 `isSelected`。
6. **空状态 / 失败态**：统一 `EmptyStateView`；失败常驻错误文案 + "重试"按钮；"请先绑定教务账号"做成可点跳转。
7. **考试状态**：按状态给容器色底（未开始 primaryContainer / 进行中 tertiaryContainer / 已结束 surfaceVariant），已结束只淡化元信息，不整卡 `alpha .72`。
8. **模拟试算**：加列头（学分/成绩）、非法输入就地报错、学分列固定 80dp、清空加撤销。

---

## 5. 落地路线图（建议 3 个迭代 + 1 次清理）

> 每个阶段都能独立出包验证，且**不改变产品结构**。

### 迭代 1 · 可读性与排版硬伤（约 2–3 天，收益 80%）

| # | 事项 | 改动面 | 验收 |
| --- | --- | --- | --- |
| 1.1 | 课程格子 container/onContainer + 文字分级 + 8dp 圆角 | `WeekPagerAdapter` ~80 行、`ThemeManager` +2 方法 | 9 色 × 浅/深 共 18 组对比度 ≥4.5（脚本） |
| 1.2 | 时间轴 32dp → 38dp、左内距 8dp、`--:--` → 只显示开始时间 | `WeekPagerAdapter` 3 处、`item_date_header.xml` | 像素校验：文字左缘 = 8dp，1.3× 字号不折行 |
| 1.3 | 恢复网格线（`gridColor` 透明 → `outlineVariant` 8%）+ 当日列淡底默认开 | `WeekPagerAdapter:336` | 深色下可见、浅色不脏 |
| 1.4 | 顶栏日期跟随"当前浏览周"，悬停/翻页同步 | `ScheduleFragment.updateDateTimeHeader` | 翻到第 12 周顶栏显示第 12 周范围 |
| 1.5 | 课表菜单：滚动渐隐 + 滚动条 + `0.7 → 0.85` + 关闭按钮 | `bottom_sheet_schedule_menu.xml`、`ScheduleFragment` | 首屏能看到"外观"入口 |
| 1.6 | Toast 暗区：`StyledDialog` 校验不关窗 + 内联错误 | `StyledDialog.kt`、`ScheduleAppearanceControls.kt` | 输 `999` 能看到错误 |
| 1.7 | 修 3 个真 bug：成绩条 `match_parent`、关于页 ScrollView、周次滑块 `valueTo<=1` 崩溃 | 3 文件小改 | 回归无裁切/无崩溃 |
| 1.8 | 热区 <48dp 清单（19 处）一次性补齐 | 各布局 `minHeight` + `TouchDelegate` | TalkBack 逐项走访 |

### 迭代 2 · 设计语言统一（约 3–4 天）

| # | 事项 | 验收 |
| --- | --- | --- |
| 2.1 | 新增 `dimens.xml` 间距/圆角/描边令牌，替换 16/20/24 三套边距 | 5 个一级/二级页左右边距一致（像素校验 = 20dp） |
| 2.2 | 顶栏统一 52dp + 20sp；`AppToolbarTitle` 18→20sp | 三页顶栏等高、标题同号 |
| 2.3 | 卡片样式收敛 4→2（`Card.Section` / `Card.Sheet`），删死样式 | 全 App 圆角只剩 4/8/12/16/24/999 |
| 2.4 | 补齐 `surfaceContainer*` 5 档 + 深色卡片描边 | 深色下 5 套 palette 都能看出卡片边界 |
| 2.5 | 主题系统闭环：配色方案入口 + 渐变按 palette 派生 + 系统栏声明 | 切 5 套配色，页面底/导航条/遮罩同步变化 |
| 2.6 | 图标规范：统一 24dp 描边 + 40dp 圆容器，拆一图多义 | 视觉抽查 10 个页面 |
| 2.7 | Sheet/Dialog 两套 spec + 关闭入口 + 真拖拽 | 3 个弹层标题/圆角/关闭一致 |
| 2.8 | 小组件统一：8/9sp → 10sp、周视图对比度、近日课程深色适配、badge 夜间色 | 6 款 × 浅/深 12 张截图逐张评审 |

### 迭代 3 · 体验增强（约 3 天）

| # | 事项 |
| --- | --- |
| 3.1 | 课表菜单分层：外观默认折叠 + 卡内分组小标题 |
| 3.2 | 学期块信息化（课程数徽标 + 未导入下载图标 + 导入前提示） |
| 3.3 | `EmptyStateView` 统一 4 处空态（工具搜索/成绩/考试/绩点） |
| 3.4 | 成绩页指标补绩点、口径标注、2×2 指标网格、排序按钮化 |
| 3.5 | 工具页网格改 LinearLayout 双列，消除右边缘裁切；占位项"即将上线" |
| 3.6 | 关于页/时间表设置/字体大小三页的重复标题与卡片重排 |
| 3.7 | 绑定教务：聚焦描边 + `setError` + 步骤行抽 `include`（240 行 → 60 行） |

### 迭代 4 · 清理（约 1 天）

删：`activity_color_theme_picker.xml`、`ScheduleSectionCard`、`SectionTitle`、`WidgetTheme.kt`、6 个 `m3_*` 占位色、
`dialog_color_picker.xml`、`dialog_background_preview.xml`、`item_week_page.xml`、`item_course.xml`、`bg_schedule_gradient.xml`；
合并：成绩/考试两份状态机 → `PageStatusController`；4 个小组件页布局 → 1 套数据驱动；
抽：用户可见文案进 `strings.xml`（≈300 处，可分批）。

---

## 6. 验收与度量（怎么证明改好了）

1. **对比度脚本**：遍历课程色 9 × 浅/深 × 文字色，输出每对 `(container, onContainer)` 的 WCAG 比值，CI 断言 ≥4.5。
2. **像素校验**（沿用 `AGENT.md` 的 dump + PIL 手法）：
   - 文字左缘/右缘与容器内边距 = 设计值（±1px）；
   - 三页卡片左边缘 x 坐标一致；
   - 课表菜单首屏是否露出"外观"入口。
3. **字体放大回归矩阵**：系统字体 1.0 / 1.3 / 2.0 × 5 个关键页，检查：不裁字、不重叠、时间轴不折行、指标卡不挤压。
4. **深色 + 5 套 palette 截图集**：每个组合 3 张（课表/我的/菜单），共 30 张留档 `develop/`。
5. **无障碍抽查**：TalkBack 走一遍课表/我的/成绩页，确认 chip 选中态、开关标签、状态播报、热区。
6. **回归清单**：改完后用 `AGENT.md` 的流程出包 → 装模拟器 → 逐页截图对比 baseline。

---

## 7. 明确"不改什么"（避免过度设计）

| 保留 | 理由 |
| --- | --- |
| 渐变页面底 + 24dp 大圆角卡 | 已成体系，是品牌识别；只需让它跟随 palette |
| 胶囊底部导航（圆形激活底 + 回弹） | 有辨识度，动画克制；只把 32dp 圆钮提到 40dp、去掉不可发现的 ⇄ 角标 |
| 主色加粗居中标题 | 与 M3 常见做法不同但清爽；统一到 20sp 即可 |
| AppToast 的合并/队列策略 | 纯 Kotlin 可测、逻辑完备；只需解决"弹层内不可见"与堆叠数量 |
| 课表网格用 Canvas/代码构建 | 性能与滚动同步（日期栏随页滚）都靠它，不动架构 |
| 9 色课程色相 | 用户已建立"某课固定某色"的记忆，只改明度/饱和与字色 |

---

## 8. 风险与取舍

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 课程格子改"浅底深字" | 视觉变化大，老用户需适应 | 先做 A/B：设置里加"经典/柔和"两套格子配色，默认柔和；保留回退 |
| 补齐 `surfaceContainer*` | 深色下大量卡面色值会变（略亮/略暗一档） | 一次性全页面截图比对，只调 token 不改布局 |
| 调色板接通 | 5 套配色 × 浅深 = 10 种组合需要全部走查 | 先接 2 套（紫/青绿）验证链路，再放开 5 套 |
| 边距 16→20dp | 部分页面内容变窄，长文案可能折行 | 同步检查 `maxLines`，必要时缩短文案 |
| 字号 10→11sp | 课程格子可容纳行数减少 | 与文字分级一起改，格子高度 <56dp 时只显示课名 |
| 大范围删死资源 | 可能删到"计划中但暂未接入"的功能 | 删除前 grep 三处（代码/布局/Manifest）确认 |

---

## 附录 A：证据索引（问题 → 文件:行）

| 结论 | 证据 |
| --- | --- |
| 课程格白字 + 50% α | `ui/adapter/WeekPagerAdapter.kt:387,402-403` |
| 格内文字无 maxLines | `WeekPagerAdapter.kt:459-467` |
| 卡片高度固定 + 14f px 圆角 | `WeekPagerAdapter.kt:414,420` |
| 时间轴 32dp + px padding | `WeekPagerAdapter.kt:366,499-528`；`item_date_header.xml:22,63-70` |
| 网格线关闭 | `WeekPagerAdapter.kt:336` |
| 顶栏日期恒为今天 | `ScheduleFragment.kt:477-478` |
| 菜单 0.7 封顶 | `ScheduleFragment.kt:140,952-964` |
| 滑块 range 崩溃边界 | `ScheduleFragment.kt:688-703` |
| 菜单滚动条/overscroll 关闭 | `bottom_sheet_schedule_menu.xml:35-40` |
| Toast 挂 Activity content | `ui/feedback/ToastManager.kt:74-83` |
| StyledDialog 无条件 dismiss | `ui/component/StyledDialog.kt:255-258` |
| 成绩条 match_parent | `res/layout/item_grade.xml:62-67` |
| 需补考写死 | `res/layout/item_grade.xml:106` |
| 关于页 ScrollView match_parent | `res/layout/activity_about.xml:25-27` |
| `colorSurfaceContainerLowest` 未定义 | `res/values/styles.xml:106` vs `themes.xml:15-147` |
| 渐变写死 hex | `drawable/bg_page_gradient.xml:7-8`、`drawable-night/…:7-8` |
| 调色板无入口 | `ui/theme/ThemeManager.kt:49-79`（无 UI 调用点） |
| 工具页三条左边缘 | `ui/screen/tools/ToolsAdapter.kt:80,91,104` |
| 字号档位 0.86–1.06 | `service/UiScaleManager.kt:36-42` |
| 小组件 8/9sp | `res/values/dimens.xml:23-24`、`widget_week_view.xml:71,76` |
| 小组件夜间缺 badge 色 | `res/values-night/widget_colors.xml`（仅 12 项） |
| 近日课程硬编码白卡 | `res/layout/widget_upcoming_days.xml:7,24,32,56,91` |

## 附录 B：本轮实拍截图（1080×2400，已归档 `develop/UI优化方案截图/`）

| 文件 | 内容 |
| --- | --- |
| `C01_schedule_light.png` | 课表 · 浅色（对比度/裁字/时间轴实证） |
| `A01_schedule_light.png` | 课表 · 深色 · 总课表关闭态 |
| `F02_menu_light.png` | 课表菜单 · 浅色（折叠线以下内容） |
| `B01_appearance_dark.png` | 课表外观面板 · 深色（标题左对齐 + 底部裁切） |
| `A02_tools_dark.png` / `A03_profile_dark.png` | 工具 / 我的 · 深色 |
| `B02_theme_picker_dark.png` | 主题页 · 深色（2/3 空白 + 无配色入口） |
| `E01_font_size.png` | 字体大小（重复标题 + 0.96× 标准档） |
| `E02_time_table.png` | 时间表设置（13 张独立卡） |
| `E03_widget_center.png` | 小组件中心（单页 + 空白） |
| `E04_about.png` | 关于（无图标行 + 0.0.0 版本号） |
| `D01_grade_query.png` | 成绩查询（空态 + 指标卡 + 排序 chip） |
| `D03_gpa.png` | 绩点计算 |

---

## 附录 C：落地记录（26 个版本）

> 每个版本都构建 → 装模拟器 → 截图验证；改前/改后对比图归档在
> `develop/UI优化方案截图/对比/`（该目录 gitignore）。下表按提交顺序。

| 版本 | 提交 | 落地条目 | 关键验证 |
| --- | --- | --- | --- |
| v1 | `c7e3bbd` | §4.1 课程格子配色 + 文字分级 | 对比度 1.48–2.77 → 5.16–10.35:1 |
| v2 | `1392f3e` | §4.1 时间轴 / 网格线 / 顶栏日期 | 时间轴内距 1.5dp→7dp，翻周顶栏跟随 |
| v3 | `59364f3` | §4.2 菜单关闭 / 钉住标题 / 滚动提示 + 学期块信息 | 首屏可见内容 +40% |
| v4 | `333766a` | §4.5 滑块比例、校验不关窗、弹窗底板不透明 | 拖动气泡 + 内联报错 |
| v5 | `7fe8980` | §2 三个真 bug + 热区放大 | 成绩条裁切、关于页滚动、28→40dp |
| v6 | `d9b3bd9` | §4.2 菜单内联错误 + 切学期失败回滚 | 失败后学期/周次自动还原 |
| v7 | `42236f0` | §3.3–3.4 间距 / 圆角 / 顶栏令牌 | 顶栏 52dp + 20sp 全站一致 |
| v8 | `7ed88d3` | §3.1 surface 容器 5 档 + 深色卡片描边 | 深色分组结构清晰 |
| v9 | `6eeaaf0` | §4.3 5 套配色闭环 + 渐变派生 + 系统栏 | 青绿 / 玫红切换全站生效 |
| v10 | `fd09baf` | §4.5 Sheet / Dialog 规格统一 | 外观面板标题+关闭、分割线主题色 |
| v11 | `c249470` | §4.4 小组件对比度 / 字号 / 夜间 token | 周视图 1.31–2.77 → 6.65–11.14:1 |
| v12 | `44d5da7` | §3.6 图标一图多义拆分 | 教室查询 / 学期周数独立图标 |
| v13 | `300d7d2` | §4.4 工具页双列卡裁切 + 占位标识 | 右列不再被裁 |
| v14 | `9b3dd04` | §4.5 空状态统一（图标 + 文案 + 动作） | 失败态可重试、可去绑定 |
| v15 | `dfde52a` | §4.6 成绩指标口径 + 列表补绩点 + 2×2 | 两页口径不再混淆 |
| v16 | `2d919fe` | §5 清理死资源与死代码 | -12 文件 / -310 行 |
| v17 | `a6d176d` | §4.2 菜单外观默认折叠 | 菜单一屏装得下 |
| v18 | `68f4219` | §4.5 更新弹窗版式重做 | 说明区按内容高度、按钮 ≥48dp |
| v19 | `5347401` | §3.6 关于页行图标 | 与我的页同一套行语言 |
| v20 | `ca1cb75` | §4.4 绑定页步骤化 + 校验贴字段 | 457 → 233 行 |
| v21 | `8f5289d` | §5 状态机合并 PageStatusController | -100 行重复、零视觉回归 |
| v22 | `c04b19a` | §6 字体 1.5× / 2.0× 回归 | 2.0× 不折行 / 不连字 / 不重叠 |
| v23 | `e25185f` | §4.4 时间表 13 卡合一 | 一屏可读 |
| v24 | `e073b04` | §5 文案迁移第一批（工具页 + 导航） | 零视觉变化 |
| v25 | `d0f6657` | §5 文案迁移第二批（我的 + 关于） | 零视觉变化，strings 77 条 |
| v26 | `c9df87f` | 收尾体检 + 落地记录 | 8 图走查（附录 D） |
| v27 | `518da50` | §5 文案迁移第三批（课表/菜单/课程详情） | 零视觉变化，29 条 |
| v28 | `ed08d7f` | §5 文案迁移第四批（成绩/考试/绩点） | 零视觉变化，19 条 |
| v29 | `614189d` | §5 文案迁移第五批（弹窗/占位）+ 小组件中心垂直居中 | 空白分布到卡片上下 |
| v30 | `5cd4d0f` | §6 小圆钮触控热区 48dp | bounds 实测 121px = 48dp，视觉不变 |
| v31 | `415e734` | 删两个时间表死布局 + 外观面板标题入资源 | 文案迁移收尾 |

### 未完成项（明确记录，不是遗漏）

1. **文案迁移**：已完成 5 批、strings 168 条；剩余 39 处均为**布局占位内容**
   （08:00 / 一二三 / 第1节 / 40 / 120 等），运行期会被真实数据覆盖，
   或属于 widget_* 的 RemoteViews 示例数据，不适合也不需要迁。
2. ~~**48dp 完整化**~~ ✅ v30 已处理：`CircleIconButton` 本身做成 48dp，
   视觉圆用 InsetDrawable 内缩回 40dp（"看不见但可点"多出的 8dp 就是热区），
   课表刷新/菜单、回到本周、工具页头像统一走这条路。实测 bounds 121px = 48dp。
3. **课程格子方案 B**（浓色块 + 白字）作为可选项保留，未实现——当前采用方案 A。

## 附录 D：收尾体检走查表（`V26_收尾体检_8图.png`）

| # | 页面 | 检查点 |
| --- | --- | --- |
| 1 | 课表 · 2.0× 字号 | 时间轴不折行、日期只显示日号、顶栏单行 |
| 2 | 课表 · 深色 | 格子对比度、网格线可见但克制 |
| 3 | 工具 · 浅色 | 双列卡等宽、占位项「即将上线」、图标语义 |
| 4 | 我的 · 浅色 | 三组卡片、图标圆底、行高一致 |
| 5 | 课表菜单 · 折叠态 | 一屏装下、关闭入口、学期色块带课程数 |
| 6 | 成绩查询 · 空态 | 图标 + 文案 + 去绑定动作 |
| 7 | 关于 · 浅色 | 四行图标、按钮风格一致 |
| 8 | 关于 · 深色 | 卡片 1dp 描边、图标与文字对比 |

---

## 附录 E：验收结论

**范围**：迭代 1–4 全部条目落地，31 个本地提交（`develop` 分支，未推送远端）。

**逐项验收**

| 验收项 | 方法 | 结果 |
| --- | --- | --- |
| 课程格子可读性 | 模拟器采样 + WCAG 公式，9 色 × 浅/深 | 1.48–2.77 → **5.16–10.35:1**（浅）/ 6.65–14.68（深），全部 ≥ AA |
| 周视图小组件可读性 | 桌面实拍采样 | 1.31–2.77 → **6.65–11.14:1** |
| 大字号不破版 | 1.0× / 1.3× / 2.0× 三档实拍 | 2.0× 下时间轴不折行、日期不连字、顶栏不挤行 |
| 5 套配色可用 | 逐套切换实拍（蓝/青绿/玫红等） | 页面底、标题、今日胶囊、导航条、遮罩全部跟随 |
| 深色层级 | 深色实拍 | 卡片 1dp 描边 + surface 容器 5 档，分组边界清晰 |
| 弹层可用性 | 菜单/外观面板/对话框实拍 | 关闭入口、钉住标题、滚动提示、内联报错、失败回滚齐全 |
| 触控热区 | uiautomator bounds 实测 | 小圆钮 121px ≈ **48dp**（视觉圆仍 40dp） |
| 全页面冒烟 | 依次进入 12 个页面 + 3 个弹层 | **0 次 FATAL EXCEPTION** |
| 文案可维护性 | strings.xml 清点 | 11 → **168 条**；剩余 39 处为布局占位内容 |
| 代码规模 | 行数/文件对比 | 死代码 -310 行、状态机 -100 行、绑定页 -224 行 |

**课程格子配色**：按用户要求**已回退到 183ed47 的原始实现**
（课程色 50% 半透明叠底 + 纯白字 + 2dp 白描边、单 TextView 拼行、14f 圆角、2dp 格间距）。
v1（配色重做）与 v33（改回浓色块）的课程格子部分已撤销，
`ThemeManager` 中相关的成对派生函数一并移除；
v2 里与「课表整体」相关的部分（时间轴宽度/网格线/日期栏/顶栏日期）**保留**。

遗留：原始实现的白字对比度仍是 1.48–2.77:1（低于 AA 4.5）。
若要重新处理，可用方案 B（按亮度求解的最低暗度，实测 4.60–6.67:1）；
当前状态是用户的明确选择。

**验证材料**：`develop/UI优化方案截图/对比/`，每版一组改前/改后图（共 41 组）。
