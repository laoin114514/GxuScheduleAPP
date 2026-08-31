package handler

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"

	"github.com/laoin114514/gxuschedule-server/internal/config"
	"github.com/laoin114514/gxuschedule-server/internal/model"
	"github.com/laoin114514/gxuschedule-server/internal/oss"
)

// ---------- 通用响应 ----------

func ok(c *gin.Context, data any) {
	c.JSON(http.StatusOK, gin.H{"code": 0, "message": "ok", "data": data})
}

func fail(c *gin.Context, status, code int, msg string) {
	c.JSON(status, gin.H{"code": code, "message": msg})
}

var (
	appKeyPattern   = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{1,63}$`)
	fileNamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]*\.(apk|ipa|aab)$`)

	contentTypes = map[string]string{
		"apk": "application/vnd.android.package-archive",
		"ipa": "application/octet-stream",
		"aab": "application/octet-stream",
	}
)

// Handler 同时承载上传与查询两个接口。
type Handler struct {
	db  *gorm.DB
	oss *oss.Client
	cfg *config.Config
}

func New(db *gorm.DB, ossClient *oss.Client, cfg *config.Config) *Handler {
	return &Handler{db: db, oss: ossClient, cfg: cfg}
}

// ---------- 上传接口 ----------

type uploadReleaseReq struct {
	AppKey      string `form:"appKey" binding:"required"`
	VersionCode int64  `form:"versionCode" binding:"required"`
	VersionName string `form:"versionName" binding:"required"`
	Name        string `form:"name"`      // 应用显示名，首次创建 app 记录时用
	Changelog   string `form:"changelog"` // 更新日志（Github release notes）
	Forced      bool   `form:"forced"`
}

