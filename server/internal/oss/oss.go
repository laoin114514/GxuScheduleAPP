package oss

import (
	"fmt"
	"os"

	aliyunoss "github.com/aliyun/aliyun-oss-go-sdk/oss"

	"github.com/laoin114514/gxuschedule-server/internal/config"
)

// Client 阿里云 OSS 封装。
// 数据库只存对象 key;URL(baseURL 拼 key 或预签名)由响应时生成。
type Client struct {
	bucket         *aliyunoss.Bucket
	baseURL        string
	signExpireSecs int64
}

func New(cfg *config.Config) (*Client, error) {
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
		baseURL:        cfg.CDNBaseURL,
		signExpireSecs: cfg.OSSSignExpireSecs,
	}, nil
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
