package com.yuanbao.downloader.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yuanbao.downloader.data.db.DownloadTaskEntity
import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.data.settings.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一网络出口。代理、UA、Referer、Cookie 策略在此集中处理，
 * 下载引擎与 HLS 解析器只依赖本类，不直接持有 OkHttpClient。
 */
@Singleton
class HttpStack @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var client: OkHttpClient = buildClient(null)

    @Volatile
    private var currentSettings: AppSettings = AppSettings()

    init {
        // 设置变化即时重建客户端，使代理、UA 等修改无需重启进程即可生效
        scope.launch {
            settingsStore.settingsFlow.collect { s ->
                currentSettings = s
                val proxy = if (s.proxyEnabled && s.proxyHost.isNotBlank()) {
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(s.proxyHost, s.proxyPort))
                } else null
                client = buildClient(proxy)
            }
        }
    }

    private fun buildClient(proxy: Proxy?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply {
            maxRequestsPerHost = 256
            maxRequests = 512
        })
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .apply { if (proxy != null) proxy(proxy) }
        .build()

    fun userAgent(preset: String, custom: String, desktop: Boolean = false): String = when (preset.lowercase()) {
        "safari" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        "ios" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        "android" -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        "custom" -> custom.ifBlank { CHROME_UA }
        else -> if (desktop) DESKTOP_UA else CHROME_UA
    }

    fun request(
        url: String,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
        task: DownloadTaskEntity? = null,
        settings: AppSettings = currentSettings
    ): Request.Builder {
        val builder = Request.Builder().url(url)
            // 强制 identity，避免 gzip 改变实体长度导致 Range 与 Content-Length 校验错位
            .header("Accept-Encoding", "identity")
            .header("User-Agent", task?.userAgent ?: userAgent(settings.defaultUa, settings.customUa))
            .header("Accept", "*/*")

        val referer = task?.referer ?: resolveReferer(url, settings)
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)

        val cookie = task?.cookie
        if (settings.cookieSync && !cookie.isNullOrBlank()) builder.header("Cookie", cookie)

        if (rangeStart != null) {
            val end = rangeEnd?.toString().orEmpty()
            builder.header("Range", "bytes=$rangeStart-$end")
        }
        return builder
    }

    private fun resolveReferer(url: String, settings: AppSettings): String? = when (settings.refererPolicy) {
        "origin" -> runCatching {
            val u = URI(url)
            "${u.scheme}://${u.host}"
        }.getOrNull()
        "url" -> url
        "custom" -> settings.customReferer.ifBlank { null }
        else -> null
    }

    suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    @SuppressLint("MissingPermission")
    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