type releaseView struct {
	ReleaseID   uint   `json:"releaseId"`
	AppKey      string `json:"appKey"`
	VersionCode int64  `json:"versionCode"`
	VersionName string `json:"versionName"`
	FileKey     string `json:"fileKey"`
	DownloadURL string `json:"downloadUrl"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
}

// UploadRelease POST /api/v1/releases/upload
// 工作流入参: appKey / versionCode / versionName / file(multipart) 及可选 changelog/forced/name。
// 幂等: 同 (appKey, versionCode) 且 sha256 一致视为重复成功; 内容不同返回 409。
func (h *Handler) UploadRelease(c *gin.Context) {
	maxBytes := h.cfg.MaxUploadMB<<20 + 1<<20 // 额外 1MB 留给表单其他字段
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxBytes)

	var req uploadReleaseReq
	if err := c.ShouldBind(&req); err != nil {
		if isBodyTooLarge(err) {
			fail(c, http.StatusRequestEntityTooLarge, 413, fmt.Sprintf("文件过大，上限 %dMB", h.cfg.MaxUploadMB))
			return
		}
		fail(c, http.StatusBadRequest, 400, "参数错误：appKey/versionCode/versionName 必填")
		return
	}
	if !appKeyPattern.MatchString(req.AppKey) {
		fail(c, http.StatusBadRequest, 400, "appKey 非法：小写字母/数字开头，仅限 [a-z0-9_-]，长度 2-64")
		return
	}
	if req.VersionCode <= 0 {
		fail(c, http.StatusBadRequest, 400, "versionCode 必须为正整数")
		return
	}

	fileHeader, err := c.FormFile("file")
	if err != nil {
		if isBodyTooLarge(err) {
			fail(c, http.StatusRequestEntityTooLarge, 413, fmt.Sprintf("文件过大，上限 %dMB", h.cfg.MaxUploadMB))
			return
		}
		fail(c, http.StatusBadRequest, 400, "缺少文件字段 file")
		return
	}
	fileName := filepath.Base(fileHeader.Filename)
	if !fileNamePattern.MatchString(strings.ToLower(fileName)) {
		fail(c, http.StatusBadRequest, 400, "文件名非法：仅允许字母数字/._-，扩展名 .apk/.ipa/.aab")
		return
	}
	if fileHeader.Size > h.cfg.MaxUploadMB<<20 {
		fail(c, http.StatusRequestEntityTooLarge, 413, fmt.Sprintf("文件过大，上限 %dMB", h.cfg.MaxUploadMB))
		return
	}

	// 先落临时文件并计算 sha256（便于 OSS 重试和幂等判断）
	src, err := fileHeader.Open()
	if err != nil {
		fail(c, http.StatusInternalServerError, 500, "读取上传文件失败")
		return
	}
	defer src.Close()

	tmp, err := os.CreateTemp("", "release-*")
	if err != nil {
		fail(c, http.StatusInternalServerError, 500, "创建临时文件失败")
		return
	}
	defer func() {
		tmp.Close()
		os.Remove(tmp.Name())
	}()
	size, err := io.Copy(tmp, src)
	if err != nil {
		fail(c, http.StatusInternalServerError, 500, "写入临时文件失败")
		return
	}
	sum, err := sha256File(tmp)
	if err != nil {
		fail(c, http.StatusInternalServerError, 500, "计算文件哈希失败")
		return
	}

	// 幂等：同版本同内容直接返回已有记录
	var existing model.Release
	err = h.db.Where("app_key = ? AND version_code = ?", req.AppKey, req.VersionCode).First(&existing).Error
	if err == nil {
		if existing.SHA256 == sum {
			url, uerr := h.oss.URL(existing.FileKey)
			if uerr != nil {
				log.Printf("upload idempotent: sign url failed: %v", uerr)
				fail(c, http.StatusInternalServerError, 500, "生成下载地址失败")
				return
			}
			log.Printf("upload idempotent hit: appKey=%s versionCode=%d", req.AppKey, req.VersionCode)
			ok(c, toView(existing, url))
			return
		}
		fail(c, http.StatusConflict, 409, "versionCode 已存在且文件内容不同")
		return
	} else if !errors.Is(err, gorm.ErrRecordNotFound) {
		fail(c, http.StatusInternalServerError, 500, "查询失败")
		return
	}

	// 单调性：默认拒绝发布低于当前最新版本的包（防误发回滚包）
	var maxVC int64
	if err := h.db.Model(&model.Release{}).
		Where("app_key = ?", req.AppKey).
		Select("COALESCE(MAX(version_code), 0)").Scan(&maxVC).Error; err != nil {
		fail(c, http.StatusInternalServerError, 500, "查询失败")
		return
	}
	if req.VersionCode < maxVC && !h.cfg.AllowDowngrade {
		fail(c, http.StatusConflict, 409, fmt.Sprintf(
			"versionCode=%d 低于当前最新 %d；如需回滚请在 .env 开启 ALLOW_DOWNGRADE", req.VersionCode, maxVC))
		return
	}

	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(fileName), "."))
	fileKey := fmt.Sprintf("release/%s/%d/%s", req.AppKey, req.VersionCode, fileName)
	if err := h.oss.PutObject(fileKey, tmp.Name(), size, contentTypes[ext]); err != nil {
		log.Printf("oss put failed: %v", err)
		fail(c, http.StatusInternalServerError, 500, "OSS 上传失败")
		return
	}

	// app 记录：不存在则创建（带回填 name 的窗口），存在则顺带更新显示名
	app, err := h.upsertApp(req.AppKey, req.Name)
	if err != nil {
		log.Printf("upsert app failed: %v", err)
		fail(c, http.StatusInternalServerError, 500, "应用信息保存失败")
		return
	}
	_ = app

	release := model.Release{
		AppKey:      req.AppKey,
		VersionCode: req.VersionCode,
		VersionName: req.VersionName,
		FileName:    fileName,
		FileKey:     fileKey,
		SHA256:      sum,
		Size:        size,
		Changelog:   req.Changelog,
		Forced:      req.Forced,
	}
	if err := h.db.Create(&release).Error; err != nil {
		if strings.Contains(err.Error(), "Duplicate entry") {
			fail(c, http.StatusConflict, 409, "versionCode 已存在")
			return
		}
		log.Printf("insert release failed: %v", err)
		fail(c, http.StatusInternalServerError, 500, "应用信息保存失败")
		return
	}

	url, uerr := h.oss.URL(fileKey)
	if uerr != nil {
		log.Printf("sign url failed: %v", uerr)
		fail(c, http.StatusInternalServerError, 500, "生成下载地址失败")
		return
	}
	log.Printf("upload success: appKey=%s versionCode=%d name=%s file=%s sha256=%s",
		req.AppKey, req.VersionCode, req.VersionName, fileName, sum)
	ok(c, toView(release, url))
}

func (h *Handler) upsertApp(appKey, name string) (*model.App, error) {
	var app model.App
	err := h.db.Where("app_key = ?", appKey).First(&app).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		if name == "" {
			name = appKey
		}
		app = model.App{AppKey: appKey, Name: name, Platform: "android"}
		return &app, h.db.Create(&app).Error
	}
	if err != nil {
		return nil, err
	}
	if name != "" && name != app.Name {
		app.Name = name
		return &app, h.db.Save(&app).Error
	}
	return &app, nil
}

// ---------- 最新版本查询接口 ----------

type latestView struct {
	AppKey             string `json:"appKey"`
	CurrentVersionCode int64  `json:"currentVersionCode"`
	HasUpdate          bool   `json:"hasUpdate"`
	LatestVersionCode  int64  `json:"latestVersionCode"`
	LatestVersionName  string `json:"latestVersionName"`
	Changelog          string `json:"changelog"`
	DownloadURL        string `json:"downloadUrl"`
	SHA256             string `json:"sha256"`
	FileSize           int64  `json:"fileSize"`
	Forced             bool   `json:"forced"`
}

// LatestRelease GET /api/v1/apps/{appKey}/latest?versionCode=xxx
// 公开接口（带限流）。versionCode 为 App 上报的当前版本号（整数比较）。
func (h *Handler) LatestRelease(c *gin.Context) {
	appKey := c.Param("appKey")
	if !appKeyPattern.MatchString(appKey) {
		fail(c, http.StatusBadRequest, 400, "appKey 非法")
		return
	}
	current, err := strconv.ParseInt(c.DefaultQuery("versionCode", "0"), 10, 64)
	if err != nil || current < 0 {
		fail(c, http.StatusBadRequest, 400, "versionCode 必须为非负整数")
		return
	}

	view := latestView{AppKey: appKey, CurrentVersionCode: current}
	var release model.Release
	err = h.db.Where("app_key = ?", appKey).Order("version_code DESC").First(&release).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		ok(c, view) // 还没有任何发布
		return
	}
	if err != nil {
		log.Printf("latest query failed: %v", err)
		fail(c, http.StatusInternalServerError, 500, "查询失败")
		return
	}

	url, uerr := h.oss.URL(release.FileKey)
	if uerr != nil {
		log.Printf("sign url failed: %v", uerr)
		fail(c, http.StatusInternalServerError, 500, "生成下载地址失败")
		return
	}
	view.HasUpdate = current < release.VersionCode
	view.LatestVersionCode = release.VersionCode
	view.LatestVersionName = release.VersionName
	view.Changelog = release.Changelog
	view.DownloadURL = url
	view.SHA256 = release.SHA256
	view.FileSize = release.Size
	view.Forced = release.Forced
	ok(c, view)
}

// ---------- 工具函数 ----------

func toView(r model.Release, url string) releaseView {
	return releaseView{
		ReleaseID:   r.ID,
		AppKey:      r.AppKey,
		VersionCode: r.VersionCode,
		VersionName: r.VersionName,
		FileKey:     r.FileKey,
		DownloadURL: url,
		SHA256:      r.SHA256,
		Size:        r.Size,
	}
}

func sha256File(f *os.File) (string, error) {
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func isBodyTooLarge(err error) bool {
	var maxErr *http.MaxBytesError
	if errors.As(err, &maxErr) {
		return true
	}
	return strings.Contains(strings.ToLower(err.Error()), "request body too large")
}
