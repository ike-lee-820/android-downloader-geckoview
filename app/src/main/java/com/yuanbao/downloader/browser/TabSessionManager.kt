package com.yuanbao.downloader.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.yuanbao.downloader.data.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 单个标签的完整运行时状态。 */
data class GeckoTabSession(
    val tabId: String,
    val session: GeckoSession,
    val liveState: GeckoLiveState,
    var incognito: Boolean,
    var desktopUa: Boolean,
    var lastUrl: String = "about:blank"
)

/**
 * 标签会话管理器。
 *
 * 迁移后的核心结构：全局只创建一个 GeckoView，每个标签对应一个独立的 GeckoSession，
 * 切换标签时调用 GeckoView.setSession(session)；session 不随标签切换而 close()，
 * 因此前进后退栈、滚动位置、表单内容与页面状态都能保留。
 *
 * window.open 由 GeckoNavigationDelegate.onNewSession 接住：这里创建新的 session，
 * 通过 onNewWindow 回调让 ViewModel 生成对应的 TabState，再注册进映射表。
 */
@Singleton
class TabSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtimeManager: GeckoSessionManager,
    private val bridge: SnifferExtensionBridge,
    private val sniffer: Sniffer
) {
    companion object {
        const val TAG = "TabSessionManager"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<String, GeckoTabSession>()
    private val sessionToTab = LinkedHashMap<GeckoSession, String>()

    @Volatile
    private var viewField: GeckoView? = null

    val callbacks = GeckoBrowserCallbacks()

    @Volatile
    private var settingsProvider: () -> AppSettings = { AppSettings() }

    init {
        // 壳层自己消费的事件：媒体外部响应进入嗅探聚合器；崩溃自动重建 session。
        callbacks.onExternalResponse = { session, uri, headers ->
            onExternalResponse(session, uri, headers)
        }
        callbacks.onCrash = { session -> onCrash(session) }
    }

    fun setSettingsProvider(provider: () -> AppSettings) {
        settingsProvider = provider
    }

    fun getView(): GeckoView? = viewField

    /**
     * Compose AndroidView 的 factory 调用，使用 Activity 上下文创建视图。
     * 全局只创建一个 GeckoView 实例；Activity 销毁时 clear() 会置空，避免持有 Activity 泄漏。
     */
    fun obtainView(activityContext: Context): GeckoView {
        viewField?.let { return it }
        return synchronized(lock) {
            viewField ?: GeckoView(activityContext).also { viewField = it }
        }
    }

    /** 设置变化时把嗅探开关、图片加载、混合内容策略下发给内置扩展。 */
    fun pushSettings(settings: AppSettings) {
        bridge.settingsProvider = {
            SnifferConfig(
                sniffEnabled = settings.sniffEnabled,
                blockImages = !settings.imageLoading,
                blockMixedContent = !settings.mixedContentAllowed
            )
        }
        bridge.pushConfig()
    }

    fun registerTab(tab: TabState, settings: AppSettings) {
        synchronized(lock) {
            if (sessions.containsKey(tab.id)) {
                applySettings(tab.id, tab, settings)
                return
            }
            val entry = createSession(tab.id, tab.incognito, tab.desktopUa, settings)
            entry.liveState.url = tab.url
            entry.lastUrl = tab.url
            sessions[tab.id] = entry
            sessionToTab[entry.session] = tab.id
            if (tab.incognito) runtimeManager.ensureIncognitoStorage()
            if (tab.url != "about:blank") entry.session.loadUri(tab.url)
        }
    }

    private fun createSession(
        tabId: String,
        incognito: Boolean,
        desktopUa: Boolean,
        settings: AppSettings
    ): GeckoTabSession {
        val builder = GeckoSessionSettings.Builder()
            .usePrivateMode(incognito)
            .allowJavascript(settings.jsEnabled)
            .userAgentMode(
                if (desktopUa || settings.desktopUaDefault) {
                    GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                }
            )
            .viewportMode(
                if (desktopUa || settings.desktopUaDefault) {
                    GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                }
            )
        val sessionSettings = builder.build()
        val session = GeckoSession(sessionSettings)
        val liveState = GeckoLiveState()

        val runtime = if (incognito) {
            runtimeManager.acquireIncognitoRuntime() ?: runtimeManager.runtime
        } else {
            runtimeManager.runtime
        }

        session.open(runtime)
        session.setActive(true)
        bridge.registerSession(session, tabId)

        val navigationDelegate = GeckoNavigationDelegate(
            tabId = tabId,
            state = liveState,
            callbacks = callbacks,
            createWindowSession = { uri -> createWindowSession(uri) },
            openExternally = ::openExternally
        )
        session.setNavigationDelegate(navigationDelegate)
        session.setProgressDelegate(navigationDelegate)
        session.setContentDelegate(GeckoContentDelegate(
            tabId = tabId,
            state = liveState,
            callbacks = callbacks
        ) { callbacks.onTabChange?.invoke(liveState.snapshot(tabId)) })
        return GeckoTabSession(
            tabId = tabId,
            session = session,
            liveState = liveState,
            incognito = incognito,
            desktopUa = desktopUa
        )
    }

    /**
     * window.open：为新的 GeckoSession 生成标签 id，注册映射并通知 ViewModel 建 TabState。
     * 新 session 复用当前 runtime，保持单 GeckoRuntime 约束。
     */
    private fun createWindowSession(uri: String): GeckoSession {
        val newTabId = UUID.randomUUID().toString()
        val settings = settingsProvider()
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .allowJavascript(settings.jsEnabled)
                .build()
        )
        session.open(runtimeManager.runtime)
        bridge.registerSession(session, newTabId)

        val liveState = GeckoLiveState(url = uri)
        val nav = GeckoNavigationDelegate(
            tabId = newTabId,
            state = liveState,
            callbacks = callbacks,
            createWindowSession = ::createWindowSession,
            openExternally = ::openExternally
        )
        session.setNavigationDelegate(nav)
        session.setProgressDelegate(nav)
        session.setContentDelegate(GeckoContentDelegate(
            tabId = newTabId,
            state = liveState,
            callbacks = callbacks
        ) { callbacks.onTabChange?.invoke(liveState.snapshot(newTabId)) })

        synchronized(lock) {
            sessions[newTabId] = GeckoTabSession(
                tabId = newTabId,
                session = session,
                liveState = liveState,
                incognito = false,
                desktopUa = false,
                lastUrl = uri
            )
            sessionToTab[session] = newTabId
        }
        bridge.activeTabIdProvider = { newTabId }
        callbacks.onNewWindow?.invoke(newTabId, uri)
        return session
    }

    fun activate(tabId: String) {
        val tab = synchronized(lock) { sessions[tabId] } ?: return
        val activeSession = tab.session
        val view = viewField
        if (view?.getSession() === activeSession) {
            bridge.activeTabIdProvider = { tabId }
            return
        }
        synchronized(lock) {
            sessions.values.forEach { it.session.setActive(it.tabId == tabId) }
        }
        view?.setSession(activeSession)
        bridge.activeTabIdProvider = { tabId }
    }

    fun applySettings(tabId: String, tab: TabState, settings: AppSettings) {
        val entry = synchronized(lock) { sessions[tabId] } ?: return
        entry.incognito = tab.incognito
        entry.desktopUa = tab.desktopUa
        val s = entry.session.getSettings()
        runCatching {
            s.setAllowJavascript(settings.jsEnabled)
            val desktop = tab.desktopUa || settings.desktopUaDefault
            s.setUserAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            s.setViewportMode(
                if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            // UA 覆盖优先采用设置中的默认 UA 预设，而不是 WebSettings.userAgentString
            s.setUserAgentOverride(resolveUa(settings))
        }.onFailure { Log.w(TAG, "应用 GeckoSession 设置失败", it) }
        pushSettings(settings)
    }

    private fun resolveUa(settings: AppSettings): String = when (settings.defaultUa.lowercase()) {
        "safari" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        "ios" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        "android" -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        "custom" -> settings.customUa.ifBlank { DESKTOP_UA }
        else -> DESKTOP_UA
    }

    fun load(tabId: String, url: String) {
        val session = synchronized(lock) { sessions[tabId]?.session } ?: return
        if (url.isBlank()) return
        session.loadUri(url)
        synchronized(lock) { sessions[tabId]?.lastUrl = url }
    }

    fun back(tabId: String) { sessionOf(tabId)?.goBack() }
    fun forward(tabId: String) { sessionOf(tabId)?.goForward() }
    fun reload(tabId: String) { sessionOf(tabId)?.reload() }

    /** 页内查找通过内置扩展的 content script 执行，不依赖任何 WebView API。 */
    fun find(tabId: String, query: String) {
        activate(tabId)
        bridge.requestFind(query)
    }

    /** 辅助探测：让扩展的 content script 补充收集 Blob / DOM / ResourceTiming 中的地址。 */
    fun probe(tabId: String) {
        activate(tabId)
        bridge.requestProbe()
    }

    fun tabIdOf(session: GeckoSession): String? = synchronized(lock) { sessionToTab[session] }

    fun closeTab(tabId: String) {
        val entry = synchronized(lock) { sessions.remove(tabId) } ?: return
        sessionToTab.remove(entry.session)
        bridge.unregisterSession(entry.session)
        sniffer.clearTab(tabId)
        runCatching {
            if (viewField?.getSession() == entry.session) viewField?.releaseSession()
            entry.session.close()
        }
        val stillIncognito = synchronized(lock) { sessions.values.any { it.incognito } }
        if (!stillIncognito) runtimeManager.releaseIncognitoRuntime()
    }

    fun clear() {
        val all = synchronized(lock) {
            val copy = sessions.values.toList()
            sessions.clear()
            sessionToTab.clear()
            copy
        }
        all.forEach { entry ->
            bridge.unregisterSession(entry.session)
            runCatching { entry.session.close() }
        }
        runCatching { viewField?.releaseSession() }
        viewField = null
        runtimeManager.releaseIncognitoRuntime()
    }

    private fun sessionOf(tabId: String): GeckoSession? =
        synchronized(lock) { sessions[tabId]?.session }

    private fun openExternally(uri: String): Boolean {
        if (uri.isBlank()) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 把 WebExtension 的 extTabId 映射到应用标签，消息归属更精确。 */
    fun bindExtTab(extTabId: Int, tabId: String) = bridge.registerExtTab(extTabId, tabId)

    fun onExternalResponse(session: GeckoSession, uri: String, headers: Map<String, String>) {
        val tabId = tabIdOf(session) ?: bridge.activeTabIdProvider() ?: return
        val mime = headers.entries.firstOrNull {
            it.key.equals("Content-Type", ignoreCase = true)
        }?.value
        sniffer.add(tabId, uri, mime, "onExternalResponse")
    }

    fun onCloseRequest(session: GeckoSession) {
        tabIdOf(session)?.let { callbacks.onCloseTab?.invoke(session) }
    }

    fun onCrash(session: GeckoSession) {
        val tabId = tabIdOf(session) ?: return
        val url = synchronized(lock) { sessions[tabId]?.lastUrl } ?: "about:blank"
        Log.w(TAG, "标签 $tabId 渲染进程崩溃，重建 session")
        closeTab(tabId)
        val settings = settingsProvider()
        registerTab(
            TabState(id = tabId, url = url),
            settings
        )
        if (url != "about:blank") load(tabId, url)
    }

}
