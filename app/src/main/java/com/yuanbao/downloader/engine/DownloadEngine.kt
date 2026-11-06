package com.yuanbao.downloader.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.yuanbao.downloader.data.db.DownloadTaskEntity
import com.yuanbao.downloader.data.db.SegmentEntity
import com.yuanbao.downloader.data.repo.DownloadRepository
import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.data.settings.SettingsDataStore
import com.yuanbao.downloader.domain.EngineEvent
import com.yuanbao.downloader.domain.TaskOptions
import com.yuanbao.downloader.domain.TaskProgress
import com.yuanbao.downloader.domain.TaskStatus
import com.yuanbao.downloader.domain.TaskType
import com.yuanbao.downloader.service.DownloadForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载引擎：与 UI 完全解耦，通过 StateFlow 广播任务状态、SharedFlow 广播事件。
 * 全局并发由 settings.maxConcurrentTasks 控制，队列按 priority DESC / createdAt ASC 出队；
 * 每个任务内部再由 SegmentDownloader 以 threadCount 并发拉取分片。
 */
@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val http: HttpStack,
    private val settingsStore: SettingsDataStore,
    private val storage: StorageHelper,
    private val hlsParser: HlsParser,
    private val hlsDownloader: HlsDownloader,
    private val mpdParser: MpdParser
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<TaskProgress>>(emptyList())
    val tasks: StateFlow<List<TaskProgress>> = _tasks.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private val progressAtoms = ConcurrentHashMap<String, AtomicLong>()
    private val speeds = ConcurrentHashMap<String, Long>()
    private val segmentStats = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val networkPaused = mutableSetOf<String>()

    @Volatile
    private var currentSettings: AppSettings = AppSettings()

    @Volatile
    private var globalBucket: TokenBucket? = null

    init {
        scope.launch {
            settingsStore.settingsFlow.collect { s ->
                currentSettings = s
                globalBucket = if (s.globalSpeedLimitKbps > 0) {
                    TokenBucket(s.globalSpeedLimitKbps * 1024L)
                } else null
                pump()
            }
        }
        scope.launch {
            repo.observeTasks().collect { list ->
                _tasks.value = list.map { it.toProgress() }
                pump()
            }
        }
        scope.launch { restorePending() }
    }

    /** 进程被杀后 RUNNING/RETRYING 不可能还在运行，回退为 PENDING，分片 .part 文件仍在缓存中。 */
    private suspend fun restorePending() {
        repo.bulkStatus("PENDING", listOf("RUNNING", "RETRYING"))
        pump()
    }

    suspend fun enqueue(url: String, options: TaskOptions = TaskOptions()): String {
        val s = currentSettings
        val id = UUID.randomUUID().toString()
        val task = DownloadTaskEntity(
            id = id,
            url = url,
            fileName = options.fileName ?: storage.resolveName(url, s.fileNameTemplate),
            type = detectType(url).name,
            status = "PENDING",
            priority = options.priority,
            threadCount = options.threadCount ?: s.safeThreadCount,
            createdAt = System.currentTimeMillis(),
            treeUri = s.downloadTreeUri,
            userAgent = options.userAgent,
            referer = options.referer,
            cookie = options.cookie,
            maxRetry = s.maxRetry,
            rateLimitKbps = options.rateLimitKbps ?: s.perTaskSpeedLimitKbps,
            segmentSizeBytes = s.segmentSizeBytes
        )
        repo.insertTask(task)
        startService()
        _events.tryEmit(EngineEvent.Message("任务已加入队列"))
        pump()
        return id
    }

    private fun detectType(url: String): TaskType {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true) -> TaskType.HLS
            path.endsWith(".mpd") -> TaskType.MPD
            path.endsWith(".m4s") -> TaskType.M4S
            else -> TaskType.HTTP
        }
    }

    private fun startService() {
        val intent = Intent(context, DownloadForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** 队列泵：设置变更、任务变更或任务结束时重新计算可启动数量。 */
    fun pump() {
        scope.launch { pumpInternal() }
    }

    private suspend fun pumpInternal() {
        mutex.withLock {
            if (!http.isNetworkAvailable()) return
            if (currentSettings.wifiOnly && !http.isOnWifi()) return
            val limit = currentSettings.safeMaxConcurrent
            val slots = limit - activeJobs.size
            if (slots <= 0) return
            repo.pending().take(slots).forEach { t ->
                if (!activeJobs.containsKey(t.id)) {
                    activeJobs[t.id] = scope.launch { runTask(t.id) }
                }
            }
        }
    }

    private suspend fun runTask(id: String) {
        val task = repo.getTask(id) ?: return
        val atom = AtomicLong(task.downloadedBytes)
        progressAtoms[id] = atom
        repo.setStatus(id, "RUNNING")

        var lastTick = atom.get()
        val ticker = scope.launch {
            while (isActive) {
                delay(250)
                val now = atom.get()
                speeds[id] = ((now - lastTick) * 4L).coerceAtLeast(0L)
                lastTick = now
                repo.updateProgress(id, now, "RUNNING", null)
                val segs = repo.segments(id)
                segmentStats[id] = segs.size to segs.count { it.status == "DONE" }
            }
        }

        try {
            val outputUri = executeTask(task, atom)
            val done = repo.getTask(id) ?: task
            val finalBytes = atom.get().coerceAtLeast(done.downloadedBytes)
            repo.updateTask(
                done.copy(
                    status = "COMPLETED",
                    downloadedBytes = finalBytes,
                    totalBytes = if (done.totalBytes > 0) done.totalBytes else finalBytes,
                    outputUri = outputUri,
                    error = null
                )
            )
            val progress = (repo.getTask(id) ?: done).toProgress()
            _events.tryEmit(EngineEvent.Completed(progress, outputUri))
        } catch (e: CancellationException) {
            repo.updateProgress(id, atom.get(), "PAUSED", null)
        } catch (e: Exception) {
            repo.updateProgress(id, atom.get(), "FAILED", e.message)
            repo.getTask(id)?.let { _events.tryEmit(EngineEvent.Failed(it.toProgress(), e.message)) }
        } finally {
            ticker.cancel()
            progressAtoms.remove(id)
            activeJobs.remove(id)
            pump()
        }
    }

    private suspend fun executeTask(task: DownloadTaskEntity, atom: AtomicLong): String {
        val s = currentSettings
        val partDir = storage.partDir(task.id)
        val taskBucket = if (task.rateLimitKbps > 0) TokenBucket(task.rateLimitKbps * 1024L) else null
        val downloader = SegmentDownloader(http, repo, s, globalBucket, taskBucket) { delta ->
            atom.addAndGet(delta)
        }

        return when (runCatching { TaskType.valueOf(task.type) }.getOrDefault(TaskType.HTTP)) {
            TaskType.HLS -> {
                val playlist = hlsParser.parse(task.url, s)
                segmentStats[task.id] = playlist.segments.size to 0
                val withVariant = playlist.variant?.url?.let { task.copy(hlsVariantUrl = it) } ?: task
                hlsDownloader.run(withVariant, playlist, downloader, partDir, s)
            }
            TaskType.MPD -> {
                val urls = mpdParser.parse(task.url, s)
                val segs = urls.mapIndexed { i, u ->
                    SegmentEntity(task.id, i, 0, -1, 0, "PENDING", url = u)
                }
                repo.clearSegments(task.id)
                repo.upsertSegments(segs)
                segmentStats[task.id] = segs.size to 0
                downloader.download(task, repo.segments(task.id), partDir)
                val parts = repo.segments(task.id).sortedBy { it.segIndex }
                    .map { File(partDir, "${it.segIndex}.part") }
                val size = parts.sumOf { it.length() }
                repo.updateSize(task.id, size, size)
                storage.mergeParts(
                    task.id, parts, task.fileName.substringBeforeLast('.') + ".mp4",
                    task.treeUri, "video/mp4"
                )
            }
            TaskType.M4S, TaskType.HTTP -> {
                val total = probeContentLength(task, s)
                val segs = buildHttpSegments(task, total, s)
                repo.clearSegments(task.id)
                repo.upsertSegments(segs)
                repo.updateSize(task.id, if (total > 0) total else 0, if (total > 0) total else 0)
                segmentStats[task.id] = segs.size to 0
                downloader.download(task, repo.segments(task.id), partDir)
                val parts = repo.segments(task.id).sortedBy { it.segIndex }
                    .map { File(partDir, "${it.segIndex}.part") }
                val size = parts.sumOf { it.length() }
                // 与 Content-Length 一致性校验，防止分片缺失或服务端返回被截断
                if (total > 0 && size != total) {
                    throw IOException("文件大小校验失败：期望 $total 字节，实际 $size 字节")
                }
                if (total <= 0) repo.updateTask(task.copy(totalBytes = size, contentLength = size))
                storage.mergeParts(task.id, parts, task.fileName, task.treeUri, storage.mimeOf(task.fileName))
            }
        }
    }

    /** 通过 Range: bytes=0-0 探测总长度；不支持 Range 时退化为单分片未知长度下载。 */
    private suspend fun probeContentLength(task: DownloadTaskEntity, s: AppSettings): Long {
        val response = http.execute(http.request(task.url, 0, 0, task, s).build())
        return response.use { r ->
            r.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 && r.header("Content-Range") == null }
                ?: -1L
        }
    }

    private fun buildHttpSegments(task: DownloadTaskEntity, total: Long, s: AppSettings): List<SegmentEntity> {
        if (total <= 0) return listOf(SegmentEntity(task.id, 0, 0, -1, 0, "PENDING"))
        val chunk = s.segmentSizeBytes.coerceAtLeast(1L)
        val out = mutableListOf<SegmentEntity>()
        var start = 0L
        var index = 0
        while (start < total) {
            val end = (start + chunk - 1).coerceAtMost(total - 1)
            out += SegmentEntity(task.id, index, start, end, 0, "PENDING")
            start = end + 1
            index++
        }
        return out
    }

    private fun DownloadTaskEntity.toProgress(): TaskProgress {
        val speed = speeds[id] ?: 0L
        val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        val eta = if (speed > 0) remaining / speed else 0L
        val (st, sd) = segmentStats[id] ?: (0 to 0)
        return TaskProgress(
            id = id,
            url = url,
            fileName = fileName,
            type = runCatching { TaskType.valueOf(type) }.getOrDefault(TaskType.HTTP),
            status = TaskStatus.of(status),
            priority = priority,
            threadCount = threadCount,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            speedBytesPerSec = speed,
            etaSeconds = eta,
            segmentTotal = st,
            segmentDone = sd,
            error = error,
            createdAt = createdAt,
            outputUri = outputUri
        )
    }

    fun pause(id: String) {
        scope.launch {
            activeJobs[id]?.cancel()
            repo.setStatus(id, "PAUSED")
        }
    }

    fun resume(id: String) {
        scope.launch {
            repo.setStatus(id, "PENDING")
            pump()
        }
    }

    fun cancel(id: String) {
        scope.launch {
            activeJobs[id]?.cancel()
            progressAtoms.remove(id)
            repo.deleteTask(id)
            storage.cleanup(id)
            pump()
        }
    }

    fun retry(id: String) {
        scope.launch {
            val t = repo.getTask(id) ?: return@launch
            repo.updateTask(t.copy(status = "PENDING", retryCount = t.retryCount + 1, error = null))
            pump()
        }
    }

    fun setPriority(id: String, priority: Int) {
        scope.launch {
            repo.getTask(id)?.let { repo.updateTask(it.copy(priority = priority)) }
        }
    }

    /** 动态调整线程数：取消当前执行（分片已落盘），以新的并发度重新入队。 */
    fun updateThreadCount(id: String, count: Int) {
        scope.launch {
            activeJobs[id]?.cancel()
            repo.getTask(id)?.let {
                repo.updateTask(it.copy(threadCount = count.coerceIn(1, 256)))
                repo.setStatus(id, "PENDING")
            }
            pump()
        }
    }

    fun pauseAll() {
        scope.launch {
            activeJobs.keys.toList().forEach { id ->
                activeJobs[id]?.cancel()
                repo.setStatus(id, "PAUSED")
            }
        }
    }

    fun pauseAllForNetwork() {
        scope.launch {
            activeJobs.keys.toList().forEach { id ->
                networkPaused += id
                activeJobs[id]?.cancel()
                repo.setStatus(id, "PAUSED")
            }
            _events.tryEmit(EngineEvent.Message("网络切换，任务已自动暂停"))
        }
    }

    fun resumeAllForNetwork() {
        scope.launch {
            networkPaused.toList().forEach { id ->
                repo.setStatus(id, "PENDING")
            }
            networkPaused.clear()
            pump()
        }
    }

    fun requestMobileConfirm(pendingUrl: String?) {
        _events.tryEmit(EngineEvent.MobileNetworkConfirm(pendingUrl))
    }

    fun hasActiveTasks(): Boolean = activeJobs.isNotEmpty()
}
