package com.yuanbao.downloader.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.downloader.domain.ResourceType
import com.yuanbao.downloader.domain.SniffedResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SniffBottomSheet(
    resources: List<SniffedResource>,
    onDismiss: () -> Unit,
    onCreate: (SniffedResource) -> Unit,
    onClear: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已发现资源 ${resources.size} 项", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClear) { Text("清空") }
            }
            if (resources.isEmpty()) {
                Text(
                    "尚未发现媒体资源。打开含视频的页面后，嗅探结果会显示在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(resources, key = { it.url }) { res ->
                        SniffRow(res, onCreate)
                    }
                }
            }
        }
    }
}

@Composable
private fun SniffRow(res: SniffedResource, onCreate: (SniffedResource) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(res.type), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(" · ${res.method}", style = MaterialTheme.typography.bodySmall)
                res.qualityHint?.let {
                    Text(" · $it", style = MaterialTheme.typography.bodySmall)
                }
                if (res.hasRequestBody) {
                    Text(" · 含请求体", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(res.url, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val extra = when {
                    res.type == ResourceType.HLS && res.segmentCount > 0 ->
                        "聚合 TS 分片 ${res.segmentCount} 个"
                    res.type == ResourceType.TS -> "分片 ${res.segmentCount} 个"
                    else -> res.source
                }
                Text(extra, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { onCreate(res) }) { Text("下载") }
            }
        }
    }
}

private fun typeLabel(type: ResourceType): String = when (type) {
    ResourceType.HLS -> "HLS m3u8"
    ResourceType.TS -> "TS 分片"
    ResourceType.MPD -> "DASH MPD"
    ResourceType.M4S -> "fMP4 分片"
    ResourceType.VIDEO -> "视频"
    ResourceType.AUDIO -> "音频"
    ResourceType.FILE -> "文件"
    ResourceType.OTHER -> "资源"
}
