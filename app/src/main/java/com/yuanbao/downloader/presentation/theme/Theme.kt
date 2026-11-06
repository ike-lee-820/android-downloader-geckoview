package com.yuanbao.downloader.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuanbao.downloader.data.settings.AppSettings

private val Blue = Color(0xFF1565C0)
private val Green = Color(0xFF2E7D32)
private val Purple = Color(0xFF6A1B9A)
private val Orange = Color(0xFFE65100)
private val Red = Color(0xFFC62828)

private fun seed(name: String): Color = when (name) {
    "green" -> Green
    "purple" -> Purple
    "orange" -> Orange
    "red" -> Red
    else -> Blue
}

private fun lightScheme(name: String) = lightColorScheme(
    primary = seed(name),
    secondary = seed(name).copy(alpha = 0.75f),
    tertiary = seed(name).copy(alpha = 0.5f)
)

private fun darkScheme(name: String) = darkColorScheme(
    primary = seed(name).copy(alpha = 0.85f),
    secondary = seed(name).copy(alpha = 0.7f),
    tertiary = seed(name).copy(alpha = 0.5f)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable
fun DownloaderTheme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val dark = when (settings.darkMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkScheme(settings.themeColor)
        else -> lightScheme(settings.themeColor)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
