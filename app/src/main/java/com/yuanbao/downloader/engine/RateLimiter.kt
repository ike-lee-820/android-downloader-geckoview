package com.yuanbao.downloader.engine

import kotlinx.coroutines.delay

/**
 * 令牌桶限速。全局桶与单任务桶串联调用，先取全局再取单任务，
 * 二者同时生效时实际速率取更严格的那个。
 */
class TokenBucket(private val bytesPerSecond: Long) {
    private val lock = Any()
    private var tokens: Double = bytesPerSecond.toDouble()
    private var lastNanos: Long = System.nanoTime()

    suspend fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0L || bytes <= 0) return
        while (true) {
            var waitMillis = 1L
            synchronized(lock) {
                val now = System.nanoTime()
                val elapsedSeconds = (now - lastNanos) / 1_000_000_000.0
                tokens = (tokens + elapsedSeconds * bytesPerSecond)
                    .coerceAtMost(bytesPerSecond.toDouble())
                lastNanos = now
                if (tokens >= bytes) {
                    tokens -= bytes
                    return
                }
                waitMillis = (((bytes - tokens) / bytesPerSecond) * 1000.0)
                    .toLong().coerceAtLeast(1L)
            }
            delay(waitMillis)
        }
    }
}
