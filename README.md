# 西大课栈

> 面向广西大学的 Android 课表应用，基于 [Schedule](https://github.com/Yngu196/Schedule) 二次开发。

[![GitHub release](https://img.shields.io/github/v/release/laoin114514/GxuScheduleAPP?include_prereleases)](https://github.com/laoin114514/GxuScheduleAPP/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

---

## 致谢

本项目基于 **[Yngu196/Schedule](https://github.com/Yngu196/Schedule)** 二次开发而来。

由衷感谢 **[Yngu196](https://github.com/Yngu196)** 在 Schedule 项目上的持续迭代与开源分享，为本项目提供了坚实的基础。

---

## 功能

- **课表显示**：周视图 / 总课表（点击「课表」页签切换）；周次滑杆、快速回到本周、今日高亮、课程重叠角标
- **课表导出**：工具页一键把当前周课表导出为整页 PNG（与 App 内课表 UI 同一套渲染），支持预览、保存到相册
- **教务一键导入**：绑定广西大学教务系统（正方 v5）后自动获取个人信息、推断当前学期并导入课表，支持手动刷新
- **成绩查询**：按学期查询，支持全部 / 必修 / 选修 / 不及格筛选、绩点汇总与单科详情
- **考试安排**：从教务系统导入并展示各科考试时间、地点
- **绩点计算**：汇总全部学期成绩计算平均学分绩点，支持模拟试算
- **学期与时间表**：学期起止日期、总周数、当前学期选择；时间段编辑、每天节数设置、恢复默认
- **外观设置**：浅色 / 深色 / 跟随系统、自定义时间自动切换、五档字体缩放；课表格子高度 40–120dp、边框颜色、显示教室 / 教师、底部留白、高亮当日
- **桌面小组件**：6 款组件（今日课程 2×2 / 4×2、下课倒计时、近日课程、一周课程、下一门课），可在应用内一键添加或从系统小组件列表添加，深浅色自适应
- **应用内更新**：从自有更新服务检查新版本，应用内下载并引导安装
- **本地存储**：Room 数据库，已导入的课表离线可看

> 暂未支持：课前通知提醒、在 App 内手动新增 / 编辑 / 删除课程、数据备份与恢复、多校适配。工具页中的教室查询、图书馆、校园卡、校园地图、食堂菜谱、番茄钟、快速笔记目前为占位入口，点击会提示「功能开发中」。

---

## 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Kotlin / Java（教务客户端模块） |
| 架构 | MVVM |
| UI | Material 3, ViewBinding, ViewPager2, Navigation |
| 数据库 | Room |
| 网络 | OkHttp（教务客户端，无 Retrofit） |
| 后台 | AlarmManager（桌面小组件刷新） |
| 构建 | R8 代码压缩 + 资源压缩 |
| 异步 | Kotlin Coroutines |

- **最低支持**：Android 8.0 (API 26)
- **目标版本**：Android 15 (API 35)

---

## 构建

```bash
./gradlew assembleDebug      # Debug 版
./gradlew assembleRelease    # Release 签名版（需配置签名文件与密码）
```

Release 签名读取 `wakeup-schedule-key.jks`，密码来自 `gradle.properties` 的
`RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD`。

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
