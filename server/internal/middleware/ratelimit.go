package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"
)

type ipEntry struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

// IPRateLimiter 按 IP 令牌桶限流，用于公开接口防刷。
// 实现简单：本地内存 map + 定时清理不活跃条目。
type IPRateLimiter struct {
	mu      sync.Mutex
	entries map[string]*ipEntry
	rps     rate.Limit
	burst   int
}

func NewIPRateLimiter(rps float64, burst int) *IPRateLimiter {
	l := &IPRateLimiter{
		entries: make(map[string]*ipEntry),
		rps:     rate.Limit(rps),
		burst:   burst,
	}
	go func() {
		for range time.Tick(5 * time.Minute) {
			l.gc()
		}
	}()
	return l
}

func (l *IPRateLimiter) Allow(ip string) bool {
	l.mu.Lock()
	e, ok := l.entries[ip]
	if !ok {
		e = &ipEntry{limiter: rate.NewLimiter(l.rps, l.burst)}
		l.entries[ip] = e
	}
	e.lastSeen = time.Now()
	l.mu.Unlock()
	return e.limiter.Allow()
}

func (l *IPRateLimiter) gc() {
	l.mu.Lock()
	defer l.mu.Unlock()
	for ip, e := range l.entries {
		if time.Since(e.lastSeen) > 10*time.Minute {
			delete(l.entries, ip)
		}
	}
}

// RateLimit 中间件：每 IP 超出配额返回 429。
func RateLimit(l *IPRateLimiter) gin.HandlerFunc {
	return func(c *gin.Context) {
		if !l.Allow(c.ClientIP()) {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"code": 429, "message": "too many requests",
			})
			return
		}
		c.Next()
	}
}
