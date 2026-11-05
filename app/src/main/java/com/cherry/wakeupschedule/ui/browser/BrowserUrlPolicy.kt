package com.cherry.wakeupschedule.ui.browser

/**
 * 内置浏览器的链接去向策略（纯函数，便于 JVM 单测）。
 *
 * WebView 里点到的链接分三类：
 *
 * - [IN_APP]：`http/https`，交给内置浏览器继续在当前页加载；
 * - [EXTERNAL]：`mailto/tel/sms/market/geo` 等有系统处理方的协议，转交系统应用；
 * - [BLOCKED]：其余协议一律不执行。页面内容来自远端，不能让它借 WebView
 *   发起任意 `intent://`、读取本地 `file:/content:`、执行 `javascript:` 或 `data:`。
 *
 * 注意：这里只回答「该往哪去」，不做任何跳转；真正的跳转在
 * [InAppBrowser] 与 [InAppBrowserActivity] 里。
 */
enum class BrowserDestination {
    /** 内置浏览器内部加载 */
    IN_APP,

    /** 交给系统应用（邮件、电话、短信、应用市场、地图） */
    EXTERNAL,

    /** 拒绝执行 */
    BLOCKED,
}

object BrowserUrlPolicy {

    /** 只认 http/https；大小写不敏感（页面里出现过 `HTTPS://`） */
    private val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

    /** RFC 3986 的 scheme 语法 */
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

    /** 允许转交系统应用的协议白名单（不含 intent://，避免被远端页面拉起任意界面） */
    private val EXTERNAL_SCHEMES = setOf(
        "mailto",
        "tel",
        "sms",
        "smsto",
        "mms",
        "market",
        "geo",
    )

    /**
     * 判断链接去向。
     *
     * @param rawUrl WebView 交来的链接（可能为 null / 带首尾空白）
     */
    fun classify(rawUrl: String?): BrowserDestination {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return BrowserDestination.BLOCKED

        if (HTTP_URL.containsMatchIn(url)) return BrowserDestination.IN_APP

        // 没有合法 scheme 的字符串（相对路径、纯文本）不加载：
        // WebView 的 shouldOverrideUrlLoading 交来的通常是已解析的绝对 URL，
        // 这里保守处理，避免把任意字符串当 URL 送去 loadUrl。
        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase()
            ?: return BrowserDestination.BLOCKED

        return if (scheme in EXTERNAL_SCHEMES) {
            BrowserDestination.EXTERNAL
        } else {
            BrowserDestination.BLOCKED
        }
    }
}
