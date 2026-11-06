package com.yuanbao.downloader.browser

import org.mozilla.geckoview.GeckoSession

/** 由 NavigationDelegate / ProgressDelegate 汇总后回传给 ViewModel 的标签状态。 */
data class GeckoTabChange(
    val tabId: String,
    val url: String,
    val title: String?,
    val progress: Int,
    val canBack: Boolean,
    val canForward: Boolean
)

/** 长按上下文菜单命中元素的结构化描述。 */
data class GeckoContextMenu(
    val tabId: String,
    val linkUri: String?,
    val srcUri: String?,
    val elementType: Int,
    val title: String?
)

/** 单个标签的实时状态，NavigationDelegate 与 ContentDelegate 共享同一份实例。 */
class GeckoLiveState(
    var url: String = "about:blank",
    var title: String? = null,
    var progress: Int = 0,
    var canBack: Boolean = false,
    var canForward: Boolean = false
) {
    fun snapshot(tabId: String) = GeckoTabChange(
        tabId = tabId,
        url = url,
        title = title,
        progress = progress,
        canBack = canBack,
        canForward = canForward
    )
}

/**
 * 浏览器壳层与 Compose 层之间唯一的回调集合。
 * 所有 Gecko 回调都在主线程触发，这里不做业务判断，只把事件转交 ViewModel / Activity。
 */
class GeckoBrowserCallbacks {
    var onTabChange: ((GeckoTabChange) -> Unit)? = null
    var onLocationChange: ((String, String) -> Unit)? = null
    var onLoadError: ((String, String) -> Unit)? = null
    var onPageFinished: ((GeckoSession) -> Unit)? = null
    /** window.open：参数依次为新建的标签 id 与目标 URL。 */
    var onNewWindow: ((String, String) -> Unit)? = null
    var onCloseTab: ((GeckoSession) -> Unit)? = null
    var onFullScreen: ((Boolean) -> Unit)? = null
    var onContextMenu: ((GeckoContextMenu) -> Unit)? = null
    var onCrash: ((GeckoSession) -> Unit)? = null
    var onExternalResponse: ((GeckoSession, String, Map<String, String>) -> Unit)? = null
}
