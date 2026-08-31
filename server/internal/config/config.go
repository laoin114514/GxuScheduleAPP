package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/joho/godotenv"
)

// Config 全部来自 .env(或容器环境变量),启动时校验必填项。
type Config struct {
	Port   string
	AppEnv string

	// 上传接口的 API Key,与 GitHub Actions secrets 中的值保持一致
	UploadAPIKey string

	DBHost     string
	DBPort     string
	DBUser     string
	DBPassword string
	DBName     string
	DBCharset  string

	OSSEndpoint        string
	OSSAccessKeyID     string
	OSSAccessKeySecret string
	OSSBucket          string
	// 空桶私有模式下用预签名 URL,此为签名有效期秒数
	OSSSignExpireSecs int64

	// CDNBaseURL 非空时下载地址 = CDNBaseURL + "/" + 对象 key;为空时用 OSS 预签名 URL
	CDNBaseURL string

	MaxUploadMB    int64
	AllowDowngrade bool
	RateLimitRPS   float64
	RateLimitBurst int
}

func Load() (*Config, error) {
	// .env 不存在时忽略(容器部署直接注入环境变量)
	_ = godotenv.Load()

	cfg := &Config{
		Port:               env("SERVER_PORT", "8080"),
		AppEnv:             env("APP_ENV", "development"),
		UploadAPIKey:       os.Getenv("UPLOAD_API_KEY"),
		DBHost:             os.Getenv("DB_HOST"),
		DBPort:             env("DB_PORT", "3306"),
		DBUser:             os.Getenv("DB_USER"),
		DBPassword:         os.Getenv("DB_PASSWORD"),
		DBName:             os.Getenv("DB_NAME"),
		DBCharset:          env("DB_CHARSET", "utf8mb4"),
		OSSEndpoint:        os.Getenv("OSS_ENDPOINT"),
		OSSAccessKeyID:     os.Getenv("OSS_ACCESS_KEY_ID"),
		OSSAccessKeySecret: os.Getenv("OSS_ACCESS_KEY_SECRET"),
		OSSBucket:          os.Getenv("OSS_BUCKET"),
		OSSSignExpireSecs:  envInt64("OSS_SIGN_EXPIRE_SECONDS", 3600),
		CDNBaseURL:         strings.TrimRight(os.Getenv("CDN_BASE_URL"), "/"),
		MaxUploadMB:        envInt64("MAX_UPLOAD_MB", 512),
		AllowDowngrade:     envBool("ALLOW_DOWNGRADE", false),
		RateLimitRPS:       envFloat("RATE_LIMIT_RPS", 2),
		RateLimitBurst:     envInt("RATE_LIMIT_BURST", 10),
	}
	if err := cfg.validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (c *Config) validate() error {
	var missing []string
	must := func(key, val string) {
		if val == "" {
			missing = append(missing, key)
		}
	}
	must("UPLOAD_API_KEY", c.UploadAPIKey)
	must("DB_HOST", c.DBHost)
	must("DB_USER", c.DBUser)
	must("DB_PASSWORD", c.DBPassword)
	must("DB_NAME", c.DBName)
	must("OSS_ENDPOINT", c.OSSEndpoint)
	must("OSS_ACCESS_KEY_ID", c.OSSAccessKeyID)
	must("OSS_ACCESS_KEY_SECRET", c.OSSAccessKeySecret)
	must("OSS_BUCKET", c.OSSBucket)

	if c.MaxUploadMB <= 0 {
		return fmt.Errorf("MAX_UPLOAD_MB 必须大于 0")
	}
	if c.RateLimitRPS <= 0 || c.RateLimitBurst <= 0 {
		return fmt.Errorf("RATE_LIMIT_RPS/RATE_LIMIT_BURST 必须大于 0")
	}
	if len(missing) > 0 {
		return fmt.Errorf("缺少必填配置: %s", strings.Join(missing, ", "))
	}
	return nil
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func envInt64(key string, def int64) int64 {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return def
	}
	return n
}

func envFloat(key string, def float64) float64 {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.ParseFloat(v, 64)
	if err != nil {
		return def
	}
	return n
}

func envBool(key string, def bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	return v == "true" || v == "1"
}
