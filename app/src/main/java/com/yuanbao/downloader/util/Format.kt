package com.yuanbao.downloader.util

fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "${value} B" else String.format("%.1f %s", v, units[i])
}

fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> String.format("%d:%02d:%02d", h, m, s)
        m > 0 -> String.format("%d:%02d", m, s)
        else -> "${s} 秒"
    }
}
