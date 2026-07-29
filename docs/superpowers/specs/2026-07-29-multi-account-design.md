# 多账号系统重构设计

**日期**: 2026-07-29
**版本**: DEV_0.2
**分支**: develop/DEV_0.1

---

## 1. 目标

将当前单账号应用重构为多账号架构。每个账号绑定一个教务系统凭据，所有数据按账号隔离，支持多账号切换/解绑。

## 2. 核心决策汇总

| 决策点 | 结论 |
|--------|------|
| 账号定义 | 教务系统登录凭据（username/password/session） |
| 多账号 | 支持多个，切换时自动从教务刷新 |
| 年级推算 | `StudentProfile.grade` + `schoolingLength` 硬推算大一~大四对应学年，TODO 异常状态 |
| Session 过期 | 账号级"无感重载"开关：开→自动重登；关→提示用户手动登录 |
| 初始化 | 个人信息 → 逐个学期课表，部分保存（空学期不算失败） |
| 数据库 | 全量 Room，按 `account_id` 外键隔离 |
| 闹钟 | 仅活跃账号课程提醒，切换时重建 |
| 活跃账号标记 | SharedPreferences 全局 `active_account_id` |
| 小组件 | 跟随活跃账号 |
| 数据迁移 | 不做迁移（未发版，直接破坏兼容） |
| 重复绑定 | 直接切换为该账号 |
| 解绑 | 调教务 `logout()` + 级联清除本地数据 |

## 3. 设置分类

### 全局设置（SharedPreferences，跟账号无关）

主题、M3 调色板索引、字体大小、课程卡片透明度、显示非本周课程、非本周课程透明度、视图模式、自定义背景图片路径、悬浮球位置 X/Y、视图状态、自动切换周、默认闹钟提前分钟数、节假日隐藏课程、更新提醒开关、上次检查更新日期、日志清理日期

### 账号级设置（Room `account_settings` 表，绑定 `account_id`）

无感重载开关、当前学期、默认显示周、闹钟启用、学期开始日期、总周数、自定义学期列表

## 4. 数据库设计

### 4.1 新增表

#### `accounts`

```sql
CREATE TABLE accounts (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    username    TEXT NOT NULL UNIQUE,
    password    TEXT NOT NULL,
    session     TEXT,           -- JSON: cookie + token + 过期时间
    bound_at    INTEGER,        -- 绑定时间戳 (ms)
    last_active INTEGER         -- 最后活跃时间戳 (ms)
);
```

#### `account_profiles`

```sql
CREATE TABLE account_profiles (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id  INTEGER NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    profile_json TEXT NOT NULL,          -- StudentProfile 完整 JSON (Gson 序列化)
    grade_year  INTEGER NOT NULL,        -- 入学年级，如 2024
    study_years INTEGER NOT NULL DEFAULT 4, -- 学制，如 4
    saved_at    INTEGER NOT NULL         -- 保存时间戳
);
```

#### `semester_infos`

```sql
CREATE TABLE semester_infos (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id      INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    academic_year   TEXT NOT NULL,       -- "2024-2025学年"
    term_code       TEXT NOT NULL,       -- "3" (AUTUMN/第一学期) 或 "12" (SPRING/第二学期)
    grade_label     TEXT NOT NULL,       -- "大一" / "大二" / "大三" / "大四"
    start_date      INTEGER,             -- 学期开始日期毫秒时间戳（从课表反推）
    total_weeks     INTEGER,             -- 总周数
    is_data_loaded  INTEGER DEFAULT 0,   -- 是否已成功拉取课表
    UNIQUE(account_id, academic_year, term_code)
);
```

#### `account_settings`

```sql
CREATE TABLE account_settings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id  INTEGER NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    settings_json TEXT NOT NULL           -- 账号级设置 JSON
);
```

### 4.2 修改现有表

#### `courses`

```sql
-- 新增列
ALTER TABLE courses ADD COLUMN account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE;
-- 移除旧数据后执行
-- ALTER TABLE courses ADD COLUMN account_id INTEGER NOT NULL DEFAULT 0;
```

注意：现有 Room 数据库未发版，直接在 schema 中新增 account_id，`fallbackToDestructiveMigration()` 会处理重建。

### 4.3 级联关系

```
accounts (1)
  ├── (1) account_profiles
  ├── (1) account_settings
  ├── (N) semester_infos
  └── (N) courses

解绑 → DELETE FROM accounts WHERE id = ? → CASCADE 删除所有关联数据
```

## 5. 架构改动

### 5.1 新增类

| 类 | 职责 |
|----|------|
| `AccountRepository` | 管理 accounts/profile/semesters/settings 四张表，切换活跃账号，解绑账号 |
| `SessionManager` | 管理账号的教务 Session（cookie/登录态），提供 login/relogin/logout |
| `AccountSettingsRepository` | 管理 account_settings 表的读写 |

### 5.2 改造现有类

| 类 | 改动 |
|----|------|
| `CourseDataManager` | 构造函数加 `accountId: Long`；所有查询/写入按 account_id 过滤；切换账号时内部重建 StateFlow；`getInstance(context)` 改为 `getInstance(context, accountId)` |
| `SettingsManager` | 全局设置方法不变（继续 SharedPreferences）；新增账号级 setter/getter 委托给 `AccountSettingsRepository` |
| `JwxtAccountManager` | 从 `object` 单例改为 `class`，支持多账号凭据管理，融入 `AccountRepository` |
| `AlarmService` | `registerAllCourseNotifications()` 只读当前活跃账号的课程；切换账号时取消所有旧闹钟 → 重建新闹钟 |
| `CourseViewModel` | 新增 `initFromJwxt(accountId)` 和进度状态 `Flow<InitProgress>`；未绑定时返回空数据 |
| `App.kt` | 初始化 AccountRepository；监听活跃账号切换 → 重建 AlarmService + CourseDataManager |

