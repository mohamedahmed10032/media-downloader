package com.melmeligy.mediadownloader.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.JsonUtils
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Central coordinator for the download queue. It is the single entry point used by the UI
 * (enqueue / pause / resume / cancel / retry) and by [DownloadQueueWorker] (runQueue).
 *
 * Execution runs inside a WorkManager foreground worker; this class holds the queue-draining
 * loop with a configurable concurrency limit, per-download control signals, retry/backoff, and
 * MediaStore publishing. State lives in Room, so downloads survive process death and resume.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val segmentedDownloader: SegmentedDownloader,
    private val hlsDownloader: HlsSegmentDownloader,
    private val mediaStoreWriter: MediaStoreWriter,
    private val notifier: DownloadNotifier,
    private val dispatchers: DispatcherProvider
) {

    private val runningJobs = ConcurrentHashMap<Long, Job>()
    private val intents = ConcurrentHashMap<Long, ControlIntent>()

    // ---- Public API used by the UI ----

    suspend fun enqueue(resolved: ResolvedMedia, stream: MediaStream): Long {
        val item = DownloadItem(
            title = resolved.title,
            sourceUrl = resolved.sourceUrl,
            remoteUrl = stream.url,
            type = stream.type,
            container = if (stream.isHls) "ts" else stream.container,
            qualityLabel = stream.label,
            status = DownloadStatus.QUEUED,
            totalBytes = stream.sizeBytes ?: 0,
            thumbnailUrl = resolved.thumbnailUrl,
            headersJson = JsonUtils.mapToJson(stream.headers),
            isHls = stream.isHls
        )
        val id = repository.enqueue(item)
        schedule()
        return id
    }

    suspend fun pause(id: Long) {
        intents[id] = ControlIntent.PAUSE
        val job = runningJobs[id]
        if (job != null) {
            job.cancel()
        } else {
            repository.updateStatus(id, DownloadStatus.PAUSED)
            intents.remove(id)
        }
    }

    suspend fun resume(id: Long) {
        intents.remove(id)
        val item = repository.getById(id) ?: return
        repository.update(item.copy(status = DownloadStatus.QUEUED, errorType = null))
        schedule()
    }

    suspend fun retry(id: Long) {
        intents.remove(id)
        val item = repository.getById(id) ?: return
        repository.update(item.copy(status = DownloadStatus.QUEUED, errorType = null, retryCount = 0))
        schedule()
    }

    suspend fun cancel(id: Long) {
        intents[id] = ControlIntent.CANCEL
        val job = runningJobs[id]
        if (job != null) {
            job.cancel()
        } else {
            cleanupStaging(id)
            repository.delete(id)
            intents.remove(id)
        }
    }

    suspend fun clearCompleted() = repository.clearCompleted()

    suspend fun deleteById(id: Long) {
        cancel(id)
    }

    /** Enqueues the foreground worker (idempotent — keeps an already-running instance). */
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(Constants.DOWNLOAD_WORK_TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(Constants.DOWNLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    // ---- Queue execution (called by the worker) ----

    suspend fun runQueue(updateForeground: suspend (ForegroundInfo) -> Unit) = coroutineScope {
        repository.resetInterrupted()
        updateForeground(currentForegroundInfo())

        while (isActive) {
            val settings = settingsRepository.current()
            while (runningJobs.size < settings.maxConcurrentDownloads) {
                val next = repository.claimNextQueued(1).firstOrNull() ?: break
                val job = launch { runOne(next) }
                runningJobs[next.id] = job
                job.invokeOnCompletion { runningJobs.remove(next.id) }
            }
            if (runningJobs.isEmpty()) break
            updateForeground(currentForegroundInfo())
            delay(FOREGROUND_REFRESH_MS)
        }
    }

    private suspend fun runOne(item: DownloadItem) {
        val staging = stagingFile(item)
        try {
            val headers = JsonUtils.jsonToMap(item.headersJson)
            val onProgress: ProgressCallback = { p ->
                repository.updateProgress(item.id, p.downloaded, p.total, p.speed, p.eta)
            }

            if (item.isHls) {
                hlsDownloader.download(item.remoteUrl, headers, staging, onProgress)
            } else {
                segmentedDownloader.download(
                    url = item.remoteUrl,
                    headers = headers,
                    dest = staging,
                    knownTotal = item.totalBytes,
                    acceptsRanges = false,
                    segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                    onProgress = onProgress
                )
            }

            val bytes = staging.length()
            if (bytes <= 0L) throw MediaException(AppError.GENERIC, "Downloaded file is empty")

            val settings = settingsRepository.current()
            val displayName = fileName(item)
            val uri = mediaStoreWriter.publish(staging, displayName, item.type, item.container, settings.saveSubfolder)
            repository.setCompleted(item.id, uri, bytes)
            notifier.notifyComplete(item.id, item.title)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { handleCancellation(item, staging) }
            throw ce
        } catch (e: Exception) {
            withContext(NonCancellable) { handleFailure(item, e) }
        }
    }

    private suspend fun handleCancellation(item: DownloadItem, staging: File) {
        when (intents.remove(item.id)) {
            ControlIntent.CANCEL -> {
                runCatching { staging.delete() }
                runCatching { File(staging.parentFile, staging.name + ".idx").delete() }
                repository.delete(item.id)
            }
            ControlIntent.PAUSE -> repository.updateStatus(item.id, DownloadStatus.PAUSED)
            null -> repository.updateStatus(item.id, DownloadStatus.QUEUED) // system stop -> resume later
        }
    }

    private suspend fun handleFailure(item: DownloadItem, e: Exception) {
        val error = (e as? MediaException)?.error ?: AppError.GENERIC
        val settings = settingsRepository.current()
        val transient = error == AppError.NO_INTERNET || error == AppError.GENERIC
        if (transient && item.retryCount < settings.retryCount) {
            repository.incrementRetry(item.id)
            val backoffMs = min(30_000.0, 2.0.pow(item.retryCount) * 1000).toLong()
            delay(backoffMs)
            repository.updateStatus(item.id, DownloadStatus.QUEUED)
        } else {
            repository.setError(item.id, error)
            notifier.notifyFailed(item.id, item.title, friendly(error))
        }
    }

    // ---- Foreground notification content ----

    suspend fun currentForegroundInfo(): ForegroundInfo {
        val active = runCatching {
            repository.observeActive().first()
                .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
        }.getOrDefault(emptyList())

        val notification = buildForegroundNotification(active)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Constants.FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Constants.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(active: List<DownloadItem>): Notification {
        if (active.isEmpty()) {
            return notifier.buildForeground(
                title = context.getString(com.melmeligy.mediadownloader.R.string.notif_downloading),
                text = "…",
                percent = 0,
                indeterminate = true
            )
        }
        val totalBytes = active.sumOf { it.totalBytes.coerceAtLeast(0) }
        val doneBytes = active.sumOf { it.downloadedBytes.coerceAtLeast(0) }
        val speed = active.sumOf { it.speedBytesPerSec.coerceAtLeast(0) }
        val indeterminate = totalBytes <= 0
        val percent = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt() else 0
        val title = if (active.size == 1) active.first().title
        else context.getString(com.melmeligy.mediadownloader.R.string.notif_downloading) + " ${active.size} files"
        val text = buildString {
            if (!indeterminate) append("$percent% · ")
            append(FormatUtils.formatSpeed(speed))
        }
        return notifier.buildForeground(title, text, percent, indeterminate)
    }

    // ---- Helpers ----

    private fun stagingDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").apply { mkdirs() }

    private fun stagingFile(item: DownloadItem): File =
        File(stagingDir(), "dl_${item.id}.${item.container}.part")

    private fun cleanupStaging(id: Long) {
        val dir = stagingDir()
        dir.listFiles()?.filter { it.name.startsWith("dl_$id.") }?.forEach { runCatching { it.delete() } }
    }

    private fun fileName(item: DownloadItem): String {
        val base = FormatUtils.sanitizeFileName(item.title)
        return "$base.${item.container}"
    }

    private fun friendly(error: AppError): String = when (error) {
        AppError.NO_INTERNET -> "No internet connection"
        AppError.INVALID_LINK -> "Invalid link"
        AppError.NO_MEDIA_FOUND -> "No media found"
        AppError.UNSUPPORTED_SITE -> "Unsupported source"
        AppError.GEO_BLOCKED -> "Unavailable in your region"
        AppError.EXPIRED_LINK -> "Link expired"
        AppError.INSUFFICIENT_STORAGE -> "Not enough storage"
        AppError.GENERIC -> "Download failed"
    }

    private companion object {
        const val FOREGROUND_REFRESH_MS = 1000L
    }
}
