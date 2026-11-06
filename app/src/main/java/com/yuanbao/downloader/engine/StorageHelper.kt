package com.yuanbao.downloader.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分区存储适配层。
 * 所有分片先落在应用私有缓存，合并阶段一次性写入 SAF 授权目录或应用专属目录，
 * 完成后删除临时分片；重名通过目录枚举自动追加序号。
 */
@Singleton
class StorageHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun partDir(taskId: String): File =
        File(context.cacheDir, "parts/$taskId").apply { if (!exists()) mkdirs() }

    fun cleanup(taskId: String) {
        runCatching { File(context.cacheDir, "parts/$taskId").deleteRecursively() }
    }

    fun resolveName(url: String, template: String, contentDisposition: String? = null): String {
        val fromHeader = contentDisposition?.let {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
        val raw = fromHeader ?: runCatching {
            Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "download_${System.currentTimeMillis()}"
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").ifBlank { "download" }
        val dot = clean.lastIndexOf('.')
        val name = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        val host = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return template
            .replace("{name}", name)
            .replace("{ext}", ext)
            .replace("{host}", host)
            .replace("{date}", date)
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .ifBlank { clean }
    }

    private fun appendIndex(name: String, index: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_$index${name.substring(dot)}"
        else "${name}_$index"
    }

    private fun uniqueName(treeUri: String?, displayName: String): String {
        if (treeUri.isNullOrBlank()) {
            val dir = fallbackDir()
            var candidate = displayName
            var i = 1
            while (File(dir, candidate).exists()) candidate = appendIndex(displayName, ++i)
            return candidate
        }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return displayName
        var candidate = displayName
        var i = 1
        while (tree.findFile(candidate) != null) candidate = appendIndex(displayName, ++i)
        return candidate
    }

    private fun fallbackDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    suspend fun mergeParts(
        taskId: String,
        parts: List<File>,
        displayName: String,
        treeUri: String?,
        mime: String
    ): String = withContext(Dispatchers.IO) {
        val finalName = uniqueName(treeUri, displayName)
        val outputUri: Uri
        val outputStream = if (!treeUri.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: throw IOException("SAF 目录已失效，请重新授权下载目录")
            val dot = finalName.lastIndexOf('.')
            val base = if (dot > 0) finalName.substring(0, dot) else finalName
            val doc = tree.createFile(mime, base)
                ?: throw IOException("无法在授权目录创建文件")
            if (doc.name != finalName) runCatching { doc.renameTo(finalName) }
            outputUri = doc.uri
            context.contentResolver.openOutputStream(outputUri)
                ?: throw IOException("无法打开输出流")
        } else {
            val dir = fallbackDir().apply { if (!exists()) mkdirs() }
            val file = File(dir, finalName)
            outputUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            file.outputStream()
        }

        outputStream.use { out ->
            parts.forEach { part ->
                part.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            }
        }
        parts.forEach { runCatching { it.delete() } }
        cleanup(taskId)
        outputUri.toString()
    }

    /**
     * 迁移 GeckoView 后不再引用 android.webkit.MimeTypeMap。
     * 下载引擎其余逻辑不变，仅把 MIME 判定改为自维护映射，彻底移除 WebView 依赖。
     */
    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MIME_MAP[ext] ?: "application/octet-stream"
    }

    private companion object {
        val MIME_MAP = mapOf(
            "mp4" to "video/mp4",
            "m4s" to "video/iso.segment",
            "ts" to "video/mp2t",
            "m3u8" to "application/vnd.apple.mpegurl",
            "mpd" to "application/dash+xml",
            "flv" to "video/x-flv",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "zip" to "application/zip",
            "apk" to "application/vnd.android.package-archive",
            "pdf" to "application/pdf",
            "rar" to "application/vnd.rar",
            "7z" to "application/x-7z-compressed",
            "tar" to "application/x-tar",
            "gz" to "application/gzip",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt" to "text/plain",
            "html" to "text/html",
            "json" to "application/json",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
            "epub" to "application/epub+zip",
            "iso" to "application/x-iso9660-image"
        )
    }
}
