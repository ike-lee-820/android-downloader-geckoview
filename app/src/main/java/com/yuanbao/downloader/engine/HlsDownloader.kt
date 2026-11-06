package com.yuanbao.downloader.engine

import com.yuanbao.downloader.data.db.DownloadTaskEntity
import com.yuanbao.downloader.data.db.SegmentEntity
import com.yuanbao.downloader.data.repo.DownloadRepository
import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.domain.HlsKey
import com.yuanbao.downloader.domain.HlsPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HLS 执行体：TS/fMP4 分片并发下载 -> AES-128 解密 -> 顺序合并。
 * 纯 Kotlin 实现，不依赖 FFmpeg；TS 流合并为 .ts，带 EXT-X-MAP 的
 * fMP4 流按 init + m4s 顺序合并为 .mp4（见 README 已知限制）。
 */
@Singleton
class HlsDownloader @Inject constructor(
    private val http: HttpStack,
    private val repo: DownloadRepository,
    private val storage: StorageHelper
) {
    suspend fun run(
        task: DownloadTaskEntity,
        playlist: HlsPlaylist,
        downloader: SegmentDownloader,
        partDir: File,
        settings: AppSettings
    ): String = withContext(Dispatchers.IO) {
        if (!partDir.exists()) partDir.mkdirs()

        val entities = playlist.segments.map { h ->
            val dec = File(partDir, "${h.segIndex}.dec")
            val done = dec.exists() && dec.length() > 0
            if (!done) runCatching { File(partDir, "${h.segIndex}.part").delete() }
            SegmentEntity(
                taskId = task.id,
                segIndex = h.segIndex,
                start = 0,
                end = -1,
                downloaded = if (done) dec.length() else 0,
                status = if (done) "DONE" else "PENDING",
                url = h.url,
                keyUri = h.key?.uri,
                ivHex = h.key?.ivHex,
                initUrl = h.initUrl
            )
        }
        // 分片清单可随直播更新，重建前先清空；真实进度以 .dec 文件为准
        repo.clearSegments(task.id)
        repo.upsertSegments(entities)
        downloader.download(task, repo.segments(task.id), partDir)

        val decrypted = mutableListOf<File>()
        val initFiles = linkedMapOf<String, File>()
        val firstSeq = playlist.segments.firstOrNull()?.segIndex ?: 0

        for (h in playlist.segments) {
            val raw = File(partDir, "${h.segIndex}.part")
            val dec = File(partDir, "${h.segIndex}.dec")
            if (!dec.exists()) {
                if (!raw.exists()) throw IOException("分片缺失: ${h.url}")
                val bytes = raw.readBytes()
                val out = if (h.key != null) decrypt(bytes, h.key, h.segIndex, settings) else bytes
                dec.writeBytes(out)
            }
            h.initUrl?.let { initUrl ->
                initFiles.getOrPut(initUrl) {
                    val initRaw = File(partDir, "init_${initUrl.hashCode()}.part")
                    if (!initRaw.exists()) {
                        val resp = http.execute(http.request(initUrl, null, null, null, settings).build())
                        resp.use { r ->
                            if (!r.isSuccessful) throw IOException("init 段拉取失败 HTTP ${r.code}")
                            initRaw.writeBytes(r.body?.bytes() ?: throw IOException("init 段为空"))
                        }
                    }
                    val initDec = File(partDir, "init_${initUrl.hashCode()}.dec")
                    if (!initDec.exists()) {
                        val bytes = initRaw.readBytes()
                        initDec.writeBytes(if (h.key != null) decrypt(bytes, h.key, firstSeq, settings) else bytes)
                    }
                    initDec
                }
            }
            decrypted += dec
        }

        val isFmp4 = initFiles.isNotEmpty() || task.url.contains(".m4s", ignoreCase = true)
        val baseName = task.fileName.substringBeforeLast('.').ifBlank { "hls_stream" }
        val displayName = baseName + if (isFmp4) ".mp4" else ".ts"
        val mime = if (isFmp4) "video/mp4" else "video/mp2t"
        val ordered = initFiles.values.toList() + decrypted
        storage.mergeParts(task.id, ordered, displayName, task.treeUri, mime)
    }

    /** AES-128/CBC 解密；IV 优先取 EXT-X-KEY，否则按媒体序列号生成 16 字节大端 IV。 */
    private suspend fun decrypt(
        data: ByteArray,
        key: HlsKey,
        seq: Int,
        settings: AppSettings
    ): ByteArray {
        val response = http.execute(http.request(key.uri, null, null, null, settings).build())
        val keyBytes = response.use { r ->
            if (!r.isSuccessful) throw IOException("解密密钥拉取失败 HTTP ${r.code}")
            r.body?.bytes() ?: throw IOException("解密密钥为空")
        }
        val iv = key.ivHex?.let { hexToBytes(it) } ?: sequenceIv(seq)
        val spec = SecretKeySpec(keyBytes.copyOf(16), "AES")
        // 标准 TS 使用 PKCS7（JCE 中即 PKCS5Padding）；部分源未填充，回退 NoPadding
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
                doFinal(data)
            }
        } catch (e: Exception) {
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
                doFinal(data)
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }.copyOf(16)
    }

    private fun sequenceIv(seq: Int): ByteArray {
        val b = ByteArray(16)
        var v = seq.toLong()
        for (i in 15 downTo 0) {
            b[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return b
    }
}
