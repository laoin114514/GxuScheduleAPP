#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""西大课栈 · 测试版（Beta）构建与发布脚本。

这个脚本是「本地打 tag → 另签名构建 → 直传更新服务端测试渠道」的完整实现。

它只做测试版：
  - tag 必须是 vX.Y.Zbeta[N]（如 v0.5.12beta、v0.5.12beta2），
  - 使用独立签名、独立包名（com.cherry.wakeupschedule.beta）、独立 app 名（西大课栈 Beta），
  - 上传到更新服务端的 schedule-beta 渠道（与正式版 schedule 渠道完全隔离）。

典型流程：
    # 一次性：生成 beta 签名与本地配置（生成后务必离线备份 jks）
    python3 scripts/beta_release.py --init-signing
    #   按提示把 beta 专用上传 key 填进 .beta-release.env

    # 每次发测试版：
    git tag -a v0.5.12beta1 -m "测试版标题" -m "- 改动一\n- 改动二"
    python3 scripts/beta_release.py

其他常用参数：
    --dry-run      只做预检并打印计划（不构建、不上传）
    --skip-upload  只构建 + 校验 + 记录，不上传
    --skip-build --apk <path>   复用已有 APK 直接上传（调试）
    --install      构建后顺带 adb install 到已连接设备
    --push-tag     上传成功后把 tag 推到 origin（CI 会跳过 beta tag）
    --offline      跳过后端预检（仅构建、无网络时用）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ENV_FILE = ROOT / ".beta-release.env"
DEFAULT_KEYSTORE = ROOT / "wakeup-schedule-beta-key.jks"

# 测试版渠道（与 app/build.gradle 的 beta build type 保持一致）
EXPECTED_APP_KEY = "schedule-beta"
APP_ID = "com.cherry.wakeupschedule.beta"
APP_NAME = "西大课栈 Beta"
CHANNEL = "beta"

# 版本号规则：base = MAJOR*10000 + MINOR*100 + PATCH；测试版 universal = base*10 + betaIndex
STABLE_APP_KEY = "schedule"

TAG_BETA_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)-?beta(?:\.?(\d+))?$", re.IGNORECASE)
TAG_STABLE_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")

GRADLE_TASK = ":app:assembleBeta"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / CHANNEL
RECORD_DIR = ROOT / "build" / "beta-releases"
ENV_TEMPLATE = ""  # filled at runtime

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_BUILD = 3
EXIT_VERIFY = 4
EXIT_UPLOAD = 5
EXIT_SIGNING = 6


class CliError(Exception):
    """带退出码的可预期错误。"""

    def __init__(self, message: str, code: int = EXIT_USAGE):
        super().__init__(message)
        self.code = code


def log(msg: str) -> None:
    print(f"[beta] {msg}", flush=True)


def warn(msg: str) -> None:
    print(f"[beta] ⚠ {msg}", flush=True)


def fail(msg: str, code: int = EXIT_USAGE) -> None:
    raise CliError(msg, code)


# ---------------------------------------------------------------------------
# 子进程 / git 工具
# ---------------------------------------------------------------------------


def run(
    cmd: list[str],
    *,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
    capture: bool = True,
    check: bool = True,
    timeout: int | None = None,
) -> subprocess.CompletedProcess:
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd),
            env=env,
            capture_output=capture,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError:
        fail(f"找不到可执行文件：{cmd[0]}", EXIT_USAGE)
    except subprocess.TimeoutExpired:
        fail(f"命令超时：{' '.join(cmd)}", EXIT_USAGE)
    if check and proc.returncode != 0:
        stderr = (proc.stderr or "").strip()
        stdout = (proc.stdout or "").strip()
        detail = stderr or stdout or f"exit={proc.returncode}"
        fail(f"命令失败：{' '.join(cmd)}\n{detail[-4000:]}", EXIT_USAGE)
    return proc


def git(*args: str, capture: bool = True, check: bool = True) -> str:
    return run(["git", *args], capture=capture, check=check).stdout.strip()


def git_ok(*args: str) -> bool:
    return run(["git", *args], check=False, capture=True).returncode == 0


# ---------------------------------------------------------------------------
# 本地配置
# ---------------------------------------------------------------------------


def load_env_file(path: Path) -> dict[str, str]:
    if not path.exists():
        fail(
            f"缺少本地配置文件：{path}\n"
            f"  先执行 `python3 scripts/beta_release.py --init-signing` 生成，"
            f"或参照 .beta-release.env.example 手动创建",
            EXIT_USAGE,
        )
    values: dict[str, str] = {}
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$", line)
        if not m:
            warn(f"{path.name}:{lineno} 无法解析，已忽略：{line}")
            continue
        key, value = m.group(1), m.group(2).strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def resolve_keystore(env: dict[str, str]) -> Path:
    raw = env.get("BETA_STORE_FILE", "").strip()
    if not raw:
        fail("配置缺少 BETA_STORE_FILE（beta 签名文件路径）")
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = ROOT / path
    if not path.exists():
        fail(
            f"beta 签名文件不存在：{path}\n"
            f"  若还没生成，执行 `python3 scripts/beta_release.py --init-signing`",
            EXIT_USAGE,
        )
    return path


