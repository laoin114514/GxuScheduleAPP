package router

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/laoin114514/gxuschedule-server/internal/config"
	"github.com/laoin114514/gxuschedule-server/internal/handler"
)

// 仅验证路由注册与中间件(鉴权/限流/参数校验)。
// Handler 的 db/oss 为 nil —— 走到处理器内部的路径这里不测(需真实 MySQL/OSS)。
func newTestRouter(t *testing.T) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	cfg := &config.Config{
		AppEnv:         "test",
		UploadAPIKey:   "testkey123",
		RateLimitRPS:   0.5,
		RateLimitBurst: 3,
	}
	return New(cfg, handler.New(nil, nil, cfg))
}

func doReq(r *gin.Engine, method, path, apiKey string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, nil)
	if apiKey != "" {
		req.Header.Set("X-API-Key", apiKey)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestHealthz(t *testing.T) {
	r := newTestRouter(t)
	if w := doReq(r, http.MethodGet, "/healthz", ""); w.Code != http.StatusOK {
		t.Fatalf("healthz = %d, want 200", w.Code)
	}
}

func TestUploadAuth(t *testing.T) {
	r := newTestRouter(t)
	if w := doReq(r, http.MethodPost, "/api/v1/releases/upload", ""); w.Code != http.StatusUnauthorized {
		t.Fatalf("no key = %d, want 401", w.Code)
	}
	if w := doReq(r, http.MethodPost, "/api/v1/releases/upload", "wrong"); w.Code != http.StatusUnauthorized {
		t.Fatalf("wrong key = %d, want 401", w.Code)
	}
}

func TestLatestParamValidation(t *testing.T) {
	r := newTestRouter(t)
	if w := doReq(r, http.MethodGet, "/api/v1/apps/BAD-KEY/latest?versionCode=1", ""); w.Code != http.StatusBadRequest {
		t.Fatalf("bad appKey = %d, want 400", w.Code)
	}
	if w := doReq(r, http.MethodGet, "/api/v1/apps/schedule/latest?versionCode=abc", ""); w.Code != http.StatusBadRequest {
		t.Fatalf("bad versionCode = %d, want 400", w.Code)
	}
}

func TestLatestRateLimit(t *testing.T) {
	r := newTestRouter(t)
	got429 := false
	for i := 0; i < 10; i++ {
		// 到处理器内部会因 db=nil 触发 recovery → 500，但限流命中时是 429
		if w := doReq(r, http.MethodGet, "/api/v1/apps/schedule/latest?versionCode=1", ""); w.Code == http.StatusTooManyRequests {
			got429 = true
			break
		}
	}
	if !got429 {
		t.Fatal("expect 429 after burst exhausted, got none")
	}
}
