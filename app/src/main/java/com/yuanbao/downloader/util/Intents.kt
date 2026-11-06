package com.yuanbao.downloader.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.net.URLEncoder

object AppIntents {

    fun openDefaultAppsSettings(context: Context) {
        val intent = runCatching { Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }
            .getOrDefault(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openAppDetails(context) }
    }

    fun requestBatteryWhitelist(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openAppDetails(context) }
    }

    /** 国产 ROM 自启动管理页无统一 API，逐个尝试常见组件，失败回退应用详情页。 */
    fun openAutoStartSettings(context: Context) {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        candidates.forEach { cn ->
            val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openFile(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newUri(context.contentResolver, "download", uri)
        }
        runCatching { context.startActivity(intent) }
    }

    fun copyText(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "分享")) }
    }

    fun searchTemplate(engine: String, custom: String): String = when (engine) {
        "google" -> "https://www.google.com/search?q={query}"
        "bing" -> "https://www.bing.com/search?q={query}"
        "duckduckgo" -> "https://duckduckgo.com/?q={query}"
        "baidu" -> "https://www.baidu.com/s?wd={query}"
        else -> custom.ifBlank { "https://www.bing.com/search?q={query}" }
    }

    /** 地址栏智能识别：URL 直接打开，其余按所选搜索引擎模板检索。 */
    fun smartAddress(engine: String, custom: String, input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        if (t.startsWith("about:", true) || t.startsWith("file:", true) ||
            t.startsWith("javascript:", true)
        ) return t
        if (Regex("^(?:https?|ftp)://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        if (Regex("^[\\w.-]+\\.[a-z]{2,}(?:/|\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return "https://$t"
        }
        val q = URLEncoder.encode(t, "UTF-8")
        return searchTemplate(engine, custom).replace("{query}", q)
    }

    fun isLikelyUrl(text: String): Boolean =
        Regex("^(?:https?|ftp)://|^[\\w.-]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
}
