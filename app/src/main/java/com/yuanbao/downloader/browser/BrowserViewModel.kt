package com.yuanbao.downloader.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuanbao.downloader.data.db.BookmarkEntity
import com.yuanbao.downloader.data.db.HistoryEntity
import com.yuanbao.downloader.data.repo.DownloadRepository
import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.data.settings.SettingsDataStore
import com.yuanbao.downloader.data.settings.SettingsKeys
import com.yuanbao.downloader.util.AppIntents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class TabState(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新标签页",
    val url: String = "about:blank",
    val incognito: Boolean = false,
    val desktopUa: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

data class BrowserUiState(
    val tabs: List<TabState> = emptyList(),
    val activeTabId: String? = null,
    val address: String = "",
    val findQuery: String = "",
    val showTabs: Boolean = false,
    val showSniff: Boolean = false,
    val showBookmarks: Boolean = false,
    val showRequests: Boolean = false,
    val fullScreen: Boolean = false
) {
    val active: TabState? get() = tabs.firstOrNull { it.id == activeTabId }
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val sniffer: Sniffer,
    private val settingsStore: SettingsDataStore
) : ViewModel() {

    private val firstTab = TabState()
    private val _ui = MutableStateFlow(
        BrowserUiState(tabs = listOf(firstTab), activeTabId = firstTab.id)
    )
    val ui: StateFlow<BrowserUiState> = _ui.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val bookmarks = repo.observeBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history = repo.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _navigate = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val navigate = _navigate.asSharedFlow()

    fun sniffedFor(tabId: String): Flow<List<com.yuanbao.downloader.domain.SniffedResource>> =
        sniffer.flow(tabId)

    fun sniffCount(tabId: String): Flow<Int> = sniffer.count(tabId)

    /** webRequest 捕获到的非 GET 请求（含 POST 鉴权参数），用于验收项 3 的可视化。 */
    fun observedFor(tabId: String): Flow<List<com.yuanbao.downloader.domain.ObservedRequest>> =
        sniffer.observedFlow(tabId)

    fun clearSniff(tabId: String) = sniffer.clearTab(tabId)

    fun setAddress(value: String) = _ui.update { it.copy(address = value) }
    fun setFindQuery(value: String) = _ui.update { it.copy(findQuery = value) }
    fun setShowTabs(value: Boolean) = _ui.update { it.copy(showTabs = value) }
    fun setShowSniff(value: Boolean) = _ui.update { it.copy(showSniff = value) }
    fun setShowBookmarks(value: Boolean) = _ui.update { it.copy(showBookmarks = value) }
    fun setShowRequests(value: Boolean) = _ui.update { it.copy(showRequests = value) }
    fun setFullScreen(value: Boolean) = _ui.update { it.copy(fullScreen = value) }

    /** window.open 由 NavigationDelegate.onNewSession 接住后，按已生成的 session id 建 TabState。 */
    fun addExternalTab(tabId: String, url: String) {
        val tab = TabState(id = tabId, url = url)
        _ui.update { state ->
            state.copy(
                tabs = state.tabs + tab,
                activeTabId = tabId,
                address = url,
                showTabs = false
            )
        }
    }

    fun newTab(incognito: Boolean = settings.value.incognitoDefault) {
        val tab = TabState(incognito = incognito)
        _ui.update {
            it.copy(
                tabs = it.tabs + tab,
                activeTabId = tab.id,
                address = "",
                showTabs = false
            )
        }
        viewModelScope.launch { _navigate.emit(tab.id to "about:blank") }
    }

    fun closeTab(id: String) {
        val state = _ui.value
        val remaining = state.tabs.filterNot { it.id == id }
        val next = if (remaining.isEmpty()) {
            val tab = TabState(incognito = settings.value.incognitoDefault)
            listOf(tab)
        } else remaining
        val activeId = if (state.activeTabId == id) next.last().id else state.activeTabId
        _ui.update {
            it.copy(
                tabs = next,
                activeTabId = activeId,
                address = next.firstOrNull { t -> t.id == activeId }?.url.orEmpty()
            )
        }
    }

    fun switchTab(id: String) {
        val tab = _ui.value.tabs.firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(activeTabId = id, address = tab.url, showTabs = false) }
    }

    fun submitAddress(input: String) {
        val id = _ui.value.activeTabId ?: return
        val url = AppIntents.smartAddress(
            settings.value.searchEngine,
            settings.value.customSearchTemplate,
            input
        )
        _ui.update { state ->
            state.copy(
                address = input,
                tabs = state.tabs.map { if (it.id == id) it.copy(url = url) else it }
            )
        }
        viewModelScope.launch { _navigate.emit(id to url) }
    }

    fun setDesktopUa(id: String, enabled: Boolean) {
        _ui.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == id) it.copy(desktopUa = enabled) else it })
        }
    }

    /** 由 NavigationDelegate / ProgressDelegate / ContentDelegate 汇总后的回调驱动；隐身标签不写入历史。 */
    fun updateTabFromWeb(
        tabId: String,
        url: String,
        title: String?,
        progress: Int,
        canBack: Boolean,
        canForward: Boolean
    ) {
        _ui.update { state ->
            state.copy(
                tabs = state.tabs.map { t ->
                    if (t.id == tabId) t.copy(
                        url = url,
                        title = title?.takeIf { it.isNotBlank() } ?: t.title,
                        progress = progress,
                        canGoBack = canBack,
                        canGoForward = canForward
                    ) else t
                },
                address = if (state.activeTabId == tabId) url else state.address
            )
        }
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId }
        if (tab != null && !tab.incognito && url != "about:blank" && progress >= 100) {
            viewModelScope.launch {
                repo.addHistory(HistoryEntity(url = url, title = title ?: url))
            }
        }
    }

    fun addBookmark() {
        val tab = _ui.value.active ?: return
        if (tab.url == "about:blank") return
        viewModelScope.launch {
            repo.addBookmark(BookmarkEntity(url = tab.url, title = tab.title))
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch { repo.removeBookmark(url) }
    }

    fun clearHistory() {
        viewModelScope.launch { repo.clearHistory() }
    }

    fun toggleIncognitoDefault(value: Boolean) {
        viewModelScope.launch { settingsStore.set(SettingsKeys.INCOGNITO_DEFAULT, value) }
    }
}
