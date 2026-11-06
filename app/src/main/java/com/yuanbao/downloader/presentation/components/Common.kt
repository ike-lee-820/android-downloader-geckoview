package com.yuanbao.downloader.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionText != null && onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
fun LoadingState(text: String = "正在加载") {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.Info,
        title = "出错了",
        subtitle = message,
        actionText = "重试",
        onAction = onRetry
    )
}

@Composable
fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large
        ) { Column { content() } }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String = "",
    onChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text("$value$suffix", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = 0
        )
    }
}

@Composable
fun SettingDropdownRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            FilledTonalButton(onClick = { expanded = true }) { Text(label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingTextRow(
    title: String,
    value: String,
    hint: String = "",
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(title) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SettingActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedSettingRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                ) { Text(text) }
            }
        }
    }
}
