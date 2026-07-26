package com.melmeligy.mediadownloader.intercept

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a blob that the JavaScript bridge already resolved to a local cache file.
 *
 * Blob media never travels over HTTP a second time, so it bypasses the download engine
 * entirely: the cache file is written straight into the shared MediaStore collections and
 * recorded as a completed task so it shows up in Downloads and Library like any other item.
 */
@Singleton
class BlobImporter @Inject constructor(
    private val repository: DownloadRepository,
    private val mediaStoreWriter: MediaStoreWriter,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider
) {

    suspend fun import(payload: MediaStreamPayload): Long = withContext(dispatchers.io) {
        val path = payload.localFile
            ?: throw MediaException(AppError.GENERIC, "Blob has not been resolved yet")
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            throw MediaException(AppError.NO_MEDIA_FOUND, "Resolved blob file is missing")
        }

        val mime = payload.mimeType.orEmpty().lowercase()
        val type = when {
            mime.startsWith("audio") -> MediaType.AUDIO
            mime.startsWith("image") -> MediaType.IMAGE
            else -> MediaType.VIDEO
        }
        val container = file.extension.ifBlank {
            when (type) {
                MediaType.AUDIO -> "m4a"
                MediaType.IMAGE -> "jpg"
                MediaType.VIDEO -> "mp4"
            }
        }

        val title = FormatUtils.sanitizeFileName(payload.suggestedTitle, "blob_media")
        val bytes = file.length()

        val id = repository.enqueue(
            DownloadItem(
                title = title,
                sourceUrl = payload.pageUrl ?: payload.url,
                remoteUrl = payload.url,
                type = type,
                container = container,
                qualityLabel = "Blob media",
                status = DownloadStatus.RUNNING,
                totalBytes = bytes,
                downloadedBytes = bytes
            )
        )

        try {
            val settings = settingsRepository.current()
            val uri = mediaStoreWriter.publish(
                stagingFile = file,
                displayName = "$title.$container",
                type = type,
                container = container,
                subfolder = settings.saveSubfolder
            )
            repository.setCompleted(id, uri, bytes)
        } catch (e: Exception) {
            repository.setError(id, (e as? MediaException)?.error ?: AppError.GENERIC)
            throw e
        }

        id
    }
}
