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
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Central coordinator for the download queue. It is the single entry point used by the UI
 * (enqueue / pause / resume / cancel / retry) and by [DownloadQueueWorker] (runQueue).
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val segmentedDownloader: SegmentedDownloader,
    private val hlsDownloader: HlsSegmentDownloader,
    private val videoAudioMuxer: VideoAudioMuxer,
    private val mediaStoreWriter: MediaStoreWriter,
    private val notifier: DownloadNotifier,
    private val dispatchers: DispatcherProvider
) {

    private val runningJobs = ConcurrentHashMap<Long, Job>()
    private val retryingJobs = ConcurrentHashMap<Long, Job>()
    private val intents = ConcurrentHashMap<Long, ControlIntent>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)

    // ---- Public API used by the UI ----

    suspend fun enqueue(resolved: ResolvedMedia, stream: MediaStream): Long {
        val finalContainer = when {
            stream.type == MediaType.AUDIO -> stream.container
            stream.isHls -> "mp4"
            else -> stream.container
        }
        val headers = LinkedHashMap<String, String>(stream.headers).apply {
            if (stream.type == MediaType.AUDIO) {
                val effectiveBitrate = stream.bitrateKbps?.takeIf { it > 0 }
                    ?: settingsRepository.current().defaultAudioBitrate.kbps
                put("X-Internal-Target-Bitrate-Kbps", effectiveBitrate.toString())
            }
        }
        val item = DownloadItem(
            title = resolved.title,
            sourceUrl = resolved.sourceUrl,
            remoteUrl = stream.url,
            type = stream.type,
            container = finalContainer,
            qualityLabel = stream.label,
            status = DownloadStatus.QUEUED,
            totalBytes = stream.sizeBytes ?: 0,
            thumbnailUrl = resolved.thumbnailUrl,
            headersJson = JsonUtils.mapToJson(headers),
            isHls = stream.isHls
        )
        val id = repository.enqueue(item)
        queueSignal.trySend(Unit)
        schedule()
        return id
    }

    /**
     * Enqueues a batch of selected [GallerySlide] items in exact reading order, naming multi-part
     * posts with zero-padded indexed filenames (e.g., `instagram_<postId>_01.jpg`, `02.jpg`, ...)
     * so they appear in sequence inside the user's Gallery.
     */
    suspend fun enqueueBatch(resolved: ResolvedMedia, slides: List<GallerySlide>): List<Long> {
        if (slides.isEmpty()) return emptyList()
        val ordered = slides.sortedBy { it.index }
        val totalCount = maxOf(resolved.gallerySlides.size, ordered.size)
        val now = System.currentTimeMillis()
        val ids = ArrayList<Long>(ordered.size)

        ordered.forEachIndexed { pos, slide ->
            val slideTitle = buildBatchSlideTitle(resolved, slide, totalCount)
            val qualityLabel = if (slide.type == MediaType.VIDEO) {
                "Video (${slide.container.uppercase()})"
            } else {
                slide.container.uppercase()
            }
            val item = DownloadItem(
                title = slideTitle,
                sourceUrl = resolved.sourceUrl,
                remoteUrl = slide.mediaUrl,
                type = slide.type,
                container = slide.container.lowercase().ifBlank { if (slide.type == MediaType.VIDEO) "mp4" else "jpg" },
                qualityLabel = qualityLabel,
                status = DownloadStatus.QUEUED,
                totalBytes = slide.sizeBytes ?: 0L,
                thumbnailUrl = slide.thumbnailUrl,
                headersJson = JsonUtils.mapToJson(slide.headers),
                isHls = false,
                createdAt = now + pos,
                updatedAt = now + pos
            )
            ids += repository.enqueue(item)
        }

        if (ids.isNotEmpty()) {
            queueSignal.trySend(Unit)
            schedule()
        }
        return ids
    }

    suspend fun pause(id: Long) {
        intents[id] = ControlIntent.PAUSE
        val activeJob = runningJobs[id] ?: retryingJobs[id]
        // Update status immediately so Home/Downloads UI reflects PAUSED without waiting for socket/muxer teardown.
        repository.updateStatus(id, DownloadStatus.PAUSED)
        if (activeJob != null) {
            activeJob.cancel()
        } else {
            intents.remove(id)
        }
        queueSignal.trySend(Unit)
    }

    suspend fun resume(id: Long) {
        intents.remove(id)
        (runningJobs[id] ?: retryingJobs[id])?.cancelAndJoin()
        val item = repository.getById(id) ?: return
        repository.update(item.copy(status = DownloadStatus.QUEUED, errorType = null))
        queueSignal.trySend(Unit)
        schedule()
    }

    suspend fun retry(id: Long) {
        intents.remove(id)
        (runningJobs[id] ?: retryingJobs[id])?.cancelAndJoin()
        val item = repository.getById(id) ?: return
        repository.update(item.copy(status = DownloadStatus.QUEUED, errorType = null, retryCount = 0))
        queueSignal.trySend(Unit)
        schedule()
    }

    suspend fun cancel(id: Long) {
        intents[id] = ControlIntent.CANCEL
        val activeJob = runningJobs[id] ?: retryingJobs[id]
        // Remove the item immediately so Home/Downloads cards disappear without blocking on I/O cleanup.
        repository.delete(id)
        if (activeJob != null) {
            activeJob.cancel()
        } else {
            withContext(dispatchers.io) { cleanupStaging(id) }
            intents.remove(id)
        }
        queueSignal.trySend(Unit)
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
        runCatching { updateForeground(currentForegroundInfo()) }

        // Wake the scheduler immediately whenever the user changes Max concurrent downloads in Settings.
        val settingsObserver = launch {
            runCatching {
                settingsRepository.settings
                    .map { it.maxConcurrentDownloads }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { queueSignal.trySend(Unit) }
            }
        }

        fun launchQueuedItem(item: DownloadItem) {
            val job = launch(start = CoroutineStart.LAZY) {
                runOne(item, this@coroutineScope)
            }
            runningJobs[item.id] = job
            job.invokeOnCompletion {
                runningJobs.remove(item.id, job)
                queueSignal.trySend(Unit)
            }
            job.start()
        }

        try {
            while (isActive) {
                val maxConcurrent = settingsRepository.current()
                    .maxConcurrentDownloads
                    .coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT)

                while (runningJobs.size < maxConcurrent) {
                    val next = repository.claimNextQueued(1).firstOrNull() ?: break
                    launchQueuedItem(next)
                }

                if (runningJobs.isEmpty() && retryingJobs.isEmpty()) {
                    // Drain window check: re-check queue once right before exiting so newly enqueued items aren't missed
                    val trailing = repository.claimNextQueued(1).firstOrNull()
                    if (trailing != null) {
                        launchQueuedItem(trailing)
                    } else {
                        break
                    }
                }
                runCatching { updateForeground(currentForegroundInfo()) }
                withTimeoutOrNull(FOREGROUND_REFRESH_MS) {
                    queueSignal.receive()
                }
            }
        } finally {
            settingsObserver.cancel()
        }
    }

    private suspend fun runOne(item: DownloadItem, queueScope: CoroutineScope) {
        val staging = stagingFile(item)
        var lastProgressWriteMs = 0L
        var highWaterDownloaded = 0L
        var highWaterTotal = item.totalBytes.coerceAtLeast(0L)
        var isFinalizing = false

        suspend fun reportProgress(
            downloaded: Long,
            total: Long,
            speed: Long,
            eta: Long,
            capBeforeFinalizing: Boolean = true,
            force: Boolean = false
        ) {
            if (intents[item.id] != null || !currentCoroutineContext().isActive) return
            val safeDownloaded = maxOf(highWaterDownloaded, downloaded.coerceAtLeast(0L))
            highWaterDownloaded = safeDownloaded
            val safeTotal = if (item.isHls) {
                if (total > 0L) maxOf(total, safeDownloaded) else total
            } else if (total > 0L) {
                maxOf(highWaterTotal, total, safeDownloaded)
            } else {
                highWaterTotal.takeIf { it > 0L }?.let { maxOf(it, safeDownloaded) } ?: total
            }
            if (!item.isHls && safeTotal > 0L) highWaterTotal = safeTotal

            val isComplete = safeTotal > 0L && safeDownloaded >= safeTotal
            // Avoid flashing a transient RUNNING 100% + speed=0 row right before enterFinalizing() runs
            if (isComplete && capBeforeFinalizing && speed <= 0L) {
                return
            }

            val displayedDownloaded = if (capBeforeFinalizing && safeTotal > 0L && safeDownloaded >= safeTotal) {
                ((safeTotal * 99L) / 100L).coerceAtMost(safeTotal - 1L).coerceAtLeast(0L)
            } else {
                safeDownloaded
            }

            val now = System.currentTimeMillis()
            if (force || isComplete || now - lastProgressWriteMs >= DB_PROGRESS_MIN_INTERVAL_MS) {
                lastProgressWriteMs = now
                repository.updateProgress(item.id, displayedDownloaded, safeTotal, speed.coerceAtLeast(0L), eta)
            }
        }

        suspend fun enterFinalizing(currentBytes: Long) {
            if (!isFinalizing) {
                isFinalizing = true
                // Switch status BEFORE clearing speed/eta so UI never flashes RUNNING + "--"
                repository.updateStatus(item.id, DownloadStatus.FINALIZING)
            }
            val safeBytes = if (currentBytes > 0L) currentBytes else highWaterDownloaded
            if (safeBytes > 0L) {
                repository.updateProgress(item.id, safeBytes, safeBytes, 0L, 0L)
            }
            queueSignal.trySend(Unit)
        }

        try {
            val headers = JsonUtils.jsonToMap(item.headersJson)
            val companionAudioUrl = headers["X-Internal-Companion-Audio"]?.takeIf { it.startsWith("http") }
            val isAudioExtraction = headers["X-Internal-Extract-Audio"] == "true" ||
                (item.type == MediaType.AUDIO && (item.container.equals("mp3", true) || item.container.equals("m4a", true)))
            val targetBitrateKbps = headers["X-Internal-Target-Bitrate-Kbps"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: Regex("""\((\d{2,3})K\)""", RegexOption.IGNORE_CASE)
                    .find(item.qualityLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: settingsRepository.current().defaultAudioBitrate.kbps

            if (item.isHls) {
                val stagingTs = File(stagingDir(), "dl_${item.id}_raw.ts.part")
                val videoKnownBytes = headers["X-Internal-Video-Bytes"]?.toLongOrNull()
                    ?: headers["X-Internal-Expected-Bytes"]?.toLongOrNull()
                    ?: item.totalBytes
                val audioKnownBytes = headers["X-Internal-Audio-Bytes"]?.toLongOrNull() ?: 0L
                val hasSplitHlsAudio = companionAudioUrl != null &&
                    item.type == MediaType.VIDEO &&
                    headers["X-Internal-Extract-Audio"] != "true"

                val stagingAudioTs = File(stagingDir(), "dl_${item.id}_hls_audio.ts.part")
                val stagingVideoMp4 = File(stagingDir(), "dl_${item.id}_hls_video.mp4.part")
                val stagingAudioM4a = File(stagingDir(), "dl_${item.id}_hls_audio.m4a.part")
                val targetAudioTotal = if (item.type == MediaType.AUDIO || headers["X-Internal-Extract-Audio"] == "true") {
                    item.totalBytes.takeIf { it > 0L } ?: 0L
                } else 0L

                val configuredRetries = settingsRepository.current()
                    .retryCount
                    .coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)

                try {
                    hlsDownloader.download(
                        playlistUrl = item.remoteUrl,
                        headers = headers,
                        dest = stagingTs,
                        expectedTotalBytes = videoKnownBytes,
                        maxRetries = configuredRetries
                    ) { p ->
                        if (targetAudioTotal > 0L) {
                            // Keep progress aligned with target audio bytes without rolling back or spiking to video size
                            val ratio = if (p.total > 0L) {
                                (p.downloaded.toDouble() / p.total.toDouble()).coerceIn(0.0, 1.0)
                            } else 0.0
                            val scaledDone = (targetAudioTotal * 0.90 * ratio).toLong()
                            reportProgress(scaledDone, targetAudioTotal, p.speed, p.eta)
                        } else {
                            val combinedEst = if (hasSplitHlsAudio && p.total > 0L) {
                                p.total + audioKnownBytes.coerceAtLeast(0L)
                            } else {
                                p.total
                            }
                            reportProgress(p.downloaded, combinedEst, p.speed, p.eta)
                        }
                    }

                    if (hasSplitHlsAudio && companionAudioUrl != null) {
                        val videoActual = stagingTs.length()
                        val audioIsHls = headers["X-Internal-Companion-Audio-Hls"] == "true" ||
                            companionAudioUrl.contains(".m3u8", ignoreCase = true)
                        if (audioIsHls) {
                            hlsDownloader.download(
                                playlistUrl = companionAudioUrl,
                                headers = headers,
                                dest = stagingAudioTs,
                                expectedTotalBytes = audioKnownBytes,
                                maxRetries = configuredRetries
                            ) { p ->
                                val total = if (p.total > 0L) videoActual + p.total else -1L
                                reportProgress(videoActual + p.downloaded, total, p.speed, p.eta)
                            }
                        } else {
                            segmentedDownloader.download(
                                url = companionAudioUrl,
                                headers = headers,
                                dest = stagingAudioTs,
                                knownTotal = audioKnownBytes,
                                acceptsRanges = false,
                                segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                                onProgress = { p ->
                                    val total = if (p.total > 0L) videoActual + p.total else -1L
                                    reportProgress(videoActual + p.downloaded, total, p.speed, p.eta)
                                }
                            )
                        }
                        enterFinalizing(videoActual + stagingAudioTs.length())
                        val ok = withContext(dispatchers.io) {
                            val vOk = videoAudioMuxer.remuxTsToMp4(stagingTs, stagingVideoMp4)
                            val aOk = videoAudioMuxer.extractM4a(stagingAudioTs, stagingAudioM4a)
                            vOk && aOk && videoAudioMuxer.mux(stagingVideoMp4, stagingAudioM4a, staging)
                        }
                        if (!ok) throw MediaException(AppError.GENERIC, "Failed to mux split HLS streams")
                    } else {
                        val finalizingBytes = if (targetAudioTotal > 0L) targetAudioTotal else stagingTs.length()
                        enterFinalizing(finalizingBytes)
                        val ok = withContext(dispatchers.io) {
                            if (item.type == MediaType.AUDIO || headers["X-Internal-Extract-Audio"] == "true") {
                                videoAudioMuxer.extractAudioOnly(stagingTs, staging, item.container, targetBitrateKbps)
                            } else {
                                videoAudioMuxer.remuxTsToMp4(stagingTs, staging)
                            }
                        }
                        if (!ok) throw MediaException(AppError.GENERIC, "Failed to convert HLS container")
                    }
                } finally {
                    runCatching { stagingTs.delete() }
                    runCatching { stagingAudioTs.delete() }
                    runCatching { stagingVideoMp4.delete() }
                    runCatching { stagingAudioM4a.delete() }
                }
            } else if (companionAudioUrl != null && item.type == MediaType.VIDEO) {
                var videoKnownBytes = headers["X-Internal-Video-Bytes"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
                var audioKnownBytes = headers["X-Internal-Audio-Bytes"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
                var videoAcceptsRanges = false
                var audioAcceptsRanges = false

                // Concurrent pre-probe when either track lacks upfront byte size so Phase 1 knows combined total
                if (videoKnownBytes <= 0L || audioKnownBytes <= 0L) {
                    coroutineScope {
                        val vProbe = if (videoKnownBytes <= 0L) {
                            async(dispatchers.io) { segmentedDownloader.probe(item.remoteUrl, headers) }
                        } else null
                        val aProbe = if (audioKnownBytes <= 0L) {
                            async(dispatchers.io) { segmentedDownloader.probe(companionAudioUrl, headers) }
                        } else null

                        vProbe?.await()?.let { info ->
                            if (info.contentLength > 0L) videoKnownBytes = info.contentLength
                            videoAcceptsRanges = info.acceptsRanges
                        }
                        aProbe?.await()?.let { info ->
                            if (info.contentLength > 0L) audioKnownBytes = info.contentLength
                            audioAcceptsRanges = info.acceptsRanges
                        }
                    }
                }

                val combinedTotal = if (videoKnownBytes > 0L && audioKnownBytes > 0L) {
                    videoKnownBytes + audioKnownBytes
                } else {
                    item.totalBytes
                }

                val stagingVideo = File(stagingDir(), "dl_${item.id}_video.${item.container}.part")
                val stagingAudio = File(stagingDir(), "dl_${item.id}_audio.part")

                var adaptiveMuxComplete = false
                try {
                    // 1. Download adaptive video track
                    segmentedDownloader.download(
                        url = item.remoteUrl,
                        headers = headers,
                        dest = stagingVideo,
                        knownTotal = videoKnownBytes,
                        acceptsRanges = videoAcceptsRanges,
                        segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                        onProgress = { p ->
                            val total = if (combinedTotal > 0L) {
                                maxOf(combinedTotal, p.total + audioKnownBytes)
                            } else {
                                p.total
                            }
                            reportProgress(p.downloaded, total, p.speed, p.eta)
                        }
                    )
                    val videoActualBytes = stagingVideo.length()

                    // 2. Download companion audio track
                    segmentedDownloader.download(
                        url = companionAudioUrl,
                        headers = headers,
                        dest = stagingAudio,
                        knownTotal = audioKnownBytes,
                        acceptsRanges = audioAcceptsRanges,
                        segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                        onProgress = { p ->
                            val done = videoActualBytes + p.downloaded
                            val total = if (p.total > 0L) videoActualBytes + p.total else combinedTotal
                            reportProgress(done, total, p.speed, p.eta)
                        }
                    )

                    // 3. Mux video + audio into final container (MP4 or WebM)
                    enterFinalizing(videoActualBytes + stagingAudio.length())
                    val ok = withContext(dispatchers.io) {
                        videoAudioMuxer.mux(stagingVideo, stagingAudio, staging)
                    }
                    if (!ok) throw MediaException(AppError.GENERIC, "Failed to mux video and audio tracks")
                    adaptiveMuxComplete = true
                } finally {
                    if (adaptiveMuxComplete) {
                        runCatching { stagingVideo.delete() }
                        runCatching { stagingAudio.delete() }
                        runCatching { File(stagingVideo.parentFile, "${stagingVideo.name}.idx").delete() }
                        runCatching { File(stagingAudio.parentFile, "${stagingAudio.name}.idx").delete() }
                    }
                }
            } else if (isAudioExtraction) {
                val isVideoSourceExtract = headers["X-Internal-Extract-Audio"] == "true"
                val realSourceBytes = headers["X-Internal-Video-Bytes"]?.toLongOrNull()?.takeIf { it > 0L }
                    ?: if (!isVideoSourceExtract) item.totalBytes else 0L
                val targetAudioTotal = item.totalBytes.takeIf { it > 0L } ?: 0L
                val stagingSource = File(stagingDir(), "dl_${item.id}_src.part")

                var extractComplete = false
                try {
                    segmentedDownloader.download(
                        url = item.remoteUrl,
                        headers = headers,
                        dest = stagingSource,
                        knownTotal = realSourceBytes,
                        acceptsRanges = false,
                        segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                        onProgress = { p ->
                            if (isVideoSourceExtract && targetAudioTotal > 0L) {
                                // Never spike totalBytes to the full video source byte size during audio extraction
                                val srcTotal = if (p.total > 0L) p.total else realSourceBytes
                                val ratio = if (srcTotal > 0L) {
                                    (p.downloaded.toDouble() / srcTotal.toDouble()).coerceIn(0.0, 1.0)
                                } else 0.0
                                val scaledDone = (targetAudioTotal * 0.90 * ratio).toLong()
                                reportProgress(scaledDone, targetAudioTotal, p.speed, p.eta)
                            } else {
                                val effectiveTotal = if (p.total > 0L) p.total else realSourceBytes
                                val scaledDone = if (effectiveTotal > 0L) {
                                    minOf(p.downloaded, (effectiveTotal * 95L) / 100L)
                                } else p.downloaded
                                reportProgress(scaledDone, effectiveTotal, p.speed, p.eta)
                            }
                        }
                    )
                    enterFinalizing(targetAudioTotal.takeIf { it > 0L } ?: stagingSource.length())
                    val ok = withContext(dispatchers.io) {
                        videoAudioMuxer.extractAudioOnly(stagingSource, staging, item.container, targetBitrateKbps)
                    }
                    if (!ok) throw MediaException(AppError.GENERIC, "Audio extraction failed")
                    extractComplete = true
                } finally {
                    if (extractComplete) {
                        runCatching { stagingSource.delete() }
                        runCatching { File(stagingSource.parentFile, "${stagingSource.name}.idx").delete() }
                    }
                }
            } else {
                val realKnownBytes = headers["X-Internal-Video-Bytes"]?.toLongOrNull()?.takeIf { it > 0L }
                    ?: item.totalBytes
                segmentedDownloader.download(
                    url = item.remoteUrl,
                    headers = headers,
                    dest = staging,
                    knownTotal = realKnownBytes,
                    acceptsRanges = false,
                    segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                    onProgress = { p ->
                        reportProgress(p.downloaded, p.total, p.speed, p.eta)
                    }
                )
            }

            val bytes = staging.length()
            if (bytes <= 0L) throw MediaException(AppError.GENERIC, "Downloaded file is empty")

            // Persist exact output size under FINALIZING state while publishing to MediaStore
            enterFinalizing(bytes)

            val settings = settingsRepository.current()
            val displayName = fileName(item)
            val uri = if (settings.isCustomSaveLocation) {
                mediaStoreWriter.publish(
                    stagingFile = staging,
                    displayName = displayName,
                    type = item.type,
                    container = item.container,
                    subfolder = settings.saveSubfolder,
                    customTreeUri = settings.customSaveTreeUri,
                    organizeByType = settings.customSaveOrganizeByType
                )
            } else {
                mediaStoreWriter.publish(staging, displayName, item.type, item.container, settings.saveSubfolder)
            }
            repository.setCompleted(item.id, uri, bytes)
            notifier.notifyComplete(item.id, item.title)
        } catch (ce: CancellationException) {
            withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging) }
            throw ce
        } catch (e: Exception) {
            // If the user cancelled or paused (which can surface as IOException on closed sockets),
            // never treat it as a failure or auto-retry.
            if (intents[item.id] == ControlIntent.CANCEL || intents[item.id] == ControlIntent.PAUSE) {
                withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging) }
                return
            }
            handleFailure(item, staging, e, queueScope)
        }
    }

    private suspend fun handleCancellation(item: DownloadItem, staging: File) {
        when (intents.remove(item.id)) {
            ControlIntent.CANCEL -> {
                cleanupStaging(item.id)
                runCatching { staging.delete() }
                repository.delete(item.id)
            }
            ControlIntent.PAUSE -> repository.updateStatus(item.id, DownloadStatus.PAUSED)
            null -> {
                if (repository.getById(item.id) != null) {
                    repository.updateStatus(item.id, DownloadStatus.QUEUED) // system stop -> resume later
                }
            }
        }
    }

    private suspend fun handleFailure(
        item: DownloadItem,
        staging: File,
        e: Exception,
        queueScope: CoroutineScope
    ) {
        val error = (e as? MediaException)?.error ?: AppError.GENERIC
        val settings = settingsRepository.current()
        val maxRetries = settings.retryCount.coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)
        val transient = error == AppError.NO_INTERNET || error == AppError.GENERIC
        if (transient && maxRetries > 0 && item.retryCount < maxRetries && intents[item.id] == null) {
            val backoffMs = computeRetryBackoffMs(item.retryCount)
            withContext(NonCancellable) {
                repository.incrementRetry(item.id)
            }
            // Schedule retry backoff in retryingJobs so this item releases its concurrency slot
            // immediately while staying cleanly cancellable if the user cancels/pauses during backoff.
            val retryJob = queueScope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(backoffMs)
                    if (intents[item.id] == ControlIntent.CANCEL || intents[item.id] == ControlIntent.PAUSE) {
                        withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging) }
                        return@launch
                    }
                    if (repository.getById(item.id) == null) return@launch
                    repository.updateStatus(item.id, DownloadStatus.QUEUED)
                    queueSignal.trySend(Unit)
                } catch (ce: CancellationException) {
                    withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging) }
                    throw ce
                }
            }
            retryingJobs[item.id] = retryJob
            retryJob.invokeOnCompletion {
                retryingJobs.remove(item.id, retryJob)
                queueSignal.trySend(Unit)
            }
            retryJob.start()
        } else {
            withContext(NonCancellable) {
                if (intents[item.id] == ControlIntent.CANCEL || intents[item.id] == ControlIntent.PAUSE) {
                    handleCancellation(item, staging)
                } else {
                    cleanupStaging(item.id)
                    repository.setError(item.id, error)
                    notifier.notifyFailed(item.id, item.title, friendly(error))
                }
            }
        }
    }

    // ---- Foreground notification content ----

    suspend fun currentForegroundInfo(): ForegroundInfo {
        val active = runCatching {
            repository.observeActive().first()
                .filter {
                    it.status == DownloadStatus.RUNNING ||
                        it.status == DownloadStatus.FINALIZING ||
                        it.status == DownloadStatus.QUEUED
                }
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
        val title = if (active.size == 1) active.first().title
        else context.getString(com.melmeligy.mediadownloader.R.string.notif_downloading) + " ${active.size} files"
        val (text, percent, indeterminate) = formatForegroundNotificationStatus(active)
        return notifier.buildForeground(title, text, percent, indeterminate)
    }

    // ---- Helpers ----

    private fun stagingDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").apply { mkdirs() }

    private fun stagingFile(item: DownloadItem): File =
        File(stagingDir(), "dl_${item.id}.${item.container}.part")

    private fun cleanupStaging(id: Long) {
        val dir = stagingDir()
        dir.listFiles()
            ?.filter {
                it.name.startsWith("dl_$id.") ||
                    it.name.startsWith("dl_${id}_") ||
                    it.name.startsWith("hls_parts_dl_${id}_") ||
                    it.name.startsWith("hls_parts_dl_${id}.")
            }
            ?.forEach {
                runCatching {
                    if (it.isDirectory) it.deleteRecursively() else it.delete()
                }
            }
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

    companion object {
        private const val FOREGROUND_REFRESH_MS = 1000L
        private const val DB_PROGRESS_MIN_INTERVAL_MS = 350L

        /** Exponential retry backoff capped at 30 seconds (1s, 2s, 4s, 8s, 16s, 30s). */
        internal fun computeRetryBackoffMs(retryAttempt: Int): Long =
            min(30_000.0, 2.0.pow(retryAttempt.coerceAtLeast(0)) * 1000.0).toLong()

        /**
         * Builds a deterministic, filesystem-safe base title (without extension) for a gallery slide.
         * Multi-slide posts use zero-padded 1-based indices (e.g. `instagram_Df4fAVSgmen_01`, `02`, ...)
         * so Gallery apps display slides in exact reading order.
         */
        internal fun buildBatchSlideTitle(
            resolved: ResolvedMedia,
            slide: GallerySlide,
            totalSlides: Int
        ): String {
            val platform = UrlUtils.platformSlug(resolved.extractor, resolved.sourceUrl)
            val rawPostId = slide.postId?.takeIf { it.isNotBlank() }
                ?: resolved.postId?.takeIf { it.isNotBlank() }
                ?: UrlUtils.extractPostId(resolved.sourceUrl)
            val cleanId = rawPostId?.let {
                FormatUtils.sanitizeFileName(it, fallback = "").replace(' ', '_')
            }?.takeIf { it.isNotBlank() }

            val basePrefix = if (cleanId != null) {
                "${platform}_$cleanId"
            } else {
                val safeTitle = FormatUtils.sanitizeFileName(resolved.title, fallback = platform)
                    .replace(' ', '_')
                    .take(48)
                if (safeTitle.startsWith(platform, ignoreCase = true)) safeTitle else "${platform}_$safeTitle"
            }

            return if (totalSlides > 1) {
                val width = if (totalSlides >= 100) 3 else 2
                val ordinal = (slide.index + 1).toString().padStart(width, '0')
                "${basePrefix}_$ordinal"
            } else {
                basePrefix
            }
        }

        internal fun formatForegroundNotificationStatus(active: List<DownloadItem>): Triple<String, Int, Boolean> {
            if (active.isEmpty()) return Triple("…", 0, true)
            if (active.all { it.status == DownloadStatus.FINALIZING }) {
                return Triple("Finalizing…", 0, true)
            }
            if (active.all { it.status == DownloadStatus.QUEUED }) {
                return Triple("Queued…", 0, true)
            }
            val totalBytes = active.sumOf { maxOf(it.totalBytes, it.downloadedBytes).coerceAtLeast(0L) }
            val doneBytes = active.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
            val speed = active.sumOf { it.speedBytesPerSec.coerceAtLeast(0L) }
            val indeterminate = active.all { it.totalBytes <= 0L }
            val percent = if (!indeterminate && totalBytes > 0L) {
                ((doneBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
            } else 0
            val suffix = when {
                speed > 0L -> FormatUtils.formatSpeed(speed)
                active.any { it.status == DownloadStatus.FINALIZING } -> "Finalizing…"
                else -> "Downloading…"
            }
            val text = if (indeterminate) suffix else "$percent% · $suffix"
            return Triple(text, percent, indeterminate)
        }
    }
}