### 5.3 关键数据流

```
绑定教务 → login → 存 account →
  1. profile() → 存 account_profiles + 解析 grade/schoolingLength
  2. 推算学期列表 (大一秋 ~ 大四春)
  3. 每个学期: schedule().personal(year, term) → convertScheduleResponse()
     → 存 courses(account_id) + 反推 start_date/total_weeks → 存 semester_infos
  4. 进度条: completedSteps / totalSteps (1 + 学期数)
  5. 完成 → 设为活跃账号 → 加载课表 → 注册闹钟

切换账号 → 更新 active_account_id → 取消旧闹钟 →
  初始化流程（跟绑定一样，每次都重新拉数据）→ 加载课表 → 注册新闹钟

解绑账号 → logout() API → DELETE account CASCADE → 闹钟取消 →
  若解绑的是活跃账号 → 选另一个账号为活跃 → 没有则进入未绑定状态
```

## 6. UI 交互设计

### 6.1 未绑定状态

- 课表页/工具页/个人中心页 → 半透明蒙版覆盖内容区域
- 蒙版内容："尚未绑定教务账号，绑定后即可查看课表" + "前往绑定"按钮
- 底部导航栏正常显示，可切换 tab

### 6.2 绑定流程

1. 进入 BindJwxtActivity → 输入学号 + 密码
2. 点击"登录并绑定" → 调用 `login()`
3. 成功 → 存 account 记录 → 跳转 ScheduleActivity
4. 触发初始化 → 显示进度条：`正在同步教务数据... ████████░░ 40% 正在获取个人信息...`
5. 进度步骤：个人信息 (1) + 每个学期课表 (N，最多 8)
6. 完成 → 课表正常展示，蒙版消失

### 6.3 已绑定状态

- 设置页："绑定教务"按钮变成"账号信息"卡片
  - 显示：姓名 (学号) | 学院 · 专业
  - 右侧：**[切换]** 按钮 + **[解绑]** 按钮（红色）
- **切换账号**：弹出账号列表（已绑定列表 + 添加新账号），选择后走初始化流程
- **解绑账号**：红色按钮 → 二次确认弹窗 "此操作不可撤销" → 确认后调 logout + 清除数据

### 6.4 学期选择

- 学期下拉框只显示数据库中该账号已有的 `semester_infos`（大一~大四 每个学期）
- 选择后加载该学期的课表数据
- 每个学期右侧有 **🔄 刷新按钮**，旋转 loading 图标，重新请求该学期课表
- 学期信息按年级推算顺序排列：大一·第一学期 → 大一·第二学期 → ... → 大四·第二学期
- **刷新全部学期按钮**：按顺序重新获取大一~大四所有学期课表，刷新期间显示 loading 图标，单个学期完成即更新进度

### 6.5 个人信息页

- 已绑定：展示缓存 StudentProfile，不刷新，永久存储
- 未绑定：蒙版 + 引导跳转绑定

### 6.6 无感重载开关

- 位置：账号级设置
- 开启：Session 过期时自动用存储的密码重新登录，用户无感
- 关闭：提示"登录已过期，请重新登录"，跳转登录页；进入 app 不自动刷新课表，需手动点刷新

## 7. 闹钟策略

- 闹钟仅针对活跃账号的课程
- 切换账号：取消所有旧闹钟 → 新账号课程闹钟全部重建
- 解绑账号：取消该账号的所有闹钟
- 开机广播：恢复活跃账号的闹钟
- 每日刷新（4/8/12/16/19 点）：仅刷新活跃账号

## 8. 小组件

- 三种小组件（今日课程/下课倒计时/近日课程）跟随活跃账号
- 活跃账号切换时通过广播触发小组件刷新
- 未绑定状态：小组件显示空状态或提示文字

## 9. 年级推算算法

```
输入：StudentProfile.grade (如 "2024"), StudentProfile.schoolingLength (如 "4")

year = grade.toInt()          // 2024
n    = schoolingLength.toInt() // 4

大一 = "${year}-${year+1}学年"     // "2024-2025学年"
大二 = "${year+1}-${year+2}学年"   // "2025-2026学年"
大三 = "${year+2}-${year+3}学年"   // "2026-2027学年"
大四 = "${year+3}-${year+4}学年"   // "2027-2028学年"

每个年级包含两个学期：
  - 第一学期 (AUTUMN, code="3")
  - 第二学期 (SPRING, code="12")

总共生成最多 n×2 个学期的查询任务

TODO: 休学/转专业等异常状态的特殊处理
```

## 10. 错误处理

- 初始化时某学期接口返回空/无数据 → 标记 `is_data_loaded=0`，不算失败
- 初始化时某学期网络错误 → 跳过，标记 `is_data_loaded=0`，用户可后续手动刷新
- login 失败 → 显示错误信息，不创建 account 记录
- Session 过期 → 根据"无感重载"开关决定行为
- 重复绑定已存在的账号 → 直接切换为该账号（不重复创建）

## 11. TodoWrite

1. 创建数据库表（migration 或 destructive fallback）
2. 实现 AccountRepository + SessionManager
3. 改造 CourseDataManager → account_id 过滤
4. 改造 SettingsManager → 全局/账号分流
5. 改造 JwxtAccountManager → 多账号
6. 改造 CourseViewModel → 初始化流程 + 进度状态
7. 实现未绑定蒙版 UI
8. 实现绑定/切换/解绑 UI
9. 实现学期选择 + 刷新 UI
10. 改造 AlarmService → 活跃账号闹钟
11. 改造小组件 → 跟随活跃账号
12. 实现无感重载开关
