package com.yuanbao.downloader.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    val type: String,
    val status: String,
    val priority: Int = 0,
    val threadCount: Int = 8,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val contentLength: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val treeUri: String? = null,
    val outputUri: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val maxRetry: Int = 3,
    val retryCount: Int = 0,
    val rateLimitKbps: Int = 0,
    val segmentSizeBytes: Long = 4L * 1024 * 1024,
    val hlsVariantUrl: String? = null
)

@Entity(tableName = "segments", primaryKeys = ["taskId", "segIndex"])
data class SegmentEntity(
    val taskId: String,
    @ColumnInfo(name = "seg_index") val segIndex: Int,
    val start: Long,
    /** end 为 -1 表示长度未知（HLS / DASH 分片），此时整片重新 GET。 */
    val end: Long,
    val downloaded: Long = 0L,
    val status: String = "PENDING",
    val url: String? = null,
    val keyUri: String? = null,
    val ivHex: String? = null,
    val initUrl: String? = null
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val folder: String = "默认"
)

@Entity(tableName = "history", indices = [Index("visitedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
    val incognito: Boolean = false
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun get(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'PENDING' ORDER BY priority DESC, createdAt ASC")
    suspend fun pending(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status IN (:statuses)")
    suspend fun byStatus(statuses: List<String>): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity)

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET downloadedBytes = :downloaded, status = :status, error = :error WHERE id = :id")
    suspend fun updateProgress(id: String, downloaded: Long, status: String, error: String?)

    @Query("UPDATE download_tasks SET totalBytes = :total, contentLength = :contentLength WHERE id = :id")
    suspend fun updateSize(id: String, total: Long, contentLength: Long)

    @Query("UPDATE download_tasks SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE download_tasks SET status = :status WHERE status IN (:from)")
    suspend fun bulkStatus(status: String, from: List<String>)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE taskId = :id ORDER BY seg_index ASC")
    fun observe(id: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE taskId = :id ORDER BY seg_index ASC")
    suspend fun list(id: String): List<SegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SegmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SegmentEntity)

    @Query("DELETE FROM segments WHERE taskId = :id")
    suspend fun clear(id: String)
}

@Dao
interface BrowserDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(item: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun removeBookmark(url: String)

    @Query("SELECT * FROM history WHERE incognito = 0 ORDER BY visitedAt DESC LIMIT 200")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun addHistory(item: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clearHistory()
}

@Database(
    entities = [DownloadTaskEntity::class, SegmentEntity::class, BookmarkEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun segmentDao(): SegmentDao
    abstract fun browserDao(): BrowserDao
}
