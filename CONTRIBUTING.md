# 贡献指南

感谢您对本项目感兴趣！欢迎提交 Pull Request 或 Issue。

## 如何贡献

### 提交 Issue
- 提交 Bug 请描述清楚问题现象、环境信息（Android 版本等）
- 提交功能建议请描述清楚需求背景和使用场景

### 提交代码
1. Fork 本项目
2. 创建您的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交您的更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 代码规范

- 使用 Kotlin 标准编码规范
- 保持代码简洁易懂
- 添加必要的注释
- 确保提交前通过构建
- 提交前跑一遍 `./gradlew testDebugUnitTest`（CI 也会跑）；涉及数据库迁移时还要跑 `connectedDebugAndroidTest`（见下）；改布局后同样要跑，`ColorStateListAsDrawableTest` 会拦截资源兼容问题（见下）
- **影响 UI / 资源 / 深色模式的改动**：除 `testDebugUnitTest` 外必须跑 `connectedDebugAndroidTest`——CI 的 `androidTest` job 在 **API35 + API29** 双机型矩阵执行，其中 `MainActivityNightModeTest` 会强制夜间模式真实拉起主界面（深色资源/布局异常直接失败）

## 资源兼容性约定（minSdk 26）

- **布局的 Drawable 类属性禁止引用颜色状态列表**：`android:background` / `src` / `foreground` /
  `drawableLeft/Right/Top/Bottom/Start/End` 不能写 `@color/X`，只要 `res/color/`、`res/color-night/`
  等 color 系列目录下存在 `X.xml`。
  原因：API ≤ 29（Android 8.0–10）的 `ResourcesImpl.loadDrawableForCookie` 用**字面前缀**
  `res/color/` 判断颜色资源，夜间变体路径 `res/color-night-v8/...` 不匹配 → 被当作普通 Drawable XML
  解析 → `Resources$NotFoundException`，Activity inflate 阶段直接闪退，**运行时 try/catch 接不住**。
  （真实事故：v0.5.9 在 Android 10 + 深色模式启动必崩，该写法自 v0.3.17 起存在。）
- 需要「随深浅色 / 换配色变化的纯色背景」：在代码里用 `ColorDrawable(...)` 设置
  （本项目 `PageBackground.bottomColor()` 即此做法），或使用真正的 `res/drawable*/` 形状/选择器资源。
  `values/colors.xml` 里的纯色值不受影响。
- tint 类属性（`app:tint` / `app:itemIconTint` / `android:backgroundTint` 等）使用颜色状态列表是
  **正确**用法，不受影响。
- 防回归（两层）：
  - 静态：`app/src/test/java/com/cherry/wakeupschedule/res/ColorStateListAsDrawableTest.kt`
    扫描所有 layout，违规引用直接测试失败；
  - 运行期：`app/src/androidTest/java/com/cherry/wakeupschedule/MainActivityNightModeTest.kt`
    强制夜间模式真实拉起 `MainActivity`（inflate 主界面 + 起始课表页）；CI 在 **API35 + API29**
    双机型执行，兼容性回归至少覆盖 API29 深色模式（Android 15 开发机无法复现该类问题）。

## 数据库迁移约定

`app/schemas/` 是 Room 导出的结构快照，**必须提交**。当前迁移链为 2→18（共 16 条迁移，`LATEST_VERSION = 18`），改实体时按这个顺序走：

1. 改实体（新增非空字段要在实体上声明 `defaultValue`，迁移 SQL 同步写 `NOT NULL DEFAULT …`）
2. 把 `AppDatabaseMigrations.LATEST_VERSION` 加 1（当前为 `18`）
3. 新增 `MIGRATION_N_N1` 并登记进 `AppDatabaseMigrations.ALL`
   （`ALL` 必须声明在所有 `MIGRATION_*` 之后，否则 Kotlin 初始化顺序会让它拿到 null）
4. 构建一次，把生成的 `app/schemas/**/<新版本>.json` 一起提交
5. **给每步迁移补 androidTest 用例**：在
   `app/src/androidTest/java/com/cherry/wakeupschedule/model/AppDatabaseMigrationTest.kt`
   里为该版本加 `MigrationTestHelper.runMigrationsAndValidate(...)`，并断言旧数据行未丢、新列默认值正确
6. 跑两套测试：
   - `./gradlew testDebugUnitTest`：`AppDatabaseMigrationsTest` 与
     `AppDatabaseSchemaSnapshotTest` 校验版本号、迁移链连续性与快照完整性；
   - `./gradlew connectedDebugAndroidTest`：在真 SQLite 上**执行**迁移并逐列校验结构
     （需要模拟器/真机；CI 的 `androidTest` job 会在 **API35 + API29** 双机型矩阵跑，含深色启动冒烟）

注意：

- `AppDatabase` 只对**版本 1**（上游 WakeUp 课表遗留）保留破坏性重建，
  其余版本缺迁移会直接抛异常。少写一步迁移不会再静默清空用户课表。
- **identity hash / 相同版本不同结构**：Room 把 schema 指纹存在库里的 `room_master_table`，
  打开数据库时与生成代码里的期望值比对。**版本号相同但结构不同 → 打开即抛
  `Room cannot verify the data integrity` 闪退**；迁移执行了但结构与实体不一致 →
  `Migration didn't properly handle: …`。两者都发生在用户首次打开数据库时，
  而 JVM 单测不执行 SQL —— 所以迁移必须靠 androidTest 验证。
- 已发布版本的迁移 SQL 和 schema 快照是历史事实，**不要修改**；要修只能新增一个迁移。
- **历史漂移只能自愈**：若发现历史迁移已造成结构漂移（例如 3→4/4→5 漏写 NOT NULL 导致六列可空），
  **不要改已发布迁移**，而在链尾新增一条「自愈」迁移：按当前标准结构重建表 → `COALESCE` 把历史 NULL
  收敛成实体默认值 → `INSERT SELECT` 整行搬迁 → `DROP` → `RENAME`（先 `DROP TABLE IF EXISTS` 临时表保证幂等）。
  参考 `MIGRATION_17_18`；`courses` 的标准建表 SQL 唯一来源是 `AppDatabaseMigrations.coursesCreateSql(tableName)`。
- 发布前确认 `git diff --exit-code app/schemas` 通过（快照没有未提交改动），
  且 `LATEST_VERSION` 与最新 JSON 的 `version` 一致、JSON 里的 `identityHash` 与生成代码里的期望哈希一致。

排查闪退与迁移历史缺陷的完整背景见 `docs/数据库迁移安全与闪退排查.md`。

## 问题反馈

- GitHub Issues: https://github.com/laoin114514/GxuScheduleAPP/issues
- 在线问卷（推荐）: https://wj.qq.com/s2/28554281/u3po/