def resolve_api_key(env: dict[str, str]) -> str:
    """上传 key：优先显式配置；留空时从本机更新服务端的 .env 读取。

    当前部署方式下更新服务与构建机是同一台机器，正式上传 key 本来就在
    GxuScheduleWebsite/.env 里；直接读取可避免把密钥复制到第二处。
    后端迁到远端后，改为填 UPDATE_API_KEY（或服务端下发的 beta 专用 key）即可。
    """
    key = env.get("UPDATE_API_KEY", "").strip()
    if key:
        return key

    server_env = env.get("SERVER_ENV_FILE", "").strip()
    if not server_env:
        fail(
            "缺少上传 key：.beta-release.env 的 UPDATE_API_KEY 为空，且未配置 SERVER_ENV_FILE。\n"
            "  单机部署（更新服务在本机）推荐：\n"
            "    SERVER_ENV_FILE=GxuScheduleWebsite/.env\n"
            "  脚本会读取该文件的 UPLOAD_API_KEY，不用把密钥再复制一份；\n"
            "  更新服务不在本机时，直接填 UPDATE_API_KEY=<服务端上传 key>。",
            EXIT_UPLOAD,
        )
    path = Path(server_env).expanduser()
    if not path.is_absolute():
        path = ROOT / path
    if not path.exists():
        fail(f"SERVER_ENV_FILE 指向的文件不存在：{path}", EXIT_UPLOAD)
    server_cfg = load_env_file(path)
    key = server_cfg.get("UPLOAD_API_KEY", "").strip()
    if not key:
        fail(f"{path} 里没有 UPLOAD_API_KEY", EXIT_UPLOAD)
    shown = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
    log(f"上传 key：读取本机更新服务端配置 {shown} 的 UPLOAD_API_KEY")
    return key


# ---------------------------------------------------------------------------
# tag / 版本号
# ---------------------------------------------------------------------------


def resolve_tag(args) -> str:
    if args.tag:
        tag = args.tag
        if not git_ok("rev-parse", "--verify", f"refs/tags/{tag}"):
            if args.create_tag:
                notes = resolve_notes_from_args(args)
                if not notes:
                    fail("--create-tag 需要 --notes 或 --notes-file 提供 tag 说明")
                with tempfile.NamedTemporaryFile(
                    "w", suffix=".txt", delete=False, encoding="utf-8"
                ) as fh:
                    fh.write(notes)
                    msg_path = fh.name
                try:
                    run(["git", "tag", "-a", tag, "-F", msg_path])
                finally:
                    os.unlink(msg_path)
                log(f"已基于 HEAD 创建 tag：{tag}")
            else:
                fail(f"tag 不存在：{tag}（可用 --create-tag 顺便创建）")
    else:
        proc = run(["git", "describe", "--tags", "--exact-match", "HEAD"], check=False)
        if proc.returncode != 0:
            fail(
                "当前 HEAD 上没有 tag。\n"
                "  先打 tag（推荐 annotated tag，注释会成为默认更新说明）：\n"
                "    git tag -a v0.5.12beta1 -m \"标题\" -m \"更新说明\"",
                EXIT_USAGE,
            )
        tag = proc.stdout.strip()

    return tag


def ensure_tag_on_head(tag: str) -> None:
    """tag 必须正好指向 HEAD，避免「tag 之后又改代码」构建出与 tag 不一致的包。"""
    tag_commit = run(["git", "rev-parse", f"{tag}^{{commit}}"]).stdout.strip()
    head_commit = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    if tag_commit != head_commit:
        fail(f"tag {tag} 不在当前 HEAD 上（tag={tag_commit[:8]} HEAD={head_commit[:8]}）")


def parse_version_tag(tag: str) -> tuple[int, int, int, int]:
    """返回 (major, minor, patch, betaIndex)。"""
    if TAG_STABLE_RE.match(tag):
        fail(
            f"{tag} 是正式版 tag，不是测试版。\n"
            f"  正式版请走 GitHub Actions：git push origin {tag}",
            EXIT_USAGE,
        )
    m = TAG_BETA_RE.match(tag)
    if not m:
        fail(
            f"无法识别测试版 tag：{tag}\n"
            f"  合法格式：v0.5.12beta / v0.5.12beta2 / v0.5.12-beta.2",
            EXIT_USAGE,
        )
    major, minor, patch = int(m.group(1)), int(m.group(2)), int(m.group(3))
    beta_index = int(m.group(4)) if m.group(4) else 1
    if minor > 99 or patch > 99:
        fail(f"版本号 minor/patch 必须 ≤ 99：{tag}")
    if not 1 <= beta_index <= 9:
        fail(f"beta 序号必须在 1..9 之间（当前 {beta_index}）；同一版本超过 9 个测试版请升 patch")
    return major, minor, patch, beta_index


def check_tag_ordering(tag: str, major: int, minor: int, patch: int, beta_index: int) -> None:
    """同一 base 下如果有更高序号的 beta tag，说明顺序错了，提前拦截。"""
    pattern = f"v{major}.{minor}.{patch}beta*"
    tags = [t for t in git("tag", "--list", pattern).splitlines() if t]
    higher: list[str] = []
    for t in tags:
        m = TAG_BETA_RE.match(t)
        if m and int(m.group(4) or 1) > beta_index:
            higher.append(t)
    if higher:
        fail(
            f"已存在更高序号的测试版 tag：{', '.join(sorted(higher))}\n"
            f"  测试渠道版本号必须单调递增，请改用更大的 beta 序号或提升 patch 版本",
            EXIT_USAGE,
        )


