package com.yuanbao.downloader.browser

import android.net.Uri
import com.yuanbao.downloader.domain.ObservedRequest
import com.yuanbao.downloader.domain.ResourceType
import com.yuanbao.downloader.domain.SniffedResource
import com.yuanbao.downloader.domain.TaskOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 由 SnifferExtensionBridge 解析 WebExtension 消息后交给聚合器的统一事件。 */
data class NetworkEvent(
    val tabId: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val mime: String?,
    val resourceTypeHint: String?,
    val requestId: String,
    val hasRequestBody: Boolean,
    val source: String
)

/**
 * 嗅探结果聚合器。
 *
 * 迁移要点：数据入口不再是 WebViewClient.shouldInterceptRequest，而是内置 WebExtension
 * 通过 webRequest + native messaging 送来的 NetworkEvent；ContentDelegate.onExternalResponse
 * 与辅助 JS 探测作为补充入口调用 add()。
 *
 * HLS 去重规则：同一主 playlist 的所有变体 m3u8 与 TS 分片聚合成一条 HLS 任务。
 * TS 先到达时按目录暂存计数，m3u8 到达后被吸收；变体 m3u8 落在画质子目录时也归入同一组。
 */
@Singleton
class SniffAggregator @Inject constructor() {

    private val _resources = MutableStateFlow<Map<String, List<SniffedResource>>>(emptyMap())
    val resources: StateFlow<Map<String, List<SniffedResource>>> = _resources.asStateFlow()

    private val _observed = MutableStateFlow<Map<String, List<ObservedRequest>>>(emptyMap())
    val observed: StateFlow<Map<String, List<ObservedRequest>>> = _observed.asStateFlow()

    private val lock = Any()
    private val hlsGroupKeyCache = ConcurrentHashMap<String, String>()

    fun flow(tabId: String): Flow<List<SniffedResource>> =
        resources.map { it[tabId].orEmpty() }

    fun count(tabId: String): Flow<Int> = flow(tabId).map { it.size }

    fun observedFlow(tabId: String): Flow<List<ObservedRequest>> =
        observed.map { it[tabId].orEmpty() }

    fun clearTab(tabId: String) {
        synchronized(lock) {
            _resources.value = _resources.value - tabId
            _observed.value = _observed.value - tabId
        }
        hlsGroupKeyCache.keys.filter { it.startsWith("$tabId|") }.forEach { hlsGroupKeyCache.remove(it) }
    }

    /** WebExtension 主通道入口。 */
    fun onNetworkEvent(event: NetworkEvent) {
        if (event.url.isBlank() || event.url == "about:blank") return
        val method = event.method.uppercase().ifBlank { "GET" }
        if (method != "GET" || event.hasRequestBody) {
            recordObserved(event.tabId, event.copy(method = method))
        }
        val type = detect(event.url, event.resourceTypeHint, event.mime) ?: return
        ingest(
            tabId = event.tabId,
            url = event.url,
            type = type,
            mime = event.mime,
            method = method,
            headers = event.headers,
            requestId = event.requestId,
            hasBody = event.hasRequestBody,
            source = event.source
        )
    }

    /** onExternalResponse / 辅助 JS 探测入口。 */
    fun add(tabId: String, url: String, mime: String?, source: String) {
        val type = detect(url, null, mime) ?: return
        ingest(tabId, url, type, mime, "GET", emptyMap(), "", false, source)
    }

    /** 生成下载任务请求；HLS 使用聚合后的主 playlist URL，由下载引擎自动识别为 HLS 任务。 */
    fun buildTaskRequest(resource: SniffedResource): Pair<String, TaskOptions> =
        resource.url to TaskOptions(priority = 1, source = "geckoview-webextension")

    private fun recordObserved(tabId: String, event: NetworkEvent) {
        synchronized(lock) {
            val list = _observed.value[tabId].orEmpty().toMutableList()
            val item = ObservedRequest(
                requestId = event.requestId,
                url = event.url,
                method = event.method,
                tabId = tabId,
                headers = event.headers,
                hasRequestBody = event.hasRequestBody,
                source = event.source
            )
            val idx = list.indexOfFirst { it.requestId == item.requestId }
            if (idx >= 0) list[idx] = item else list.add(0, item)
            while (list.size > 40) list.removeAt(list.lastIndex)
            _observed.value = _observed.value + (tabId to list)
        }
    }

