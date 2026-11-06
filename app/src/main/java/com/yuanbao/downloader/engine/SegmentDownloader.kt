package com.yuanbao.downloader.engine

import com.yuanbao.downloader.data.db.DownloadTaskEntity
import com.yuanbao.downloader.data.db.SegmentEntity
import com.yuanbao.downloader.data.repo.DownloadRepository
import com.yuanbao.downloader.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 自研分片执行体。
 * 设计要点：每个分片写入 cacheDir/parts/<taskId>/<index>.part，
 * 以文件真实长度作为断点依据，分片元数据写入 Room。进程被杀后
 * 重启时重新读取 .part 长度即可续传；全部完成后再顺序合并到 SAF 目录，
 * 从而避开 SAF 不支持随机写的限制。
 */
class SegmentDownloader(
    private val http: HttpStack,
    private val repo: DownloadRepository,
    private val settings: AppSettings,
    private val globalBucket: TokenBucket?,
    private val taskBucket: TokenBucket?,
    private val onBytes: (Long) -> Unit
) {
    suspend fun download(
        task: DownloadTaskEntity,
        segments: List<SegmentEntity>,
        partDir: File
    ): Long = coroutineScope {
        if (!partDir.exists()) partDir.mkdirs()
        val permits = Semaphore(task.threadCount.coerceIn(1, 256))
        segments.filter { it.status != "DONE" }.map { seg ->
            async(Dispatchers.IO) {
                permits.withPermit { downloadSegment(task, seg, partDir) }
            }
        }.awaitAll()
        repo.segments(task.id).sumOf { it.downloaded }
    }

    private suspend fun downloadSegment(task: DownloadTaskEntity, seg: SegmentEntity, partDir: File) {
        val part = File(partDir, "${seg.segIndex}.part")
        val expected = if (seg.end < 0) Long.MAX_VALUE else (seg.end - seg.start + 1).coerceAtLeast(1L)
        if (!partDir.exists()) partDir.mkdirs()

        var written = if (part.exists()) part.length() else 0L
        if (seg.end >= 0 && written > expected) {
            RandomAccessFile(part, "rw").use { it.setLength(expected) }
            written = expected
        }
        if (seg.end >= 0 && written >= expected) {
            repo.upsertSegment(seg.copy(downloaded = written, status = "DONE"))
            return
        }
        if (seg.end < 0 && seg.status == "DONE" && written > 0) return

        var attempt = 0
        while (true) {
            try {
                val url = seg.url ?: task.url
                // HTTP 直链按 start/end 发 Range；HLS/MPD 分片用自身 URL，
                // 已有 .part 时从 written 续拉，否则整片 GET。
                val rangeStart: Long? = if (seg.url == null) seg.start + written
                else if (written > 0) written else null
                val rangeEnd: Long? = if (seg.url == null && seg.end >= 0) seg.end else null

                val response = http.execute(http.request(url, rangeStart, rangeEnd, task, settings).build())
                response.use { resp ->
                    if (resp.code == 416) {
                        // 服务端认为区间已越界，视为该分片已完成
                        repo.upsertSegment(seg.copy(downloaded = expected, status = "DONE"))
                        return
                    }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
                    val body = resp.body ?: throw IOException("响应体为空: $url")
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(written)
                        val buffer = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                globalBucket?.acquire(n)
                                taskBucket?.acquire(n)
                                raf.write(buffer, 0, n)
                                written += n
                                onBytes(n.toLong())
                                if (seg.end >= 0 && written >= expected) break
                            }
                        }
                    }
                }
                val finalWritten = if (seg.end < 0) part.length() else written.coerceAtMost(expected)
                repo.upsertSegment(seg.copy(downloaded = finalWritten, status = "DONE"))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt > settings.maxRetry) throw e
                // 指数退避：第 n 次等待 backoff * 2^(n-1)，上限 30 秒
                val backoff = (settings.retryBackoffMs shl (attempt - 1)).coerceAtMost(30_000L)
                delay(backoff)
            }
        }
    }
}