# ---------------------------------------------------------------------------
# 后端预检
# ---------------------------------------------------------------------------


def http_json(url: str, *, timeout: int = 20) -> dict:
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "GxuScheduleAPP-beta-release/1.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        fail(f"请求失败 HTTP {exc.code}：{url}\n{body[:500]}")
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        fail(
            f"连接更新服务失败：{url}\n  {exc}\n"
            f"  只想本地构建可用 `--offline` 跳过后端预检",
            EXIT_USAGE,
        )
    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        fail(f"更新服务返回的不是 JSON：{body[:500]}")
    if data.get("code") != 0:
        fail(f"更新服务返回错误：{body[:500]}")
    return data.get("data") or {}


def fetch_latest(base_url: str, app_key: str) -> dict:
    return http_json(f"{base_url}/api/v1/apps/{app_key}/latest?versionCode=0")


def preflight_backend(base_url: str, vc: int, base_version_code: int) -> dict:
    beta_latest = fetch_latest(base_url, EXPECTED_APP_KEY)
    stable_latest = fetch_latest(base_url, STABLE_APP_KEY)

    latest_beta_vc = int(beta_latest.get("latestVersionCode") or 0)
    latest_stable_vc = int(stable_latest.get("latestVersionCode") or 0)

    log(
        f"渠道现状：beta={beta_latest.get('latestVersionName') or '-'}({latest_beta_vc}) "
        f"stable={stable_latest.get('latestVersionName') or '-'}({latest_stable_vc})"
    )

    if vc <= latest_beta_vc:
        fail(
            f"测试渠道当前最新 versionCode={latest_beta_vc}，本次要发布 {vc} 不大于它。\n"
            f"  同一版本重发请把 beta 序号加一（如 ...beta2），否则服务端会拒绝覆盖",
            EXIT_USAGE,
        )
    if base_version_code * 10 <= latest_stable_vc:
        warn(
            f"测试版基线（对应正式版 versionCode≈{base_version_code * 10}）"
            f"不高于正式版最新（{latest_stable_vc}），请确认这是预期行为"
        )
    return {"beta_latest_vc": latest_beta_vc, "stable_latest_vc": latest_stable_vc}


# ---------------------------------------------------------------------------
# 更新说明
# ---------------------------------------------------------------------------


def resolve_notes_from_args(args) -> str:
    if args.notes:
        return args.notes.strip()
    if args.notes_file:
        path = Path(args.notes_file)
        if not path.is_absolute():
            path = ROOT / path
        if not path.exists():
            fail(f"更新说明文件不存在：{path}")
        return path.read_text(encoding="utf-8").strip()
    return ""


def resolve_changelog(tag: str, args) -> str:
    notes = resolve_notes_from_args(args)
    if notes:
        return notes

    # annotated tag 的注释优先（正式发版也是这个约定）
    contents = run(
        ["git", "for-each-ref", f"refs/tags/{tag}", "--format=%(contents)"], check=False
    ).stdout.strip()
    if contents:
        return contents

    # 回退到「上一个 tag 以来」的提交标题
    prev = run(
        ["git", "describe", "--tags", "--abbrev=0", "--match", "v*", "HEAD^"],
        check=False,
    ).stdout.strip()
    if prev:
        log_lines = run(
            ["git", "log", "--no-merges", "--pretty=format:- %s", f"{prev}..HEAD"],
            check=False,
        ).stdout.strip()
    else:
        log_lines = run(
            ["git", "log", "--no-merges", "--pretty=format:- %s", "-n", "15", "HEAD"],
            check=False,
        ).stdout.strip()
    return log_lines or "（无更新说明）"


# ---------------------------------------------------------------------------
# 构建
# ---------------------------------------------------------------------------


def build_beta(args, base_version_code: int, version_name: str, beta_index: int, env: dict[str, str]) -> None:
    if APK_DIR.exists():
        for stale in APK_DIR.glob("*-universal.apk"):
            try:
                stale.unlink()
            except OSError:
                pass

    gradle_env = dict(os.environ)
    gradle_env.update(
        {
            # 敏感信息走环境变量，避免进入进程列表 / shell 历史
            "ORG_GRADLE_PROJECT_BETA_STORE_FILE": str(resolve_keystore(env)),
            "ORG_GRADLE_PROJECT_BETA_STORE_PASSWORD": env.get("BETA_STORE_PASSWORD", ""),
            "ORG_GRADLE_PROJECT_BETA_KEY_ALIAS": env.get("BETA_KEY_ALIAS", "beta"),
            "ORG_GRADLE_PROJECT_BETA_KEY_PASSWORD": env.get("BETA_KEY_PASSWORD", ""),
        }
    )
    if args.gradle_home:
        gradle_env["GRADLE_USER_HOME"] = str(Path(args.gradle_home).expanduser().resolve())

    cmd = [
        str(ROOT / "gradlew"),
        GRADLE_TASK,
        f"-PappVersionCode={base_version_code}",
        f"-PappVersionName={version_name}",
        f"-PbetaIndex={beta_index}",
    ]
    if args.offline_build:
        cmd.append("--offline")

    display = " ".join(cmd[1:])
    log(f"构建命令：./gradlew {display}")
    proc = run(cmd, env=gradle_env, capture=False, check=False)
    if proc.returncode != 0:
        fail("Gradle 构建失败（完整日志见上方输出）", EXIT_BUILD)


