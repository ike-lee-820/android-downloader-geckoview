package com.yuanbao.downloader.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yuanbao.downloader.domain.TaskProgress
import com.yuanbao.downloader.domain.TaskStatus
import com.yuanbao.downloader.presentation.DownloadViewModel
import com.yuanbao.downloader.presentation.MainViewModel
import com.yuanbao.downloader.presentation.components.EmptyState
import com.yuanbao.downloader.util.AppIntents
import com.yuanbao.downloader.util.formatBytes
import com.yuanbao.downloader.util.formatDuration
import com.yuanbao.downloader.util.formatSpeed
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    mainViewModel: MainViewModel,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val tasks by viewModel.tasks.collectAsState()
    val pendingUrl by mainViewModel.pendingUrl.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var inputUrl by remember { mutableStateOf("") }
    var threadDialogFor by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(pendingUrl) {
        if (pendingUrl != null) {
            inputUrl = pendingUrl.orEmpty()
            showAdd = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("下载任务") },
            actions = {
                IconButton(onClick = { viewModel.pauseAll() }) {
                    Icon(Icons.Outlined.Pause, contentDescription = "全部暂停")
                }
                IconButton(onClick = { inputUrl = ""; showAdd = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建任务")
                }
            }
        )

        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Download,
                title = "还没有下载任务",
                subtitle = "粘贴直链、从浏览器嗅探结果创建，或通过分享链接直接建任务",
                actionText = "新建下载",
                onAction = { inputUrl = ""; showAdd = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    DownloadItemCard(
                        task = task,
                        onPause = { viewModel.pause(task.id) },
                        onResume = { viewModel.resume(task.id) },
                        onCancel = { viewModel.cancel(task.id) },
                        onRetry = { viewModel.retry(task.id) },
                        onOpen = { task.outputUri?.let { AppIntents.openFile(context, it) } },
                        onThreadClick = { threadDialogFor = task.id }
                    )
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; mainViewModel.consumePendingUrl() },
            title = { Text("新建下载任务") },
            text = {
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    label = { Text("HTTP / HTTPS / m3u8 / MPD 链接") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (inputUrl.isNotBlank()) viewModel.createTask(inputUrl.trim())
                    showAdd = false
                    mainViewModel.consumePendingUrl()
                }) { Text("加入队列") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; mainViewModel.consumePendingUrl() }) {
                    Text("取消")
                }
            }
        )
    }

    val threadTaskId = threadDialogFor
    if (threadTaskId != null) {
        val task = tasks.firstOrNull { it.id == threadTaskId }
        if (task != null) {
            var value by remember(task.id) { mutableStateOf(task.threadCount.toFloat()) }
            AlertDialog(
                onDismissRequest = { threadDialogFor = null },
                title = { Text("调整并发线程数") },
                text = {
                    Column {
                        Text("${value.roundToInt()} 线程（修改后即时重启该任务，已下载分片保留）")
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            valueRange = 1f..256f,
                            steps = 0
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setThreadCount(task.id, value.roundToInt())
                        threadDialogFor = null
                    }) { Text("应用") }
                },
                dismissButton = {
                    TextButton(onClick = { threadDialogFor = null }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun DownloadItemCard(
    task: TaskProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onThreadClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    if (task.totalBytes > 0) {
                        CircularProgressIndicator(progress = { task.percent }, modifier = Modifier.size(44.dp))
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(44.dp))
                    }
                    Text("${(task.percent * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(task.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(statusText(task.status), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(task.type.name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            if (task.totalBytes > 0) {
                LinearProgressIndicator(progress = { task.percent }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall)
                Text(formatSpeed(task.speedBytesPerSec), style = MaterialTheme.typography.bodySmall)
                Text("剩余 ${formatDuration(task.etaSeconds)}", style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("分片 ${task.segmentDone}/${task.segmentTotal}", style = MaterialTheme.typography.bodySmall)
                Text("线程 ${task.threadCount} · 优先级 ${task.priority}",
                    style = MaterialTheme.typography.bodySmall)
            }

            task.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.status) {
                    TaskStatus.RUNNING, TaskStatus.PENDING, TaskStatus.RETRYING -> {
                        FilledTonalButton(onClick = onPause) {
                            Icon(Icons.Outlined.Pause, contentDescription = null); Text("暂停")
                        }
                    }
                    TaskStatus.PAUSED -> {
                        FilledTonalButton(onClick = onResume) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null); Text("继续")
                        }
                    }
                    TaskStatus.FAILED -> {
                        FilledTonalButton(onClick = onRetry) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null); Text("重试")
                        }
                    }
                    TaskStatus.COMPLETED -> {
                        FilledTonalButton(onClick = onOpen, enabled = task.outputUri != null) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null); Text("打开")
                        }
                    }
                    else -> Unit
                }
                FilledTonalButton(onClick = onThreadClick) { Text("线程") }
                if (task.status != TaskStatus.COMPLETED) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Delete, contentDescription = "取消任务")
                    }
                } else {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除记录")
                    }
                }
            }
        }
    }
}

private fun statusText(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "等待中"
    TaskStatus.RUNNING -> "下载中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.RETRYING -> "重试中"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELED -> "已取消"
}
