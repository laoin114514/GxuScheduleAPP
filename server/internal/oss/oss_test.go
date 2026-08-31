package oss

import (
	"strings"
	"testing"

	"github.com/laoin114514/gxuschedule-server/internal/config"
)

// 桶名带了 endpoint 后缀（常见的 .env 填错方式）时，应直接给出友好报错，
// 而不是等 SDK 校验后报原始错误。
func TestNewRejectsBucketWithDomainSuffix(t *testing.T) {
	cfg := &config.Config{
		OSSEndpoint:        "oss-cn-shenzhen.aliyuncs.com",
		OSSAccessKeyID:     "dummy",
		OSSAccessKeySecret: "dummy",
		OSSBucket:          "laoinwork.oss-cn-shenzhen.aliyuncs.com",
	}
	_, err := New(cfg)
	if err == nil {
		t.Fatal("expect error for bucket with domain suffix, got nil")
	}
	if !strings.Contains(err.Error(), "OSS_BUCKET") {
		t.Fatalf("error should mention OSS_BUCKET, got: %v", err)
	}
}