    private fun ingest(
        tabId: String,
        url: String,
        type: ResourceType,
        mime: String?,
        method: String,
        headers: Map<String, String>,
        requestId: String,
        hasBody: Boolean,
        source: String
    ) {
        synchronized(lock) {
            val list = _resources.value[tabId].orEmpty().toMutableList()
            when (type) {
                ResourceType.HLS -> absorbHls(tabId, url, list, mime, method, headers, requestId, hasBody, source)
                ResourceType.TS -> absorbTs(tabId, url, list, mime, method, headers, requestId, hasBody, source)
                else -> addSingle(url, type, tabId, mime, method, headers, requestId, hasBody, source, list)
            }
            _resources.value = _resources.value + (tabId to list)
        }
    }

    private fun absorbHls(
        tabId: String,
        url: String,
        list: MutableList<SniffedResource>,
        mime: String?,
        method: String,
        headers: Map<String, String>,
        requestId: String,
        hasBody: Boolean,
        source: String
    ) {
        val key = groupKey(url)
        val oldHls = list.firstOrNull {
            it.type == ResourceType.HLS && groupKey(it.url) == key
        }
        hlsGroupKeyCache["$tabId|$key"] = pickMaster(oldHls?.url, url)

        // 同组 TS 暂存项与变体 m3u8 全部吸收到一条 HLS 任务
        var segments = 0
        var variants = 0
        val remain = list.filter { item ->
            when {
                item.type == ResourceType.TS && groupKey(item.url) == key -> {
                    segments += item.segmentCount.coerceAtLeast(1)
                    false
                }
                item.type == ResourceType.HLS && groupKey(item.url) == key -> {
                    segments += item.segmentCount.coerceAtLeast(1)
                    variants += 1
                    false
                }
                else -> true
            }
        }.toMutableList()

        val master = pickMaster(oldHls?.url, url)
        remain += hlsResource(
            master, tabId, mime, method, headers, requestId, hasBody, source, segments, variants
        )
        list.clear()
        list.addAll(remain)
    }

    private fun absorbTs(
        tabId: String,
        url: String,
        list: MutableList<SniffedResource>,
        mime: String?,
        method: String,
        headers: Map<String, String>,
        requestId: String,
        hasBody: Boolean,
        source: String
    ) {
        val key = groupKey(url)
        val hlsIndex = list.indexOfFirst { it.type == ResourceType.HLS && groupKey(it.url) == key }
        if (hlsIndex >= 0) {
            val old = list[hlsIndex]
            list[hlsIndex] = old.copy(segmentCount = old.segmentCount + 1)
            return
        }
        val aggIndex = list.indexOfFirst { it.type == ResourceType.TS && groupKey(it.url) == key }
        if (aggIndex >= 0) {
            val old = list[aggIndex]
            list[aggIndex] = old.copy(segmentCount = old.segmentCount + 1)
            return
        }
        // 单独展示一条聚合项，而不是逐条列出 TS 分片
        list += SniffedResource(
            url = url,
            type = ResourceType.TS,
            mime = mime,
            tabId = tabId,
            qualityHint = qualityHint(url),
            source = source,
            segmentCount = 1,
            method = method,
            headers = headers,
            requestId = requestId,
            hasRequestBody = hasBody
        )
    }

