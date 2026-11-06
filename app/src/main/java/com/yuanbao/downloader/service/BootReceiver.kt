package com.yuanbao.downloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yuanbao.downloader.worker.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 开机后拉起前台服务并投递 WorkManager 兜底任务，恢复未完成任务队列。 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val service = Intent(context, DownloadForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service)
                } else {
                    context.startService(service)
                }
                scheduler.scheduleRestore()
                scheduler.schedulePeriodicWatchdog()
            } finally {
                pending.finish()
            }
        }
    }
}
