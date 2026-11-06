package com.yuanbao.downloader.engine

import com.yuanbao.downloader.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MPD 基础支持：解析 BaseURL、Representation id、SegmentTemplate 与
 * SegmentTimeline / SegmentURL，生成分片 URL 列表。
 * 不实现动态 ABR 切换与多 Period 时间轴（见 README 已知限制）。
 */
@Singleton
class MpdParser @Inject constructor(private val http: HttpStack) {

    suspend fun parse(url: String, settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        val response = http.execute(http.request(url, null, null, null, settings).build())
        val xml = response.use { r -> r.body?.string().orEmpty() }
        val base = Regex("<BaseURL>([^<]+)</BaseURL>").find(xml)?.groupValues?.get(1)
            ?.let { resolve(url, it) } ?: (url.substringBeforeLast("/") + "/")
        val repId = Regex("<Representation[^>]*id=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
        val template = Regex("<SegmentTemplate[^>]*media=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
        val segmentUrls = Regex("<SegmentURL[^>]*media=\"([^\"]+)\"")
            .findAll(xml).map { it.groupValues[1] }.toList()

        val result = mutableListOf<String>()
        when {
            template != null -> {
                val startNumber = Regex("startNumber=\"(\\d+)\"").find(xml)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val timelineCount = Regex("<S[^>]*d=\"\\d+\"").findAll(xml).count()
                val count = if (timelineCount > 0) timelineCount else 1
                for (i in 0 until count) {
                    val path = template
                        .replace("\$Number\$", (startNumber + i).toString())
                        .replace("\$RepresentationID\$", repId.orEmpty())
                    result += resolve(base, path)
                }
            }
            segmentUrls.isNotEmpty() -> segmentUrls.forEach { result += resolve(base, it) }
            else -> result += url
        }
        result
    }

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        if (ref.startsWith("http", ignoreCase = true)) ref
        else base.trimEnd('/') + "/" + ref.removePrefix("/")
    }
}
