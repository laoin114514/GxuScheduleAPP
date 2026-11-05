package com.cherry.wakeupschedule.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内置浏览器链接策略单测。
 *
 * 边界都在这里钉死：只有 http/https 进内置浏览器；常用系统协议才转交系统应用；
 * 其余一律拒绝（远端页面不能借 WebView 执行脚本或拉起任意 Intent）。
 */
class BrowserUrlPolicyTest {

    // ==================== 内置加载 ====================

    @Test
    fun `https 与 http 走内置浏览器`() {
        assertEquals(
            BrowserDestination.IN_APP,
            BrowserUrlPolicy.classify("https://wj.qq.com/s2/28554281/u3po/")
        )
        assertEquals(
            BrowserDestination.IN_APP,
            BrowserUrlPolicy.classify("http://example.com/path?a=1&b=2#frag")
        )
    }

    @Test
    fun `协议大小写不敏感`() {
        assertEquals(BrowserDestination.IN_APP, BrowserUrlPolicy.classify("HTTPS://example.com"))
        assertEquals(BrowserDestination.IN_APP, BrowserUrlPolicy.classify("HtTp://example.com"))
        assertEquals(BrowserDestination.EXTERNAL, BrowserUrlPolicy.classify("MAILTO:a@b.com"))
    }

    @Test
    fun `首尾空白会被去掉`() {
        assertEquals(
            BrowserDestination.IN_APP,
            BrowserUrlPolicy.classify("  https://example.com  \n")
        )
    }

    // ==================== 转交系统应用 ====================

    @Test
    fun `系统协议转交外部应用`() {
        listOf(
            "mailto:someone@example.com",
            "tel:10086",
            "sms:10086",
            "smsto:10086",
            "mms:10086",
            "market://details?id=com.example",
            "geo:0,0?q=Guangxi+University",
        ).forEach { url ->
            assertEquals("应转交系统应用: $url", BrowserDestination.EXTERNAL, BrowserUrlPolicy.classify(url))
        }
    }

    // ==================== 拒绝执行 ====================

    @Test
    fun `危险协议一律拒绝`() {
        listOf(
            "javascript:alert(1)",
            "JavaScript:void(0)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "file:///data/data/com.cherry.wakeupschedule/databases/schedule.db",
            "content://com.android.contacts/contacts",
            "intent://scan/#Intent;scheme=zxing;end",
            "about:blank",
            "blob:https://example.com/uuid",
            "ftp://example.com/file.apk",
            "weixin://dl/chat",
        ).forEach { url ->
            assertEquals("应拒绝: $url", BrowserDestination.BLOCKED, BrowserUrlPolicy.classify(url))
        }
    }

    @Test
    fun `空值与无协议字符串拒绝`() {
        listOf(null, "", "   ", "not a url", "/relative/path", "example.com").forEach { url ->
            assertEquals("应拒绝: $url", BrowserDestination.BLOCKED, BrowserUrlPolicy.classify(url))
        }
    }
}
