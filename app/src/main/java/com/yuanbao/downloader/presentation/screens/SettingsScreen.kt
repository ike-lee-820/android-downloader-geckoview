package com.yuanbao.downloader.presentation.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.yuanbao.downloader.presentation.SettingsViewModel
import com.yuanbao.downloader.presentation.components.SegmentedSettingRow
import com.yuanbao.downloader.presentation.components.SettingActionRow
import com.yuanbao.downloader.presentation.components.SettingDropdownRow
import com.yuanbao.downloader.presentation.components.SettingSection
import com.yuanbao.downloader.presentation.components.SettingSliderRow
import com.yuanbao.downloader.presentation.components.SettingSwitchRow
import com.yuanbao.downloader.presentation.components.SettingTextRow
import com.yuanbao.downloader.util.AppIntents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadTreeUri(uri.toString())
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            TopAppBar(title = { Text("设置") })
        }

        item {
            SettingSection("下载") {
                SettingSliderRow("并发线程数", settings.threadCount, 1, 256, " 线程",
                    viewModel::setThreadCount)
                SettingSliderRow("最大同时任务数", settings.maxConcurrentTasks, 1, 32, " 项",
                    viewModel::setMaxConcurrentTasks)
                SettingSliderRow("单任务限速", settings.perTaskSpeedLimitKbps, 0, 102400,
                    " KB/s", viewModel::setPerTaskSpeedLimit)
                SettingSliderRow("全局限速", settings.globalSpeedLimitKbps, 0, 102400,
                    " KB/s", viewModel::setGlobalSpeedLimit)
                SettingSliderRow("失败重试次数", settings.maxRetry, 0, 10, " 次",
                    viewModel::setMaxRetry)
                SettingSliderRow("重试退避基准", settings.retryBackoffMs.toInt(), 200, 10000,
                    " ms", { viewModel.setRetryBackoff(it.toLong()) })
                SettingSliderRow("分片大小", settings.segmentSizeMb, 1, 64, " MB",
                    viewModel::setSegmentSize)
                SettingTextRow("文件名模板", settings.fileNameTemplate,
                    "{name}{ext} / {host} / {date}", viewModel::setFileNameTemplate)
                SettingActionRow(
                    "下载目录",
                    settings.downloadTreeUri ?: "应用专属目录，点击通过 SAF 授权自定义目录"
                ) { treeLauncher.launch(null) }
                SettingDropdownRow("完成后操作", listOf(
                    "notify" to "仅通知", "open" to "通知并打开", "none" to "无操作"
                ), settings.afterComplete, viewModel::setAfterComplete)
                SettingDropdownRow("HLS 码率策略", listOf(
                    "auto" to "自适应", "highest" to "最高码率", "lowest" to "最低码率"
                ), settings.hlsQuality, viewModel::setHlsQuality)
            }
        }

        item {
            SettingSection("网络") {
                SettingDropdownRow("默认 User-Agent", listOf(
                    "chrome" to "Chrome", "safari" to "Safari", "ios" to "iOS",
                    "android" to "Android", "custom" to "自定义"
                ), settings.defaultUa, viewModel::setDefaultUa)
                SettingTextRow("自定义 UA", settings.customUa, "留空则回退 Chrome",
                    viewModel::setCustomUa)
                SettingDropdownRow("Referer 策略", listOf(
                    "none" to "不发送", "origin" to "同源", "url" to "完整 URL",
                    "custom" to "自定义"
                ), settings.refererPolicy, viewModel::setRefererPolicy)
                SettingTextRow("自定义 Referer", settings.customReferer, "例如 https://example.com/",
                    viewModel::setCustomReferer)
                SettingSwitchRow("Cookie 同步", "透传任务携带的 Cookie 请求头",
                    settings.cookieSync, viewModel::setCookieSync)
                SettingSwitchRow("仅 Wi-Fi 下载", "移动网络下自动暂停任务",
                    settings.wifiOnly, viewModel::setWifiOnly)
                SettingSwitchRow("移动网络确认", "切换到移动网络时弹出确认",
                    settings.mobileConfirm, viewModel::setMobileConfirm)
                SettingSwitchRow("启用 HTTP 代理", "修改后网络栈即时重建",
                    settings.proxyEnabled, viewModel::setProxyEnabled)
                SettingTextRow("代理主机", settings.proxyHost, "例如 127.0.0.1",
                    viewModel::setProxyHost)
                SettingSliderRow("代理端口", settings.proxyPort, 1, 65535, "",
                    viewModel::setProxyPort)
            }
        }

        item {
            SettingSection("浏览器") {
                SettingDropdownRow("默认搜索引擎", listOf(
                    "google" to "Google", "bing" to "Bing", "duckduckgo" to "DuckDuckGo",
                    "baidu" to "百度", "custom" to "自定义模板"
                ), settings.searchEngine, viewModel::setSearchEngine)
                SettingTextRow("自定义搜索模板", settings.customSearchTemplate,
                    "https://example.com/search?q={query}", viewModel::setCustomSearchTemplate)
                SettingSwitchRow("默认桌面版 UA", "新标签页使用桌面版标识",
                    settings.desktopUaDefault, viewModel::setDesktopUaDefault)
                SettingSwitchRow("JavaScript", "关闭后注入式嗅探同步停用",
                    settings.jsEnabled, viewModel::setJsEnabled)
                SettingSwitchRow("加载图片", "关闭可节省流量",
                    settings.imageLoading, viewModel::setImageLoading)
                SettingSwitchRow("允许混合内容", "HTTPS 页面加载 HTTP 子资源",
                    settings.mixedContentAllowed, viewModel::setMixedContentAllowed)
                SettingSwitchRow("默认无痕标签", "新建标签默认进入隐身模式",
                    settings.incognitoDefault, viewModel::setIncognitoDefault)
                SettingActionRow("清除缓存 / Cookie / 历史", "同时清空临时分片与浏览记录")
                { viewModel.clearBrowserData() }
            }
        }

        item {
            SettingSection("外观") {
                SettingSwitchRow("Material You 动态取色", "Android 12 及以上使用系统壁纸取色",
                    settings.dynamicColor, viewModel::setDynamicColor)
                SettingDropdownRow("主题色", listOf(
                    "blue" to "蓝色", "green" to "绿色", "purple" to "紫色",
                    "orange" to "橙色", "red" to "红色"
                ), settings.themeColor, viewModel::setThemeColor)
                SegmentedSettingRow("深色模式", listOf(
                    "system" to "跟随系统", "light" to "常亮", "dark" to "常暗"
                ), settings.darkMode, viewModel::setDarkMode)
            }
        }

        item {
            SettingSection("隐私") {
                SettingSwitchRow("退出隐身即清理", "关闭全部隐身标签后清空 Cookie 与缓存",
                    settings.incognitoClearOnExit, viewModel::setIncognitoClearOnExit)
                SettingSwitchRow("资源嗅探", "拦截请求并注入 JS 钩子捕获媒体地址",
                    settings.sniffEnabled, viewModel::setSniffEnabled)
                SettingSwitchRow("日志上报", "仅记录本地诊断信息，不上传个人数据",
                    settings.logUpload, viewModel::setLogUpload)
                SettingActionRow(
                    "隐身模式说明",
                    "使用 GeckoSession usePrivateMode 与独立 storage 目录隔离，见 README"
                ) { }
            }
        }

        item {
            SettingSection("关于") {
                SettingActionRow("设为默认下载器", "跳转系统默认应用设置页")
                { AppIntents.openDefaultAppsSettings(context) }
                SettingActionRow("电池优化白名单", "保证后台下载不被系统冻结")
                { AppIntents.requestBatteryWhitelist(context) }
                SettingActionRow("自启动权限", "国产 ROM 自启动管理页引导")
                { AppIntents.openAutoStartSettings(context) }
                SettingActionRow("版本号", "1.0.0 · minSdk 26 / targetSdk 34") { }
                SettingActionRow("开源许可", "OkHttp、Room、Compose、Hilt 等 Apache-2.0") { }
                SettingActionRow("检查更新", "当前为内置最新工程版本") { }
            }
        }
    }
}
