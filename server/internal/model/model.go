package model

import "time"

// App 应用信息表。appKey 是全局唯一标识,如 "schedule"。
type App struct {
	ID        uint   `gorm:"primaryKey"`
	AppKey    string `gorm:"size:64;uniqueIndex;not null;comment:应用唯一key"`
	Name      string `gorm:"size:128;not null;comment:应用显示名"`
	Platform  string `gorm:"size:32;not null;default:android;comment:平台"`
	CreatedAt time.Time
	UpdatedAt time.Time
}

// Release 上传记录。只存 OSS 对象 key,完整下载地址由响应时拼接/签名生成。
// 唯一约束 (app_key, version_code) 防止同版本重复插入。
type Release struct {
	ID          uint   `gorm:"primaryKey"`
	AppKey      string `gorm:"size:64;not null;uniqueIndex:uk_app_version,priority:1"`
	VersionCode int64  `gorm:"not null;uniqueIndex:uk_app_version,priority:2"`
	VersionName string `gorm:"size:64;not null"`
	FileName    string `gorm:"size:256;not null;comment:原始文件名"`
	FileKey     string `gorm:"size:512;not null;comment:OSS对象key，如release/{appKey}/{versionCode}/{file}"`
	SHA256      string `gorm:"size:64;not null"`
	Size        int64  `gorm:"not null"`
	Changelog   string `gorm:"type:text"`
	Forced      bool   `gorm:"not null;default:false;comment:强制更新"`
	CreatedAt   time.Time
}