def locate_apk() -> Path:
    apks = sorted(APK_DIR.glob("*-universal.apk"))
    if not apks:
        fail(f"构建结束但没有找到 universal APK：{APK_DIR}", EXIT_BUILD)
    if len(apks) > 1:
        warn(f"发现多个 universal APK，使用最新的一个：{apks[-1].name}")
    return apks[-1]


# ---------------------------------------------------------------------------
# APK 校验
# ---------------------------------------------------------------------------


def find_sdk() -> Path:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(key)
        if value and Path(value).exists():
            return Path(value)
    local_props = ROOT / "local.properties"
    if local_props.exists():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip())
    fail(
        "找不到 Android SDK（ANDROID_HOME / ANDROID_SDK_ROOT / local.properties 均未配置），"
        "无法校验 APK；可加 --skip-verify 跳过",
        EXIT_VERIFY,
    )


def find_build_tools() -> tuple[Path, Path]:
    sdk = find_sdk()
    tools_root = sdk / "build-tools"
    if not tools_root.exists():
        fail(f"SDK 下没有 build-tools 目录：{tools_root}", EXIT_VERIFY)

    def version_key(path: Path) -> tuple[int, ...]:
        return tuple(int(p) for p in re.findall(r"\d+", path.name)) or (0,)

    for candidate in sorted(tools_root.iterdir(), key=version_key, reverse=True):
        aapt = candidate / "aapt"
        apksigner = candidate / "apksigner"
        if aapt.exists() and apksigner.exists():
            return aapt, apksigner
    fail(f"build-tools 中找不到 aapt/apksigner：{tools_root}", EXIT_VERIFY)


def verify_apk(apk: Path, version_code: int, version_name: str) -> str:
    """校验包名/版本/签名，返回文件 sha256。"""
    aapt, apksigner = find_build_tools()

    badging = run([str(aapt), "dump", "badging", str(apk)], check=False).stdout
    pkg = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not pkg:
        fail("aapt 无法解析 APK 元信息（包可能损坏）", EXIT_VERIFY)
    got_pkg, got_vc, got_vn = pkg.group(1), pkg.group(2), pkg.group(3)
    if got_pkg != APP_ID:
        fail(f"包名不符：期望 {APP_ID}，实际 {got_pkg}", EXIT_VERIFY)
    if got_vc != str(version_code):
        fail(f"versionCode 不符：期望 {version_code}，实际 {got_vc}", EXIT_VERIFY)
    if got_vn != version_name:
        fail(f"versionName 不符：期望 {version_name}，实际 {got_vn}", EXIT_VERIFY)

    labels = re.findall(r"application-label(?:-[A-Za-z-]+)?:'([^']*)'", badging)
    labels = [x for x in labels if x]
    if labels and labels[0] != APP_NAME:
        warn(f"应用显示名与预期不同：期望「{APP_NAME}」，实际「{labels[0]}」")
    log(f"APK 元信息：package={got_pkg} versionCode={got_vc} versionName={got_vn} label={labels[0] if labels else '-'}")

    proc = run([str(apksigner), "verify", "--print-certs", str(apk)], check=False)
    if proc.returncode != 0:
        fail(f"APK 签名校验失败：\n{(proc.stderr or proc.stdout)[-2000:]}", EXIT_VERIFY)
    cert_sha = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]+)", proc.stdout)
    log(f"签名校验通过，证书 SHA-256：{cert_sha.group(1) if cert_sha else '(未解析到)'}")

    digest = hashlib.sha256()
    with apk.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


# ---------------------------------------------------------------------------
# 上传
# ---------------------------------------------------------------------------


