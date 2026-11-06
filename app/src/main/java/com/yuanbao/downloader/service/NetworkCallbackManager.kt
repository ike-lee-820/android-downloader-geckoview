package com.yuanbao.downloader.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.yuanbao.downloader.data.settings.SettingsDataStore
import com.yuanbao.downloader.engine.DownloadEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 监听网络能力变化：仅 Wi-Fi 策略下自动暂停/恢复，移动网络触发确认事件。 */
@Singleton
class NetworkCallbackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: DownloadEngine,
    settingsStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var wifiOnly = false

    @Volatile private var mobileConfirm = true

    init {
        scope.launch {
            settingsStore.settingsFlow.collect {
                wifiOnly = it.wifiOnly
                mobileConfirm = it.mobileConfirm
                evaluate()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluate()
            override fun onLost(network: Network) = evaluate()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
        callback = cb
        evaluate()
    }

    @SuppressLint("MissingPermission")
    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun evaluate() {
        val wifi = isOnWifi()
        if (wifiOnly && !wifi) {
            engine.pauseAllForNetwork()
        } else {
            engine.resumeAllForNetwork()
            if (!wifi && mobileConfirm) engine.requestMobileConfirm(null)
        }
    }

    fun stop() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
    }
}
