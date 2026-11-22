package com.melmeligy.mediadownloader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.melmeligy.mediadownloader.data.local.entity.DownloadEntity
import com.melmeligy.mediadownloader.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 6): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE type = :type ORDER BY createdAt DESC")
    fun observeByType(type: MediaType): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','RUNNING','FINALIZING','PAUSED','FAILED') ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE type = :type AND status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompletedByType(type: MediaType): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, now: Long)

    @Query("UPDATE downloads SET thumbnailUrl = :thumbnailUrl, updatedAt = :now WHERE id = :id")
    suspend fun updateThumbnail(id: Long, thumbnailUrl: String, now: Long)

    @Query("UPDATE downloads SET status = 'QUEUED', errorType = NULL, updatedAt = :now WHERE id = :id")
    suspend fun requeueForResume(id: Long, now: Long)

    @Query("UPDATE downloads SET status = 'QUEUED', errorType = NULL, retryCount = 0, updatedAt = :now WHERE id = :id")
    suspend fun requeueForRetry(id: Long, now: Long)

    @Query(
        "UPDATE downloads SET downloadedBytes = :downloaded, " +
            "totalBytes = CASE WHEN :total > 0 THEN MAX(:total, :downloaded) " +
            "ELSE CASE WHEN totalBytes > 0 THEN MAX(totalBytes, :downloaded) ELSE 0 END END, " +
            "speedBytesPerSec = :speed, etaSeconds = :eta, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long, speed: Long, eta: Long, now: Long)

    @Query("UPDATE downloads SET status = 'FAILED', errorType = :error, speedBytesPerSec = 0, etaSeconds = 0, updatedAt = :now WHERE id = :id")
    suspend fun setError(id: Long, error: String, now: Long)

    @Query("UPDATE downloads SET retryCount = retryCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementRetry(id: Long, now: Long)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', contentUri = :uri, totalBytes = :total, " +
            "downloadedBytes = :total, speedBytesPerSec = 0, etaSeconds = 0, errorType = NULL, updatedAt = :now WHERE id = :id"
    )
    suspend fun setCompleted(id: Long, uri: String, total: Long, now: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun nextQueued(limit: Int): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('RUNNING','FINALIZING')")
    suspend fun runningCount(): Int

    // Recover from process death: any download left RUNNING or FINALIZING is returned to the queue.
    @Query("UPDATE downloads SET status = 'QUEUED' WHERE status IN ('RUNNING','FINALIZING')")
    suspend fun resetRunningToQueued()
}
