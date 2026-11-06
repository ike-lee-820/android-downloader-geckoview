package com.yuanbao.downloader.browser

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/**
 * 内容委托：标题、进度之外的页面事件。
 *
 * 迁移对照（WebChromeClient -> GeckoView）：
 * 标题变化        -> ContentDelegate.onTitleChange
 * 全屏            -> ContentDelegate.onFullScreen
 * 上下文菜单      -> ContentDelegate.onContextMenu
 * 下载 / 媒体响应 -> ContentDelegate.onExternalResponse（WebResponse 补充入口）
 * window.close    -> ContentDelegate.onCloseRequest
 * 渲染进程崩溃    -> ContentDelegate.onCrash
 */
class GeckoContentDelegate(
    private val tabId: String,
    private val state: GeckoLiveState,
    private val callbacks: GeckoBrowserCallbacks,
    private val emit: () -> Unit
) : GeckoSession.ContentDelegate {

    override fun onTitleChange(session: GeckoSession, title: String?) {
        state.title = title
        emit()
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        callbacks.onFullScreen?.invoke(fullScreen)
    }

    override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
    ) {
        callbacks.onContextMenu?.invoke(
            GeckoContextMenu(
                tabId = tabId,
                linkUri = element.linkUri?.takeIf { it.isNotBlank() },
                srcUri = element.srcUri?.takeIf { it.isNotBlank() },
                elementType = element.type,
                title = element.title ?: element.altText
            )
        )
    }

    /**
     * HLS 感知补充通道。
     * Gecko 内部通过 Necko 网络栈加载 HLS 资源；无法在页面内呈现的响应以 WebResponse
     * 形式交给应用。这里读取 uri 与响应头 Content-Type 送入嗅探聚合器，
     * WebResponse.body 是流式 InputStream，应用不消费时主动关闭避免泄漏。
     */
    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        callbacks.onExternalResponse?.invoke(
            session,
            response.uri.orEmpty(),
            response.headers.toMap()
        )
        runCatching { response.body?.close() }
    }

    override fun onCloseRequest(session: GeckoSession) {
        callbacks.onCloseTab?.invoke(session)
    }

    override fun onCrash(session: GeckoSession) {
        callbacks.onCrash?.invoke(session)
    }
}
