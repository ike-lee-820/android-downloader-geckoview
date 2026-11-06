package com.yuanbao.downloader.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yuanbao.downloader.R
import com.yuanbao.downloader.domain.TaskProgress
import com.yuanbao.downloader.domain.TaskStatus
import com.yuanbao.downloader.util.formatSpeed
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val FG_NOTIFICATION_ID = 1001
        const val CHANNEL_FOREGROUND = "download_foreground"
        const val CHANNEL_EVENTS = "download_events"
        private const val TASK_ID_BASE = 3000
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val foreground = NotificationChannel(
            CHANNEL_FOREGROUND,
            context.getString(R.string.notification_channel_foreground),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "下载进度与常驻服务通知"
            setSound(null, null)
        }
        val events = NotificationChannel(
            CHANNEL_EVENTS,
            context.getString(R.string.notification_channel_events),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "下载完成与失败提醒" }
        manager.createNotificationChannel(foreground)
        manager.createNotificationChannel(events)
    }

    private fun canNotify(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true

    private fun openAppPendingIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 10, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 常驻通知：setOngoing(true) + 仅首次提醒，保证前台服务保活且不被随手清除。 */
    fun foregroundNotification(active: Int, percent: Int, speed: String) =
        NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_downloading))
            .setContentText("进行中 $active 项 · $speed")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_stat_download,
                context.getString(R.string.notif_pause_all),
                servicePendingIntent(DownloadForegroundService.ACTION_PAUSE_ALL, 11)
            )
            .addAction(
                R.drawable.ic_stat_download,
                context.getString(R.string.notif_stop),
                servicePendingIntent(DownloadForegroundService.ACTION_STOP, 12)
            )
            .build()

    fun updateAggregate(tasks: List<TaskProgress>) {
        if (!canNotify()) return
        val active = tasks.filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.RETRYING ||
                it.status == TaskStatus.PENDING
        }
        val total = active.sumOf { it.totalBytes }
        val done = active.sumOf { it.downloadedBytes }
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        val speed = formatSpeed(active.sumOf { it.speedBytesPerSec })
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(FG_NOTIFICATION_ID, foregroundNotification(active.size, percent, speed))
        }
        active.forEach { notifyTaskProgress(it) }
    }

    private fun taskNotificationId(id: String): Int =
        TASK_ID_BASE + (id.hashCode() and 0x7fffffff) % 100000

    fun notifyTaskProgress(task: TaskProgress) {
        if (!canNotify()) return
        val pct = (task.percent * 100).toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(task.fileName)
            .setContentText("${formatSpeed(task.speedBytesPerSec)} · $pct%")
            .setProgress(100, pct, false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(taskNotificationId(task.id), notification) }
    }

    fun notifyCompleted(task: TaskProgress, outputUri: String?) {
        if (!canNotify()) return
        cancelTask(task.id)
        val builder = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_complete))
            .setContentText(task.fileName)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())

        if (!outputUri.isNullOrBlank()) {
            val uri = Uri.parse(outputUri)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, storageMime(task.fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newUri(context.contentResolver, "download", uri)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, taskNotificationId(task.id), view,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            builder.setContentText("${task.fileName} · ${context.getString(R.string.notif_open_file)}")
        }
        runCatching { NotificationManagerCompat.from(context).notify(taskNotificationId(task.id), builder.build()) }
    }

    fun notifyFailed(task: TaskProgress, reason: String?) {
        if (!canNotify()) return
        cancelTask(task.id)
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_failed))
            .setContentText(reason ?: task.error ?: task.fileName)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(taskNotificationId(task.id), notification) }
    }

    fun cancelTask(id: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(taskNotificationId(id)) }
    }

    private fun storageMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4s" -> "video/mp4"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
