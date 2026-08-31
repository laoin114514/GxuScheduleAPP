# Schedule Update Server

西大课栈 App 的版本更新服务（Gin + GORM + 阿里云 OSS + MySQL）。

- `POST /api/v1/releases/upload` —— GitHub Actions 发版时上传 APK（API Key 鉴权）
- `GET /api/v1/apps/{appKey}/latest?versionCode=xxx` —— App 检查最新版本（公开，限流）
- `GET /healthz` —— 健康检查（Docker healthcheck 用）

## 部署（Docker Compose，国内源）

```bash
cp .env.example .env
# 编辑 .env：填 OSS 密钥、数据库地址、改 API Key、CDN_BASE_URL
docker compose up -d --build
```

镜像源：基础镜像走 `m.daocloud.io`，Go 依赖走 `goproxy.cn`，alpine apk 走阿里云镜像。

**MySQL 用外部实例**（如阿里云 RDS），compose 只起 app 服务。首次部署需要在数据库中建库建用户：

```sql
CREATE DATABASE schedule CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'schedule'@'%' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON schedule.* TO 'schedule'@'%';
```

- RDS 白名单需放行服务器 IP；`DB_HOST` 填 RDS 内网/公网地址
- 若本地已有 MySQL 容器，Docker Desktop 下 `DB_HOST` 填 `host.docker.internal`
- 服务启动时自动建表（GORM AutoMigrate）

数据库只存 OSS 对象 key（`release/{appKey}/{versionCode}/{file}`），
下载地址由响应时生成：`CDN_BASE_URL` 非空时拼 base URL；留空时返回 OSS 预签名 URL（桶私有场景）。

## 上传接口（Workflow 调用）

```bash
curl -X POST https://your-domain/api/v1/releases/upload \
  -H "X-API-Key: ${UPLOAD_API_KEY}" \
  -F "appKey=schedule" \
  -F "versionCode=10706" \
  -F "versionName=1.7.6" \
  -F "name=西大课栈" \
  -F "changelog=$(cat changelog.txt)" \
  -F "file=@app/build/outputs/apk/release/GxuScheduleAPP-release-1.7.6-universal.apk"
```

表单字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| appKey | 是 | 应用 key，`[a-z0-9_-]`，2-64 位 |
| versionCode | 是 | 正整数，与 App 上报口径一致（见下方约定） |
| versionName | 是 | 如 `1.7.6` |
| file | 是 | APK 文件，文件名限 `[A-Za-z0-9._-]` 且扩展名 .apk/.ipa/.aab |
| name | 否 | 应用显示名，首次上传注册 app 信息 |
| changelog | 否 | 更新日志（release notes） |
| forced | 否 | 强制更新标记，默认 false |

行为约定：

- **幂等**：同 `(appKey, versionCode)` 重复上传时，SHA256 一致视为成功（200）并返回已有记录；内容不同返回 409。
- **单调性**：默认拒绝发布低于当前最新 versionCode 的包；确需回滚时在 `.env` 设 `ALLOW_DOWNGRADE=true`。
- 大小上限 `MAX_UPLOAD_MB`（默认 512MB）。

响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "releaseId": 1,
    "appKey": "schedule",
    "versionCode": 10706,
    "versionName": "1.7.6",
    "fileKey": "release/schedule/10706/GxuScheduleAPP-release-1.7.6-universal.apk",
    "downloadUrl": "https://cdn.your-domain.com/release/schedule/10706/....apk",
    "sha256": "...",
    "size": 38689642
  }
}
```

### versionCode 口径（重要约定）

`app/build.gradle` 的 ABI splits 会把最终 APK 的 versionCode 乘 10（universal：`base*10`）。
**上传与 App 上报使用同一个值**，两者必须一致，否则"检查更新"永远判成已是最新：

- Workflow 传 `VERSION_CODE`（tag 派生值，如 v1.7.6 → 10706）
- App 侧上报 `BuildConfig.VERSION_CODE`（与 workflow 同源，同为 10706）

## 最新版查询接口（App 调用）

```bash
curl "https://your-domain/api/v1/apps/schedule/latest?versionCode=10706"
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "appKey": "schedule",
    "currentVersionCode": 10706,
    "hasUpdate": true,
    "latestVersionCode": 10716,
    "latestVersionName": "1.7.7",
    "changelog": "...",
    "downloadUrl": "https://cdn.your-domain.com/release/schedule/10716/....apk",
    "sha256": "...",
    "fileSize": 38689642,
    "forced": false
  }
}
```

- `hasUpdate = current < latest`，`latest` 取该 appKey 下最大 versionCode。
- 无任何发布记录时返回 `hasUpdate=false`，字段为空。
- 该接口公开，按 IP 限流（`RATE_LIMIT_RPS` / `RATE_LIMIT_BURST`）。

## 接入 GitHub Actions

在 `release.yml` 的"Extract release notes from tag"步骤之后、发布 GitHub Release 之前插入
（软失败：后端临时故障不阻断官方 Release）：

```yaml
      - name: Upload universal APK to update server（软失败）
        env:
          UPDATE_SERVER_URL: ${{ secrets.UPDATE_SERVER_URL }}
          UPDATE_SERVER_API_KEY: ${{ secrets.UPDATE_SERVER_API_KEY }}
        run: |
          UNIVERSAL_APK=$(ls app/build/outputs/apk/release/*-universal.apk 2>/dev/null | head -1) || true
          if [ -z "$UNIVERSAL_APK" ]; then
            echo "universal apk not found, skip"
            exit 0
          fi
          curl -sS -X POST "${UPDATE_SERVER_URL}/api/v1/releases/upload" \
            -H "X-API-Key: ${UPDATE_SERVER_API_KEY}" \
            -F "appKey=schedule" \
            -F "versionCode=${{ steps.version.outputs.VERSION_CODE }}" \
            -F "versionName=${{ steps.version.outputs.VERSION }}" \
            -F "name=西大课栈" \
            -F "changelog=${{ steps.release_notes.outputs.notes }}" \
            -F "file=@${UNIVERSAL_APK}" \
            || echo "::warning::上传更新服务器失败(忽略，GitHub Release 已正常发布)"
```

需要在仓库 Settings → Secrets 里配置：

| Secret | 说明 |
|---|---|
| `UPDATE_SERVER_URL` | 后端地址，如 `https://update.example.com` |
| `UPDATE_SERVER_API_KEY` | 与服务器 `.env` 的 `UPLOAD_API_KEY` 一致 |

## 本地开发

```bash
copy .env.example .env   # 填好本地 MySQL/OSS 配置
go run .
```

本地起一个 MySQL（docker 或本机安装均可），`DB_HOST=127.0.0.1`，建好库后 `go run .` 即可。
