package com.yuanbao.downloader.presentation.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuanbao.downloader.util.AppIntents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(topBar = { TopAppBar(title = { Text("首次启动设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "为保证后台下载稳定，请完成以下授权。各项均可稍后在设置中重新打开。",
                style = MaterialTheme.typography.bodyMedium
            )
            OnboardingCard(
                "通知权限",
                "Android 13 及以上需要 POST_NOTIFICATIONS，用于常驻进度与完成提醒。",
                "去授权"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            OnboardingCard(
                "电池优化白名单",
                "加入白名单可避免系统在灭屏后冻结前台服务。",
                "去设置"
            ) { AppIntents.requestBatteryWhitelist(context) }
            OnboardingCard(
                "自启动权限",
                "国产 ROM 需单独允许开机自启动，才能恢复未完成任务队列。",
                "打开自启动管理"
            ) { AppIntents.openAutoStartSettings(context) }
            OnboardingCard(
                "设为默认下载器",
                "在系统默认应用设置中将本应用设为浏览器下载链接的处理程序。",
                "打开默认应用设置"
            ) { AppIntents.openDefaultAppsSettings(context) }

            FilledTonalButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("完成，进入应用") }
        }
    }
}

@Composable
private fun OnboardingCard(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}
