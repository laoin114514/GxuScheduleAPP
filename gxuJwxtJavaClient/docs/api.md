# 广西大学教务系统 API 文档（2026-07-31 实测修订版）

> 目标站点：`https://jwxt2018.gxu.edu.cn`（正方教务系统 v5 / zftal-ui-v5）
>
> 文中标注“实测”的只读接口使用学生账号进行了真实请求验证，返回结构来自 2026-07-31 的响应。
> 所有示例均已脱敏；条数、开放状态和业务数据只是验证时快照，不属于稳定接口契约。
>
> 验证状态说明：正文中的“实测”表示收到符合描述的成功响应；“前端脚本确认”仅表示路径和参数来自当前页面 JavaScript；选课、退课、确认等有副作用的写接口均未提交，不能视为端到端验证。

---

## 目录

1. [通用说明](#1-通用说明)
2. [会话与登录](#2-会话与登录)
3. [学生基本信息](#3-学生基本信息)
4. [课表相关](#4-课表相关)
5. [成绩相关](#5-成绩相关)
6. [考试相关](#6-考试相关)
7. [空闲教室](#7-空闲教室)
8. [选课与确认](#8-选课与确认)
8A. [选课（预选 N253511 / 正选 N253512）](#8a-选课预选-n253511--正选-n253512)
9. [学业与培养](#9-学业与培养)
10. [教学执行计划](#10-教学执行计划)
11. [网上上课地址](#11-网上上课地址)
12. [公共代码字典](#12-公共代码字典)
13. [其他（冷门，仅记录）](#13-其他冷门仅记录)

---

## 1. 通用说明

### 1.1 编码

- 登录页、模块页和已验证的 JSON 接口均使用 UTF-8；登录页响应头为 `text/html;charset=UTF-8`，原始字节可严格按 UTF-8 解码。
- 已验证的 JSON 数据接口通常返回 `application/json;charset=UTF-8`，中文正常。
- 不要强制按 GBK/GB2312 二次转码，否则会导致解码失败或乱码。客户端应优先遵循响应头，并在响应头缺失时尝试严格 UTF-8 解码。

### 1.2 Referer 规则（重要）

建议携带对应功能模块页的 `Referer`，因为部分模块会据此检查页面来源或功能权限；但它不是所有接口的硬性要求。实测个人信息和成绩查询在不携带 `Referer` 时仍能正常返回 JSON。遇到登录页、无权限页或空响应时，应同时检查会话、功能权限、必需参数和 `Referer`，不能只依据 HTTP 200 判断成功。

### 1.3 统一分页参数（jqGrid）

大部分列表接口用 POST + 表单，并带 jqGrid 标准分页参数：

| 参数 | 示例 | 说明 |
|---|---|---|
| `_search` | `false` | 固定 |
| `nd` | `1785499500000` | 时间戳(ms)，可省略 |
| `queryModel.showCount` | `100` | 每页条数 |
| `queryModel.currentPage` | `1` | 页码 |
| `queryModel.sortName` | 空 | 排序字段 |
| `queryModel.sortOrder` | `asc` | 排序方向 |
| `time` | `1785499500000` | 时间戳(ms)，可省略 |

典型分页响应如下；不同 Action 可能省略 `queryModel` 或增加其他分页字段，客户端应按实际 JSON 容错解析：

```json
{
  "items": [ ... ],
  "totalResult": 51,
  "currentPage": 1,
  "pageSize": 15,
  "showCount": 100,
  "totalCount": 51,
  "totalPage": 4,
  "queryModel": { ... }
}
```

### 1.4 学年学期编码

| 语义 | 编码 | 说明 |
|---|---|---|
| 学年 `xnm` | `2025` | 代表 2025-2026 学年 |
| 秋季学期 `xqm` | `3` | 第一学期（9月–次年1月） |
| 春季学期 `xqm` | `12` | 第二学期（2月–7月） |

实测：2025-2026 学年第 2 学期 = `xnm=2025&xqm=12`。

### 1.5 常用 ID 示例

| ID | 值 | 说明 |
|---|---|---|
| `xh_id` / `xh` | `<学号>` | 当前登录学生的学号 |
| `bh_id` | `<班级ID>` | 班级 ID |
| `njdm_id` | `2024` | 年级代码 |
| `zyh_id` | `<专业代码>` | 专业代码 |
| `jg_id` | `<学院代码>` | 学院代码 |
| `xqh_id` | `1` | 校区号（默认主校区） |

---

## 2. 会话与登录

### 2.1 获取登录页（拿 csrf token）

```
GET /jwglxt/xtgl/login_slogin.html
```

返回 HTML 登录页，从中提取：

```html
<input type="hidden" name="csrftoken" value="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx,xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"/>
```

> **注意**：csrftoken 的值是「UUID,32位去连字符UUID」的拼接（逗号分隔），提交时**整体**作为 `csrftoken` 值提交。已实测确认。

### 2.2 获取 RSA 公钥

```
GET /jwglxt/xtgl/login_getPublicKey.html?time=<毫秒时间戳>
```

返回 JSON：

```json
{
  "modulus": "base64...",
  "exponent": "base64..."
}
```

### 2.3 提交登录

```
POST /jwglxt/xtgl/login_slogin.html?time=<毫秒时间戳>
Content-Type: application/x-www-form-urlencoded

csrftoken=<登录页csrf>&language=zh_CN&yhm=<学号>&mm=<RSA加密后的密码>&ydType=
```

- 密码加密：**RSA/ECB/PKCS1Padding**，公钥为上面拿到的 modulus/exponent（Base64 解码后构造 `RSAPublicKey`），加密结果 Base64 编码。
- 成功：302 跳转到 `/jwglxt/xtgl/index_initMenu.html`（带 `jsdm=xs`）。
- 失败：302 跳回登录页，页面内可解析错误提示（`#tips`、`.error` 等）。

### 2.4 初始化会话（登录成功后调用）

```
GET /jwglxt/xtgl/index_initMenu.html?jsdm=xs&_t=<毫秒时间戳>&echarts=1
```

该页面用于建立学生角色的会话上下文。若登录 POST 自动跟随重定向，客户端通常已经访问过此 URL；关闭自动重定向时需要显式请求 `Location`。重复请求不是必需步骤。

### 2.5 会话过期检测

session 过期后，需要认证的数据接口通常会被重定向到登录页。OkHttp 自动跟随后拿到登录页 HTML，可用 `name="csrftoken"` + `name="yhm"` 两个特征同时存在来判断；同时记录最终 URL，避免把登录页的 HTTP 200 当作业务成功。

### 2.6 退出

```
GET /jwglxt/xtgl/login_logoutAccount.html
```

### 2.7 主菜单（模块清单）

```
POST /jwglxt/xtgl/index_cxMenuList.html
```

返回 JSON 数组（菜单树）。菜单项含 `name`、`gnmkdm`、`url`。

实测菜单树包含 37 个带中文名称的节点，`name`、`url` 与 `gnmkdm` 均可按 UTF-8 正常读取。下表列出部分功能模块（`url` 需补 `/jwglxt` 前缀）：

| gnmkdm | 模块 url | 功能 |
|---|---|---|
| N100801 | `/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html` | 查询个人信息 |
| N2151 | `/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html` | 个人课表查询 |
| N214505 | `/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html` | 班级课表查询 |
| N2155 | `/jwglxt/cdjy/cdjy_cxKxcdlb.html` | 查询空闲教室 |
| N2158 | `/jwglxt/kbcx/xskbqr_cxXskbqrIndex.html?doType=details` | 学生选课结果确认 |
| N255010 | `/jwglxt/xkcx/xkmdcx_cxXkmdcxIndex.html` | 选课名单查询 |
| N305005 | `/jwglxt/cjcx/cjcx_cxDgXscj.html` | 学生成绩查询 |
| N358105 | `/jwglxt/kwgl/kscx_cxXsksxxIndex.html` | 考试信息查询 |
| N1598 | `/jwglxt/rwlscx/wsskdzwh_cxWsskdzwhIndex.html` | 网上上课地址 |
| N105515 | `/jwglxt/xsxy/xsxyqk_cxXsxyqkIndex.html` | 学生学业情况 |
| N153540 | `/jwglxt/jxzxjhgl/jxzxjhck_cxJxzxjhckIndex.html` | 教学执行计划查看 |
| N253511 | `/jwglxt/xsxk/tjxkyzb_cxTjxkYzbIndex.html` | 学生选课（预选） |
| N253512 | `/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html` | 正选 |
| N1053 | `/jwglxt/fxgl/fxbm_cxXsfxbmIndex.html` | 缓考补考 |
| N1056 | `/jwglxt/cxbm/cxbm_cxXscxbmIndex.html` | 免修报名 |
| N104810 | `/jwglxt/tmgl3/xstmsq_cxXstmsqIndex.html` | 体育免测 |
| N401605 | `/jwglxt/xspjgl/xspj_cxXspjIndex.html?doType=details` | 学生评教 |
| N402505 | `/jwglxt/wjdcgl/wjdc_cxWjdcIndex.html` | 问卷调查 |
| N410510 | `/jwglxt/jxxxygl/jxxxfk_cxJxxxfkIndex.html` | 教学信息反馈 |
| N558020 | `/jwglxt/bysxxcx/xscjzbdy_cxXscjzbdyIndex.html` | 成绩总表打印 |

---

## 3. 学生基本信息

### 3.1 学生基本信息（综合，含学籍、培养、当前学期）

```
POST /jwglxt/xsxxxggl/xsxxwh_cxCkDgxsxx.html?gnmkdm=N100801
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=N100801
参数: 无（自动取当前登录学生）
```

返回 JSON 对象（实测 keys）：

```
bdh, bdzcbj, bh_id, byzx, csrq, cyNum, date, dateDigit, dateDigitSeparator, day,
has_xszp, jdNum, jg_id, jgpxzd, jlNum, ksh, kslbm, lym, month, mzm, njdm_id,
pageTotal, pageable, pyccdm, pyfaxx_id, pyfsdm, queryModel, rangeable, rxrq, rxzf,
sfzx, syd, totalResult, userModel, xbm, xh, xh_id, xjztdm, xlccdm, xm, xmpy, xnm,
xnmc, xqm, xqmc, xslbdm, xxnx, xz, year, zjhm, zjlxm, zkzh, zsjg_id, zsnddm,
zszyh_id, zyh_id, zzmmm
```

关键字段（示例值已脱敏）：

| 字段 | 含义 | 示例值 |
|---|---|---|
| `xh` / `xh_id` | 学号 | `<学号>` |
| `xm` | 姓名 | `<姓名>` |
| `xmpy` | 姓名拼音 | `<姓名拼音>` |
| `xbm` | 性别 | `<性别>` |
| `csrq` | 出生日期 | `<出生日期>` |
| `mzm` | 民族 | `<民族>` |
| `njdm_id` | 年级 | `2024` |
| `bh_id` | 班级名称 | `<班级名称>` |
| `zyh_id` | 专业名称 | `<专业名称>` |
| `jg_id` | 学院名称 | `<学院名称>` |
| `rxrq` | 入学日期 | `<入学日期>` |
| `xnm` / `xqm` | 当前学年/学期 | `2025` / `12` |
| `pyccdm` | 培养层次 | 本科 |
| `zjhm` | 身份证号 | 脱敏 |

### 3.2 班级列表

```
POST /jwglxt/xsxxxggl/xsxxwh_cxBjxxList.html?gnmkdm=N100801
Referer: <同上>
参数: xh_id=<学号>
```

返回 JSON 数组：

```json
[{"bh_id": "<班级ID>", "bj": "<班级名称>"}]
```

### 3.3 学年学期列表

```
POST /jwglxt/xsxxxggl/xsxxwh_cxXqm.html?gnmkdm=N100801
参数: xh_id=<学号>, xnm=2025
```

返回 JSON 数组，元素含 `dm`（学期编码）、`mc`（学期名称）。实测 2 个元素（对应第 1、2 学期）。

### 3.4 修读学年学期列表（当前版本不可用）

```
POST /jwglxt/xsxxxggl/xsxxwh_cxXxqm.html?gnmkdm=N100801
参数: xh_id=<学号>, xnm=2025
```

实测返回警告页：`请求的方法 cxXxqm 在对象 XsxxwhAction 中未定义`。该 URL 仅出现在前端已注释的旧代码中，不应作为可用接口调用。需要学期列表时使用 3.3 的 `cxXqm`。

### 3.5 宿舍楼列表

```
POST /jwglxt/xsxxxggl/xsxxwh_cxSsxList.html?gnmkdm=N100801
参数: xh_id=<学号>
```

返回 HTML（宿舍楼选择框）。

### 3.6 扩班资格

```
POST /jwglxt/xsxxxggl/xsxxwh_cxViewKbzg.html?gnmkdm=N100801
参数: xh_id=<学号>
```

返回 `application/json`，响应体是 JSON 字符串 `"1"` 或 `"0"`，不是布尔值或未加引号的纯文本。

### 3.7 等级考试成绩（CET-4/6 等）

```
POST /jwglxt/xsxxxggl/xsxxwh_cxDjksxx.html?gnmkdm=N100801
Referer: <同上>
参数: 标准分页 + xh_id=<学号>
```

返回 JSON（分页结构 `items`）。脱敏后的条目示例：

```json
{
  "items": [
    {
      "xh_id": "<学号>", "xm": "<姓名>", "xnm": "2024", "xqm": "3",
      "xnmmc": "2024-2025", "xqmmc": "1",
      "xmlbdm": "04", "xmlbmc": "大学英语四级考试", "xmmc": "大学英语四级考试",
      "cj": "<成绩>", "sftg": 1, "zkzh": "<准考证号>", "zsbh": "<证书编号>",
      "zjhm": "<证件号码>", "xz": "4", "xmdm": "1"
    }
  ],
  "totalResult": 0
}
```

关键字段：`xmlbmc`/`xmmc`（考试名称）、`cj`（成绩）、`sftg`（是否通过 0/1）、`zkzh`（准考证号）、`zsbh`（证书编号）、`xnm`/`xqm`（考试学年学期）、`zjhm`（身份证号）。

### 3.8 选课信息（历史选课记录）

```
POST /jwglxt/xsxxxggl/xsxxwh_cxXsxkxx.html?gnmkdm=N100801
参数: 标准分页 + xh_id=<学号>
```

返回 JSON 分页结构。实测 57 条。元素字段：`kcmc`（课程名）、`kch`（课程号）、`jxb_id`（教学班ID）、`jxbmc`（教学班名）、`jsxm`（教师姓名）、`xf`（学分）、`xnm`/`xqm`（学年学期）、`sksj`（上课时间）、`jxdd`（上课地点）、`kcgsmc`（课程归属：公共课）、`kclbmc`（课程类别）。

### 3.9 家庭成员 / 学习简历 / 学年鉴定

```
POST /jwglxt/xsxxxggl/xsxxwh_cxXsjtcy.html?gnmkdm=N100801    // 家庭成员
POST /jwglxt/xsxxxggl/xsxxwh_cxXsxxjl.html?gnmkdm=N100801    // 学习简历
POST /jwglxt/xsxxxggl/xsxxwh_cxXsxnjd.html?gnmkdm=N100801    // 学年鉴定
参数: 标准分页 + xh_id=<学号>
```

返回 JSON 分页结构（`items` 数组）。实测该生当前均为空（`totalResult: 0`），接口本身可用。

---

## 4. 课表相关

### 4.1 个人课表（核心）

```
POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON 对象，顶层 keys：

```
qsxqj, xsxx, sjkList, sjfwkg, xqjmcMap, xskbsfxstkzt, rqazcList, kbList, xsbjList,
zckbsfxssj, djdzList, kblx, jxhjkcList, xnxqsfkz
```

- `xsxx`：学生信息（含 `XM`、`XH`、`BJMC`、`XNM`、`XQM`、`ZYMC`、`NJDM_ID`、`ZYH_ID`、`KCMS`(开课门数) 等）。
- `kbList`：理论课排课记录数组（2025/12 快照为 26 条；同一课程可能按时间、周次或教师拆成多条，不能直接当作课程门数）。
- `sjkList`：实践环节课程数组。
- `xqjmcMap`：星期映射，如 `{"1":"星期一", ...}`。
- `xsbjList`：学时类型列表。
- `rqazcList`：按周次排列的日期。
- `qsxqj`：学期起始周标志。

课程条目字段（实测）：

```json
{
  "kch": "1071330", "kcmc": "计算机网络原理", "kch_id": "FBCD...",
  "jxb_id": "<教学班ID>", "jxbmc": "计算机网络原理-0001A", "jxbzc": "<班级名称>",
  "kcbj": "主修", "kclb": "专业选修课", "kcxszc": "理论:32,实验:16",
  "cdbh": "计电202", "cdmc": "计电202", "cdlbmc": "机房",
  "jc": "1-2节", "jcor": "1-2", "jcs": "1-2",
  "sksj": "星期一第1-2节{1-16周};...",
  "jsxm": "<教师姓名>", "jgh_id": "<教师ID>",
  "kczxs": "48", "khfsmc": "考试",
  "sjkList": [...], "shms": {...}, "xb": "理论"
}
```

> 注：`kbList` 中不含教师字段的场景，教师信息在 `xsbjList`/`sjkList` 或通过教学班查询。个人课表实测条目不直接含 `jsmc` 时，可看 `jxbmc`（教学班名）。

### 4.2 教师课表

```
POST /jwglxt/kbcx/jskbcx_cxJsKb.html?gnmkdm=N2151
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151
参数: 标准分页 + xnm=2025&xqm=12&jgh_id=<教师工号>
```

- `jsmc`（教师姓名）参数单独传**实测返回空**（学生账号无权限按姓名查）；用 **`jgh_id`（教师工号）** 可正常返回。
- 返回 JSON：`kbList`（课程，含 `jsxm` 教师名、`jxbrs` 人数、`jxbzc` 教学班组成）、`timeList`（时间表）、`bjsjkList`、`xqjmcMap` 等。

实测使用有效教师工号可以返回课表；条数取决于教师和学期。

### 4.3 日节次 / 日时段

```
POST /jwglxt/kbcx/xskbcx_cxRjc.html?gnmkdm=N2151
POST /jwglxt/kbcx/xskbcx_cxRsd.html?gnmkdm=N2151
参数: xnm=2025&xqm=12&xqh_id=1
```

- `cxRjc`：返回节次数组（实测 13 条），字段 `jcmc`、`jssj`、`jc`。
- `cxRsd`：返回时段数组（实测 3 条），字段 `kbsfxs`、`xsdj`。

### 4.4 实践课表

```
POST /jwglxt/kbcx/xskbcx_cxXsywKb.html?gnmkdm=N2151
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON（结构同个人课表，`sjkList` 为实践环节）。

### 4.5 作息时间模型

```
POST /jwglxt/kbdy/jskbdy_cxTimeModelList.html?gnmkdm=N2151
参数: xnm=2025&xqm=12&xqh_id=1
```

返回 JSON 数组，字段：`sdmc`（时段名）、`xsdj`（学时等级）、`djgs`（节间）、`xjgs`（小节）。实测 3 条（上午/下午/晚上）。

### 4.6 学时等级列表

```
POST /jwglxt/kbdy/jskbdy_cxXsdjList2.html?gnmkdm=N2151
参数: xnm=2025&xqm=12&xqh_id=1
```

返回 JSON 数组，字段：`jcmc`（节次名）、`xsdj`（学时等级）、`xjgs`（小节）。实测 6 条。

### 4.7 班级课表（核心）

```
POST /jwglxt/kbdy/bjkbdy_cxBjKb.html?gnmkdm=N214505
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=N214505
参数: 标准分页 + xnm=2025&xqm=12&bh_id=<班级ID>&njdm_id=2024&zyh_id=<专业代码>&
      xqh_id=1&tjkbzdm=1&tjkbzxsdm=0&kzlx=ck&sfcxxqh=1
```

返回 JSON，顶层 keys：

```
qsxqj, sjkList, xqjmcMap, kbList, lxfs, xsbjList, djdzList, kblx, sfxsd,
xqbzxxszList, weekNum, sxgykbbz, xkkg, jxhjkcList
```

- `kbList`：排课记录数组（快照为 52 条，不等同于 52 门不同课程），字段比个人课表多 `jxbrs`（教学班人数）、`jsmc`（教师姓名）、`pkbj`（排课标志）、`jgh_id`（教师工号）。
- `weekNum`：**周次起止日期数组**，每个元素含 `zs`（周次序号）、`zcrq`（如 `1(2026-03-02至2026-03-08)`）、`rq`（如 `2026-03-02/2026-03-08`）、`zsmc`。实测 20 周。
- `xqjmcMap`：星期映射。

实测课程条目：

```json
{
  "kcmc": "计算机组成原理", "kch": "<课程号>", "jxb_id": "<教学班ID>",
  "jxbmc": "计算机组成原理-0003A", "jxbrs": "<人数>", "jxbzc": "<班级名称>",
  "jsmc": "<教师姓名>", "jgh_id": "<教师工号>",
  "cdmc": "计电302", "cdlbmc": "机房", "jc": "1-2节",
  "kcxszc": "理论:56,实验:16", "kcxzjc": "学类", "kczxs": "72",
  "khfsmc": "未安排", "ksfsmc": "未安排", "kkzt": "1", "pkbj": "1"
}
```

### 4.8 周次信息

```
POST /jwglxt/kbdy/bjkbdy_cxZcxx.html?gnmkdm=N214505
参数: xnm=2025&xqm=12
```

返回 JSON 数组（实测 20 条），每条含 `zs`（周次）、`rq`（日期区间）、`zsmc`。

### 4.9 课表纵向显示字段

```
POST /jwglxt/kbdy/bjkbdy_cxKbzdxsxx.html?gnmkdm=N214505
参数: doType=query&kbzl=bj&xnm=2025&xqm=12
```

返回 JSON 数组（实测 24 条），字段：`ZDM`（字段名）、`ZDMC`（显示名）、`SFXS`（是否显示）。

### 4.10 学年学期开放控制

```
POST /jwglxt/kbdy/bjkbdy_cxXnxqsfkz.html?gnmkdm=N214505
参数: xnm=2025&xqm=12
```

返回 `application/json`，响应体是 JSON 字符串 `"true"` 或 `"false"`。实测快照为 `"false"`，不是 JSON 布尔值。

---

## 5. 成绩相关

### 5.1 个人成绩（核心）

```
POST /jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005
参数: 标准分页 + xnm=2025&xqm=12   （xnm/xqm 都为空则查全部）
```

返回 JSON 分页结构 `items`。实测：
- 2025/12 学期：12 条
- 全部学期（`xnm=`&`xqm=` 空）：51 条

成绩条目字段（实测）：

```json
{
  "xh_id": "<学号>", "xh": "<学号>", "xm": "<姓名>",
  "kcmc": "计算机网络原理", "kch": "<课程号>", "kch_id": "<课程ID>",
  "kcywmc": "Principles of Computer Network",
  "jxb_id": "<教学班ID>", "jxbmc": "计算机网络原理-0004",
  "kcbj": "主修", "kclbmc": "学类核心课", "kcxzmc": "学类核心课",
  "khfsmc": "考试", "ksxz": "正考", "ksxzdm": "01",
  "cj": "<成绩>", "bfzcj": "<百分制成绩>", "jd": "<绩点>", "xf": "4.0", "zxs": "64",
  "cjsfzf": "否", "sfkj": "否", "sfpk": "否",
  "jsxm": "<教师姓名>", "cjbdsj": "<成绩录入时间>", "cjbdczr": "<录入人>",
  "jgmc": "<学院名称>", "kkbmmc": "<开课学院>",
  "bh": "<班级ID>", "bj": "<班级名称>", "njdm_id": "2024", "njmc": "2024",
  "xnm": "2025", "xnmmc": "2025-2026", "xqm": "12", "xqmmc": "2",
  "kklxdm": "主修课程", "xslb": "必修", "sfxwkc": "0", "rwzxs": "64"
}
```

关键字段：
- `kcmc` 课程名 / `kch` 课程号 / `kcywmc` 英文名
- `cj` 成绩 / `bfzcj` 百分制成绩 / `jd` 绩点 / `xf` 学分 / `zxs` 总学时
- `ksxz` 考试性质（正考/补考）、`khfsmc` 考核方式（考试/考查）
- `jsxm` 任课教师 / `cjbdsj` 录入时间 / `cjbdczr` 录入人
- `xnm`/`xqm` 学年学期 / `xnmmc`/`xqmmc` 名称
- `kcbj` 课程标记（主修）、`kclbmc` 课程类别、`kklxdm` 开课类型

### 5.2 学分类统计

```
GET /jwglxt/cjcx/cjcx_cxXsxftj.html
```

返回 JSON 数组（实测 12 条），字段：`kcxzmc`（课程性质名）、`fxxf`（已修学分）、`kcxzdm`。用于页面显示「课程性质/学分要求/已获学分」统计。

### 5.3 信息计数

```
POST /jwglxt/cjcx/cjcx_cxXxCount.html?gnmkdm=N305005
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON：`{"xss": 1, "kcs": 12}`（学生数、课程数）。

### 5.4 项目类别列表（成绩查询页下拉）

```
GET /jwglxt/cjcx/cjcx_cxXmblbzlist.html?xnm=2025&xqm=12
```

返回 JSON 数组（项目类别字符串列表）。2026-07-31 补测正常返回 16 项；响应耗时可能随服务端负载变化，客户端仍应设置合理超时。

### 5.5 打印/导出成绩（当前学生权限不可用）

```
POST /jwglxt/cjcx/cjcx_dyList.html?gnmkdm=N305005
POST /jwglxt/cjcx/cjcx_dcListByXs.html?gnmkdm=N305005
参数: 标准分页 + xnm=2025&xqm=12
```

实测 `cjcx_dyList` 返回“无功能权限”页面，`cjcx_dcListByXs` 返回系统异常页面；当前学生角色下不能把它们作为可用的打印/导出接口。HTTP 状态均为 200，因此必须检查响应内容。

### 5.6 成绩详情（弹层，实测 2026-09）

```
POST /jwglxt/cjcx/cjcx_cxCjxqGjh.html?gnmkdm=N305005
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005
参数(form): jxb_id=<教学班ID>&xnm=<学年>&xqm=<学期>&xh_id=<学号ID>&kcmc=<课程名>
```

> **`gnmkdm=N305005` 是必需参数**。页面 JS 的 `$.getURL` 会自动追加它；手工请求漏掉时，
> POST 返回 HTTP 500、GET 被重定向到登录页（HTTP 200 但内容是登录页）。参数取自成绩列表
> 条目的 `jxb_id` / `xnm` / `xqm` / `xh_id` / `kcmc`。GET + query 参数同样可用。

返回 **HTML 弹层**（非 JSON），结构固定：

```html
<span class="red2" role="button">大学生心理健康教育</span>   <!-- 课程名回显 -->
<table id="subtab">
  <thead><tr><td>成绩分项</td><td>成绩分项比例</td><td>成绩</td></tr></thead>
  <tbody>
    <tr><td>【 平时成绩 】</td><td>40%</td><td>94</td></tr>
    <tr><td>【 期末成绩 】</td><td>60%</td><td>89</td></tr>
    <tr><td>【 总评 】</td><td></td><td>91</td></tr>
  </tbody>
</table>
<span class="bigger-120">本课程期末强制达标线为 分</span>
```

实测 50 条成绩中只有两种形态：

| 形态 | tbody 行 | 说明 |
|---|---|---|
| 有分项拆分 | 平时成绩 / 期末成绩 / 总评 | 三条，比例为百分数（如 `40%`/`60%`） |
| 无分项拆分 | 总评成绩(100%) / 总评 | 两条，只有总评 |

解析注意点：

- `总评成绩` 与 `总评` 是**两回事**：前者是 100% 权重的分项，实测可能与列表成绩存在小数差
  （如列表 `cj=93`，`总评成绩=93.1`，`总评=93`）。应优先采用 `【 总评 】` 行。
- 总评行的比例单元格为 `&nbsp;`，需按空值处理。
- 底部“本课程期末强制达标线为 N 分”在未设置时数字为空，只有文字。
- 会话过期时该端点返回登录页，需按登录页特征识别（不能只看 HTTP 200）。

---

## 6. 考试相关

### 6.1 学生考试信息（核心）

```
POST /jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON 分页结构。实测 2025/12 学期 8 门考试。

考试条目字段（实测）：

```json
{
  "xh_id": "<学号>", "xh": "<学号>", "xm": "<姓名>", "xb": "<性别>",
  "kcmc": "<课程名称>", "kch": "<课程号>",
  "ksmc": "2025-2026学年第二学期本科课程期末考试",
  "kssj": "2026-07-16(15:00-17:00)",
  "cdmc": "<考试教室>", "cdbh": "<场地编号>", "cdxqmc": "*", "cdjc": "无简称",
  "khfs": "考试",
  "sksj": "星期三第5-6节{5周,9周,15周};...",
  "jxbmc": "<教学班名称>", "jxbzc": "<教学班组成>",
  "kkxy": "马克思主义学院", "pycc": "本科",
  "sjbh": "统一-2025-2026学年第二学期本科课程期末考试-...",
  "jsxx": "<教师工号>/<教师姓名>", "xf": "2.5", "xnm": "2025", "xnmc": "2025-2026",
  "xqm": "12", "xqmc": "*", "xqmmc": "2", "cxbj": "否", "zxbj": "否",
  "jxdd": "广西大学慕课学习中心;6B-301;广西大学慕课学习中心;6B-501"
}
```

关键字段：
- `ksmc` 考试名称（场次）、`kssj` 考试时间
- `cdmc`/`cdbh` 考试教室、`jxdd` 考试地点（多个）
- `kcmc`/`kch` 课程、`khfs` 考核方式
- `sksj` 平时上课时间
- `sjbh` 试卷编号（含考试场次+课程+课程号，可用于区分场次）

### 6.2 无排考课程

```
POST /jwglxt/kwgl/kscx_cxWpkskcList.html?doType=query&gnmkdm=N358105
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON 分页结构（无需排考的课程列表）。

### 6.3 考试名称（按学年学期）

```
GET /jwglxt/ksglcommon/common_cxKsmcByXnxq.html?xnm=2025&xqm=12
```

返回 JSON 数组（2026-07-31 补测为 4 项），字段：`KSMCDMB_ID`（考试场次ID）、`KSMC`（考试名称）、`KSXS`（考试形式）、`KSXZ`（考试性质）、`SFBKBJ`、`SFKCFPKC`。数量取决于学年学期和当前考试配置。

### 6.4 考试安排打印列表

```
POST /jwglxt/kwgl/kscx_cxDyKsapylb.html?gnmkdm=N358105
参数: 标准分页 + xnm=2025&xqm=12
```

返回 HTML（打印用）。实测该学生账号权限下返回空内容（`len=0`）。

---

## 7. 空闲教室

### 7.1 空闲教室列表（核心）

```
POST /jwglxt/cdjy/cdjy_cxKxcdlb.html?doType=query&gnmkdm=N2155
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155
参数: 标准分页 + xnm=2025&xqm=12&xqh_id=1&jyfs=1&qssj=&jssj=&cdmc=&lh=&cdlb_id=&
      cdejlb_id=&qszws=&jszws=&sjfw=&cdjylx=
```

返回 JSON 分页结构。实测 1730 个教室（未筛选），单页 100 条。

教室条目字段（实测）：

```json
{
  "cd_id": "0044AC...", "cdbh": "化工1号楼4楼", "cdmc": "化工1号楼4楼",
  "cdlb_id": "014", "cdlbmc": "实验室",
  "cdxqxx_id": "410C...", "xqh_id": "1", "xqmc": "*",
  "lh": "18", "jxlmc": "化工学院办公楼",
  "jg_id": "30400", "jgmc": "化学化工学院",
  "zws": "0", "fjsclj": "0", "sfkjy": "0"
}
```

关键字段：
- `cd_id` 场地ID / `cdbh` 场地编号 / `cdmc` 场地名
- `cdlbmc` 场地类别（普通教室/实验室/机房等）
- `jxlmc` 教学楼 / `lh` 楼号 / `zws` 座位数
- `jgmc` 托管部门 / `sfkjy` 是否可借用

> 筛选参数：`jyfs`（借用方式：1/2/3）、`qssj`/`jssj`（起始/结束节次）、`qszws`/`jszws`（座位范围）、`cdlbmc`（场地类别）、`lx`（楼号）。周末/夜间可选 `sjfw`。

### 7.2 校区节次

```
GET /jwglxt/cdjy/cdjy_cxXqjc.html?xnm=2025&xqm=12&xqh_id=1
```

返回 JSON：`{ "lhList": [...], "jcList": [...] }`，`jcList` 字段含 `JCMC`、`RSDMC`、`RSDZJS`。

### 7.3 空调节次

```
GET /jwglxt/cdjy/cdjy_cxKtjc.html?xnm=2025&xqm=12&xqh_id=1
```

返回 JSON 或 null（空调相关节次）。

### 7.4 当前周次/星期信息

```
POST /jwglxt/cdjy/cdjy_cxQtlb.html?gnmkdm=N2155
参数: xnm=2025&xqm=12&xqh_id=1&flag=0
```

返回 JSON：`{ "dqzcxq": {...}, "nxqzcList": [...] }`。
- `dqzcxq`：当前周次信息，含 `ZXRQ`（今天）、`ZDRQ`、`DQZC`（当前周）、`DQQX`（当前星期）、`ZDKXZC`（最大可查询周）、`ZQST`（周起始）。
- `nxqzcList`：周次列表，含 `zc`（周次）、`dxqzc`（大写周次）、`zczt`（周次状态 0/1）。

### 7.5 日期信息

```
POST /jwglxt/cdjy/cdjy_cxDateInforma.html?gnmkdm=N2155
参数: xnm=2025&xqm=12&xqj=1,2,3,4,5,6,7&zcd=1
```

返回 JSON 数组（选中周次+星期对应的具体日期字符串，如 `2026-03-02`）。实测 7 条。

---

## 8. 选课与确认

> **时间窗口说明**：选课分为「预选（N253511）/ 正选（N253512）」和「选课结果确认（N2158）」两个阶段。
> - 预选/正选的数据接口受选课时间和选课控制配置约束。关闭期间可能返回业务页、空页面、登录页或错误提示，不能只用是否发生 302 判断。
> - 正选页当前包含 `#iskxk`；预选页补测时没有该字段，因此不能用同一字段统一判断两个阶段。
> - N2158 的只读查询在非选课时间可用；确认、取消确认等写操作没有实际提交验证，其可用时间与业务校验仍以页面提示为准。

### 8.0 判断选课是否开放

```
GET /jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default   // 正选页
GET /jwglxt/xsxk/tjxkyzb_cxTjxkYzbIndex.html?gnmkdm=N253511&layout=default   // 预选页
```

正选页返回 HTML，并可能包含隐藏字段：

```html
<input type="hidden" name="iskxk" id="iskxk" value="1"/>   <!-- 1=选课中, 0=未开放 -->
```

2026-07-31 实测正选页为 `iskxk=0`；预选页返回正常 HTML，但未包含 `iskxk`。因此客户端应分别解析页面状态、选课控制 ID 和业务提示，不能把缺少该字段直接解释为开放或关闭。

### 8.1 选课结果确认列表（核心，非选课时间可用）

```
POST /jwglxt/kbcx/xskbqr_cxXskbqrIndex.html?doType=query&gnmkdm=N2158
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/kbcx/xskbqr_cxXskbqrIndex.html?gnmkdm=N2158
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON 分页结构。实测 20 门课。

条目字段（实测）：

```json
{
  "jxb_id": "4454AF...", "kch_id": "1160120", "kch": "1160120",
  "kcmc": "马克思主义理论与实践", "jxbmc": "马克思主义理论与实践-0032",
  "kcxz": "通识必修课", "xf": "1.5",
  "jsxx": "<教师工号>/<教师姓名>/<职称>",
  "jxdd": "6B-303", "sksj": "...",
  "bixbj": "1", "cxbj": "0", "fxbj": "0", "sfqr": "0",
  "xnm": "2025", "xnmc": "2025-2026", "xqm": "12"
}
```

关键字段：`sfqr`（是否确认 0/1）、`bixbj`/`cxbj`/`fxbj`（必修/重修/辅修标志）、`jsxx`（教师工号/姓名/职称）、`kcxz`（课程性质）。

### 8.2 选课信息（该学期选课数）

```
POST /jwglxt/kbcx/xskbqr_cxXsxkxx.html?gnmkdm=N2158
参数: 标准分页 + xnm=2025&xqm=12
```

返回纯数字（该学期选课门数）。实测 `20`。

### 8.3 学分汇总（未确认/已确认学分）

```
POST /jwglxt/kbcx/xskbqr_cxXsxkxfxx.html?gnmkdm=N2158
参数: xnm=2025&xqm=12 (+ jxb_ids 可选)
```

返回 JSON（实测）：

```json
{
  "xswqrxf": "30.5", "xsyqrxf": "0", "xscxzxf": "0",
  "xkzxf": "30.5", "zczxf": "30.5",
  "xfqrsj": "2026-03-16 09:00:00至2026-03-30 22:00:00"
}
```

字段：`xsyqrxf`（已确认学分）、`xswqrxf`（未确认学分）、`xkzxf`（可选总学分）、`zczxf`（总成选学分）、`xfqrsj`（学分确认时间窗）。

### 8.4 是否已确认

```
POST /jwglxt/kbcx/xskbqr_cxSfyqr.html?gnmkdm=N2158
参数: xnm=2025&xqm=12
```

返回 `"0"`（未确认）或 `"1"`（已确认）。

### 8.5 选课明细（HTML 表格）

```
POST /jwglxt/kbcx/xskbqr_cxXkmx.html?gnmkdm=N2158
参数: 标准分页 + xnm=2025&xqm=12 (+ jxb_id 查看单门课明细)
```

返回 HTML（选课明细表）。

### 8.6 确认选课（提交确认）

```
POST /jwglxt/kbcx/xskbqr_qrXskbqrSdzyy.html?gnmkdm=N2158
参数: xnm=2025&xqm=12&sfqr=1&jxb_ids=<逗号分隔的教学班ID>&dqyzm=<动态验证码>&xfqryzm=<学分确认验证码>&xfqryzmcssj=<时间戳>
```

前端脚本在响应体等于 `"1"` 时按成功处理，但本次未实际提交验证。对应流程会先请求 `xskbqr_qrXskbqrSdzyyView.html`，参数包含 `xnm`、`xqm`、`jxb_ids`、`kcmcs` 和 `xfs`，再从弹窗取得验证码相关字段。其他确认模式还可能使用 `xskbqr_qrXskbqrView.html`。

> **未端到端验证**：该操作会修改真实选课确认状态。路径和参数来自当前前端脚本，响应语义仍需在授权的测试数据上验证。

### 8.7 取消确认 / 删除确认信息

```
POST /jwglxt/kbcx/xskbqr_qxqrXfqrxx.html?gnmkdm=N2158    // 取消某门已确认课程（未确认学分退回到未确认）
参数: xnm=2025&xqm=12&jxb_ids=<逗号分隔的教学班ID>

POST /jwglxt/kbcx/xskbqr_qxqrYqrxx.html?gnmkdm=N2158     // 删除确认信息（全部）
参数: jxb_ids=<逗号分隔的教学班ID>
```

前端脚本对 `qxqrXfqrxx` 的响应检查是否包含本地化的“成功”文本；`qxqrYqrxx` 的回调按 JSON 解析。两者都会修改真实确认状态，本次均未提交验证，不能保证所有状态下的响应结构。

### 8.8 选课名单查询（某课程的选课学生）

```
POST /jwglxt/xkcx/xkmdcx_cxXkmdcxIndex.html?doType=query&gnmkdm=N255010
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/xkcx/xkmdcx_cxXkmdcxIndex.html?gnmkdm=N255010
参数: 标准分页 + xnm=2025&xqm=12 (+ kch_id / jxb_id 筛选)
```

返回 JSON 分页结构（该课程选课学生名单）。实测该生权限下可见自己所在教学班名单。

---

## 8A. 选课（预选 N253511 / 正选 N253512）

> 本节路径和参数来自当前模块 JavaScript。由于验证时不在选课窗口，且提交/退课会修改真实数据，本节没有完成端到端验证。下面的返回结构是前端脚本的处理约定，不是成功响应实测结果；正式接入前必须在授权测试数据和实际开放窗口中抓包复核。

### 8A.1 查询可选课程

**预选（N253511）—— 按课程查教学班：**

```
POST /jwglxt/xsxk/tjxkyzb_cxJxbTjxkYzb.html?gnmkdm=N253511
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/xsxk/tjxkyzb_cxTjxkYzbIndex.html?gnmkdm=N253511
参数:
  kch_id=<课程ID>
  cxbj=<重修标记> rwbj=<任务标记> rlkz=<容量控制> cdrlkz=<冲突容量控制> rlzlkz=<容量组控制>
  njdm_id=2024 zyh_id=0711 zyfx_id= bh_id= zh=<志愿>
  xkxnm=2025 xkxqm=12 xkly=<选课来源> txbsfrl=<同下班非认领>
```

前端预期返回教学班列表（含容量、时间和教室）；成功响应结构未实测。

**正选（N253512）—— 高级查询可选课程（searchBox 条件）：**

```
POST /jwglxt/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html?gnmkdm=N253512
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512
参数: 大量可选筛选字段（课程号/名、教师名、开课学院、周次、节次、星期等，见 8A.5）
```

前端按 JSON 教学班列表处理。`requestMap` 由 `searchBox("getConditions")` + 固定筛选参数组成，其中 `kch_id`、`kcmc`/`kch`、`jsxm`、`xkxnm`/`xkxqm` 为常用筛选；成功响应结构未实测。

**正选 —— 可选课程主列表（分页）：**

```
POST /jwglxt/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512
参数: xkkz_id=<选课控制ID>&kklxdm=<开课类型>&xszxzt=<学生选课状态>&
      njdm_id=2024&zyh_id=0711&kspage=0&jspage=0
```

前端预期返回可选课程页签 HTML；成功响应未实测。

**正选 —— 已选列表：**

```
POST /jwglxt/xsxk/zzxkyzb_cxZzxkYzbChoosed.html?gnmkdm=N253512
参数: 无
```

前端预期返回已选课程列表 HTML；成功响应未实测。

### 8A.2 选课提交

**预选 —— 保存选课（单个教学班）：**

```
POST /jwglxt/xsxk/tjxkyzb_xkBcZyTjxkYzb.html?gnmkdm=N253511
参数:
  jxb_ids=<教学班ID> kch_id=<课程ID> kcmc=<课程名> rwlx=1
  rlkz= cdrlkz= rlzlkz= sxbj= qz=0 xxkbj= cxbj=
  xkkz_id=<选课控制ID> zyh_id=0711 njdm_id=2024 xklc=<选课轮次>
  xkxnm=2025 xkxqm=12
```

前端脚本按 JSON 的 `flag`/`msg` 字段处理，其中 `flag="1"` 表示成功、`flag="6"` 表示其他页面已选、`flag="0"` 表示失败。该语义未通过真实提交验证。

**预选 —— 快速选课（一键选满）：**

```
POST /jwglxt/xsxk/tjxkyzb_xkTjxkyzbQuickly.html?gnmkdm=N253511
参数: xkkz_id=<选课控制ID>
```

**正选 —— 选课提交（单个教学班，同预选结构）：**

```
POST /jwglxt/xsxk/zzxkyzb_xkZzxkyzbQuickly.html?gnmkdm=N253512   // 快速选课
参数: xkkz_id=<选课控制ID>
```

前端脚本按 JSON `{ "flag": "0"|"1", "msg": "..." }` 处理；真实成功响应未验证。

> 正选模块单个教学班点「选课」按钮触发的是 `zzxkyzb_xkZzxkyzb.html` 或经由 display HTML 内的表单提交，核心参数与预选一致（`jxb_id`、`xkkz_id`、`xkxnm`/`xkxqm`）。因当前非选课时间，正选显示页未加载，单班提交的确切 URL 无法从已加载页面确认——选课开放时以浏览器 DevTools 实际捕获为准。

### 8A.3 退课 / 取消

**预选 —— 退课：**

```
POST /jwglxt/xsxk/tjxkyzb_tuikBcTjxkYzb.html?gnmkdm=N253511
参数:
  kch_id=<课程ID> jxb_id=<教学班ID> kcmc=<课程名> jxb_ids=<逗号分隔的教学班ID>
  rlkz= cdrlkz= rlzlkz= xklc=<选课轮次> xkxnm=2025 xkxqm=12 txbsfrl=
```

前端脚本将 `"1"`、`"2"`、`"3"` 分别解释为成功、服务器繁忙和未知异常；真实退课响应未验证。

> 预选页的「退课」也可通过 `tjxkyzb_cxZkcTjxkYzb.html`（按已选教学班查询）辅助定位要退的教学班。

### 8A.4 选课辅助查询（预选/正选通用）

```
POST /jwglxt/xsxk/tjxkyzb_cxZkcTjxkYzb.html?gnmkdm=N253511    // 按 jxb_ids 查教学班详情（含教师、子班）
参数: jxb_ids=<逗号分隔>&zyh_id=0711&njdm_id=2024

POST /jwglxt/xsxk/tjxkyzb_cxJcxxList.html?gnmkdm=N253511      // 教材信息
参数: jxb_id=<教学班ID>

POST /jwglxt/xsxk/tjxkyzb_cxXkbzMsg.html?gnmkdm=N253512       // 教学班备注
参数: jxb_id=<教学班ID>

POST /jwglxt/xsxk/tjxkyzb_cxTsxxkXfTjxkYzb.html?gnmkdm=N253511 // 通识选修课学分
参数: xkxnm=2025&xkxqm=12&zyh_id=0711&njdm_id=2024

POST /jwglxt/xsxk/tjxkyzb_cxJdyxxfTjxkYzb.html?gnmkdm=N253511  // 学分结点已修学分
参数: zyh_id=0711&njdm_id=2024

POST /jwglxt/xsxk/tjxkyzb_cxXscxkcCount.html?gnmkdm=N253511   // 已选课程数
参数: 无
```

### 8A.5 正选高级查询字段（searchBox 条件）

`cxJxbWithKchZzxkYzb` 的 `requestMap` 除 `searchBox` 条件外，还含以下固定筛选字段（均可选）：

| 字段 | 含义 |
|---|---|
| `xkxnm` / `xkxqm` | 选课学年 / 学期 |
| `kch_id` / `kch` / `kcmc` | 课程ID / 课程号 / 课程名 |
| `jsxm` | 教师姓名 |
| `jg_id` | 开课学院 |
| `kklxdm` | 开课类型代码 |
| `kklb` / `kcxz` / `kccz` | 课程类别 / 课程性质 / 课程层次 |
| `xqh_id` | 校区 |
| `zcd` / `jc` / `xqj` / `ksjc` / `jsjc` | 周次 / 节次 / 星期 / 起始节 / 结束节 |
| `rlkz` / `cdrlkz` / `rlzlkz` | 容量控制 / 冲突容量控制 / 容量组控制 |
| `sxbj` / `xxkbj` / `cxbj` | 是否限选 / 共享课标记 / 重修标记 |
| `sfkxq` / `sfkcfx` / `bbhzxjxb` | 是否可选 / 是否可分方向 / 是否合班教学 |
| `txbsfrl` | 同下班非认领 |
| `xkxskcgskg` / `cxcykclxxskg` / `jxbzcxskg` | 显示开关类标志 |

---

## 9. 学业与培养

### 9.1 学业情况页面

```
GET /jwglxt/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515
```

返回 HTML 大页面（实测 1.7MB，含成绩/学分/培养方案全部数据，可用于整体抓取）。

### 9.2 各明细弹窗（HTML）

以下接口返回 HTML 弹窗内容，`Referer` 使用学业情况页。它们不是只传 `xh_id` 即可得到完整内容的通用查询；参数来自页面当前课程或培养方案节点：

```
POST /jwglxt/xsxy/xsxyqk_cxDgshCjxfrdxx.html?gnmkdm=N105515
参数: xh_id=<学号>&kch_id=<课程ID>&kcthzbm=xfrd&fromXh_id=<来源学号，可选>
说明: 学分认定信息

POST /jwglxt/xsxy/xsxyqk_cxKcxdxx.html?gnmkdm=N105515
参数: xdzt=<修读状态>&xh_id=<学号>&kch_id=<课程ID>&xnm=<学年>&xqm=<学期>&
      xfyqjd_id=<学分要求节点ID>，以及页面携带的成绩查询控制字段
说明: 课程修读明细

POST /jwglxt/xsxy/xsxyqk_cxJdtdView.html?gnmkdm=N105515
参数: xh_id=<学号>&xfyqjd_id=<学分要求节点ID>&provenance=xsxyqkcx
说明: 节点替代信息

POST /jwglxt/xsxy/xsxyqk_cxXwtdView.html?gnmkdm=N105515
参数: xh_id=<学号>&kch_id=<课程ID>&kcthzbm=xwktjd&fromXh_id=<来源学号，可选>
说明: 校外课程认定/替换信息
```

实测前两个端点可返回 HTML；后两个端点只传 `xh_id` 会返回系统异常页，因此调用时必须带页面上下文参数。

### 9.3 培养方案信息（维护页）

```
GET /jwglxt/xspyfagl/xspyfaxxwh_cxXsPyfaxxwhIndex.html?gnmkdm=N1532
```

返回 HTML（个人培养方案）。当前前端实际使用的数据接口：

```
POST /jwglxt/xspyfagl/xspyfaxxwh_cxGrpyfaxfyqKcxx.html?gnmkdm=N1532
参数: xfyqjd_id=<学分要求节点ID>&xh_id=<学号>&jdkcsx=<节点课程属性>&kcxzdm=<课程性质代码>
```

`xspyfaxxwh_cxPyfaxfyqKcxx.html` 只出现在当前前端已注释的旧代码中，未列为可用接口。

### 9.4 成绩总表打印页

```
GET /jwglxt/bysxxcx/xscjzbdy_cxXscjzbdyIndex.html?gnmkdm=N558020
```

返回 HTML（成绩总表打印页）。

---

## 10. 教学执行计划

### 10.1 教学执行计划列表（核心）

```
POST /jwglxt/jxzxjhgl/jxzxjhck_cxJxzxjhckIndex.html?doType=query&gnmkdm=N153540
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/jxzxjhgl/jxzxjhck_cxJxzxjhckIndex.html?gnmkdm=N153540
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON 分页结构。实测全校 2185 条（所有专业培养计划）。

条目字段（实测）：

```json
{
  "jxzxjhxx_id": "20113124", "njdm": "2011", "njmc": "2011",
  "zyh": "3124", "zyh_id": "3124", "zymc": "农业资源与环境",
  "dlbs": "专业", "rwbj": "班级",
  "bjgs": "2", "jhrs": "0", "kcs": "80",
  "jg_id": "33100", "xqh_id": "1", "xz": "4",
  "sfgazy": "否"
}
```

关键字段：`jxzxjhxx_id`（计划ID，用于查询详情）、`zymc`（专业名）、`njmc`（年级）、`kcs`（课程数）、`bjgs`（班级数）、`jhrs`（计划人数）、`xz`（学制年）。

### 10.2 教学执行计划课程（计划内课程列表）

```
POST /jwglxt/jxzxjhgl/jxzxjhkcxx_cxJxzxjhkcxxIndex.html?doType=query&gnmkdm=N153540
参数: 标准分页 + xnm=2025&xqm=12 (+ jxzxjhxx_id 筛选)
```

前端页面确认该路径用于计划内课程列表；未传 `jxzxjhxx_id` 时为空。2026-08-01 使用对应年级和专业的有效计划 ID 复测，仍返回空分页结果（`totalResult: 0`），因此尚不能确认课程条目字段；客户端不应封装为带字段模型的接口。

### 10.3 计划详情/课程要求

```
POST /jwglxt/jxzxjhgl/jxzxjhck_cxJxzxjhxdyqIndex.html?gnmkdm=N153540
POST /jwglxt/jxzxjhgl/jxzxjhxxwh_cxBjxx.html?jxzxjhxx_id=<计划ID>
POST /jwglxt/jxzxjhgl/jxzxjhxxwh_cxZyfxxx.html?jxzxjhxx_id=<计划ID>
```

这些路径来自教学执行计划页面；`cxJxzxjhxdyqIndex` 已实测返回 HTML，后两个端点需有效计划 ID，成功响应结构尚未单独实测。

### 10.4 课程基本信息

```
POST /jwglxt/jxjhgl/common_cxKcJbxx.html?id=<课程ID>
参数: content
```

路径来自页面脚本，需有效课程 ID；成功响应结构尚未单独实测。

---

## 11. 网上上课地址

### 11.1 网上上课地址列表（核心）

```
POST /jwglxt/rwlscx/wsskdzwh_cxWsskdzwhIndex.html?doType=query&gnmkdm=N1598
Referer: https://jwxt2018.gxu.edu.cn/jwglxt/rwlscx/wsskdzwh_cxWsskdzwhIndex.html?gnmkdm=N1598
参数: 标准分页 + xnm=2025&xqm=12
```

返回 JSON 分页结构。实测该生 16 门课有网上上课地址。

条目字段（实测）：

```json
{
  "kch": "<课程号>", "kch_id": "<课程ID>", "kcmc": "<课程名称>",
  "jxbmc": "<教学班名称>", "jxb_id": "<教学班ID>", "jgh": "<教师工号>", "jsxm": "<教师姓名>",
  "kcgsmc": "公共课", "kclbmc": "专业选修课", "kcxzmc": "专业选修课",
  "kkbm": "外国语学院", "kkbm_id": "32500",
  "jxdd": "<上课地点或平台地址>",
  "qqqh": "<群号>", "xf": "1.5", "xkrs": "0",
  "sfhxkc": "否", "sksj": "星期三第1-2节{1-5周(单),...}",
  "xnm": "2025", "xnmc": "2025-2026", "xqm": "12", "xiaoqmc": "校本部"
}
```

关键字段：`qqqh`（**群QQ号**）、`jxdd`（上课地点，多个用 `;` 分隔）、`jsxm`（教师）、`sfhxkc`（是否核心课程）。

### 11.2 教学班分组信息

```
POST /jwglxt/rwlscx/wsskdzwh_ckJxbfzxxView.html?gnmkdm=N1598
参数: jxb_id=<教学班ID>&jxbfzxx=<列表行中的分组信息>
```

返回 HTML（教学班分组详情）。`jxb_id` 不是可选筛选条件；缺少教学班上下文会返回系统异常页。

### 11.3 网上上课地址详情（单教学班）

```
POST /jwglxt/rwlscx/wsskdzwh_ckWsskdzwhView.html?jxb_id=<教学班ID>
POST /jwglxt/rwlscx/wsskdzwh_cxQQqhView.html?jxb_id=<教学班ID>
```

路径和 `jxb_id` 参数由当前前端脚本确认；成功响应结构尚未单独实测。

---

## 12. 公共代码字典

以下 GET 接口返回 JSON 数组（下拉框数据），Referer 用模块页：

| 接口 | 参数 | 返回字段 | 实测 |
|---|---|---|---|
| `GET /jwglxt/xtgl/comm_cxBjdmList.html` | `njdm_id=2024` | `bh`、`bh_id`、`bj`、`jgmc`、`njmc` | 194 个班级 |
| `GET /jwglxt/xtgl/comm_cxZydmList.html` | `njdm_id=2024` | `zyh`、`zyh_id`、`zymc`、`jgmc` | 281 个专业 |
| `GET /jwglxt/xtgl/comm_cxZyfxList.html` | `zyh_id=0711` | `zyfx_id`、`zyfxdm`、`zyfxmc`、`njdm_id` | 10 个专业方向 |
| `GET /jwglxt/xtgl/comm_cxXxdmList.html` | 无 | — | 空数组（当前学校） |

---

## 13. 其他（冷门，仅记录）

以下接口中只有明确标注“实测”的项目收到了符合描述的响应；其余仅由页面或脚本确认路径：

| 接口 | 说明 |
|---|---|
| `POST /jwglxt/jsjxrl/jsjxrl_cxJsjxrlckView.html?gnmkdm=N2151` | 教室容量查看，实测返回 HTML |
| `POST /jwglxt/kwgl/kscx_cxDyKsapylb.html?gnmkdm=N358105` | 考试安排打印，实测学生权限返回空响应体 |
| `GET /jwglxt/xtgl/init_cxBrowser.html` | 浏览器检测 |
| `GET /jwglxt/xtgl/index_cxGxDlztxx.html?dlztxxtj_id=` | 登录状态信息 |
| `GET /jwglxt/commonShow/show_cxMhpf.html` | 门户评分（返回 `data[0].sz`） |
| `POST /jwglxt/xtgl/index_changeRole.html` | 切换身份 |

---

## 附录 A：Python 实测样例（登录 + 查成绩）

```python
import base64, re, time, requests
from cryptography.hazmat.primitives.asymmetric import rsa, padding

BASE = "https://jwxt2018.gxu.edu.cn"
s = requests.Session()
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}

# 1. 登录页拿 csrf（注意逗号拼接）
html = s.get(BASE + "/jwglxt/xtgl/login_slogin.html", headers=UA).text
csrf = re.search(r'name="csrftoken"\s+value="([^"]*)"', html).group(1)

# 2. RSA 公钥
key = s.get(BASE + "/jwglxt/xtgl/login_getPublicKey.html?time=%d" % int(time.time()*1000),
            headers=UA).json()
pub = rsa.RSAPublicNumbers(
    int.from_bytes(base64.b64decode(key["exponent"]), "big"),
    int.from_bytes(base64.b64decode(key["modulus"]), "big"),
).public_key()
mm = base64.b64encode(pub.encrypt(b"PASSWORD", padding.PKCS1v15())).decode()

# 3. 登录
r = s.post(BASE + "/jwglxt/xtgl/login_slogin.html?time=%d" % int(time.time()*1000),
           data={"csrftoken": csrf, "language": "zh_CN", "yhm": "USERNAME",
                 "mm": mm, "ydType": ""}, headers=UA)
assert "index_initMenu" in r.url, "登录失败"

# 4. 查成绩（xnm/xqm 空 = 全部）
r = s.post(BASE + "/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005",
           data={"_search": "false", "queryModel.showCount": "100",
                 "queryModel.currentPage": "1", "xnm": "", "xqm": ""},
           headers={**UA, "Referer": BASE + "/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005"})
for item in r.json()["items"]:
    print(item["xnmmc"], item["kcmc"], item["cj"], item["jd"], item["xf"])
```

---

## 附录 B：验证记录

- 验证账号：学生账号（学号及个人资料已从文档中移除）
- 验证日期：2026-07-31
- 验证方式：真实登录 + 逐接口实测请求，记录状态码、Content-Type、响应体大小、返回结构 keys、示例数据
- 全流程原始响应保存在 `probe/responses/` 目录（gitignored，含凭据相关）

> **限流说明**：连续高频请求和频繁重新登录可能触发会话失效、重定向或超时，未确认固定阈值。2026-07-31 的全接口实测使用单个会话、每次请求间隔 1.2 秒，未触发 HTTP 429；客户端默认据此限制为约 0.83 请求/秒。该值是保守默认值，不是服务端承诺的最大频率。网络超时及 5xx 按 1s/2s/4s 退避后最多重试 3 次。
