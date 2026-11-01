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
- 提交前跑一遍 `./gradlew testDebugUnitTest`（CI 也会跑）

## 数据库迁移约定

`app/schemas/` 是 Room 导出的结构快照，**必须提交**。改实体时按这个顺序走：

1. 改实体
2. 把 `AppDatabaseMigrations.LATEST_VERSION` 加 1
3. 新增 `MIGRATION_N_N1` 并登记进 `AppDatabaseMigrations.ALL`
4. 构建一次，把生成的 `app/schemas/**/<新版本>.json` 一起提交
5. 跑 `testDebugUnitTest`，`AppDatabaseMigrationsTest` 与
   `AppDatabaseSchemaSnapshotTest` 会校验版本号、迁移链连续性与快照完整性

注意：`AppDatabase` 只对**版本 1**（上游 WakeUp 课表遗留）保留破坏性重建，
其余版本缺迁移会直接抛异常。少写一步迁移不会再静默清空用户课表。

## 问题反馈

- GitHub Issues: https://github.com/laoin114514/GxuScheduleAPP/issues
- 邮箱: 2908451607@qq.com
