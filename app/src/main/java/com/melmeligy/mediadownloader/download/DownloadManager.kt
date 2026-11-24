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
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class DownloadManager private constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val segmentedDownloader: SegmentedDownloader,
    private val hlsDownloader: HlsSegmentDownloader,
    private val videoAudioMuxer: VideoAudioMuxer,
    private val mediaStoreWriter: MediaStoreWriter,
    private val notifier: DownloadNotifier,
    private val dispatchers: DispatcherProvider,
    private val thumbnailCache: DownloadThumbnailCache?,
    private val availableBytesProvider: (File) -> Long,
    private val mediaRepository: dagger.Lazy<com.melmeligy.mediadownloader.domain.repository.MediaRepository>?,
    @Suppress("UNUSED_PARAMETER") marker: Boolean
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        repository: DownloadRepository,
        settingsRepository: SettingsRepository,
        segmentedDownloader: SegmentedDownloader,
        hlsDownloader: HlsSegmentDownloader,
        videoAudioMuxer: VideoAudioMuxer,
        mediaStoreWriter: MediaStoreWriter,
        notifier: DownloadNotifier,
        dispatchers: DispatcherProvider,
        thumbnailCache: DownloadThumbnailCache,
        mediaRepository: dagger.Lazy<com.melmeligy.mediadownloader.domain.repository.MediaRepository>
    ) : this(
        context = context,
        repository = repository,
        settingsRepository = settingsRepository,
        segmentedDownloader = segmentedDownloader,
        hlsDownloader = hlsDownloader,
        videoAudioMuxer = videoAudioMuxer,
        mediaStoreWriter = mediaStoreWriter,
        notifier = notifier,
        dispatchers = dispatchers,
        thumbnailCache = thumbnailCache,
        availableBytesProvider = MediaStoreWriter::queryAvailableBytes,
        mediaRepository = mediaRepository,
        marker = true
    )

    internal constructor(
        context: Context,
        repository: DownloadRepository,
        settingsRepository: SettingsRepository,
        segmentedDownloader: SegmentedDownloader,
        hlsDownloader: HlsSegmentDownloader,
        videoAudioMuxer: VideoAudioMuxer,
        mediaStoreWriter: MediaStoreWriter,
        notifier: DownloadNotifier,
        dispatchers: DispatcherProvider,
        thumbnailCache: DownloadThumbnailCache
    ) : this(
        context = context,
        repository = repository,
        settingsRepository = settingsRepository,
        segmentedDownloader = segmentedDownloader,
        hlsDownloader = hlsDownloader,
        videoAudioMuxer = videoAudioMuxer,
        mediaStoreWriter = mediaStoreWriter,
        notifier = notifier,
        dispatchers = dispatchers,
        thumbnailCache = thumbnailCache,
        availableBytesProvider = MediaStoreWriter::queryAvailableBytes,
        mediaRepository = null,
        marker = false
    )

    internal constructor(
        context: Context,
        repository: DownloadRepository,
        settingsRepository: SettingsRepository,
        segmentedDownloader: SegmentedDownloader,
        hlsDownloader: HlsSegmentDownloader,
        videoAudioMuxer: VideoAudioMuxer,
        mediaStoreWriter: MediaStoreWriter,
        notifier: DownloadNotifier,
        dispatchers: DispatcherProvider
    ) : this(
        context = context,
        repository = repository,
        settingsRepository = settingsRepository,
        segmentedDownloader = segmentedDownloader,
        hlsDownloader = hlsDownloader,
        videoAudioMuxer = videoAudioMuxer,
        mediaStoreWriter = mediaStoreWriter,
        notifier = notifier,
        dispatchers = dispatchers,
        thumbnailCache = null,
        availableBytesProvider = MediaStoreWriter::queryAvailableBytes,
        mediaRepository = null,
        marker = false
    )

    internal constructor(
        context: Context,
        repository: DownloadRepository,
        settingsRepository: SettingsRepository,
        segmentedDownloader: SegmentedDownloader,
        hlsDownloader: HlsSegmentDownloader,
        videoAudioMuxer: VideoAudioMuxer,
        mediaStoreWriter: MediaStoreWriter,
        notifier: DownloadNotifier,
        dispatchers: DispatcherProvider,
        thumbnailCache: DownloadThumbnailCache?,
        availableBytesProvider: (File) -> Long
    ) : this(
        context = context,
        repository = repository,
        settingsRepository = settingsRepository,
        segmentedDownloader = segmentedDownloader,
        hlsDownloader = hlsDownloader,
        videoAudioMuxer = videoAudioMuxer,
        mediaStoreWriter = mediaStoreWriter,
        notifier = notifier,
        dispatchers = dispatchers,
        thumbnailCache = thumbnailCache,
        availableBytesProvider = availableBytesProvider,
        mediaRepository = null,
        marker = false
    )

    internal constructor(
        context: Context,
        repository: DownloadRepository,
        settingsRepository: SettingsRepository,
        segmentedDownloader: SegmentedDownloader,
        hlsDownloader: HlsSegmentDownloader,
        videoAudioMuxer: VideoAudioMuxer,
        mediaStoreWriter: MediaStoreWriter,
        notifier: DownloadNotifier,
        dispatchers: DispatcherProvider,
        thumbnailCache: DownloadThumbnailCache?,
        availableBytesProvider: (File) -> Long,
        mediaRepository: dagger.Lazy<com.melmeligy.mediadownloader.domain.repository.MediaRepository>?
    ) : this(
        context = context,
        repository = repository,
        settingsRepository = settingsRepository,
        segmentedDownloader = segmentedDownloader,
        hlsDownloader = hlsDownloader,
        videoAudioMuxer = videoAudioMuxer,
        mediaStoreWriter = mediaStoreWriter,
        notifier = notifier,
        dispatchers = dispatchers,
        thumbnailCache = thumbnailCache,
        availableBytesProvider = availableBytesProvider,
        mediaRepository = mediaRepository,
        marker = false
    )

    private val runningJobs = ConcurrentHashMap<Long, Job>()
    private val retryingJobs = ConcurrentHashMap<Long, Job>()
    private val intents = ConcurrentHashMap<Long, ControlIntent>()
    private val jobIntents = ConcurrentHashMap<Job, ControlIntent>()
    private val controlLocks = ConcurrentHashMap<Long, Mutex>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)

    private fun lockFor(id: Long): Mutex = controlLocks.computeIfAbsent(id) { Mutex() }

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
            val origThumb = resolved.thumbnailUrl?.trim()
            if (!origThumb.isNullOrBlank() &&
                (origThumb.startsWith("http://", ignoreCase = true) || origThumb.startsWith("https://", ignoreCase = true)) &&
                !containsKey("X-Internal-Original-Thumbnail-Url")
            ) {
                put("X-Internal-Original-Thumbnail-Url", origThumb)
            }
        }
        val normalizedQualityLabel = if (stream.type == MediaType.VIDEO) {
            FormatUtils.normalizeQualityLabel(stream.label, stream.width, stream.height)
        } else {
            stream.label
        }
        val item = DownloadItem(
            title = resolved.title,
            sourceUrl = resolved.sourceUrl,
            remoteUrl = stream.url,
            type = stream.type,
            container = finalContainer,
            qualityLabel = normalizedQualityLabel,
            status = DownloadStatus.QUEUED,
            totalBytes = stream.sizeBytes ?: 0,
            thumbnailUrl = resolved.thumbnailUrl,
            headersJson = JsonUtils.mapToJson(headers),
            isHls = stream.isHls
        )
        val id = repository.enqueue(item)
        // If extraction already cached a local preview poster, promote it into thumb_<id>.jpg right away
        // so QUEUED, RUNNING, PAUSED, and FAILED cards render the real poster immediately.
        val cache = thumbnailCache
        if (cache != null && DownloadThumbnailCache.resolveExistingLocalFile(resolved.thumbnailUrl) != null) {
            runCatching {
                val promoted = cache.cacheRemoteThumbnail(
                    downloadId = id,
                    thumbnailUrl = resolved.thumbnailUrl,
                    sourceUrl = resolved.sourceUrl,
                    headers = headers
                )
                if (promoted != null) {
                    repository.updateThumbnail(id, promoted.absolutePath)
                }
            }
        }
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
        lockFor(id).withLock {
            intents[id] = ControlIntent.PAUSE
            val activeJob = runningJobs[id] ?: retryingJobs[id]
            if (activeJob != null) {
                jobIntents[activeJob] = ControlIntent.PAUSE
            } else {
                intents.remove(id)
            }
            // Update status immediately so Home/Downloads UI reflects PAUSED without waiting for socket/muxer teardown.
            repository.updateStatus(id, DownloadStatus.PAUSED)
            activeJob?.cancel()
            queueSignal.trySend(Unit)
        }
    }

    suspend fun resume(id: Long) {
        lockFor(id).withLock {
            val activeJob = runningJobs[id] ?: retryingJobs[id]
            activeJob?.cancelAndJoin()
            intents.remove(id)
            if (repository.getById(id) == null) return@withLock
            repository.requeue(id, resetRetryCount = false)
            queueSignal.trySend(Unit)
            schedule()
        }
    }

    suspend fun retry(id: Long) {
        lockFor(id).withLock {
            val activeJob = runningJobs[id] ?: retryingJobs[id]
            activeJob?.cancelAndJoin()
            intents.remove(id)
            val existing = repository.getById(id) ?: return@withLock

            val (errCode, _) = AppError.decodeFromStorage(existing.errorType)
            val needsFreshLink = errCode == AppError.ACCESS_DENIED ||
                errCode == AppError.EXPIRED_LINK ||
                errCode == AppError.GEO_BLOCKED
            val repo = mediaRepository?.get()
            if (needsFreshLink &&
                repo != null &&
                (existing.sourceUrl.startsWith("http://", ignoreCase = true) ||
                    existing.sourceUrl.startsWith("https://", ignoreCase = true))
            ) {
                val refreshed = runCatching {
                    (repo as? com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl)
                        ?.invalidateCache(existing.sourceUrl)
                    val resolved = withContext(dispatchers.io) {
                        withTimeoutOrNull(SMART_RETRY_RESOLVE_TIMEOUT_MS) {
                            repo.resolve(existing.sourceUrl)
                        }
                    } ?: return@runCatching null
                    selectBestRefreshStream(existing, resolved.streams)
                }.getOrNull()

                if (refreshed != null) {
                    withContext(dispatchers.io) { cleanupStaging(id, deleteThumbnail = false) }
                    val existingHeaders = JsonUtils.jsonToMap(existing.headersJson)
                    val mergedHeaders = LinkedHashMap<String, String>(refreshed.headers).apply {
                        existingHeaders["X-Internal-Target-Bitrate-Kbps"]?.let { putIfAbsent("X-Internal-Target-Bitrate-Kbps", it) }
                        existingHeaders["X-Internal-Original-Thumbnail-Url"]?.let { putIfAbsent("X-Internal-Original-Thumbnail-Url", it) }
                    }
                    val finalContainer = when {
                        refreshed.type == MediaType.AUDIO -> refreshed.container
                        refreshed.isHls -> "mp4"
                        else -> refreshed.container
                    }
                    repository.update(
                        existing.copy(
                            remoteUrl = refreshed.url,
                            container = finalContainer,
                            qualityLabel = refreshed.label.ifBlank { existing.qualityLabel },
                            totalBytes = refreshed.sizeBytes ?: existing.totalBytes,
                            downloadedBytes = 0L,
                            speedBytesPerSec = 0L,
                            etaSeconds = -1L,
                            headersJson = JsonUtils.mapToJson(mergedHeaders),
                            isHls = refreshed.isHls,
                            errorType = null,
                            retryCount = 0,
                            status = DownloadStatus.QUEUED
                        )
                    )
                    queueSignal.trySend(Unit)
                    schedule()
                    return@withLock
                }
            }

            repository.requeue(id, resetRetryCount = true)
            queueSignal.trySend(Unit)
            schedule()
        }
    }

    internal fun selectBestRefreshStream(existing: DownloadItem, streams: List<MediaStream>): MediaStream? {
        if (streams.isEmpty()) return null
        val sameType = streams.filter { it.type == existing.type }.ifEmpty { streams }
        val targetHeight = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(existing.qualityLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (targetHeight != null) {
            sameType.firstOrNull { it.height == targetHeight }?.let { return it }
        }
        sameType.firstOrNull { it.label.equals(existing.qualityLabel, ignoreCase = true) }?.let { return it }
        sameType.firstOrNull { it.isHls == existing.isHls }?.let { return it }
        return sameType.maxByOrNull { it.height ?: 0 } ?: sameType.first()
    }

    suspend fun cancel(id: Long) {
        lockFor(id).withLock {
            intents[id] = ControlIntent.CANCEL
            val activeJob = runningJobs[id] ?: retryingJobs[id]
            if (activeJob != null) {
                jobIntents[activeJob] = ControlIntent.CANCEL
            }
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
    }

    suspend fun clearCompleted() {
        repository.clearCompleted()
        runCatching {
            val cache = thumbnailCache ?: return@runCatching
            val keepIds = repository.observeAll().first().mapTo(HashSet()) { it.id }
            withContext(dispatchers.io) {
                cache.clearUnusedThumbnails(keepIds)
            }
        }
    }

    /**
     * Deletes unused thumbnail cache files (not belonging to any row in [DownloadRepository]) and
     * orphaned staging files (`.part`, `.idx`, `hls_parts_*`) whose download ID is not active/paused.
     */
    suspend fun cleanupOrphanedCacheFiles(): Pair<Int, Int> {
        val allItems = runCatching { repository.observeAll().first() }.getOrDefault(emptyList())
        val allKnownIds = allItems.mapTo(HashSet()) { it.id }
        val activeOrPausedIds = allItems
            .asSequence()
            .filter {
                it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.RUNNING ||
                    it.status == DownloadStatus.FINALIZING ||
                    it.status == DownloadStatus.PAUSED
            }
            .mapTo(HashSet()) { it.id }

        return withContext(dispatchers.io) {
            val removedThumbnails = thumbnailCache?.clearUnusedThumbnails(allKnownIds) ?: 0
            val dir = stagingDir()
            var removedStaging = 0
            dir.listFiles()?.forEach { entry ->
                val itemId = extractStagingItemId(entry.name)
                if (itemId != null && itemId !in activeOrPausedIds) {
                    val deleted = runCatching {
                        if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                    }.getOrDefault(false)
                    if (deleted) removedStaging++
                }
            }
            removedThumbnails to removedStaging
        }
    }

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
                jobIntents.remove(job)
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
        val currentJob = currentCoroutineContext()[Job]
        var lastProgressWriteMs = 0L
        var highWaterDownloaded = 0L
        var highWaterTotal = item.totalBytes.coerceAtLeast(0L)
        var isFinalizing = false
        var midDownloadFrameStage = 0

        fun currentControlIntent(): ControlIntent? =
            (currentJob?.let { jobIntents[it] }) ?: intents[item.id]

        suspend fun persistCachedThumbnail(file: File?) {
            if (file == null || currentControlIntent() == ControlIntent.CANCEL) return
            if (repository.getById(item.id) != null) {
                repository.updateThumbnail(item.id, file.absolutePath)
            }
        }

        suspend fun reportProgress(
            downloaded: Long,
            total: Long,
            speed: Long,
            eta: Long,
            capBeforeFinalizing: Boolean = true,
            force: Boolean = false
        ) {
            if (currentControlIntent() != null || !currentCoroutineContext().isActive) return
            if (item.type == MediaType.VIDEO &&
                thumbnailCache != null &&
                !thumbnailCache.hasCachedThumbnail(item.id)
            ) {
                if (item.isHls) {
                    val hlsDirs = listOf(
                        File(stagingDir(), "hls_parts_dl_${item.id}_raw.ts"),
                        File(stagingDir(), "hls_parts_${staging.nameWithoutExtension}")
                    )
                    val nextHlsStage = when {
                        midDownloadFrameStage < 1 && hasHlsThumbProbeCandidate(hlsDirs) -> 1
                        midDownloadFrameStage == 1 && (downloaded >= 3 * 1024 * 1024L || (total > 0L && downloaded >= total)) -> 2
                        midDownloadFrameStage == 2 && (downloaded >= 10 * 1024 * 1024L || (total > 0L && downloaded >= total)) -> 3
                        else -> 0
                    }
                    if (nextHlsStage > midDownloadFrameStage) {
                        midDownloadFrameStage = nextHlsStage
                        queueScope.launch(dispatchers.io) {
                            if (currentControlIntent() != ControlIntent.CANCEL && !thumbnailCache.hasCachedThumbnail(item.id)) {
                                val extracted = extractHlsMidDownloadFrame(item.id, hlsDirs, thumbnailCache)
                                persistCachedThumbnail(extracted)
                            }
                        }
                    }
                } else {
                    val nextStage = when {
                        midDownloadFrameStage < 1 && (downloaded >= 64 * 1024L || (total > 0L && downloaded >= total)) -> 1
                        midDownloadFrameStage == 1 && (downloaded >= 2 * 1024 * 1024L || (total > 0L && downloaded >= total)) -> 2
                        midDownloadFrameStage == 2 && (downloaded >= 10 * 1024 * 1024L || (total > 0L && downloaded >= total)) -> 3
                        else -> 0
                    }
                    if (nextStage > midDownloadFrameStage) {
                        val adaptiveVideo = File(stagingDir(), "dl_${item.id}_video.mp4.part")
                        val candidate = when {
                            adaptiveVideo.exists() && adaptiveVideo.length() > 0L -> adaptiveVideo
                            staging.exists() && staging.length() > 0L -> staging
                            else -> null
                        }
                        if (candidate != null) {
                            midDownloadFrameStage = nextStage
                            queueScope.launch(dispatchers.io) {
                                if (currentControlIntent() != ControlIntent.CANCEL && !thumbnailCache.hasCachedThumbnail(item.id)) {
                                    val extracted = thumbnailCache.extractVideoFrameThumbnail(item.id, candidate)
                                    persistCachedThumbnail(extracted)
                                }
                            }
                        }
                    }
                }
            }
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

        suspend fun enterFinalizing(currentBytes: Long, resetProgressForMux: Boolean = false) {
            if (!isFinalizing) {
                isFinalizing = true
                // Switch status BEFORE clearing speed/eta so UI never flashes RUNNING + "--"
                repository.updateStatus(item.id, DownloadStatus.FINALIZING)
            }
            val safeBytes = if (currentBytes > 0L) currentBytes else highWaterDownloaded
            if (safeBytes > 0L) {
                val initialDone = if (resetProgressForMux) 0L else safeBytes
                repository.updateProgress(item.id, initialDone, safeBytes, 0L, 0L)
            }
            queueSignal.trySend(Unit)
        }

        suspend fun runMuxWithProgress(
            stageTotalBytes: Long,
            baseFraction: Float = 0f,
            fractionSpan: Float = 1f,
            block: ((Float) -> Unit) -> Boolean
        ): Boolean = coroutineScope {
            val safeTotal = stageTotalBytes.coerceAtLeast(100L)
            val progressCh = Channel<Pair<Float, Long>>(
                capacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            val reporter = launch {
                var lastScaled = -1f
                var lastWriteMs = 0L
                for ((raw, sentAtMs) in progressCh) {
                    val scaled = (baseFraction + raw.coerceIn(0f, 1f) * fractionSpan).coerceIn(0f, 1f)
                    if (scaled >= 1f || lastScaled < 0f || (scaled - lastScaled >= 0.01f && sentAtMs - lastWriteMs >= 150L)) {
                        lastScaled = scaled
                        lastWriteMs = sentAtMs
                        if (currentControlIntent() == null) {
                            val doneBytes = (safeTotal.toDouble() * scaled.toDouble()).toLong().coerceIn(0L, safeTotal)
                            repository.updateProgress(item.id, doneBytes, safeTotal, 0L, 0L)
                        }
                    }
                }
            }
            try {
                withContext(dispatchers.io) {
                    block { pct -> progressCh.trySend(pct to System.currentTimeMillis()) }
                }
            } finally {
                progressCh.close()
                reporter.join()
            }
        }

        val initialIntent = currentControlIntent()
        if (initialIntent == ControlIntent.PAUSE || initialIntent == ControlIntent.CANCEL) {
            withContext(NonCancellable + dispatchers.io) {
                handleCancellation(item, staging, currentJob)
            }
            return
        }

        try {
            val headers = JsonUtils.jsonToMap(item.headersJson)
            val preProbedAcceptsRanges = headers["X-Internal-Accepts-Ranges"]?.equals("true", ignoreCase = true) == true
            val companionAcceptsRanges = headers["X-Internal-Companion-Accepts-Ranges"]?.equals("true", ignoreCase = true) == true

            if (thumbnailCache != null && !thumbnailCache.hasCachedThumbnail(item.id)) {
                // Cache remote thumbnail (or extract a remote video frame if no poster URL exists / fetch fails)
                // on queueScope so it finishes even if the user pauses right after starting.
                val effectiveThumbCandidate = item.thumbnailUrl?.takeIf {
                    DownloadThumbnailCache.resolveExistingLocalFile(it) != null ||
                        it.startsWith("http://", ignoreCase = true) ||
                        it.startsWith("https://", ignoreCase = true)
                } ?: headers["X-Internal-Original-Thumbnail-Url"]?.takeIf {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                }
                queueScope.launch(dispatchers.io) {
                    val cached = if (!effectiveThumbCandidate.isNullOrBlank()) {
                        thumbnailCache.cacheRemoteThumbnail(
                            downloadId = item.id,
                            thumbnailUrl = effectiveThumbCandidate,
                            sourceUrl = item.sourceUrl,
                            headers = headers
                        )
                    } else {
                        null
                    }
                    persistCachedThumbnail(cached)
                }
            }
            val companionAudioUrl = headers["X-Internal-Companion-Audio"]?.takeIf { it.startsWith("http") }
            val isAudioExtraction = headers["X-Internal-Extract-Audio"] == "true" ||
                (item.type == MediaType.AUDIO && (item.container.equals("mp3", true) || item.container.equals("m4a", true)))
            val targetBitrateKbps = headers["X-Internal-Target-Bitrate-Kbps"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: Regex("""\((\d{2,3})K\)""", RegexOption.IGNORE_CASE)
                    .find(item.qualityLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: settingsRepository.current().defaultAudioBitrate.kbps

            val upfrontVideoBytes = headers["X-Internal-Video-Bytes"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
            val upfrontAudioBytes = headers["X-Internal-Audio-Bytes"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
            val isExtractFromVideo = headers["X-Internal-Extract-Audio"] == "true"
            val upfrontExpectedBytes = when {
                isExtractFromVideo && upfrontVideoBytes > 0L ->
                    upfrontVideoBytes + item.totalBytes.coerceAtLeast(0L)
                upfrontVideoBytes > 0L && upfrontAudioBytes > 0L -> upfrontVideoBytes + upfrontAudioBytes
                upfrontVideoBytes > 0L -> upfrontVideoBytes
                item.totalBytes > 0L -> item.totalBytes
                else -> 0L
            }
            ensureSufficientStorage(stagingDir(), upfrontExpectedBytes)

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
                                acceptsRanges = companionAcceptsRanges,
                                segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                                onProgress = { p ->
                                    val total = if (p.total > 0L) videoActual + p.total else -1L
                                    reportProgress(videoActual + p.downloaded, total, p.speed, p.eta)
                                }
                            )
                        }
                        val finalizingBytes = (videoActual + stagingAudioTs.length()).coerceAtLeast(1L)
                        ensureSufficientStorage(stagingDir(), finalizingBytes)
                        enterFinalizing(finalizingBytes, resetProgressForMux = true)
                        val vOk = runMuxWithProgress(finalizingBytes, baseFraction = 0f, fractionSpan = 0.45f) { onP ->
                            videoAudioMuxer.remuxTsToMp4(stagingTs, stagingVideoMp4, onP)
                        }
                        val aOk = if (vOk) {
                            runMuxWithProgress(finalizingBytes, baseFraction = 0.45f, fractionSpan = 0.25f) { onP ->
                                videoAudioMuxer.extractM4a(stagingAudioTs, stagingAudioM4a, onProgress = onP)
                            }
                        } else false
                        val ok = vOk && aOk && runMuxWithProgress(finalizingBytes, baseFraction = 0.70f, fractionSpan = 0.30f) { onP ->
                            videoAudioMuxer.mux(stagingVideoMp4, stagingAudioM4a, staging, onP)
                        }
                        if (!ok) throw MediaException(AppError.GENERIC, "Failed to mux split HLS streams")
                    } else {
                        val finalizingBytes = (if (targetAudioTotal > 0L) targetAudioTotal else stagingTs.length()).coerceAtLeast(1L)
                        ensureSufficientStorage(stagingDir(), finalizingBytes)
                        enterFinalizing(finalizingBytes, resetProgressForMux = true)
                        val ok = if (item.type == MediaType.AUDIO || headers["X-Internal-Extract-Audio"] == "true") {
                            runMuxWithProgress(finalizingBytes) { onP ->
                                videoAudioMuxer.extractAudioOnly(stagingTs, staging, item.container, targetBitrateKbps, onP)
                            }
                        } else {
                            runMuxWithProgress(finalizingBytes) { onP ->
                                videoAudioMuxer.remuxTsToMp4(stagingTs, staging, onP)
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
                var videoAcceptsRanges = preProbedAcceptsRanges
                var audioAcceptsRanges = companionAcceptsRanges

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
                ensureSufficientStorage(stagingDir(), combinedTotal.coerceAtLeast(0L))

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
                    val finalizingBytes = (videoActualBytes + stagingAudio.length()).coerceAtLeast(1L)
                    ensureSufficientStorage(stagingDir(), finalizingBytes)
                    enterFinalizing(finalizingBytes, resetProgressForMux = true)
                    val ok = runMuxWithProgress(finalizingBytes) { onP ->
                        videoAudioMuxer.mux(stagingVideo, stagingAudio, staging, onP)
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

                ensureSufficientStorage(stagingDir(), realSourceBytes.coerceAtLeast(0L))

                var extractComplete = false
                try {
                    segmentedDownloader.download(
                        url = item.remoteUrl,
                        headers = headers,
                        dest = stagingSource,
                        knownTotal = realSourceBytes,
                        acceptsRanges = preProbedAcceptsRanges,
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
                    val finalizingBytes = (targetAudioTotal.takeIf { it > 0L } ?: stagingSource.length()).coerceAtLeast(1L)
                    ensureSufficientStorage(stagingDir(), finalizingBytes)
                    enterFinalizing(finalizingBytes, resetProgressForMux = true)
                    val ok = runMuxWithProgress(finalizingBytes) { onP ->
                        videoAudioMuxer.extractAudioOnly(stagingSource, staging, item.container, targetBitrateKbps, onP)
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
                ensureSufficientStorage(stagingDir(), realKnownBytes.coerceAtLeast(0L))
                segmentedDownloader.download(
                    url = item.remoteUrl,
                    headers = headers,
                    dest = staging,
                    knownTotal = realKnownBytes,
                    acceptsRanges = preProbedAcceptsRanges,
                    segmentCount = Constants.DEFAULT_SEGMENT_COUNT,
                    onProgress = { p ->
                        reportProgress(p.downloaded, p.total, p.speed, p.eta)
                    }
                )
            }

            val bytes = staging.length()
            if (bytes <= 0L) throw MediaException(AppError.GENERIC, "Downloaded file is empty")

            // Persist exact output size under FINALIZING state while publishing to MediaStore
            enterFinalizing(bytes, resetProgressForMux = false)

            if (item.type == MediaType.VIDEO && thumbnailCache != null && !thumbnailCache.hasCachedThumbnail(item.id)) {
                val localFrame = thumbnailCache.extractVideoFrameThumbnail(item.id, staging)
                persistCachedThumbnail(localFrame)
            }

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
            withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging, currentJob) }
            throw ce
        } catch (e: Exception) {
            // If the user cancelled or paused (which can surface as IOException on closed sockets),
            // never treat it as a failure or auto-retry.
            val intent = currentControlIntent()
            if (intent == ControlIntent.CANCEL || intent == ControlIntent.PAUSE) {
                withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging, currentJob) }
                return
            }
            val mapped = if (MediaStoreWriter.isInsufficientStorageException(e) &&
                (e as? MediaException)?.error != AppError.INSUFFICIENT_STORAGE
            ) {
                MediaException(AppError.INSUFFICIENT_STORAGE, e.message, e)
            } else {
                e
            }

            // Automatic failover to a verified backup server mirror if the primary stream died at download time
            val mappedError = (mapped as? MediaException)?.error ?: AppError.GENERIC
            if (mappedError != AppError.INSUFFICIENT_STORAGE && mappedError != AppError.NO_INTERNET) {
                val primaryHeaders = JsonUtils.jsonToMap(item.headersJson)
                val fallbackUrl = primaryHeaders["X-Internal-Fallback-Url"]?.trim()
                    ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                if (fallbackUrl != null && fallbackUrl != item.remoteUrl) {
                    val fallbackIsHls = primaryHeaders["X-Internal-Fallback-IsHls"]?.equals("true", ignoreCase = true) == true
                    val fallbackContainer = if (fallbackIsHls) {
                        "mp4"
                    } else {
                        primaryHeaders["X-Internal-Fallback-Container"]?.takeIf { it.isNotBlank() } ?: item.container
                    }
                    val fallbackHeaders = LinkedHashMap<String, String>().apply {
                        val rawFallbackHdrs = primaryHeaders["X-Internal-Fallback-Headers"]
                        if (!rawFallbackHdrs.isNullOrBlank()) {
                            putAll(JsonUtils.jsonToMap(rawFallbackHdrs))
                        } else {
                            primaryHeaders.forEach { (k, v) ->
                                if (!k.startsWith("X-Internal-", ignoreCase = true)) put(k, v)
                            }
                        }
                        primaryHeaders["X-Internal-Original-Thumbnail-Url"]?.let {
                            put("X-Internal-Original-Thumbnail-Url", it)
                        }
                        primaryHeaders["X-Internal-Target-Bitrate-Kbps"]?.let {
                            put("X-Internal-Target-Bitrate-Kbps", it)
                        }
                    }
                    cleanupStaging(item.id, deleteThumbnail = false)
                    val fallbackItem = item.copy(
                        remoteUrl = fallbackUrl,
                        isHls = fallbackIsHls,
                        container = fallbackContainer,
                        headersJson = JsonUtils.mapToJson(fallbackHeaders)
                    )
                    runOne(fallbackItem, queueScope)
                    return
                }
            }

            handleFailure(item, staging, mapped, queueScope, currentJob)
        }
    }

    private suspend fun handleCancellation(item: DownloadItem, staging: File, job: Job? = null) {
        val jobIntent = job?.let { jobIntents.remove(it) }
        val effectiveIntent = jobIntent ?: intents[item.id]
        if (effectiveIntent != null) {
            intents.remove(item.id, effectiveIntent)
        }
        when (effectiveIntent) {
            ControlIntent.CANCEL -> {
                cleanupStaging(item.id, deleteThumbnail = true)
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
        queueScope: CoroutineScope,
        originJob: Job? = null
    ) {
        val error = (e as? MediaException)?.error ?: AppError.GENERIC
        val errorDetail = e.message?.trim()?.takeIf { it.isNotEmpty() && !it.equals(error.name, ignoreCase = true) }
        val activeIntent = (originJob?.let { jobIntents[it] }) ?: intents[item.id]
        val settings = settingsRepository.current()
        val maxRetries = settings.retryCount.coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)
        val transient = error == AppError.NO_INTERNET || error == AppError.GENERIC || error == AppError.SERVER_ERROR
        if (transient && maxRetries > 0 && item.retryCount < maxRetries && activeIntent == null) {
            val backoffMs = computeRetryBackoffMs(item.retryCount)
            withContext(NonCancellable) {
                repository.incrementRetry(item.id)
            }
            // Schedule retry backoff in retryingJobs so this item releases its concurrency slot
            // immediately while staying cleanly cancellable if the user cancels/pauses during backoff.
            val retryJob = queueScope.launch(start = CoroutineStart.LAZY) {
                val thisRetryJob = currentCoroutineContext()[Job]
                try {
                    delay(backoffMs)
                    val retryIntent = (thisRetryJob?.let { jobIntents[it] }) ?: intents[item.id]
                    if (retryIntent == ControlIntent.CANCEL || retryIntent == ControlIntent.PAUSE) {
                        withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging, thisRetryJob) }
                        return@launch
                    }
                    if (repository.getById(item.id) == null) return@launch
                    repository.updateStatus(item.id, DownloadStatus.QUEUED)
                    queueSignal.trySend(Unit)
                } catch (ce: CancellationException) {
                    withContext(NonCancellable + dispatchers.io) { handleCancellation(item, staging, thisRetryJob) }
                    throw ce
                }
            }
            retryingJobs[item.id] = retryJob
            retryJob.invokeOnCompletion {
                jobIntents.remove(retryJob)
                retryingJobs.remove(item.id, retryJob)
                queueSignal.trySend(Unit)
            }
            retryJob.start()
        } else {
            withContext(NonCancellable) {
                if (activeIntent == ControlIntent.CANCEL || activeIntent == ControlIntent.PAUSE) {
                    handleCancellation(item, staging, originJob)
                } else {
                    // Keep cached local thumbnail intact so the failed card still shows the real poster
                    cleanupStaging(item.id, deleteThumbnail = false)
                    if (repository is com.melmeligy.mediadownloader.data.repository.DownloadRepositoryImpl) {
                        repository.setError(item.id, error, errorDetail)
                    } else {
                        repository.setError(item.id, error)
                    }
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

    private fun cleanupStaging(id: Long, deleteThumbnail: Boolean = true) {
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
        if (deleteThumbnail) {
            thumbnailCache?.deleteThumbnail(id)
        }
    }

    private fun ensureSufficientStorage(target: File, requiredBytes: Long) {
        val minRequired = if (requiredBytes > 0L) requiredBytes else MIN_FREE_STORAGE_FLOOR_BYTES
        val available = runCatching { availableBytesProvider(target) }.getOrDefault(-1L)
        if (available >= 0L && available < minRequired) {
            throw MediaException(
                AppError.INSUFFICIENT_STORAGE,
                "Not enough free storage (required=$minRequired, available=$available)"
            )
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
        AppError.ACCESS_DENIED -> "Access denied by host server"
        AppError.SERVER_ERROR -> "Host server temporarily busy"
        AppError.EXPIRED_LINK -> "Link expired"
        AppError.INSUFFICIENT_STORAGE -> "Not enough storage"
        AppError.GENERIC -> "Download failed"
    }

    companion object {
        private const val FOREGROUND_REFRESH_MS = 1000L
        private const val DB_PROGRESS_MIN_INTERVAL_MS = 350L
        private const val MIN_FREE_STORAGE_FLOOR_BYTES = 512L * 1024L
        private const val SMART_RETRY_RESOLVE_TIMEOUT_MS = 8_000L
        private val STAGING_ITEM_ID_REGEX = Regex("""^(?:hls_parts_)?dl_(\d+)(?:[._]|$)""")

        internal fun extractStagingItemId(fileName: String): Long? =
            STAGING_ITEM_ID_REGEX.find(fileName)?.groupValues?.getOrNull(1)?.toLongOrNull()

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

        internal fun hasHlsThumbProbeCandidate(partsDirs: List<File>): Boolean {
            val dir = partsDirs.firstOrNull { it.isDirectory } ?: return false
            val seg0 = File(dir, "seg_0.part").takeIf { it.exists() && it.length() > 0L }
            val laterExists = (1..5).any { idx ->
                val f = File(dir, "seg_$idx.part")
                f.exists() && f.length() > 0L
            }
            if (seg0 != null && looksLikeFmp4InitSegment(seg0)) {
                return laterExists
            }
            return seg0 != null || laterExists
        }

        internal fun looksLikeFmp4InitSegment(file: File): Boolean {
            if (!file.exists() || file.length() < 12L) return false
            return runCatching {
                val readLen = minOf(file.length(), 65_536L).toInt()
                val buf = ByteArray(readLen)
                file.inputStream().use { input ->
                    var offset = 0
                    while (offset < readLen) {
                        val n = input.read(buf, offset, readLen - offset)
                        if (n <= 0) break
                        offset += n
                    }
                    if (offset < 12) return@runCatching false
                    val isFtyp = buf[4] == 'f'.code.toByte() &&
                        buf[5] == 't'.code.toByte() &&
                        buf[6] == 'y'.code.toByte() &&
                        buf[7] == 'p'.code.toByte()
                    if (!isFtyp) return@runCatching false
                    val hasMdat = containsAsciiTag(buf, offset, "mdat")
                    !hasMdat
                }
            }.getOrDefault(false)
        }

        private fun containsAsciiTag(buf: ByteArray, length: Int, tag: String): Boolean {
            val needle = tag.toByteArray(Charsets.US_ASCII)
            if (needle.isEmpty() || length < needle.size) return false
            outer@ for (i in 0..(length - needle.size)) {
                for (j in needle.indices) {
                    if (buf[i + j] != needle[j]) continue@outer
                }
                return true
            }
            return false
        }

        private fun looksLikeMpegTs(file: File): Boolean = runCatching {
            if (!file.exists() || file.length() < 189L) return@runCatching false
            val buf = ByteArray(189)
            file.inputStream().use { it.read(buf) } >= 189 &&
                buf[0] == 0x47.toByte() && buf[188] == 0x47.toByte()
        }.getOrDefault(false)

        internal suspend fun extractHlsMidDownloadFrame(
            downloadId: Long,
            partsDirs: List<File>,
            thumbnailCache: DownloadThumbnailCache
        ): File? {
            val dir = partsDirs.firstOrNull { it.isDirectory } ?: return null
            val seg0 = File(dir, "seg_0.part").takeIf { it.exists() && it.length() > 0L }
            val laterSegs = (1..5).mapNotNull { idx ->
                File(dir, "seg_$idx.part").takeIf { it.exists() && it.length() > 0L }
            }

            // fMP4 HLS: seg_0.part is an init-only ftyp/moov header with 0 video frames.
            // Concatenate seg_0.part + first available media segment into a temp .mp4 probe file.
            if (seg0 != null && looksLikeFmp4InitSegment(seg0)) {
                for (mediaSeg in laterSegs.take(3)) {
                    val tempProbe = File(dir, ".thumb_probe_${downloadId}_${System.nanoTime()}.mp4")
                    try {
                        tempProbe.outputStream().use { out ->
                            seg0.inputStream().use { it.copyTo(out) }
                            mediaSeg.inputStream().use { it.copyTo(out) }
                        }
                        val extracted = thumbnailCache.extractVideoFrameThumbnail(downloadId, tempProbe)
                        if (extracted != null) return extracted
                    } catch (_: Throwable) {
                        // Try next segment
                    } finally {
                        runCatching { tempProbe.delete() }
                    }
                }
                return null
            }

            // Standard MPEG-TS or standalone segments: try seg_0..seg_5, retrying with proper .ts/.mp4
            // probe extension if the raw .part filename fails sniffing.
            val candidates = buildList {
                if (seg0 != null) add(seg0)
                addAll(laterSegs)
            }.take(4)

            for (seg in candidates) {
                val direct = thumbnailCache.extractVideoFrameThumbnail(downloadId, seg)
                if (direct != null) return direct

                val ext = if (looksLikeMpegTs(seg)) "ts" else "mp4"
                val tempProbe = File(dir, ".thumb_probe_${downloadId}_${System.nanoTime()}.$ext")
                try {
                    seg.inputStream().use { input ->
                        tempProbe.outputStream().use { out ->
                            val buf = ByteArray(16_384)
                            var copied = 0L
                            val maxProbeBytes = 2L * 1024L * 1024L
                            while (copied < maxProbeBytes) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                copied += n
                            }
                        }
                    }
                    val probed = thumbnailCache.extractVideoFrameThumbnail(downloadId, tempProbe)
                    if (probed != null) return probed
                } catch (_: Throwable) {
                    // Try next segment
                } finally {
                    runCatching { tempProbe.delete() }
                }
            }
            return null
        }
    }
}
