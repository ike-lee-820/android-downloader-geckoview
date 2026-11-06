package com.yuanbao.downloader.presentation

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuanbao.downloader.browser.GeckoSessionManager
import com.yuanbao.downloader.data.repo.DownloadRepository
import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.data.settings.SettingsDataStore
import com.yuanbao.downloader.data.settings.SettingsKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsDataStore,
    private val repo: DownloadRepository,
    private val geckoRuntimeManager: GeckoSessionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private fun <T> put(key: Preferences.Key<T>, value: T) {
        viewModelScope.launch { store.set(key, value) }
    }

    // 下载
    fun setThreadCount(v: Int) = put(SettingsKeys.THREAD_COUNT, v)
    fun setMaxConcurrentTasks(v: Int) = put(SettingsKeys.MAX_CONCURRENT_TASKS, v)
    fun setPerTaskSpeedLimit(v: Int) = put(SettingsKeys.PER_TASK_SPEED_LIMIT, v)
    fun setGlobalSpeedLimit(v: Int) = put(SettingsKeys.GLOBAL_SPEED_LIMIT, v)
    fun setMaxRetry(v: Int) = put(SettingsKeys.MAX_RETRY, v)
    fun setRetryBackoff(v: Long) = put(SettingsKeys.RETRY_BACKOFF_MS, v)
    fun setSegmentSize(v: Int) = put(SettingsKeys.SEGMENT_SIZE_MB, v)
    fun setFileNameTemplate(v: String) = put(SettingsKeys.FILE_NAME_TEMPLATE, v)
    fun setAfterComplete(v: String) = put(SettingsKeys.AFTER_COMPLETE, v)
    fun setHlsQuality(v: String) = put(SettingsKeys.HLS_QUALITY, v)

    fun setDownloadTreeUri(uri: String?) {
        viewModelScope.launch { store.setNullable(SettingsKeys.DOWNLOAD_TREE_URI, uri) }
    }

    // 网络
    fun setDefaultUa(v: String) = put(SettingsKeys.DEFAULT_UA, v)
    fun setCustomUa(v: String) = put(SettingsKeys.CUSTOM_UA, v)
    fun setRefererPolicy(v: String) = put(SettingsKeys.REFERER_POLICY, v)
    fun setCustomReferer(v: String) = put(SettingsKeys.CUSTOM_REFERER, v)
    fun setCookieSync(v: Boolean) = put(SettingsKeys.COOKIE_SYNC, v)
    fun setWifiOnly(v: Boolean) = put(SettingsKeys.WIFI_ONLY, v)
    fun setMobileConfirm(v: Boolean) = put(SettingsKeys.MOBILE_CONFIRM, v)
    fun setProxyEnabled(v: Boolean) = put(SettingsKeys.PROXY_ENABLED, v)
    fun setProxyHost(v: String) = put(SettingsKeys.PROXY_HOST, v)
    fun setProxyPort(v: Int) = put(SettingsKeys.PROXY_PORT, v)

    // 浏览器
    fun setSearchEngine(v: String) = put(SettingsKeys.SEARCH_ENGINE, v)
    fun setCustomSearchTemplate(v: String) = put(SettingsKeys.CUSTOM_SEARCH_TEMPLATE, v)
    fun setDesktopUaDefault(v: Boolean) = put(SettingsKeys.DESKTOP_UA_DEFAULT, v)
    fun setJsEnabled(v: Boolean) = put(SettingsKeys.JS_ENABLED, v)
    fun setImageLoading(v: Boolean) = put(SettingsKeys.IMAGE_LOADING, v)
    fun setMixedContentAllowed(v: Boolean) = put(SettingsKeys.MIXED_CONTENT_ALLOWED, v)
    fun setIncognitoDefault(v: Boolean) = put(SettingsKeys.INCOGNITO_DEFAULT, v)

    fun clearBrowserData() {
        viewModelScope.launch {
            // Cookie / DOM Storage / 缓存统一由 GeckoRuntime 的 StorageController 清理，
            // 不再依赖 android.webkit.CookieManager 与 WebStorage。
            geckoRuntimeManager.clearBrowserData()
            repo.clearHistory()
            runCatching { File(context.cacheDir, "parts").deleteRecursively() }
        }
    }

    // 外观
    fun setDynamicColor(v: Boolean) = put(SettingsKeys.DYNAMIC_COLOR, v)
    fun setThemeColor(v: String) = put(SettingsKeys.THEME_COLOR, v)
    fun setDarkMode(v: String) = put(SettingsKeys.DARK_MODE, v)

    // 隐私
    fun setIncognitoClearOnExit(v: Boolean) = put(SettingsKeys.INCOGNITO_CLEAR_ON_EXIT, v)
    fun setSniffEnabled(v: Boolean) = put(SettingsKeys.SNIFF_ENABLED, v)
    fun setLogUpload(v: Boolean) = put(SettingsKeys.LOG_UPLOAD, v)
}
