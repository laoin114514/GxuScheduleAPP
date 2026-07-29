# 西大课栈

> 基于 [Schedule](https://github.com/Yngu196/Schedule) 二次开发的 Android 课表应用。

[![GitHub release](https://img.shields.io/github/v/release/laoin114514/GxuScheduleAPP?include_prereleases)](https://github.com/laoin114514/GxuScheduleAPP/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

---

## 致谢

本项目是基于 **[Yngu196/Schedule](https://github.com/Yngu196/Schedule)** 二次开发而来。

由衷感谢 **[Yngu196](https://github.com/Yngu196)** 在 Schedule 项目上的持续迭代与开源分享，为本项目提供了坚实的基础。


---

## 新增功能

在 Yngu196/Schedule 基础上，西大课栈增加了以下改进：

- **桌面小组件增强** — 近日课程预览、可滑动课程列表、跨组件同步刷新
- **通知四重保障** — `setAlarmClock` + `WorkManager` + 前台服务 + 智能心跳，确保课前提醒不遗漏
- **节假日管理** — 内置法定节假日，支持自定义，自动隐藏课程和通知
- **个性化主题** — 6 套卡片配色 + 14 款背景色 + 自定义图片背景
- **权限引导** — 新手引导 + 各厂商电池优化设置指引
- **Material 3 重构** — 全新 UI 风格，深色/浅色模式

---

## 功能

- 课程表显示（周视图 / 今日视图）
- 教务系统课程导入（支持多校适配）
- 课程手动添加 / 编辑 / 删除
- 课前提醒通知
- 桌面小组件（今日课程 / 下课倒计时 / 近日课程 / 一周课程）
- 课程全览
- 数据备份与恢复

---

## 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Kotlin |
| 架构 | MVVM |
| UI | Material 3, ViewBinding |
| 数据库 | Room |
| 网络 | Retrofit2, OkHttp |
| 图片 | Glide |
| 后台 | WorkManager, AlarmManager |
| Excel | Apache POI |

- **最低支持**: Android 8.0 (API 26)
- **目标版本**: Android 15 (API 35)

---

## 构建

```bash
./gradlew assembleDebug      # Debug 版
./gradlew assembleRelease    # Release 签名版
```

---

## 下载

前往 [Releases](https://github.com/laoin114514/GxuScheduleAPP/releases) 页面下载最新 APK。

---

## License

本项目沿用原项目的 [Apache License 2.0](LICENSE)。

---

## 参考项目

- [Yngu196/Schedule](https://github.com/Yngu196/Schedule) — 本项目直接上游，基于其二次开发
- [WakeUp 课程表](https://github.com/YZune/WakeUpSchedule) — 最原始的安卓课表开源项目