def upload_release(
    *,
    base_url: str,
    api_key: str,
    apk: Path,
    version_code: int,
    version_name: str,
    changelog: str,
    sha256: str,
) -> dict:
    if not api_key:
        fail(
            "缺少上传 key：请在 .beta-release.env 里填写 UPDATE_API_KEY，"
            "或配置 SERVER_ENV_FILE=GxuScheduleWebsite/.env 从本机服务端读取",
            EXIT_UPLOAD,
        )

    notes_file = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8")
    try:
        notes_file.write(changelog)
        notes_file.close()

        cmd = [
            "curl",
            "-sS",
            "--fail-with-body",
            "--retry", "3",
            "--retry-delay", "3",
            "--retry-max-time", "600",
            "-X", "POST",
            f"{base_url}/api/v1/releases/upload",
            "-H", f"X-API-Key: {api_key}",
            "-F", f"appKey={EXPECTED_APP_KEY}",
            "-F", f"versionCode={version_code}",
            "-F", f"versionName={version_name}",
            "-F", f"name={APP_NAME}",
            "-F", f"changelog=<{notes_file.name}",
            "-F", f"file=@{apk};type=application/vnd.android.package-archive",
        ]
        log(f"上传：{base_url}/api/v1/releases/upload  (appKey={EXPECTED_APP_KEY}, versionCode={version_code})")
        proc = run(cmd, check=False, timeout=900)
        body = (proc.stdout or "").strip()
        if proc.returncode != 0:
            hint = upload_error_hint(body, proc.returncode)
            fail(f"上传失败（curl exit={proc.returncode}）：{body[:1000]}\n{hint}", EXIT_UPLOAD)

        try:
            payload = parse_response_json(body)
        except CliError:
            fail(f"上传响应不是 JSON：{body[:1000]}", EXIT_UPLOAD)
        if payload.get("code") != 0:
            fail(f"上传失败：{body[:1000]}\n{upload_error_hint(body, proc.returncode)}", EXIT_UPLOAD)

        data = payload.get("data") or {}
        if int(data.get("versionCode") or 0) != version_code:
            fail(f"上传响应 versionCode 不一致：{data.get('versionCode')} != {version_code}", EXIT_UPLOAD)
        if data.get("sha256") and str(data["sha256"]).lower() != sha256:
            fail("上传响应 sha256 与本地不一致，请检查是否传错文件", EXIT_UPLOAD)
        return data
    finally:
        try:
            os.unlink(notes_file.name)
        except OSError:
            pass


def parse_response_json(body: str) -> dict:
    """解析上传响应。curl 在重试场景下可能输出多段 JSON，取最后一段。"""
    text = (body or "").strip()
    if not text:
        fail("上传响应为空", EXIT_UPLOAD)
    decoder = json.JSONDecoder()
    parsed: list[dict] = []
    idx = 0
    while True:
        start = text.find("{", idx)
        if start < 0:
            break
        try:
            obj, end = decoder.raw_decode(text, start)
        except json.JSONDecodeError:
            idx = start + 1
            continue
        if isinstance(obj, dict):
            parsed.append(obj)
        idx = end
    if not parsed:
        fail(f"上传响应不是 JSON：{text[:1000]}", EXIT_UPLOAD)
    return parsed[-1]


def upload_error_hint(body: str, curl_exit: int) -> str:
    low = body.lower()
    if "invalid api key" in low:
        return (
            "  → 上传 key 错误。\n"
            "    单机部署：脚本默认读取 GxuScheduleWebsite/.env 的 UPLOAD_API_KEY；\n"
            "    请确认 SERVER_ENV_FILE 指向正确、服务端容器已加载该 key（改 .env 后需重启）。\n"
            "    后端在远端时：直接配置 UPDATE_API_KEY；若服务端启用了 beta 专用 key，\n"
            "    则填 UPLOAD_API_KEY_BETA 的值（该 key 只能上传 *-beta 渠道）。"
        )
    if "beta 上传密钥仅允许" in body or "仅允许上传" in body:
        return "  → beta key 只允许上传 appKey 以 -beta 结尾的渠道，请检查脚本 appKey 配置"
    if "低于当前最新" in body or "allow_downgrade" in low:
        return "  → 版本号倒退。测试渠道最新版更高，请提升版本号 / beta 序号"
    if "已存在" in body or "duplicate" in low:
        return "  → 同 versionCode 已存在且内容不同。提升 beta 序号（如 ...beta2）后重发"
    if curl_exit == 28:
        return "  → 网络超时，弱网可重试；确认本机能访问更新服务域名"
    if curl_exit == 22:
        return "  → 服务端返回了非 2xx（见上方响应体）"
    return "  → 可查看服务端日志定位；确认 appKey/versionCode/文件大小限制"


def verify_after_upload(base_url: str, expected_vc: int, stable_vc_before: int) -> dict:
    beta_latest = fetch_latest(base_url, EXPECTED_APP_KEY)
    if int(beta_latest.get("latestVersionCode") or 0) < expected_vc:
        fail("上传后测试渠道最新版本仍低于本次发布，请检查服务端状态", EXIT_UPLOAD)
    stable_latest = fetch_latest(base_url, STABLE_APP_KEY)
    stable_vc_after = int(stable_latest.get("latestVersionCode") or 0)
    if stable_vc_before and stable_vc_after != stable_vc_before:
        fail(
            f"发布期间正式渠道最新版本发生变化（{stable_vc_before} → {stable_vc_after}），"
            f"请人工复核是否误传正式渠道",
            EXIT_UPLOAD,
        )
    return beta_latest


# ---------------------------------------------------------------------------
# 记录与提示
# ---------------------------------------------------------------------------


