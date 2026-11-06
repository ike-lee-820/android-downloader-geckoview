package com.yuanbao.downloader.data.repo

import com.yuanbao.downloader.data.db.BookmarkEntity
import com.yuanbao.downloader.data.db.BrowserDao
import com.yuanbao.downloader.data.db.DownloadDao
import com.yuanbao.downloader.data.db.DownloadTaskEntity
import com.yuanbao.downloader.data.db.HistoryEntity
import com.yuanbao.downloader.data.db.SegmentDao
import com.yuanbao.downloader.data.db.SegmentEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface DownloadRepository {
    fun observeTasks(): Flow<List<DownloadTaskEntity>>
    fun observeSegments(taskId: String): Flow<List<SegmentEntity>>

    suspend fun insertTask(task: DownloadTaskEntity)
    suspend fun getTask(id: String): DownloadTaskEntity?
    suspend fun updateTask(task: DownloadTaskEntity)
    suspend fun updateProgress(id: String, downloaded: Long, status: String, error: String?)
    suspend fun updateSize(id: String, total: Long, contentLength: Long)
    suspend fun setStatus(id: String, status: String)
    suspend fun pending(): List<DownloadTaskEntity>
    suspend fun byStatus(statuses: List<String>): List<DownloadTaskEntity>
    suspend fun bulkStatus(status: String, from: List<String>)
    suspend fun deleteTask(id: String)

    suspend fun upsertSegments(items: List<SegmentEntity>)
    suspend fun upsertSegment(item: SegmentEntity)
    suspend fun segments(taskId: String): List<SegmentEntity>
    suspend fun clearSegments(taskId: String)

    fun observeBookmarks(): Flow<List<BookmarkEntity>>
    suspend fun addBookmark(item: BookmarkEntity)
    suspend fun removeBookmark(url: String)
    fun observeHistory(): Flow<List<HistoryEntity>>
    suspend fun addHistory(item: HistoryEntity)
    suspend fun clearHistory()
}

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val downloadDao: DownloadDao,
    private val segmentDao: SegmentDao,
    private val browserDao: BrowserDao
) : DownloadRepository {

    override fun observeTasks(): Flow<List<DownloadTaskEntity>> = downloadDao.observeTasks()
    override fun observeSegments(taskId: String): Flow<List<SegmentEntity>> = segmentDao.observe(taskId)

    override suspend fun insertTask(task: DownloadTaskEntity) = downloadDao.insert(task)
    override suspend fun getTask(id: String): DownloadTaskEntity? = downloadDao.get(id)
    override suspend fun updateTask(task: DownloadTaskEntity) = downloadDao.update(task)
    override suspend fun updateProgress(id: String, downloaded: Long, status: String, error: String?) =
        downloadDao.updateProgress(id, downloaded, status, error)

    override suspend fun updateSize(id: String, total: Long, contentLength: Long) =
        downloadDao.updateSize(id, total, contentLength)

    override suspend fun setStatus(id: String, status: String) = downloadDao.setStatus(id, status)
    override suspend fun pending(): List<DownloadTaskEntity> = downloadDao.pending()
    override suspend fun byStatus(statuses: List<String>): List<DownloadTaskEntity> =
        downloadDao.byStatus(statuses)

    override suspend fun bulkStatus(status: String, from: List<String>) =
        downloadDao.bulkStatus(status, from)

    override suspend fun deleteTask(id: String) {
        downloadDao.delete(id)
        segmentDao.clear(id)
    }

    override suspend fun upsertSegments(items: List<SegmentEntity>) = segmentDao.upsertAll(items)
    override suspend fun upsertSegment(item: SegmentEntity) = segmentDao.upsert(item)
    override suspend fun segments(taskId: String): List<SegmentEntity> = segmentDao.list(taskId)
    override suspend fun clearSegments(taskId: String) = segmentDao.clear(taskId)

    override fun observeBookmarks(): Flow<List<BookmarkEntity>> = browserDao.observeBookmarks()
    override suspend fun addBookmark(item: BookmarkEntity) = browserDao.addBookmark(item)
    override suspend fun removeBookmark(url: String) = browserDao.removeBookmark(url)
    override fun observeHistory(): Flow<List<HistoryEntity>> = browserDao.observeHistory()
    override suspend fun addHistory(item: HistoryEntity) = browserDao.addHistory(item)
    override suspend fun clearHistory() = browserDao.clearHistory()
}
