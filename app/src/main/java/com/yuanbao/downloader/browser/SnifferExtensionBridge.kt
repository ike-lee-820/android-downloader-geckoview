package com.yuanbao.downloader.browser

import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 下发到内置扩展的运行配置。 */
data class SnifferConfig(
    val sniffEnabled: Boolean,
    val blockImages: Boolean,
    val blockMixedContent: Boolean
)

/**
 * WebExtension 消息桥。
 *
 * 扩展通过 browser.runtime.sendNativeMessage 把 webRequest 事件送到 onMessage()，
 * 通过 browser.runtime.connectNative 建立的长连接由 onConnect() 接管，用于把
 * 设置（嗅探开关、图片拦截）与页内查找 / 辅助探测指令下发给扩展。
 *
 * 这里只做 JSON 解析与标签归属判定，聚合去重交给 SniffAggregator。
 */
@Singleton
class SnifferExtensionBridge @Inject constructor(
    private val sniffer: Sniffer
) : WebExtension.MessageDelegate {

    companion object {
        const val TAG = "GeckoSnifferBridge"
    }

    private val tabIdBySession = ConcurrentHashMap<GeckoSession, String>()
    private val tabIdByExtTab = ConcurrentHashMap<Int, String>()

    /** 后台脚本发起的请求没有可用 session 时，回退到当前活动标签。 */
    @Volatile
    var activeTabIdProvider: () -> String? = { null }

    @Volatile
    var settingsProvider: () -> SnifferConfig =
        { SnifferConfig(true, false, true) }

    @Volatile
    private var port: WebExtension.Port? = null

    fun registerSession(session: GeckoSession, tabId: String) {
        tabIdBySession[session] = tabId
    }

    fun unregisterSession(session: GeckoSession) {
        tabIdBySession.remove(session)
    }

    fun registerExtTab(extTabId: Int, tabId: String) {
        if (extTabId > 0) tabIdByExtTab[extTabId] = tabId
    }

    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender
    ): GeckoResult<Any> {
        val json = toJson(message)
        if (json != null) handle(json, sender.session)
        return GeckoResult.fromValue<Any>(JSONObject().put("ack", true))
    }

    override fun onConnect(port: WebExtension.Port) {
        this.port = port
        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                // 预留双向通道：扩展主动回执 / 补充探测结果走这里。
                // 主通道仍是 onMessage 中的 sendNativeMessage。
            }

            override fun onDisconnect(port: WebExtension.Port) {
                this@SnifferExtensionBridge.port = null
            }
        })
        pushConfig()
    }

    /** 设置变更后即时推送，Flow 驱动，无需重启扩展或 runtime。 */
    fun pushConfig() {
        val config = settingsProvider()
        val message = JSONObject().apply {
            put("type", "config")
            put("sniffEnabled", config.sniffEnabled)
            put("blockImages", config.blockImages)
            put("blockMixedContent", config.blockMixedContent)
        }
        runCatching { port?.postMessage(message) }
            .onFailure { Log.w(TAG, "下发扩展配置失败", it) }
    }

    fun requestFind(query: String) {
        val message = JSONObject().apply {
            put("type", "find")
            put("tabId", -1)
            put("query", query)
        }
        runCatching { port?.postMessage(message) }
    }

    fun requestProbe() {
        val message = JSONObject().apply {
            put("type", "probe")
            put("tabId", -1)
        }
        runCatching { port?.postMessage(message) }
    }

    private fun handle(json: JSONObject, session: GeckoSession?) {
        val kind = json.optString("kind")
        if (kind != "network" && kind != "page") return
        val url = optNullableString(json, "url") ?: return
        if (url.isBlank()) return

        val extTabId = json.optInt("tabId", -1)
        val tabId = tabIdByExtTab[extTabId]
            ?: session?.let { tabIdBySession[it] }
            ?: activeTabIdProvider()
            ?: return

        sniffer.onNetworkEvent(
            NetworkEvent(
                tabId = tabId,
                url = url,
                method = json.optString("method", "GET").ifBlank { "GET" },
                headers = parseStringMap(json.optJSONObject("headers")),
                mime = optNullableString(json, "mime"),
                resourceTypeHint = optNullableString(json, "resourceType"),
                requestId = json.optString("requestId", ""),
                hasRequestBody = json.optBoolean("hasRequestBody", false),
                source = json.optString("source", "webextension").ifBlank { "webextension" }
            )
        )
    }

    private fun optNullableString(json: JSONObject, name: String): String? =
        json.optString(name, "").takeIf { it.isNotBlank() && it != "null" }

    private fun parseStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        json.keys().forEach { key -> out[key] = json.optString(key, "") }
        return out
    }

    private fun toJson(message: Any): JSONObject? = when (message) {
        is JSONObject -> message
        is Map<*, *> -> JSONObject().apply {
            message.forEach { (key, value) ->
                if (key is String) put(key, value ?: JSONObject.NULL)
            }
        }
        is String -> runCatching { JSONObject(message) }.getOrNull()
        else -> null
    }
}