def write_record(record: dict) -> Path:
    RECORD_DIR.mkdir(parents=True, exist_ok=True)
    path = RECORD_DIR / f"{record['tag']}.json"
    path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def print_summary(record: dict, record_path: Path | None) -> None:
    print()
    print("=" * 68)
    print(f"  测试版发布完成：{record['tag']}")
    print("=" * 68)
    print(f"  渠道        : {record['appKey']}（正式版是 {STABLE_APP_KEY}，互相隔离）")
    print(f"  包名        : {APP_ID}")
    print(f"  版本名      : {record['versionName']}")
    print(f"  versionCode : {record['versionCode']}")
    print(f"  APK         : {record['apk']}")
    if record.get("sha256"):
        print(f"  SHA-256     : {record['sha256']}")
    if record.get("size"):
        print(f"  大小        : {record['size']} bytes")
    if record.get("downloadUrl"):
        print(f"  下载直链    : {record['downloadUrl']}")
    if record_path:
        print(f"  本地记录    : {record_path}")
    print()
    print("  给测试者：")
    print("    1) 首次安装「西大课栈 Beta」需要允许安装未知来源应用（与正式版并存，数据独立）")
    print("    2) 之后每次启动 App 会自动检查测试渠道更新，点更新即可覆盖安装")
    print("    3) 想回到正式版：卸载 Beta，重新安装正式版（正式版数据一直不受影响）")
    print()
    if record.get("tagPushed") is False:
        print("  提示：tag 仅存在于本地。需要留档可执行 `git push origin " + record["tag"] + "`，")
        print("        release.yml 已配置跳过 beta tag，不会触发正式发版。")
    print()


# ---------------------------------------------------------------------------
# 签名初始化
# ---------------------------------------------------------------------------


def init_signing(args) -> None:
    keystore = Path(args.keystore).expanduser() if args.keystore else DEFAULT_KEYSTORE
    keystore = keystore if keystore.is_absolute() else ROOT / keystore
    env_path = Path(args.env_file).expanduser()
    env_path = env_path if env_path.is_absolute() else ROOT / env_path

    if keystore.exists() and not args.force:
        fail(f"签名文件已存在：{keystore}\n  如需重建请加 --force（重建后测试者必须卸载重装）", EXIT_SIGNING)
    if env_path.exists() and not args.force:
        fail(f"配置文件已存在：{env_path}\n  如需重建请加 --force", EXIT_SIGNING)

    keytool = shutil.which("keytool")
    if not keytool:
        java_home = os.environ.get("JAVA_HOME")
        if java_home and (Path(java_home) / "bin" / "keytool").exists():
            keytool = str(Path(java_home) / "bin" / "keytool")
    if not keytool:
        fail("找不到 keytool（请安装 JDK 17 或设置 JAVA_HOME）", EXIT_SIGNING)

    password = args.password or secrets.token_hex(16)
    store_type = "PKCS12"
    log(f"生成 beta 签名：{keystore}")
    run(
        [
            keytool,
            "-genkeypair",
            "-keystore", str(keystore),
            "-storetype", store_type,
            "-alias", "beta",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=GxuSchedule APP Beta, OU=dev, O=laoin, L=Nanning, ST=Guangxi, C=CN",
            "-storepass", password,
            "-keypass", password,
        ],
        check=False,
    )
    if not keystore.exists():
        fail("签名生成失败（keytool 未产出文件）", EXIT_SIGNING)

    api_key = args.api_key or ""
    env_path.parent.mkdir(parents=True, exist_ok=True)
    env_path.write_text(
        ENV_TEMPLATE.format(
            base_url="https://easo.laoin.work/schedule",
            api_key=api_key,
            store_file=keystore.name if keystore.parent == ROOT else str(keystore),
            store_password=password,
            key_alias="beta",
            key_password=password,
        ),
        encoding="utf-8",
    )
    try:
        env_path.chmod(0o600)
    except OSError:
        pass
    keystore.chmod(0o600)

    print()
    print("=" * 68)
    print("  beta 签名与本地配置已生成")
    print("=" * 68)
    print(f"  签名文件 : {keystore}")
    print(f"  配置文件 : {env_path}（权限 600，已被 .gitignore 忽略）")
    if not args.api_key:
        print(f"  上传 key : 已配置从 GxuScheduleWebsite/.env 读取（单机部署）")
        print(f"             更新服务在远端时，编辑 {env_path.name} 填 UPDATE_API_KEY")
    print()
    print("  ⚠ 请立即离线备份签名文件与密码：")
    print("     证书丢失后，测试者无法覆盖安装新 beta（只能卸载重装）。")
    print()


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="西大课栈测试版（Beta）构建与发布",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
        "  python3 scripts/beta_release.py --init-signing\n"
        "  git tag -a v0.5.12beta1 -m \"标题\" -m \"说明\"\n"
        "  python3 scripts/beta_release.py\n",
    )
    p.add_argument("--tag", help="显式指定 tag（默认取 HEAD 上正好指向它的 tag）")
    p.add_argument("--create-tag", action="store_true", help="tag 不存在时基于 HEAD 创建 annotated tag")
    p.add_argument("--notes", help="更新说明（优先于 tag 注释）")
    p.add_argument("--notes-file", help="从文件读取更新说明")
    p.add_argument("--beta-index", type=int, help="覆盖 tag 中的 beta 序号（一般不用）")
    p.add_argument("--dry-run", action="store_true", help="只预检并打印计划，不构建不上传")
    p.add_argument("--skip-build", action="store_true", help="跳过构建（配合 --apk）")
    p.add_argument("--apk", help="复用已有 APK 路径")
    p.add_argument("--skip-upload", action="store_true", help="只构建/校验/记录，不上传")
    p.add_argument("--skip-verify", action="store_true", help="跳过 aapt/apksigner 校验（不推荐）")
    p.add_argument("--install", action="store_true", help="构建后 adb install 到已连接设备")
    p.add_argument("--push-tag", action="store_true", help="上传成功后把 tag 推到 origin")
    p.add_argument("--offline", action="store_true", help="跳过更新服务预检（仅本地构建）")
    p.add_argument("--offline-build", action="store_true", help="Gradle 加 --offline")
    p.add_argument("--gradle-home", help="自定义 GRADLE_USER_HOME（本机缓存目录）")
    p.add_argument("--env-file", default=str(DEFAULT_ENV_FILE), help="本地配置路径（默认 .beta-release.env）")
    p.add_argument("--allow-dirty", action="store_true", help="允许工作区有未提交改动（仅调试用）")
    p.add_argument("--init-signing", action="store_true", help="一次性生成 beta 签名与本地配置文件")
    p.add_argument("--keystore", help="--init-signing 时指定签名输出路径")
    p.add_argument("--api-key", help="--init-signing 时直接写入上传 key")
    p.add_argument("--password", help="--init-signing 时指定签名密码（默认随机生成）")
    p.add_argument("--force", action="store_true", help="--init-signing 时允许覆盖已有文件")
    return p


