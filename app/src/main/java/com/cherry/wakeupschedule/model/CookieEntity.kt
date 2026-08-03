package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 持久化的 HTTP Cookie（教务系统会话）。
 *
 * 主键为 (name, domain, path)，同名同域同路径的 Set-Cookie 会覆盖旧值。
 * 字段与 okhttp3.Cookie 一一对应，[RoomCookieJar] 负责互相转换。
 */
@Entity(tableName = "cookies", primaryKeys = ["name", "domain", "path"])
data class CookieEntity(
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "domain")
    val domain: String,
    @ColumnInfo(name = "path")
    val path: String,
    /** 过期时间戳（毫秒），0 表示会话 Cookie */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long = 0L,
    @ColumnInfo(name = "secure")
    val secure: Boolean = false,
    @ColumnInfo(name = "http_only")
    val httpOnly: Boolean = false,
    /** host-only cookie 的域不得带前导点，且仅对该主机生效 */
    @ColumnInfo(name = "host_only")
    val hostOnly: Boolean = false
)
