package com.yuanbao.downloader.browser

import com.yuanbao.downloader.domain.ObservedRequest
import com.yuanbao.downloader.domain.SniffedResource
import com.yuanbao.downloader.domain.TaskOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 嗅探门面，供 ViewModel 与 Compose 层消费。
 *
 * 迁移后不再持有任何 WebView / shouldInterceptRequest 逻辑：
 * 真实数据由 SniffAggregator 从内置 WebExtension 的 native messaging 与
 * ContentDelegate.onExternalResponse 聚合而来，本类只做转发，保持 UI 层接口不变。
 */
@Singleton
class Sniffer @Inject constructor(
    private val aggregator: SniffAggregator
) {
    val resources: StateFlow<Map<String, List<SniffedResource>>> = aggregator.resources

    fun flow(tabId: String): Flow<List<SniffedResource>> = aggregator.flow(tabId)

    fun count(tabId: String): Flow<Int> = aggregator.count(tabId)

    fun observedFlow(tabId: String): Flow<List<ObservedRequest>> = aggregator.observedFlow(tabId)

    fun clearTab(tabId: String) = aggregator.clearTab(tabId)

    /** 供 ContentDelegate.onExternalResponse 与辅助 JS 探测调用。 */
    fun add(tabId: String, url: String, mime: String?, source: String) =
        aggregator.add(tabId, url, mime, source)

    fun onNetworkEvent(event: NetworkEvent) = aggregator.onNetworkEvent(event)

    fun buildTaskRequest(resource: SniffedResource): Pair<String, TaskOptions> =
        aggregator.buildTaskRequest(resource)
}
