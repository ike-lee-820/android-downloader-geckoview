package com.yuanbao.downloader.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.yuanbao.downloader.data.settings.SettingsDataStore
import com.yuanbao.downloader.domain.EngineEvent
import com.yuanbao.downloader.engine.DownloadEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 下载前台服务。
 * 使用 dataSync 类型并在 5 秒内调用 startForeground；常驻通知 setOngoing(true)，
 * Android 14 起显式传入 FOREGROUND_SERVICE_TYPE_DATA_SYNC。
 */
@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject lateinit var engine: DownloadEngine
    @Inject lateinit var notifier: NotificationHelper
    @Inject lateinit var settingsStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var afterComplete: String = "notify"

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        startForegroundCompat(notifier.foregroundNotification(0, 0, "0 B/s"))

        // 引擎状态与通知解耦：服务只订阅 Flow，不参与下载逻辑
        scope.launch {
            launch { engine.tasks.collect { notifier.updateAggregate(it) } }
            launch { engine.events.collect { event -> handleEvent(event) } }
            launch { settingsStore.settingsFlow.collect { afterComplete = it.afterComplete } }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NotificationHelper.FG_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.FG_NOTIFICATION_ID, notification)
        }
    }

    private fun handleEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Completed -> {
                notifier.notifyCompleted(event.task, event.outputUri)
                if (afterComplete == "open" && event.outputUri != null) {
                    runCatching { startActivity(openFileIntent(event.outputUri!!)) }
                }
            }
            is EngineEvent.Failed -> notifier.notifyFailed(event.task, event.reason)
            is EngineEvent.MobileNetworkConfirm -> showMobileConfirm()
            is EngineEvent.Message -> Unit
        }
    }

    private fun openFileIntent(uriString: String): Intent {
        val uri = Uri.parse(uriString)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun showMobileConfirm() {
        val intent = Intent(this, DownloadForegroundService::class.java)
            .setAction(ACTION_CONFIRM_MOBILE)
        val pi = android.app.PendingIntent.getService(
            this, 13, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, NotificationHelper.CHANNEL_EVENTS)
            .setSmallIcon(com.yuanbao.downloader.R.drawable.ic_stat_download)
            .setContentTitle("当前为移动网络")
            .setContentText("点击继续使用移动网络下载")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(1002, notification) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_CONFIRM_MOBILE -> engine.resumeAllForNetwork()
            ACTION_STOP -> {
                engine.pauseAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.yuanbao.downloader.action.PAUSE_ALL"
        const val ACTION_STOP = "com.yuanbao.downloader.action.STOP_SERVICE"
        const val ACTION_CONFIRM_MOBILE = "com.yuanbao.downloader.action.CONFIRM_MOBILE"
    }
}
