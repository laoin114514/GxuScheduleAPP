# AGENT.md —— 模拟器起机与截图验证流程

这份文档记录**本机（容器内）**跑 Android 模拟器、装包装机、抓截图并做像素级校验的完整步骤，
以及踩过的坑。目的是下次改 UI 时能直接照着跑，不用再摸索。

> 适用范围：只做「本地验证 + 出图」，不涉及发布、上传服务器等对外操作。

---

## 0. 环境事实

| 项 | 值 |
| --- | --- |
| Android SDK | `/home/laoin/android-sdk`（`local.properties` 里的 `sdk.dir`） |
| adb | `/home/laoin/android-sdk/platform-tools/adb`（**不在 PATH 里**） |
| AVD | `gxu35`（`/home/laoin/.android/avd/gxu35.avd`，system image `android-35/google_apis/x86_64`） |
| 宿主显示 | `:0`（lightdm / Xorg，Intel UHD 630），认证文件 `/var/run/lightdm/root/:0` |
| GPU | `-gpu host` 可用前提：**带窗口**跑 + 有可用 X 显示 |
| 模拟器分辨率/密度 | 1080×2400 / 420dpi（≈2.625 px per dp） |
| 包名 | `com.cherry.wakeupschedule` |

`COLOR` 无关，但注意：命令行里 `emulator`、`adb` 都要写全路径。

---

## 1. 启动模拟器（GPU 渲染）

### 1.1 AVD 必须打开硬件 GPU

`~/.android/avd/gxu35.avd/config.ini`：

```ini
hw.gpu.enabled = yes
hw.gpu.mode = host
```

`hw.gpu.enabled = no` 时即使命令行传 `-gpu host`，生成出来的 `hardware-qemu.ini` 里
`hw.gpu.mode` 仍会被改回 `swiftshader`（CPU 渲染）。

### 1.2 起机命令

```bash
export DISPLAY=:0
export XAUTHORITY=/var/run/lightdm/root/:0
export HOME=/home/laoin
export ANDROID_AVD_HOME=/home/laoin/.android/avd

cd /tmp
setsid env DISPLAY=:0 XAUTHORITY=/var/run/lightdm/root/:0 \
  HOME=/home/laoin ANDROID_AVD_HOME=/home/laoin/.android/avd \
  nohup /home/laoin/android-sdk/emulator/emulator \
  -avd gxu35 -no-audio -no-boot-anim -gpu host \
  -no-snapshot > /tmp/emu.log 2>&1 < /dev/null &
```

关键点：

- **不要加 `-no-window`**。headless 模式下 `-gpu host` 必然失败（见坑 2）。
- `HOME` / `ANDROID_AVD_HOME` 必须指到位，否则报 `Unknown AVD name`（当前 shell 以 root 跑，
  `$HOME=/root`，找不到 AVD）。
- `setsid ... < /dev/null &` 让模拟器脱离当前 shell，避免工具调用结束把进程带走。

### 1.3 校验是不是真 GPU

```bash
grep -E "Created extended window|Boot completed|supportsNativeGLES" /tmp/emu.log
/home/laoin/android-sdk/platform-tools/adb devices   # 出现 emulator-5554 device
```

成功时日志里有：

```
AVD supportsNativeGLES=1, supportsGuestAngle=1
Created extended window in ... ms
Boot completed in ... ms
```

失败时是 `Failed to get EGL display` / `Could not start renderer`。

### 1.4 想确认宿主 GL 可用（可选）

```bash
XAUTHORITY=/var/run/lightdm/root/:0 DISPLAY=:0 glxinfo -B | head -8
# 应看到 Intel(R) UHD Graphics ... Accelerated: yes
```

---

## 2. 构建、安装、启动