    private fun addSingle(
        url: String,
        type: ResourceType,
        tabId: String,
        mime: String?,
        method: String,
        headers: Map<String, String>,
        requestId: String,
        hasBody: Boolean,
        source: String,
        list: MutableList<SniffedResource>
    ) {
        val idx = list.indexOfFirst { it.url == url }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(
                mime = mime ?: old.mime,
                method = method,
                headers = headers.ifEmpty { old.headers },
                requestId = requestId.ifBlank { old.requestId },
                hasRequestBody = hasBody || old.hasRequestBody,
                source = source
            )
        } else {
            list += SniffedResource(
                url = url,
                type = type,
                mime = mime,
                tabId = tabId,
                qualityHint = qualityHint(url),
                source = source,
                segmentCount = 0,
                method = method,
                headers = headers,
                requestId = requestId,
                hasRequestBody = hasBody
            )
        }
    }

    private fun hlsResource(
        url: String,
        tabId: String,
        mime: String?,
        method: String,
        headers: Map<String, String>,
        requestId: String,
        hasBody: Boolean,
        source: String,
        segments: Int,
        variants: Int
    ) = SniffedResource(
        url = url,
        type = ResourceType.HLS,
        mime = mime,
        tabId = tabId,
        qualityHint = buildList {
            qualityHint(url)?.let { add(it) }
            if (variants > 0) add("变体流 $variants 条")
        }.joinToString(" · ").ifBlank { null },
        source = source,
        segmentCount = segments,
        method = method,
        headers = headers,
        requestId = requestId,
        hasRequestBody = hasBody
    )

    private fun pickMaster(old: String?, candidate: String): String {
        if (old.isNullOrBlank()) return candidate
        return if (masterScore(candidate) > masterScore(old)) candidate else old
    }

    /** 文件名含 master / index 或路径更短者更可能是主 playlist。 */
    private fun masterScore(url: String): Int {
        val name = url.substringBefore("?").substringAfterLast("/").lowercase()
        var score = if (name.contains("master") || name.contains("index") || name.contains("playlist")) 10 else 0
        score += (6 - url.substringBefore("?").count { it == '/' }.coerceAtMost(6))
        return score
    }

    /** HLS 分组键：画质变体子目录（720p、1080、video1 等）向上归并一层。 */
    private fun groupKey(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#")
        var dir = clean.substringBeforeLast("/", "")
        val last = dir.substringAfterLast("/")
        if (Regex("^\\d{3,4}(?:[xXpP]\\d{3,4})?$|^\\d+p$|^v(?:ideo)?\\d+$", RegexOption.IGNORE_CASE)
                .matches(last)
        ) {
            dir = dir.substringBeforeLast("/", dir)
        }
        return dir
    }

    private fun detect(url: String, hint: String?, mime: String?): ResourceType? {
        hint?.let {
            return when (it.uppercase()) {
                "HLS" -> ResourceType.HLS
                "TS" -> ResourceType.TS
                "MPD" -> ResourceType.MPD
                "M4S" -> ResourceType.M4S
                "VIDEO" -> ResourceType.VIDEO
                "AUDIO" -> ResourceType.AUDIO
                "FILE" -> ResourceType.FILE
                else -> null
            }
        }
        val m = mime?.lowercase().orEmpty()
        val p = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            m.contains("vnd.apple.mpegurl") || m.contains("x-mpegurl") || p.endsWith(".m3u8") ->
                ResourceType.HLS
            m.contains("dash+xml") || p.endsWith(".mpd") -> ResourceType.MPD
            p.endsWith(".m4s") -> ResourceType.M4S
            m == "video/mp2t" || p.endsWith(".ts") -> ResourceType.TS
            m.startsWith("video/") || p.endsWith(".mp4") || p.endsWith(".flv") ||
                p.endsWith(".webm") -> ResourceType.VIDEO
            m.startsWith("audio/") || p.endsWith(".mp3") || p.endsWith(".m4a") ->
                ResourceType.AUDIO
            p.endsWith(".zip") || p.endsWith(".apk") || p.endsWith(".pdf") ||
                p.endsWith(".rar") || p.endsWith(".7z") -> ResourceType.FILE
            else -> null
        }
    }

    private fun qualityHint(url: String): String? {
        val resolution = Regex("(\\d{3,4})[xX](\\d{3,4})").find(url)?.value
        val bitrate = Regex("(?:bandwidth|bw)[=_](\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
        return listOfNotNull(resolution, bitrate?.let { "码率 $it" })
            .joinToString(" · ").ifBlank { null }
    }
}
