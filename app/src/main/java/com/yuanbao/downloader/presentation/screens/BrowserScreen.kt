package com.yuanbao.downloader.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yuanbao.downloader.browser.BrowserViewModel
import com.yuanbao.downloader.browser.GeckoBrowserView
import com.yuanbao.downloader.browser.GeckoContextMenu
import com.yuanbao.downloader.browser.TabSessionManager
import com.yuanbao.downloader.domain.ObservedRequest
import com.yuanbao.downloader.domain.SniffedResource
import com.yuanbao.downloader.presentation.DownloadViewModel
import com.yuanbao.downloader.util.AppIntents
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    tabSessionManager: TabSessionManager,
    viewModel: BrowserViewModel = hiltViewModel(),
    downloadViewModel: DownloadViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.history.collectAsState()
    val context = LocalContext.current
    val active = ui.active
    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val sniffFlow = remember(ui.activeTabId) { viewModel.sniffedFor(ui.activeTabId.orEmpty()) }
    val sniffList by sniffFlow.collectAsState(initial = emptyList<SniffedResource>())
    val observedFlow = remember(ui.activeTabId) { viewModel.observedFor(ui.activeTabId.orEmpty()) }
    val observedList by observedFlow.collectAsState(initial = emptyList<ObservedRequest>())

    var menuExpanded by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var contextMenu by remember { mutableStateOf<GeckoContextMenu?>(null) }

    // 一、把 Gecko 回调接到 ViewModel。全部回调由壳层主线程转出，UI 无业务逻辑。
    LaunchedEffect(Unit) {
        val callbacks = tabSessionManager.callbacks
        callbacks.onTabChange = { change ->
            viewModel.updateTabFromWeb(
                change.tabId, change.url, change.title, change.progress,
                change.canBack, change.canForward
            )
        }
        callbacks.onNewWindow = { tabId, url -> viewModel.addExternalTab(tabId, url) }
        callbacks.onCloseTab = { session ->
            tabSessionManager.tabIdOf(session)?.let { id ->
                tabSessionManager.closeTab(id)
                viewModel.closeTab(id)
            }
        }
        callbacks.onFullScreen = { full -> viewModel.setFullScreen(full) }
        callbacks.onContextMenu = { item -> contextMenu = item }
        callbacks.onLoadError = { _, uri ->
            coroutineScope.launch { snackbar.showSnackbar("页面加载失败：$uri") }
        }
        tabSessionManager.setSettingsProvider { settings }
        tabSessionManager.pushSettings(settings)
    }
    LaunchedEffect(settings) {
        tabSessionManager.setSettingsProvider { settings }
        tabSessionManager.pushSettings(settings)
    }
    DisposableEffect(Unit) {
        onDispose {
            val callbacks = tabSessionManager.callbacks
            callbacks.onTabChange = null
            callbacks.onNewWindow = null
            callbacks.onCloseTab = null
            callbacks.onFullScreen = null
            callbacks.onContextMenu = null
            callbacks.onLoadError = null
        }
    }

    // 二、标签集合变化时注册 GeckoSession；活动标签变化时切换 setSession。
    LaunchedEffect(ui.tabs, settings) {
        ui.tabs.forEach { tab -> tabSessionManager.registerTab(tab, settings) }
        tabSessionManager.pushSettings(settings)
    }
    LaunchedEffect(ui.activeTabId) {
        ui.activeTabId?.let { tabSessionManager.activate(it) }
    }

    // 三、地址栏 / 书签 / 历史跳转统一走 loadUri。
    LaunchedEffect(Unit) {
        viewModel.navigate.collect { (id, url) ->
            tabSessionManager.load(id, url)
            tabSessionManager.probe(id)
        }
    }

    // 四、桌面 UA 切换即时生效。
    LaunchedEffect(ui.tabs, settings) {
        ui.tabs.forEach { tab ->
            tabSessionManager.applySettings(tab.id, tab, settings)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!ui.fullScreen) {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = ui.address,
                        onValueChange = viewModel::setAddress,
                        singleLine = true,
                        placeholder = { Text("输入网址或搜索内容") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { viewModel.submitAddress(ui.address) }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { active?.let { tabSessionManager.back(it.id) } },
                        enabled = active?.canGoBack == true
                    ) { Icon(Icons.Outlined.ArrowBack, contentDescription = "后退") }
                },
                actions = {
                    IconButton(
                        onClick = { active?.let { tabSessionManager.forward(it.id) } },
                        enabled = active?.canGoForward == true
                    ) { Icon(Icons.Outlined.ArrowForward, contentDescription = "前进") }
                    IconButton(onClick = { active?.let { tabSessionManager.reload(it.id) } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                }
            )

            if (active != null && active.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { active.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(onClick = { viewModel.submitAddress(ui.address) }) {
                    Text("前往")
                }
                IconButton(onClick = { viewModel.setShowTabs(true) }) {
                    BadgedBox(badge = { Badge { Text("${ui.tabs.size}") } }) {
                        Icon(Icons.Outlined.Tab, contentDescription = "标签页")
                    }
                }
                IconButton(onClick = { viewModel.setShowSniff(true) }) {
                    BadgedBox(
                        badge = {
                            if (sniffList.isNotEmpty()) Badge { Text("${sniffList.size}") }
                        }
                    ) { Icon(Icons.Outlined.Search, contentDescription = "嗅探结果") }
                }
                IconButton(onClick = { viewModel.setShowRequests(true) }) {
                    BadgedBox(
                        badge = {
                            if (observedList.isNotEmpty()) Badge { Text("${observedList.size}") }
                        }
                    ) { Icon(Icons.Outlined.Language, contentDescription = "请求观测") }
                }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("新建标签页") }, onClick = {
                        viewModel.newTab(false); menuExpanded = false
                    })
                    DropdownMenuItem(text = { Text("新建隐身标签页") }, onClick = {
                        viewModel.newTab(true); menuExpanded = false
                    })
                    DropdownMenuItem(
                        text = {
                            Text(if (active?.desktopUa == true) "切换为移动版 UA" else "切换为桌面版 UA")
                        },
                        onClick = {
                            active?.let { viewModel.setDesktopUa(it.id, !it.desktopUa) }
                            menuExpanded = false
                        }
                    )
                    DropdownMenuItem(text = { Text("查找页内文字") }, onClick = {
                        showFind = true; menuExpanded = false
                    })
                    DropdownMenuItem(text = { Text("辅助探测媒体地址") }, onClick = {
                        active?.let { tabSessionManager.probe(it.id) }; menuExpanded = false
                    })
                    DropdownMenuItem(text = { Text("添加书签") }, onClick = {
                        viewModel.addBookmark(); menuExpanded = false
                    })
                    DropdownMenuItem(text = { Text("书签与历史") }, onClick = {
                        viewModel.setShowBookmarks(true); menuExpanded = false
                    })
                    DropdownMenuItem(text = { Text("分享") }, onClick = {
                        active?.let { AppIntents.shareText(context, it.url) }
                        menuExpanded = false
                    })
                    DropdownMenuItem(text = { Text("复制链接") }, onClick = {
                        active?.let { AppIntents.copyText(context, "link", it.url) }
                        menuExpanded = false
                    })
                }
                if (settings.sniffEnabled) {
                    Text(
                        "嗅探开启",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 五、全局唯一 GeckoView。标签切换由 update 回调触发 setSession。
        GeckoBrowserView(
            manager = tabSessionManager,
            activeTabId = ui.activeTabId,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        androidx.compose.material3.SnackbarHost(hostState = snackbar)
    }

    if (showFind && active != null) {
        AlertDialog(
            onDismissRequest = { showFind = false },
            title = { Text("页内查找") },
            text = {
                OutlinedTextField(
                    value = ui.findQuery,
                    onValueChange = viewModel::setFindQuery,
                    singleLine = true,
                    label = { Text("关键字") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    tabSessionManager.find(active.id, ui.findQuery)
                    showFind = false
                }) { Text("查找") }
            },
            dismissButton = { TextButton(onClick = { showFind = false }) { Text("关闭") } }
        )
    }

    contextMenu?.let { item ->
        AlertDialog(
            onDismissRequest = { contextMenu = null },
            title = { Text("页面元素") },
            text = {
                Text(
                    listOfNotNull(
                        item.linkUri?.let { "链接：$it" },
                        item.srcUri?.let { "资源：$it" },
                        item.title?.let { "标题：$it" }
                    ).joinToString("\n").ifBlank { "无可操作内容" }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = item.srcUri ?: item.linkUri
                    if (!target.isNullOrBlank()) {
                        downloadViewModel.createTask(target)
                    }
                    contextMenu = null
                }) { Text("下载该资源") }
            },
            dismissButton = {
                TextButton(onClick = { contextMenu = null }) { Text("关闭") }
            }
        )
    }

    if (ui.showTabs) {
        ModalBottomSheet(onDismissRequest = { viewModel.setShowTabs(false) }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("标签页", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { viewModel.newTab(false) }) { Text("新建") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("默认无痕", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.incognitoDefault,
                        onCheckedChange = viewModel::toggleIncognitoDefault
                    )
                }
                LazyColumn {
                    items(ui.tabs, key = { it.id }) { tab ->
                        ListItem(
                            headlineContent = { Text(tab.title, maxLines = 1) },
                            supportingContent = {
                                Text(if (tab.incognito) "隐身标签页" else tab.url, maxLines = 1)
                            },
                            leadingContent = {
                                if (tab.incognito) Icon(Icons.Outlined.VisibilityOff, null)
                                else Icon(Icons.Outlined.Language, null)
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    tabSessionManager.closeTab(tab.id)
                                    viewModel.closeTab(tab.id)
                                }) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
                            },
                            modifier = Modifier.clickable { viewModel.switchTab(tab.id) }
                        )
                    }
                }
                FilledTonalButton(
                    onClick = { viewModel.newTab(true) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Text("新建隐身标签页")
                }
            }
        }
    }

    if (ui.showSniff) {
        SniffBottomSheet(
            resources = sniffList,
            onDismiss = { viewModel.setShowSniff(false) },
            onCreate = { res -> downloadViewModel.createFromSniff(res) },
            onClear = { viewModel.clearSniff(ui.activeTabId.orEmpty()) }
        )
    }

    if (ui.showRequests) {
        ObservedRequestsSheet(
            requests = observedList,
            onDismiss = { viewModel.setShowRequests(false) }
        )
    }

    if (ui.showBookmarks) {
        ModalBottomSheet(onDismissRequest = { viewModel.setShowBookmarks(false) }) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("书签与历史", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { viewModel.clearHistory() }) { Text("清空历史") }
                    }
                }
                item { Text("书签", style = MaterialTheme.typography.titleSmall) }
                items(bookmarks, key = { it.url }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title, maxLines = 1) },
                        supportingContent = { Text(item.url, maxLines = 1) },
                        leadingContent = { Icon(Icons.Outlined.Bookmark, null) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.removeBookmark(item.url) }) {
                                Icon(Icons.Outlined.Close, contentDescription = "删除书签")
                            }
                        },
                        modifier = Modifier.clickable { viewModel.submitAddress(item.url) }
                    )
                }
                item { Text("历史记录", style = MaterialTheme.typography.titleSmall) }
                items(history, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title, maxLines = 1) },
                        supportingContent = { Text(item.url, maxLines = 1) },
                        modifier = Modifier.clickable { viewModel.submitAddress(item.url) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObservedRequestsSheet(
    requests: List<ObservedRequest>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("请求观测（POST 与带请求体）", style = MaterialTheme.typography.titleMedium)
            Text(
                "由内置 WebExtension 的 webRequest 捕获，请求方法与请求头均可查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (requests.isEmpty()) {
                Text(
                    "当前标签尚未捕获到非 GET 请求。打开带鉴权初始化请求的页面即可验证。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(requests, key = { it.requestId + it.url }) { req ->
                        Card(shape = MaterialTheme.shapes.medium) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(req.method, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        if (req.hasRequestBody) " · 含请求体" else " · 请求头已捕获",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(req.url, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                req.headers.entries.take(8).forEach { (name, value) ->
                                    Text(
                                        "$name: $value",
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