```bash
cd /home/laoin/Desktop/code/android/GxuScheduleAPP
./gradlew :app:assembleDebug --offline          # 约 3s（增量）
A=/home/laoin/android-sdk/platform-tools/adb
$A install -r -d app/build/outputs/apk/debug/GxuScheduleAPP-debug-0.0.0-universal.apk
$A shell am force-stop com.cherry.wakeupschedule
$A shell monkey -p com.cherry.wakeupschedule -c android.intent.category.LAUNCHER 1
```

- `-d` 允许降级安装：本机 `versionCode` 是 `0001 * 10 + abi`，而模拟器里可能装着更高的旧包，
  不加 `-d` 会 `INSTALL_FAILED_VERSION_DOWNGRADE`。
- 仓库里 `gradlew` 曾经没有可执行位（`Permission denied`），已补 `chmod +x` 并在提交里带上。

---

## 3. 操作 UI（优先 dump，别硬编码坐标）

### 3.1 先 dump 再点

```bash
$A shell uiautomator dump /sdcard/ui.xml
$A pull /sdcard/ui.xml /tmp/ui.xml
python3 - <<'EOF'
import re
x = open('/tmp/ui.xml').read()
for m in re.finditer(r'<node[^>]*>', x):
    s = m.group(0)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
    t = re.search(r' text="([^"]*)"', s)
    if b and t and t.group(1):
        print(b.group(1), b.group(2), b.group(3), b.group(4), t.group(1))
EOF
```

拿到目标节点的中心点再 `$A shell input tap X Y`，比记坐标稳。

### 3.2 这台模拟器上的常用入口（1080×2400，仅供示意）

| 目标 | 操作 |
| --- | --- |
| 课表菜单 | 课表页右上 `⋮` → `input tap 993 196` |
| 「我的」页 | 底部导航第三个 → `input tap 931 2252` |
| 「我的 → 课表外观」 | 我的页里该行 → `input tap 480 1720` |
| 菜单内向下滚 | `input swipe 540 2000 540 1100 400` |
| 关菜单/返回 | `input keyevent 4` |

注意：`input tap` 用的是**设备像素**。截图预览若是 900×2000（缩放 0.833），
预览坐标 × 1.2 才是设备像素。

### 3.3 深浅色切换

```bash
$A shell cmd uimode night yes    # 或 no
```

切换会重建 Activity → 已打开的弹层全部消失，**要重新打开菜单再截图**（我第一次就因此截到了
一张课程详情弹层）。

---

## 4. 截图与像素校验

### 4.1 抓图

```bash
$A shell screencap -p /sdcard/shot.png
$A pull /sdcard/shot.png /tmp/shots/name.png
```

### 4.2 验证对齐（比肉眼可靠）

本次排版验证用到的测量：找轨道端点、开关/胶囊右边缘、文字块的 bounds 是否对齐。

```python
from PIL import Image
im = Image.open('/tmp/shots/name.png').convert('RGB')
y = 1465
for x in (120, 128, 200, 430, 952, 960, 990):
    print(x, im.getpixel((x, y)))
```

配合 `uiautomator dump` 的 `bounds` 就能把「40 左边缘 128px = 轨道左端 128px」这种结论坐实，
而不是靠看图猜。

### 4.3 改前/改后对比图

```python
from PIL import Image
before = Image.open('/tmp/shots/before.png').convert('RGB').crop((0, 1130, 1080, 2400))
after  = Image.open('/tmp/shots/after.png').convert('RGB').crop((0, 1130, 1080, 2400))
w, h = before.size
canvas = Image.new('RGB', (w * 2 + 6, h), (255, 255, 255))
canvas.paste(before, (0, 0)); canvas.paste(after, (w + 6, 0))
canvas.save('/tmp/shots/compare.png')
```

截图归档放 `develop/<主题>截图/`（该目录被 git 忽略，不会污染提交）。

---

## 5. 复位测试数据（截图想拍「干净态」时）

