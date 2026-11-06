package com.yuanbao.downloader.data.settings

/**
 * 全量设置模型。所有字段均由 Preferences DataStore 持久化，
 * 界面通过 settingsFlow 观察，修改后即时生效。
 */
data class AppSettings(
    val threadCount: Int = 8,
    val maxConcurrentTasks: Int = 3,
    val perTaskSpeedLimitKbps: Int = 0,
    val globalSpeedLimitKbps: Int = 0,
    val maxRetry: Int = 3,
    val retryBackoffMs: Long = 1000L,
    val segmentSizeMb: Int = 4,
    val fileNameTemplate: String = "{name}{ext}",
    val downloadTreeUri: String? = null,
    val afterComplete: String = "notify",

    val defaultUa: String = "chrome",
    val customUa: String = "",
    val refererPolicy: String = "none",
    val customReferer: String = "",
    val cookieSync: Boolean = true,
    val wifiOnly: Boolean = false,
    val mobileConfirm: Boolean = true,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,

    val searchEngine: String = "bing",
    val customSearchTemplate: String = "https://www.google.com/search?q={query}",
    val desktopUaDefault: Boolean = false,
    val jsEnabled: Boolean = true,
    val imageLoading: Boolean = true,
    val mixedContentAllowed: Boolean = false,
    val incognitoDefault: Boolean = false,

    val dynamicColor: Boolean = true,
    val themeColor: String = "blue",
    val darkMode: String = "system",

    val incognitoClearOnExit: Boolean = true,
    val sniffEnabled: Boolean = true,
    val logUpload: Boolean = false,
    val hlsQuality: String = "auto",
    val onboardingDone: Boolean = false
) {
    val segmentSizeBytes: Long get() = segmentSizeMb.coerceIn(1, 64) * 1024L * 1024L
    val safeThreadCount: Int get() = threadCount.coerceIn(1, 256)
    val safeMaxConcurrent: Int get() = maxConcurrentTasks.coerceIn(1, 32)
}
