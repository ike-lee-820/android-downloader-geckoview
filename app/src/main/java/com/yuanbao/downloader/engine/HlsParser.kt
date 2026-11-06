package com.yuanbao.downloader.engine

import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.domain.HlsKey
import com.yuanbao.downloader.domain.HlsPlaylist
import com.yuanbao.downloader.domain.HlsSegment
import com.yuanbao.downloader.domain.HlsVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * m3u8 解析器。
 * 支持 master playlist 多码率选择、媒体 playlist 的 EXT-X-KEY(AES-128)、
 * EXT-X-MAP(fMP4 init 段)、相对 URL 解析与直播列表识别。
 */
@Singleton
class HlsParser @Inject constructor(private val http: HttpStack) {

    private data class TextResult(val text: String, val finalUrl: String)

    suspend fun parse(url: String, settings: AppSettings): HlsPlaylist = withContext(Dispatchers.IO) {
        val first = fetchText(url, settings)
        val isMaster = first.text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }
        if (isMaster) {
            val variants = parseMaster(first.text, first.finalUrl)
            val chosen = selectVariant(variants, settings)
            val media = fetchText(chosen.url, settings)
            parseMedia(media.text, media.finalUrl, chosen)
        } else {
            parseMedia(first.text, first.finalUrl, null)
        }
    }

    private suspend fun fetchText(url: String, settings: AppSettings): TextResult {
        val response = http.execute(http.request(url, null, null, null, settings).build())
        return response.use { resp ->
            if (!resp.isSuccessful) throw IOException("m3u8 拉取失败 HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw IOException("m3u8 内容为空")
            TextResult(text, resp.request.url.toString())
        }
    }

    private fun parseMaster(text: String, baseUrl: String): List<HlsVariant> {
        val result = mutableListOf<HlsVariant>()
        var bandwidth = 0
        var resolution: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.startsWith("#EXT-X-STREAM-INF") -> {
                    bandwidth = Regex("BANDWIDTH=(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    resolution = Regex("RESOLUTION=([^,\\s]+)").find(t)?.groupValues?.get(1)
                }
                t.isNotEmpty() && !t.startsWith("#") -> {
                    result += HlsVariant(bandwidth, resolution, resolve(baseUrl, t))
                    bandwidth = 0
                    resolution = null
                }
            }
        }
        return result
    }

    /** 自适应选择：自动模式下 Wi-Fi 取最高码率，移动网络取最低码率。 */
    private fun selectVariant(variants: List<HlsVariant>, settings: AppSettings): HlsVariant {
        if (variants.isEmpty()) throw IOException("m3u8 主列表无可用码率")
        return when (settings.hlsQuality) {
            "lowest" -> variants.minByOrNull { it.bandwidth }!!
            "highest" -> variants.maxByOrNull { it.bandwidth }!!
            else -> if (http.isOnWifi() || !settings.wifiOnly) variants.maxByOrNull { it.bandwidth }!!
            else variants.minByOrNull { it.bandwidth }!!
        }
    }

    private fun parseMedia(text: String, baseUrl: String, variant: HlsVariant?): HlsPlaylist {
        val segments = mutableListOf<HlsSegment>()
        var key: HlsKey? = null
        var initUrl: String? = null
        var mediaSequence = 0
        var targetDuration = 10
        var endList = false

        for (raw in text.lines()) {
            val t = raw.trim()
            when {
                t.startsWith("#EXT-X-KEY") -> key = parseKey(t, baseUrl)
                t.startsWith("#EXT-X-MAP") -> initUrl = quoted(t, "URI")?.let { resolve(baseUrl, it) }
                t.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSequence = t.substringAfter(":").trim().toIntOrNull() ?: 0
                t.startsWith("#EXT-X-TARGETDURATION") ->
                    targetDuration = t.substringAfter(":").trim().toIntOrNull() ?: 10
                t == "#EXT-X-ENDLIST" -> endList = true
                t.startsWith("#EXTINF") -> Unit
                t.isNotEmpty() && !t.startsWith("#") ->
                    segments += HlsSegment(mediaSequence + segments.size, resolve(baseUrl, t), key, initUrl)
            }
        }
        if (segments.isEmpty()) throw IOException("m3u8 媒体列表无分片")
        return HlsPlaylist(segments, !endList, targetDuration, variant)
    }

    private fun parseKey(line: String, baseUrl: String): HlsKey? {
        val method = Regex("METHOD=([A-Z0-9-]+)").find(line)?.groupValues?.get(1) ?: "NONE"
        if (method.equals("NONE", ignoreCase = true)) return null
        val uri = quoted(line, "URI") ?: return null
        val iv = Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.get(1)
        return HlsKey(method, resolve(baseUrl, uri), iv)
    }

    private fun quoted(line: String, keyName: String): String? =
        Regex(keyName + "=\"([^\"]+)\"").find(line)?.groupValues?.get(1)

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        if (ref.startsWith("http", ignoreCase = true)) ref
        else base.substringBeforeLast("/") + "/" + ref.removePrefix("/")
    }
}