```bash
$A root                                    # google_apis 镜像支持 adb root
$A shell am force-stop com.cherry.wakeupschedule
$A shell "sed -i 's/name=\"course_cell_height\" value=\"[0-9]*\"/name=\"course_cell_height\" value=\"68\"/' \
  /data/data/com.cherry.wakeupschedule/shared_prefs/app_settings.xml"
$A shell monkey -p com.cherry.wakeupschedule -c android.intent.category.LAUNCHER 1
```

只改单个偏好项，别 `pm clear`——会把模拟器里准备好的课表数据一起清掉。

---

## 6. 关模拟器

```bash
pids=$(ps -eo pid,comm | awk '$2 ~ /^qemu/ {print $1}'); [ -n "$pids" ] && kill -9 $pids
rm -f /home/laoin/.android/avd/gxu35.avd/*.lock    # 杀掉后锁文件会残留，导致 Multiinstance 冲突
```

---

## 7. 踩坑清单（都实际踩过）

| # | 现象 | 原因 / 解法 |
| --- | --- | --- |
| 1 | `Unknown AVD name [gxu35]` | `HOME` 是 `/root`；导出 `HOME=/home/laoin` + `ANDROID_AVD_HOME` |
| 2 | `Failed to get EGL display` + `Could not start renderer` | 用了 `-no-window`，或没有可用 X 显示。host GPU 必须带窗口跑在 `:0` 上 |
| 3 | 命令行 `-gpu host` 但日志 `gpu_mode_requested: host ... GPU emulation enabled` 仍失败 | AVD 的 `hw.gpu.enabled = no`，改 `yes` 后重新起机 |
| 4 | 日志里出现 `-no-window ... no_window: 1`，以为当前起机方式错了 | 那是 **crashpad 回放的旧崩溃报告**，不是本次进程参数；清掉 `/tmp/android-root` 再复现 |
| 5 | `pkill -f "qemu-system..."` 之后自己的命令被杀（SIGTERM/SIGKILL） | `pkill -f` 会匹配到当前 bash 的命令行本身。用 `ps -eo pid,comm \| awk '$2 ~ /^qemu/'` 取 PID |
| 6 | `Running multiple emulators with the same AVD` | 上次 kill 后 `*.lock` 残留，删掉再起 |
| 7 | `INSTALL_FAILED_VERSION_DOWNGRADE` | 加 `-d` |
| 8 | 模拟器启动后 adb 迟迟没有设备 | 冷启动约 20–30s；`adb devices` 从 `offline` 变 `device` 再等 `sys.boot_completed=1` |
| 9 | 截图拍到了别的弹层 | `cmd uimode night` 会重建 Activity，之前打开的菜单已关闭，需要重新打开 |
| 10 | 硬编码坐标点错位置 | 先 `uiautomator dump` 取 bounds；注意预览缩放（×1.2） |

---

## 8. 经验总结

1. **能用 dump 就别猜坐标**：`uiautomator dump` + Python 解析 bounds，比反复试点省时间。
2. **排版问题用像素说话**：轨道端点、文字块左右边缘这类「对齐 / 内缩」问题，
   只用肉眼很容易得出错误结论（这次就发现默认值提示整体偏右 24dp 的坐标 bug）。
3. **改布局后必须真机/模拟器看一眼**：`measure` 出来的高度、`wrap_content` 的实际表现、
   深浅色下的对比度，静态读 XML 都推不出来。
4. **先确定「这一屏到底占多少」再改内部**：外观控件内联进菜单后内容接近一屏，
   内部再怎么调「遮挡面积」都不会变——最后是通过「量内容 + 封顶 70%」解决的。
5. **一份 layout 两个入口**（菜单内联 / 我的面板）可以省掉两套视觉分叉，
   但两个 layout 的 id 必须严格一致，否则 `findViewById` 为 null 直接崩（有意为之，宁崩不静默）。
6. **别在模拟器上做「提交前最后验证」之外的决定**：模拟器的字号缩放、系统栏高度都和真机有差，
   比例类常量（如 `MENU_SHEET_MAX_HEIGHT_RATIO`）要按真机再调一次。
