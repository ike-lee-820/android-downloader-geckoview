package com.yuanbao.downloader.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yuanbao.downloader.data.repo.DownloadRepository
import com.yuanbao.downloader.engine.DownloadEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 兜底调度：进程被杀或开机后由 WorkManager 拉起，重新投递失败任务并唤醒队列。 */
@HiltWorker
class DownloadWatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val engine: DownloadEngine,
    private val repo: DownloadRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val failed = repo.byStatus(listOf("FAILED"))
        failed.filter { it.retryCount < it.maxRetry }.forEach { engine.retry(it.id) }
        engine.pump()
        return Result.success()
    }
}

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun scheduleRestore() {
        val request = OneTimeWorkRequestBuilder<DownloadWatchdogWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("download_restore", ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodicWatchdog() {
        val request = PeriodicWorkRequestBuilder<DownloadWatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "download_watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
