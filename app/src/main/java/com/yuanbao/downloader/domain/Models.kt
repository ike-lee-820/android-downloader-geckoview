package com.yuanbao.downloader.domain

enum class TaskStatus {
    PENDING, RUNNING, PAUSED, RETRYING, COMPLETED, FAILED, CANCELED;

    companion object {
        fun of(value: String): TaskStatus = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

enum class TaskType { HTTP, HLS, MPD, M4S }

enum class ResourceType { HLS, TS, MPD, M4S, VIDEO, AUDIO, FILE, OTHER }

data class TaskProgress(
    val id: String,
    val url: String,
    val fileName: String,
    val type: TaskType,
    val status: TaskStatus,
    val priority: Int,
    val threadCount: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val segmentTotal: Int,
    val segmentDone: Int,
    val error: String? = null,
    val createdAt: Long = 0L,
    val outputUri: String? = null
) {
    val percent: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** 建任务时的可选项，支持按任务覆盖 UA / Referer / Cookie / 限速 / 优先级。 */
data class TaskOptions(
    val priority: Int = 0,
    val threadCount: Int? = null,
    val rateLimitKbps: Int? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val fileName: String? = null,
    val source: String = "manual"
)

data class SniffedResource(
    val url: String,
    val type: ResourceType,
    val mime: String?,
    val tabId: String,
    val qualityHint: String?,
    val source: String,
    val segmentCount: Int = 0,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val requestId: String = "",
    val hasRequestBody: Boolean = false
)

/** webRequest 捕获到的非 GET 请求观测，用于验证 POST 请求头不再是嗅探盲区。 */
data class ObservedRequest(
    val requestId: String,
    val url: String,
    val method: String,
    val tabId: String,
    val headers: Map<String, String>,
    val hasRequestBody: Boolean,
    val source: String
)

data class HlsVariant(val bandwidth: Int, val resolution: String?, val url: String)

data class HlsKey(val method: String, val uri: String, val ivHex: String?)

data class HlsSegment(
    val segIndex: Int,
    val url: String,
    val key: HlsKey?,
    val initUrl: String?
)

data class HlsPlaylist(
    val segments: List<HlsSegment>,
    val isLive: Boolean,
    val targetDuration: Int,
    val variant: HlsVariant?
)

/** 引擎向服务层广播的事件，服务据此刷新通知或发起移动网络确认。 */
sealed class EngineEvent {
    data class Completed(val task: TaskProgress, val outputUri: String?) : EngineEvent()
    data class Failed(val task: TaskProgress, val reason: String?) : EngineEvent()
    data class MobileNetworkConfirm(val pendingUrl: String?) : EngineEvent()
    data class Message(val text: String) : EngineEvent()
}
