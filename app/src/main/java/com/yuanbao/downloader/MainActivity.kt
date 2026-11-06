package com.yuanbao.downloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.yuanbao.downloader.browser.GeckoSessionManager
import com.yuanbao.downloader.browser.TabSessionManager
import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.presentation.AppNavHost
import com.yuanbao.downloader.presentation.MainViewModel
import com.yuanbao.downloader.presentation.theme.DownloaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tabSessionManager: TabSessionManager
    @Inject lateinit var geckoSessionManager: GeckoSessionManager

    private val incomingIntent = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // GeckoRuntime 绑定到进程生命周期，这里只做预热，Activity 销毁时不 shutdown。
        geckoSessionManager.warmUp()
        incomingIntent.value = intent

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState(initial = AppSettings())

            LaunchedEffect(Unit) {
                incomingIntent.collect { intent -> intent?.let { viewModel.handleIntent(it) } }
            }

            DownloaderTheme(settings = settings) {
                AppNavHost(
                    tabSessionManager = tabSessionManager,
                    mainViewModel = viewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingIntent.value = intent
    }

    override fun onDestroy() {
        // 只关闭 session 与释放 View；GeckoRuntime 仍由进程级单例持有。
        tabSessionManager.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NEW_TASK_URL = "com.yuanbao.downloader.extra.NEW_TASK_URL"
    }
}

/** 透明 Activity：接收分享文本或外部 VIEW 链接，转发给主界面建立任务。 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.data?.toString()
            ?: intent.getStringExtra(MainActivity.EXTRA_NEW_TASK_URL)
        val url = raw?.let {
            Regex("(?:https?|ftp)://[^\\s<>\"']+").find(it)?.value ?: it.trim()
        }
        val forward = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NEW_TASK_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (url != null) data = Uri.parse(url)
        }
        startActivity(forward)
        finish()
    }
}
