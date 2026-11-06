package com.yuanbao.downloader.browser

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GeckoRuntime 管理。
 *
 * GeckoView 明确规定一个进程只允许一个活跃的 GeckoRuntime，且必须绑定到进程生命周期
 * 而不是 Activity。因此这里由 Hilt 单例持有，Application.onCreate 预热，
 * Activity 销毁时绝不调用 shutdown()。
 *
 * 内置嗅探扩展通过 WebExtensionController.ensureBuiltIn 安装：
 * ensureBuiltIn 会在版本一致时跳过重复安装，避免每次冷启动重复注册。
 */
@Singleton
class GeckoSessionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bridge: SnifferExtensionBridge
) {
    companion object {
        const val TAG = "GeckoSessionManager"
        const val EXTENSION_LOCATION = "resource://android/assets/sniffer/"
        const val EXTENSION_ID = "sniffer@yuanbao.downloader"
        const val NATIVE_APP = "gecko_sniffer"
    }

    private val lock = Any()

    @Volatile
    private var runtimeField: GeckoRuntime? = null

    @Volatile
    private var incognitoRuntime: GeckoRuntime? = null

    /**
     * 是否为隐身标签尝试创建独立 GeckoRuntime。默认关闭：GeckoView 单进程只允许一个
     * 活跃 runtime，创建第二个会抛 IllegalStateException。隐身隔离主要依赖
     * GeckoSessionSettings.usePrivateMode(true) 与独立 storage 目录。
     */
    @Volatile
    var allowSeparateIncognitoRuntime: Boolean = false

    private val incognitoDir: File
        get() = File(appContext.noBackupFilesDir, "gecko_incognito")

    val runtime: GeckoRuntime
        get() = ensureRuntime()

    /** Application 启动时调用，提前初始化 runtime 并注册内置扩展。 */
    fun warmUp() {
        ensureRuntime()
    }

    private fun ensureRuntime(): GeckoRuntime = synchronized(lock) {
        runtimeField ?: GeckoRuntime.create(appContext, buildRuntimeSettings()).also {
            runtimeField = it
            installSniffer(it)
        }
    }

    private fun buildRuntimeSettings(): GeckoRuntimeSettings =
        GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(false)
            .remoteDebuggingEnabled(false)
            .extensionsProcessEnabled(true)
            .build()

    private fun installSniffer(runtime: GeckoRuntime) {
        // 内置扩展位于 APK assets/sniffer/，只允许 resource://android URI。
        // 内置扩展自动获得 native messaging 能力，无需签名。
        runCatching {
            runtime.webExtensionController
                .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                .accept(object : GeckoResult.Consumer<WebExtension> {
                    override fun accept(value: WebExtension?) {
                        value?.setMessageDelegate(bridge, NATIVE_APP)
                        bridge.pushConfig()
                        Log.i(TAG, "内置嗅探扩展已就绪")
                    }
                })
        }.onFailure { Log.w(TAG, "内置嗅探扩展安装失败", it) }
    }

    /** 隐身标签的独立 storage 目录；退出全部隐身标签时清空。 */
    fun ensureIncognitoStorage() {
        runCatching { incognitoDir.mkdirs() }
    }

    fun acquireIncognitoRuntime(): GeckoRuntime? {
        if (!allowSeparateIncognitoRuntime) return null
        return synchronized(lock) {
            incognitoRuntime ?: runCatching {
                GeckoRuntime.create(appContext, buildRuntimeSettings()).also {
                    incognitoRuntime = it
                    ensureIncognitoStorage()
                }
            }.onFailure {
                Log.i(TAG, "单进程仅允许一个 GeckoRuntime，隐身标签回退 usePrivateMode", it)
            }.getOrNull()
        }
    }

    fun releaseIncognitoRuntime() {
        synchronized(lock) {
            incognitoRuntime?.shutdown()
            incognitoRuntime = null
        }
        runCatching { incognitoDir.deleteRecursively() }
    }

    /** 清除 Cookie、DOM Storage、缓存等；替代迁移前的 CookieManager / WebStorage。 */
    fun clearBrowserData() {
        runCatching {
            runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        }.onFailure { Log.w(TAG, "清理 Gecko 存储失败", it) }
    }
}
