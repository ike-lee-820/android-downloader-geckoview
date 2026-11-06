package com.yuanbao.downloader.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yuanbao.downloader.engine.DownloadEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 系统入口接收器。
 * 本应用不使用系统 DownloadManager 作为执行体，仅在收到其完成广播时做兼容处理；
 * 外部应用可通过自定义 ACTION 或 EXTRA_TEXT 直接投递下载链接。
 */
@AndroidEntryPoint
class DownloadRequestReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: DownloadEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETED) return
        val raw = intent.getStringExtra(EXTRA_URL)
            ?: intent.data?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: return
        val url = extractFirstUrl(raw) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                engine.enqueue(url)
            } finally {
                pending.finish()
            }
        }
    }

    private fun extractFirstUrl(text: String): String? =
        Regex("(?:https?|ftp)://[^\\s<>\"']+").find(text)?.value

    companion object {
        const val ACTION_REQUEST_DOWNLOAD = "com.yuanbao.downloader.action.REQUEST_DOWNLOAD"
        const val EXTRA_URL = "com.yuanbao.downloader.extra.URL"
    }
}
