package com.yuanbao.downloader.browser

import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import java.net.URLEncoder

/**
 * 加载生命周期委托。
 *
 * 迁移对照（WebViewClient -> GeckoView）：
 * 页面开始       -> NavigationDelegate.onLocationChange / ProgressDelegate.onPageStart
 * 重定向与拦截   -> NavigationDelegate.onLoadRequest（GeckoResult.deny() 阻断）
 * 加载完成       -> ProgressDelegate.onPageStop
 * 加载错误       -> NavigationDelegate.onLoadError（返回错误页 data URI）
 * 新窗口         -> NavigationDelegate.onNewSession（接住 window.open）
 *
 * 注意：当前 Stable 的 GeckoView 把 onPageStop 放在 ProgressDelegate 而不是
 * NavigationDelegate，因此本类同时实现两个接口，由同一实例承载全部生命周期回调。
 */
class GeckoNavigationDelegate(
    private val tabId: String,
    private val state: GeckoLiveState,
    private val callbacks: GeckoBrowserCallbacks,
    private val createWindowSession: (String) -> GeckoSession,
    private val openExternally: (String) -> Boolean
) : GeckoSession.NavigationDelegate, GeckoSession.ProgressDelegate {

    private fun emit() {
        callbacks.onTabChange?.invoke(state.snapshot(tabId))
    }

    // GeckoResult.allow() / deny() 在部分版本以原始类型声明，显式转换保证 Kotlin 泛型匹配。
    @Suppress("UNCHECKED_CAST")
    private fun allow(): GeckoResult<AllowOrDeny> =
        GeckoResult.allow() as GeckoResult<AllowOrDeny>

    @Suppress("UNCHECKED_CAST")
    private fun deny(): GeckoResult<AllowOrDeny> =
        GeckoResult.deny() as GeckoResult<AllowOrDeny>

    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny> {
        val uri = request.uri.orEmpty()
        if (uri.isBlank()) return deny()

        // 外部协议交给系统处理，并阻断在 GeckoView 内的导航
        if (!isSupportedScheme(uri)) {
            openExternally(uri)
            return deny()
        }

        if (request.isRedirect) {
            callbacks.onLocationChange?.invoke(tabId, uri)
        }
        // TARGET_WINDOW_NEW 由 onNewSession 接住，此处不重复创建标签
        return allow()
    }

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean?
    ) {
        state.url = url?.takeIf { it.isNotBlank() } ?: "about:blank"
        state.progress = 5
        callbacks.onLocationChange?.invoke(tabId, state.url)
        emit()
    }

    override fun onLoadError(
        session: GeckoSession,
        uri: String,
        error: WebRequestError
    ): GeckoResult<String> {
        val reason = runCatching { error.toString() }.getOrDefault("页面加载失败")
        callbacks.onLoadError?.invoke(tabId, uri.orEmpty())
        return GeckoResult.fromValue(buildErrorPage(uri.orEmpty(), reason))
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
        val newSession = createWindowSession(uri.orEmpty())
        return GeckoResult.fromValue(newSession)
    }

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        state.canBack = canGoBack
        emit()
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        state.canForward = canGoForward
        emit()
    }

    // ProgressDelegate

    override fun onPageStart(session: GeckoSession, url: String) {
        state.url = url?.takeIf { it.isNotBlank() } ?: state.url
        state.progress = 10
        emit()
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        state.progress = 100
        emit()
        callbacks.onPageFinished?.invoke(session)
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        state.progress = progress.coerceIn(0, 100)
        emit()
    }

    private fun isSupportedScheme(uri: String): Boolean {
        val scheme = uri.substringBefore(":").lowercase()
        return scheme in setOf("http", "https", "ftp", "about", "data", "file", "blob", "javascript")
    }

    /** 构造内联错误页，避免加载失败时停留在空白页。 */
    private fun buildErrorPage(uri: String, reason: String): String {
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>页面加载失败</title>")
            append("<style>body{font-family:sans-serif;padding:24px;background:#f7f7f9;color:#1b1b1f}")
            append("h2{font-size:20px}p{font-size:14px;line-height:1.6;color:#5a5a66}")
            append("code{word-break:break-all;font-size:12px}</style></head><body>")
            append("<h2>页面加载失败</h2>")
            append("<p>").append(escapeHtml(reason)).append("</p>")
            append("<p><code>").append(escapeHtml(uri)).append("</code></p>")
            append("</body></html>")
        }
        return "data:text/html;charset=utf-8," + URLEncoder.encode(html, "UTF-8")
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
