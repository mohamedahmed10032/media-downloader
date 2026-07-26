package com.melmeligy.mediadownloader.domain.repository

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.Bookmark
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.model.ThemeMode
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import kotlinx.coroutines.flow.Flow

/** Resolves a raw link into downloadable streams via the extractor chain. */
interface MediaRepository {
    suspend fun resolve(url: String): ResolvedMedia
}

/** Persists and exposes download tasks; the single source of truth for the queue. */
interface DownloadRepository {
    fun observeAll(): Flow<List<DownloadItem>>
    fun observeByType(type: MediaType): Flow<List<DownloadItem>>
    fun observeActive(): Flow<List<DownloadItem>>
    fun observeCompletedByType(type: MediaType): Flow<List<DownloadItem>>

    suspend fun getById(id: Long): DownloadItem?
    suspend fun enqueue(item: DownloadItem): Long
    suspend fun update(item: DownloadItem)
    suspend fun updateStatus(id: Long, status: DownloadStatus)
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long, speed: Long, eta: Long)
    suspend fun setError(id: Long, error: AppError)
    suspend fun incrementRetry(id: Long)
    suspend fun setCompleted(id: Long, contentUri: String, total: Long)
    suspend fun delete(id: Long)
    suspend fun clearCompleted()
    suspend fun claimNextQueued(limit: Int): List<DownloadItem>
    suspend fun resetInterrupted()
}

/** Browser bookmarks. */
interface BookmarkRepository {
    fun observeAll(): Flow<List<Bookmark>>
    suspend fun add(title: String, url: String)
    suspend fun delete(id: Long)
    suspend fun exists(url: String): Boolean
}

/** User settings, backed by encrypted storage. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDefaultVideoQuality(quality: VideoQuality)
    suspend fun setDefaultAudioBitrate(bitrate: AudioBitrate)
    suspend fun setMaxConcurrent(count: Int)
    suspend fun setRetryCount(count: Int)
    suspend fun setSaveSubfolder(name: String)
}
