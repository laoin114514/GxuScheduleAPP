package middleware

import (
	"crypto/subtle"
	"net/http"

	"github.com/gin-gonic/gin"
)

// APIKeyHeader 上传接口的鉴权头
const APIKeyHeader = "X-API-Key"

// APIKeyAuth 常量时间比较，防时序侧信道。
// expected 为空时直接拒绝所有请求（配置缺失时不允许裸奔）。
func APIKeyAuth(expected string) gin.HandlerFunc {
	if expected == "" {
		return func(c *gin.Context) {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{
				"code": 503, "message": "server not configured",
			})
		}
	}
	return func(c *gin.Context) {
		got := c.GetHeader(APIKeyHeader)
		if subtle.ConstantTimeCompare([]byte(got), []byte(expected)) != 1 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code": 401, "message": "invalid api key",
			})
			return
		}
		c.Next()
	}
}
