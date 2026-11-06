package com.yuanbao.downloader.browser

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * GeckoView 的 Compose 封装。
 *
 * 与迁移前的关键差异：这里不按标签创建多个 View，而是全局复用同一个 GeckoView 实例；
 * 标签切换只调用 GeckoView.setSession()，由 TabSessionManager 维护 session 生命周期。
 * factory 中先摘除旧父容器，避免 Compose 页面进出时重复 attach 同一 View。
 */
@Composable
fun GeckoBrowserView(
    manager: TabSessionManager,
    activeTabId: String?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            val view = manager.obtainView(ctx)
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        update = { _ ->
            if (!activeTabId.isNullOrBlank()) manager.activate(activeTabId)
        },
        modifier = modifier
    )
}
