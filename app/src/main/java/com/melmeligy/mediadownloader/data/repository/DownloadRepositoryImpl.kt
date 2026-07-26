package com.melmeligy.mediadownloader.data.repository

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.data.local.dao.DownloadDao
import com.melmeligy.mediadownloader.data.local.entity.toDomain
import com.melmeligy.mediadownloader.data.local.entity.toEntity
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val dao: DownloadDao,
    private val dispatchers: DispatcherProvider
) : DownloadRepository {

    private fun now() = System.currentTimeMillis()

    override fun observeAll(): Flow<List<DownloadItem>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByType(type: MediaType): Flow<List<DownloadItem>> =
        dao.observeByType(type).map { list -> list.map { it.toDomain() } }

    override fun observeActive(): Flow<List<DownloadItem>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observeCompletedByType(type: MediaType): Flow<List<DownloadItem>> =
        dao.observeCompletedByType(type).map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: Long): DownloadItem? =
        withContext(dispatchers.io) { dao.getById(id)?.toDomain() }

    override suspend fun enqueue(item: DownloadItem): Long = withContext(dispatchers.io) {
        dao.insert(item.copy(createdAt = now(), updatedAt = now()).toEntity())
    }

    override suspend fun update(item: DownloadItem) = withContext(dispatchers.io) {
        dao.update(item.copy(updatedAt = now()).toEntity())
    }

    override suspend fun updateStatus(id: Long, status: DownloadStatus) = withContext(dispatchers.io) {
        dao.updateStatus(id, status.name, now())
    }

    override suspend fun updateProgress(id: Long, downloaded: Long, total: Long, speed: Long, eta: Long) =
        withContext(dispatchers.io) {
            dao.updateProgress(id, downloaded, total, speed, eta, now())
        }

    override suspend fun setError(id: Long, error: AppError) = withContext(dispatchers.io) {
        dao.setError(id, error.name, now())
    }

    override suspend fun incrementRetry(id: Long) = withContext(dispatchers.io) {
        dao.incrementRetry(id, now())
    }

    override suspend fun setCompleted(id: Long, contentUri: String, total: Long) =
        withContext(dispatchers.io) {
            dao.setCompleted(id, contentUri, total, now())
        }

    override suspend fun delete(id: Long) = withContext(dispatchers.io) { dao.delete(id) }

    override suspend fun clearCompleted() = withContext(dispatchers.io) { dao.clearCompleted() }

    override suspend fun claimNextQueued(limit: Int): List<DownloadItem> = withContext(dispatchers.io) {
        if (limit <= 0) return@withContext emptyList()
        val next = dao.nextQueued(limit)
        next.forEach { dao.updateStatus(it.id, DownloadStatus.RUNNING.name, now()) }
        next.map { it.toDomain().copy(status = DownloadStatus.RUNNING) }
    }

    override suspend fun resetInterrupted() = withContext(dispatchers.io) {
        dao.resetRunningToQueued()
    }
}
