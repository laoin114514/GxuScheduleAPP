package router

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/laoin114514/gxuschedule-server/internal/config"
	"github.com/laoin114514/gxuschedule-server/internal/handler"
	"github.com/laoin114514/gxuschedule-server/internal/middleware"
)

// New 组装路由。上传接口鉴权；最新版查询公开但限流。
func New(cfg *config.Config, h *handler.Handler) *gin.Engine {
	if cfg.AppEnv == "production" {
		gin.SetMode(gin.ReleaseMode)
	}
	r := gin.New()
	r.Use(gin.Logger(), gin.Recovery())

	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

	limiter := middleware.NewIPRateLimiter(cfg.RateLimitRPS, cfg.RateLimitBurst)

	api := r.Group("/api/v1")
	api.POST("/releases/upload", middleware.APIKeyAuth(cfg.UploadAPIKey), h.UploadRelease)
	api.GET("/apps/:appKey/latest", middleware.RateLimit(limiter), h.LatestRelease)

	return r
}
