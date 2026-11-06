package com.yuanbao.downloader.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "downloader_settings")

object SettingsKeys {
    val THREAD_COUNT = intPreferencesKey("thread_count")
    val MAX_CONCURRENT_TASKS = intPreferencesKey("max_concurrent_tasks")
    val PER_TASK_SPEED_LIMIT = intPreferencesKey("per_task_speed_limit")
    val GLOBAL_SPEED_LIMIT = intPreferencesKey("global_speed_limit")
    val MAX_RETRY = intPreferencesKey("max_retry")
    val RETRY_BACKOFF_MS = longPreferencesKey("retry_backoff_ms")
    val SEGMENT_SIZE_MB = intPreferencesKey("segment_size_mb")
    val FILE_NAME_TEMPLATE = stringPreferencesKey("file_name_template")
    val DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
    val AFTER_COMPLETE = stringPreferencesKey("after_complete")

    val DEFAULT_UA = stringPreferencesKey("default_ua")
    val CUSTOM_UA = stringPreferencesKey("custom_ua")
    val REFERER_POLICY = stringPreferencesKey("referer_policy")
    val CUSTOM_REFERER = stringPreferencesKey("custom_referer")
    val COOKIE_SYNC = booleanPreferencesKey("cookie_sync")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val MOBILE_CONFIRM = booleanPreferencesKey("mobile_confirm")
    val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
    val PROXY_HOST = stringPreferencesKey("proxy_host")
    val PROXY_PORT = intPreferencesKey("proxy_port")

    val SEARCH_ENGINE = stringPreferencesKey("search_engine")
    val CUSTOM_SEARCH_TEMPLATE = stringPreferencesKey("custom_search_template")
    val DESKTOP_UA_DEFAULT = booleanPreferencesKey("desktop_ua_default")
    val JS_ENABLED = booleanPreferencesKey("js_enabled")
    val IMAGE_LOADING = booleanPreferencesKey("image_loading")
    val MIXED_CONTENT_ALLOWED = booleanPreferencesKey("mixed_content_allowed")
    val INCOGNITO_DEFAULT = booleanPreferencesKey("incognito_default")

    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val THEME_COLOR = stringPreferencesKey("theme_color")
    val DARK_MODE = stringPreferencesKey("dark_mode")

    val INCOGNITO_CLEAR_ON_EXIT = booleanPreferencesKey("incognito_clear_on_exit")
    val SNIFF_ENABLED = booleanPreferencesKey("sniff_enabled")
    val LOG_UPLOAD = booleanPreferencesKey("log_upload")
    val HLS_QUALITY = stringPreferencesKey("hls_quality")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
}

class SettingsDataStore(private val context: Context) {

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            threadCount = p[SettingsKeys.THREAD_COUNT] ?: 8,
            maxConcurrentTasks = p[SettingsKeys.MAX_CONCURRENT_TASKS] ?: 3,
            perTaskSpeedLimitKbps = p[SettingsKeys.PER_TASK_SPEED_LIMIT] ?: 0,
            globalSpeedLimitKbps = p[SettingsKeys.GLOBAL_SPEED_LIMIT] ?: 0,
            maxRetry = p[SettingsKeys.MAX_RETRY] ?: 3,
            retryBackoffMs = p[SettingsKeys.RETRY_BACKOFF_MS] ?: 1000L,
            segmentSizeMb = p[SettingsKeys.SEGMENT_SIZE_MB] ?: 4,
            fileNameTemplate = p[SettingsKeys.FILE_NAME_TEMPLATE] ?: "{name}{ext}",
            downloadTreeUri = p[SettingsKeys.DOWNLOAD_TREE_URI],
            afterComplete = p[SettingsKeys.AFTER_COMPLETE] ?: "notify",
            defaultUa = p[SettingsKeys.DEFAULT_UA] ?: "chrome",
            customUa = p[SettingsKeys.CUSTOM_UA] ?: "",
            refererPolicy = p[SettingsKeys.REFERER_POLICY] ?: "none",
            customReferer = p[SettingsKeys.CUSTOM_REFERER] ?: "",
            cookieSync = p[SettingsKeys.COOKIE_SYNC] ?: true,
            wifiOnly = p[SettingsKeys.WIFI_ONLY] ?: false,
            mobileConfirm = p[SettingsKeys.MOBILE_CONFIRM] ?: true,
            proxyEnabled = p[SettingsKeys.PROXY_ENABLED] ?: false,
            proxyHost = p[SettingsKeys.PROXY_HOST] ?: "",
            proxyPort = p[SettingsKeys.PROXY_PORT] ?: 8080,
            searchEngine = p[SettingsKeys.SEARCH_ENGINE] ?: "bing",
            customSearchTemplate = p[SettingsKeys.CUSTOM_SEARCH_TEMPLATE]
                ?: "https://www.google.com/search?q={query}",
            desktopUaDefault = p[SettingsKeys.DESKTOP_UA_DEFAULT] ?: false,
            jsEnabled = p[SettingsKeys.JS_ENABLED] ?: true,
            imageLoading = p[SettingsKeys.IMAGE_LOADING] ?: true,
            mixedContentAllowed = p[SettingsKeys.MIXED_CONTENT_ALLOWED] ?: false,
            incognitoDefault = p[SettingsKeys.INCOGNITO_DEFAULT] ?: false,
            dynamicColor = p[SettingsKeys.DYNAMIC_COLOR] ?: true,
            themeColor = p[SettingsKeys.THEME_COLOR] ?: "blue",
            darkMode = p[SettingsKeys.DARK_MODE] ?: "system",
            incognitoClearOnExit = p[SettingsKeys.INCOGNITO_CLEAR_ON_EXIT] ?: true,
            sniffEnabled = p[SettingsKeys.SNIFF_ENABLED] ?: true,
            logUpload = p[SettingsKeys.LOG_UPLOAD] ?: false,
            hlsQuality = p[SettingsKeys.HLS_QUALITY] ?: "auto",
            onboardingDone = p[SettingsKeys.ONBOARDING_DONE] ?: false
        )
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    suspend fun setNullable(key: Preferences.Key<String>, value: String?) {
        context.settingsDataStore.edit { prefs ->
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }
}