def main(argv: list[str] | None = None) -> int:
    global ENV_TEMPLATE
    args = build_parser().parse_args(argv)

    ENV_TEMPLATE = """# 西大课栈 · 本地测试版发布配置
# 该文件已被 .gitignore 忽略，请勿提交；权限建议 600。

# 更新服务地址（正式/测试共用，只是 appKey 不同）
UPDATE_BASE_URL={base_url}

# 上传 key：
# - 单机部署（更新服务与构建机是同一台机器）：留空即可，脚本会从下面
#   SERVER_ENV_FILE 指向的服务端 .env 读取 UPLOAD_API_KEY，避免密钥复制第二份；
# - 更新服务在远端：填写远端下发的上传 key；若服务端启用了 beta 专用 key，
#   就填 UPLOAD_API_KEY_BETA（只能上传 *-beta 渠道）。
UPDATE_API_KEY={api_key}

# 本机更新服务端的配置文件（单机部署用；后端不在本机时删掉这行）
SERVER_ENV_FILE=GxuScheduleWebsite/.env

# 测试版渠道 appKey（与 app/build.gradle 的 beta build type 一致）
BETA_APP_KEY=schedule-beta

# 测试版签名（请离线备份）
BETA_STORE_FILE={store_file}
BETA_STORE_PASSWORD={store_password}
BETA_KEY_ALIAS={key_alias}
BETA_KEY_PASSWORD={key_password}
"""

    try:
        if args.init_signing:
            init_signing(args)
            return EXIT_OK

        env_path = Path(args.env_file).expanduser()
        env_path = env_path if env_path.is_absolute() else ROOT / env_path
        cfg = load_env_file(env_path)

        base_url = cfg.get("UPDATE_BASE_URL", "").rstrip("/")
        if not base_url:
            fail(f"{env_path.name} 缺少 UPDATE_BASE_URL")
        app_key = cfg.get("BETA_APP_KEY", EXPECTED_APP_KEY)
        if app_key != EXPECTED_APP_KEY:
            fail(f"BETA_APP_KEY 必须为 {EXPECTED_APP_KEY}（当前 {app_key}）")
        if not app_key.endswith("-beta"):
            fail(f"BETA_APP_KEY 必须以 -beta 结尾（当前 {app_key}）")

        # 上传 key：留空时从本机更新服务端 .env 读取（单机部署，避免密钥复制）
        api_key = "" if args.skip_upload else resolve_api_key(cfg)

        # 1) tag / 版本
        tag = resolve_tag(args)
        # 先判断 tag 类型（正式版 tag 立刻给出明确指引），再校验它是否指向 HEAD
        major, minor, patch, tag_beta_index = parse_version_tag(tag)
        ensure_tag_on_head(tag)
        if args.beta_index is not None:
            if args.beta_index != tag_beta_index:
                fail(
                    f"--beta-index={args.beta_index} 与 tag 中的序号 {tag_beta_index} 不一致，"
                    f"二者保持一致或去掉 --beta-index"
                )
            beta_index = args.beta_index
        else:
            beta_index = tag_beta_index

        check_tag_ordering(tag, major, minor, patch, beta_index)
        base_version_code = major * 10000 + minor * 100 + patch
        version_code = base_version_code * 10 + beta_index
        version_name = f"{major}.{minor}.{patch}-beta.{beta_index}"
        if version_code > 2_147_483_647:
            fail(f"versionCode={version_code} 超出 Int 范围")

        # 2) 工作区干净（tag 与实际构建内容一致）
        dirty = git("status", "--porcelain", "--untracked-files=no")
        if dirty and not args.allow_dirty:
            fail(
                "工作区有未提交的改动，构建出的包与 tag 内容不一致：\n" + dirty +
                "\n  请先提交，或仅在调试时加 --allow-dirty"
            )
        if dirty and args.allow_dirty:
            warn("工作区有未提交改动（--allow-dirty），产物不代表 tag 内容")

        commit = git("rev-parse", "--short", "HEAD")
        changelog = resolve_changelog(tag, args)

        print()
        log(f"tag          : {tag}")
        log(f"渠道          : {app_key}")
        log(f"版本          : {version_name}  (versionCode={version_code})")
        log(f"包名          : {APP_ID}")
        log(f"commit        : {commit}")
        print("[beta] 更新说明:")
        for line in changelog.splitlines() or ["（空）"]:
            print(f"         {line}")
        print()

        # 3) 后端预检
        preflight = None
        if args.offline:
            warn("--offline：跳过更新服务预检")
        else:
            preflight = preflight_backend(base_url, version_code, base_version_code)

        if args.dry_run:
            if not args.skip_build:
                # dry-run 也提前验证签名文件与工具链，避免"看起来能跑，真跑才炸"
                resolve_keystore(cfg)
            log("--dry-run：预检通过，未构建、未上传")
            log(f"将执行：./gradlew {GRADLE_TASK} -PappVersionCode={base_version_code} "
                f"-PappVersionName={version_name} -PbetaIndex={beta_index}")
            log(f"将上传：{base_url}/api/v1/releases/upload (appKey={app_key}, versionCode={version_code})")
            return EXIT_OK

        # 4) 构建
        if args.skip_build:
            if not args.apk:
                fail("--skip-build 必须同时用 --apk 指定要上传的 APK")
            apk = Path(args.apk).expanduser()
            apk = apk if apk.is_absolute() else ROOT / apk
            if not apk.exists():
                fail(f"APK 不存在：{apk}", EXIT_BUILD)
        else:
            build_beta(args, base_version_code, version_name, beta_index, cfg)
            apk = locate_apk()

        # 5) 校验
        sha256 = ""
        apk_size = apk.stat().st_size
        if args.skip_verify:
            warn("--skip-verify：未校验包名/版本/签名")
        else:
            sha256 = verify_apk(apk, version_code, version_name)
        if not sha256:
            digest = hashlib.sha256()
            with apk.open("rb") as fh:
                for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                    digest.update(chunk)
            sha256 = digest.hexdigest()
        log(f"APK：{apk}  ({apk_size} bytes, sha256={sha256[:16]}…)")

        # 6) 上传
        upload_data: dict = {}
        tag_pushed = False
        if args.skip_upload:
            warn("--skip-upload：未上传更新服务，仅保留本地产物")
        else:
            upload_data = upload_release(
                base_url=base_url,
                api_key=api_key,
                apk=apk,
                version_code=version_code,
                version_name=version_name,
                changelog=changelog,
                sha256=sha256,
            )
            log(f"上传成功，下载地址：{upload_data.get('downloadUrl', '')}")
            if preflight is not None:
                verified = verify_after_upload(
                    base_url, version_code, int(preflight["stable_latest_vc"])
                )
                log(f"复检通过：测试渠道最新 = {verified.get('latestVersionName')}"
                    f"({verified.get('latestVersionCode')})，正式渠道未变化")

        # 7) 记录
        record = {
            "tag": tag,
            "channel": CHANNEL,
            "appKey": app_key,
            "appId": APP_ID,
            "appName": APP_NAME,
            "versionName": version_name,
            "versionCode": version_code,
            "baseVersionCode": base_version_code,
            "betaIndex": beta_index,
            "gitCommit": commit,
            "changelog": changelog,
            "apk": str(apk.relative_to(ROOT) if apk.is_relative_to(ROOT) else apk),
            "size": apk_size,
            "sha256": sha256,
            "downloadUrl": upload_data.get("downloadUrl", ""),
            "uploaded": bool(upload_data),
            "uploadedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
            "tagPushed": tag_pushed,
        }
        record_path = write_record(record)

        # 8) 可选：推到远端 / 装到设备
        if args.push_tag:
            log(f"推送 tag：git push origin {tag}")
            proc = run(["git", "push", "origin", tag], check=False)
            if proc.returncode == 0:
                tag_pushed = True
                record["tagPushed"] = True
                record_path = write_record(record)
            else:
                warn("tag 推送失败（不影响已发布的测试包）")
        if args.install:
            install_apk(apk)

        print_summary(record, record_path)
        return EXIT_OK
    except CliError as exc:
        print(f"\n[beta] ✖ {exc}", file=sys.stderr)
        if exc.code == EXIT_USAGE:
            print("[beta] 用法参考：python3 scripts/beta_release.py --help", file=sys.stderr)
        return exc.code
    except KeyboardInterrupt:
        print("\n[beta] 已取消", file=sys.stderr)
        return 130


def install_apk(apk: Path) -> None:
    try:
        adb_path = str(find_sdk() / "platform-tools" / "adb")
    except CliError:
        adb_path = ""
    if not adb_path or not Path(adb_path).exists():
        adb_path = shutil.which("adb") or ""
    if not adb_path:
        warn("找不到 adb，跳过安装")
        return
    log(f"安装到已连接设备：{apk.name}")
    proc = run([adb_path, "install", "-r", "-d", str(apk)], check=False, timeout=300)
    if proc.returncode != 0:
        warn("adb install 失败（设备未连接 / 版本降级 / 签名不一致），不影响发布结果")


if __name__ == "__main__":
    sys.exit(main())
