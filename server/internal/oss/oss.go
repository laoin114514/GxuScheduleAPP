package oss

import (
	"fmt"
	"os"
	"regexp"
	"strings"

	aliyunoss "github.com/aliyun/aliyun-oss-go-sdk/oss"

	"github.com/laoin114514/gxuschedule-server/internal/config"
)

// 桶名只允许小写字母/数字/短横线，不允许出现 "."（域名形式）
var bucketNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{1,62}$`)

// Client 阿里云 OSS 封装。
// 数据库只存对象 key;URL(baseURL 拼 key 或预签名)由响应时生成。
type Client struct {
	bucket         *aliyunoss.Bucket
	baseURL        string
	signExpireSecs int64
}

func New(cfg *config.Config) (*Client, error) {
	if !bucketNamePattern.MatchString(cfg.OSSBucket) {
		return nil, fmt.Errorf("OSS_BUCKET 只能填桶名（小写字母/数字/-），不能带 endpoint 后缀；"+
			"示例: OSS_BUCKET=laoinwork, OSS_ENDPOINT=oss-cn-shenzhen.aliyuncs.com，当前值: %q", cfg.OSSBucket)
	}
	client, err := aliyunoss.New(cfg.OSSEndpoint, cfg.OSSAccessKeyID, cfg.OSSAccessKeySecret)
	if err != nil {
		return nil, fmt.Errorf("init oss client: %w", err)
	}
	bucket, err := client.Bucket(cfg.OSSBucket)
	if err != nil {
		return nil, fmt.Errorf("init oss bucket %q: %w", cfg.OSSBucket, err)
	}
	return &Client{
		bucket:         bucket,
		baseURL:        normalizeBaseURL(cfg.CDNBaseURL),
		signExpireSecs: cfg.OSSSignExpireSecs,
	}, nil
}

// normalizeBaseURL 兼容"只填域名不填协议"的配置（默认补 https://）
func normalizeBaseURL(base string) string {
	base = strings.TrimRight(base, "/")
	if base == "" || strings.Contains(base, "://") {
		return base
	}
	return "https://" + base
}

// PutObject 从本地临时文件流式上传，不整包读入内存。
func (c *Client) PutObject(key, localPath string, size int64, contentType string) error {
	f, err := os.Open(localPath)
	if err != nil {
		return fmt.Errorf("open temp file: %w", err)
	}
	defer f.Close()
	return c.bucket.PutObject(key, f,
		aliyunoss.ContentLength(size),
		aliyunoss.ContentType(contentType))
}

// URL 返回最终下载地址:
// - 配置了 CDN_BASE_URL(桶公共读/CDN 回源):baseURL + "/" + key
// - 未配置(桶私有):生成带有效期的预签名 URL
func (c *Client) URL(key string) (string, error) {
	if c.baseURL != "" {
		return c.baseURL + "/" + key, nil
	}
	url, err := c.bucket.SignURL(key, aliyunoss.HTTPGet, c.signExpireSecs)
	if err != nil {
		return "", fmt.Errorf("sign url: %w", err)
	}
	return url, nil
}
