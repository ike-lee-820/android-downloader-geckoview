package com.yuanbao.downloader

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yuanbao.downloader.browser.GeckoSessionManager
import com.yuanbao.downloader.service.NetworkCallbackManager
import com.yuanbao.downloader.worker.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DownloaderApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var networkCallbackManager: NetworkCallbackManager
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var geckoSessionManager: GeckoSessionManager

    // WorkManager 2.9 起 Configuration.Provider 使用属性形式，配合 Manifest 移除默认初始化器
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        networkCallbackManager.start()
        workScheduler.schedulePeriodicWatchdog()
        // 进程级预热：创建唯一 GeckoRuntime 并通过 ensureBuiltIn 安装内置嗅探扩展。
        geckoSessionManager.warmUp()
    }
}
