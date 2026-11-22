package com.melmeligy.mediadownloader.download

import android.content.Context
import androidx.work.WorkManager
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DownloadManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val repository = mockk<DownloadRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val segmentedDownloader = mockk<SegmentedDownloader>(relaxed = true)
    private val hlsDownloader = mockk<HlsSegmentDownloader>(relaxed = true)
    private val videoAudioMuxer = mockk<VideoAudioMuxer>(relaxed = true)
    private val mediaStoreWriter = mockk<MediaStoreWriter>(relaxed = true)
    private val notifier = mockk<DownloadNotifier>(relaxed = true)
    private val dispatchers = TestDispatcherProvider()
    private val workManager = mockk<WorkManager>(relaxed = true)

    private lateinit var downloadManager: DownloadManager

    @Before
    fun setUp() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        downloadManager = DownloadManager(
            context = context,
            repository = repository,
            settingsRepository = settingsRepository,
            segmentedDownloader = segmentedDownloader,
            hlsDownloader = hlsDownloader,
            videoAudioMuxer = videoAudioMuxer,
            mediaStoreWriter = mediaStoreWriter,
            notifier = notifier,
            dispatchers = dispatchers
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun enqueue_assignsMp4ContainerToHlsVideoStreams() = runTest {
        val resolved = ResolvedMedia(title = "Video Title", sourceUrl = "https://example.com/page")
        val stream = MediaStream(
            id = "v1",
            url = "https://example.com/playlist.m3u8",
            type = MediaType.VIDEO,
            container = "ts",
            isHls = true,
            label = "720p"
        )

        val slot = slot<DownloadItem>()
        coEvery { repository.enqueue(capture(slot)) } returns 42L

        downloadManager.enqueue(resolved, stream)

        assertEquals("mp4", slot.captured.container)
        assertEquals(true, slot.captured.isHls)
    }

    @Test
    fun enqueue_preservesAudioContainerForHlsAudioStreams() = runTest {
        val resolved = ResolvedMedia(title = "Audio Title", sourceUrl = "https://example.com/page")
        val stream = MediaStream(
            id = "a1",
            url = "https://example.com/audio.m3u8",
            type = MediaType.AUDIO,
            container = "m4a",
            isHls = true,
            label = "Fast"
        )

        val slot = slot<DownloadItem>()
        coEvery { repository.enqueue(capture(slot)) } returns 43L

        downloadManager.enqueue(resolved, stream)

        assertEquals("m4a", slot.captured.container)
        assertEquals(true, slot.captured.isHls)
    }

    @Test
    fun enqueue_preservesContainerForProgressiveStreams() = runTest {
        val resolved = ResolvedMedia(title = "Prog Video", sourceUrl = "https://example.com/page")
        val stream = MediaStream(
            id = "v2",
            url = "https://example.com/video.webm",
            type = MediaType.VIDEO,
            container = "webm",
            isHls = false,
            label = "1080p"
        )

        val slot = slot<DownloadItem>()
        coEvery { repository.enqueue(capture(slot)) } returns 44L

        downloadManager.enqueue(resolved, stream)

        assertEquals("webm", slot.captured.container)
        assertEquals(false, slot.captured.isHls)
    }

    @Test
    fun runQueue_hlsDownload_transitionsToFinalizingBeforeRemuxAndNeverFlashesRunning100PercentZeroSpeed() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_finalizing_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir

            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings()
            coEvery { settingsRepository.current() } returns settings
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)

            val statusHistory = mutableListOf<com.melmeligy.mediadownloader.domain.model.DownloadStatus>()
            val progressSnapshots = mutableListOf<Triple<com.melmeligy.mediadownloader.domain.model.DownloadStatus, Long, Long>>()
            var currentItem = DownloadItem(
                id = 99L,
                title = "Episode 5",
                sourceUrl = "https://topcinemaa.one/watch/",
                remoteUrl = "https://cdn.example/480p.m3u8",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "480p",
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                totalBytes = 100_000L,
                isHls = true
            )
            var claimed = false

            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                if (!claimed) {
                    claimed = true
                    currentItem = currentItem.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    statusHistory += currentItem.status
                    listOf(currentItem)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                kotlinx.coroutines.flow.flowOf(if (currentItem.isActive) listOf(currentItem) else emptyList())
            }
            coEvery { repository.updateStatus(99L, any()) } answers {
                val st = secondArg<com.melmeligy.mediadownloader.domain.model.DownloadStatus>()
                currentItem = currentItem.copy(status = st)
                statusHistory += st
            }
            coEvery { repository.updateProgress(99L, any(), any(), any(), any()) } answers {
                val dl = arg<Long>(1)
                val tot = arg<Long>(2)
                val spd = arg<Long>(3)
                currentItem = currentItem.copy(downloadedBytes = dl, totalBytes = tot, speedBytesPerSec = spd)
                progressSnapshots += Triple(currentItem.status, dl, spd)
            }
            coEvery { repository.setCompleted(99L, any(), any()) } answers {
                val tot = arg<Long>(2)
                currentItem = currentItem.copy(
                    status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED,
                    downloadedBytes = tot,
                    totalBytes = tot,
                    speedBytesPerSec = 0L
                )
                statusHistory += currentItem.status
            }

            coEvery {
                hlsDownloader.download(any(), any(), any(), any(), any(), any())
            } coAnswers {
                val dest = arg<java.io.File>(2)
                val onProgress = arg<suspend (DownloadProgress) -> Unit>(5)
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(120_000) { 0x47 })
                onProgress(DownloadProgress(downloaded = 60_000L, total = 120_000L, speed = 2_000_000L, eta = 1L))
                // Final callback emitted by HlsSegmentDownloader when all segments finish
                onProgress(DownloadProgress(downloaded = 120_000L, total = 120_000L, speed = 0L, eta = 0L))
            }

            var statusDuringRemux: com.melmeligy.mediadownloader.domain.model.DownloadStatus? = null
            every { videoAudioMuxer.remuxTsToMp4(any(), any()) } answers {
                statusDuringRemux = currentItem.status
                val out = secondArg<java.io.File>()
                out.writeBytes(ByteArray(112_000) { 0x01 })
                true
            }

            var statusDuringPublish: com.melmeligy.mediadownloader.domain.model.DownloadStatus? = null
            coEvery { mediaStoreWriter.publish(any(), any(), any(), any(), any()) } coAnswers {
                statusDuringPublish = currentItem.status
                "content://media/external/video/media/99"
            }

            downloadManager.runQueue { }

            assertEquals(
                listOf(
                    com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING,
                    com.melmeligy.mediadownloader.domain.model.DownloadStatus.FINALIZING,
                    com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED
                ),
                statusHistory
            )
            assertEquals(com.melmeligy.mediadownloader.domain.model.DownloadStatus.FINALIZING, statusDuringRemux)
            assertEquals(com.melmeligy.mediadownloader.domain.model.DownloadStatus.FINALIZING, statusDuringPublish)

            // Never recorded a RUNNING snapshot with speed == 0 and 100% downloaded
            val badGlitchSnapshots = progressSnapshots.filter { (st, dl, spd) ->
                st == com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING && spd == 0L && dl >= 120_000L
            }
            assertEquals("Must never emit transient RUNNING 100%/0-speed snapshot", emptyList<Any>(), badGlitchSnapshots)
            assertEquals(112_000L, currentItem.totalBytes)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pause_updatesStatusImmediatelyWhileActiveDownloadIsStalled() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_pause_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir
            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings(maxConcurrentDownloads = 1)
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)
            coEvery { settingsRepository.current() } returns settings

            var currentItem: DownloadItem? = DownloadItem(
                id = 101L,
                title = "Pause Test",
                sourceUrl = "https://example.com/page",
                remoteUrl = "https://example.com/master.m3u8",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "480p",
                isHls = true,
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            val downloadStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cleanupGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val downloadCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()

            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                val item = currentItem
                if (item != null && item.status == com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED) {
                    val running = item.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    currentItem = running
                    listOf(running)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                val item = currentItem
                kotlinx.coroutines.flow.flowOf(if (item != null && item.isActive) listOf(item) else emptyList())
            }
            coEvery { repository.getById(101L) } answers { currentItem }
            coEvery { repository.updateStatus(101L, any()) } answers {
                val st = secondArg<com.melmeligy.mediadownloader.domain.model.DownloadStatus>()
                currentItem = currentItem?.copy(status = st)
            }
            coEvery {
                hlsDownloader.download(any(), any(), any(), any(), any(), any())
            } coAnswers {
                downloadStarted.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    downloadCancelled.complete(Unit)
                    cleanupGate.await()
                }
            }

            val queueJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                downloadManager.runQueue { }
            }

            kotlinx.coroutines.withTimeout(2000L) { downloadStarted.await() }

            // Pause must update DB status to PAUSED immediately, even before worker cleanup finishes
            downloadManager.pause(101L)
            assertEquals(
                com.melmeligy.mediadownloader.domain.model.DownloadStatus.PAUSED,
                currentItem?.status
            )

            // Worker coroutine must have been cancelled promptly
            kotlinx.coroutines.withTimeout(2000L) { downloadCancelled.await() }
            cleanupGate.complete(Unit)
            kotlinx.coroutines.withTimeout(2000L) { queueJob.join() }
            assertEquals(
                com.melmeligy.mediadownloader.domain.model.DownloadStatus.PAUSED,
                currentItem?.status
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cancel_deletesItemImmediatelyWhileActiveDownloadIsStalled() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_cancel_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir
            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings(maxConcurrentDownloads = 1)
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)
            coEvery { settingsRepository.current() } returns settings

            var currentItem: DownloadItem? = DownloadItem(
                id = 102L,
                title = "Cancel Test",
                sourceUrl = "https://example.com/page",
                remoteUrl = "https://example.com/master.m3u8",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "480p",
                isHls = true,
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            val downloadStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cleanupGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val downloadCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()

            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                val item = currentItem
                if (item != null && item.status == com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED) {
                    val running = item.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    currentItem = running
                    listOf(running)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                val item = currentItem
                kotlinx.coroutines.flow.flowOf(if (item != null && item.isActive) listOf(item) else emptyList())
            }
            coEvery { repository.getById(102L) } answers { currentItem }
            coEvery { repository.delete(102L) } answers { currentItem = null }
            coEvery { repository.updateStatus(102L, any()) } answers {
                val st = secondArg<com.melmeligy.mediadownloader.domain.model.DownloadStatus>()
                currentItem = currentItem?.copy(status = st)
            }
            coEvery {
                hlsDownloader.download(any(), any(), any(), any(), any(), any())
            } coAnswers {
                downloadStarted.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    downloadCancelled.complete(Unit)
                    cleanupGate.await()
                }
            }

            val queueJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                downloadManager.runQueue { }
            }

            kotlinx.coroutines.withTimeout(2000L) { downloadStarted.await() }

            // Cancel must remove the item immediately before worker cleanup completes
            downloadManager.cancel(102L)
            org.junit.Assert.assertNull(currentItem)

            kotlinx.coroutines.withTimeout(2000L) { downloadCancelled.await() }
            cleanupGate.complete(Unit)
            kotlinx.coroutines.withTimeout(2000L) { queueJob.join() }
            org.junit.Assert.assertNull(currentItem)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pauseThenResume_waitsForCleanTeardownAndEndsInQueued() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_pause_resume_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir
            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings(maxConcurrentDownloads = 1)
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)
            coEvery { settingsRepository.current() } returns settings

            var currentItem: DownloadItem? = DownloadItem(
                id = 103L,
                title = "Pause Resume Test",
                sourceUrl = "https://example.com/page",
                remoteUrl = "https://example.com/master.m3u8",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "480p",
                isHls = true,
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            val downloadStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            var claimCount = 0

            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                val item = currentItem
                if (claimCount == 0 && item != null && item.status == com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED) {
                    claimCount++
                    val running = item.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    currentItem = running
                    listOf(running)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                val item = currentItem
                kotlinx.coroutines.flow.flowOf(if (item != null && item.isActive) listOf(item) else emptyList())
            }
            coEvery { repository.getById(103L) } answers { currentItem }
            coEvery { repository.requeue(103L, any()) } answers {
                currentItem = currentItem?.copy(
                    status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                    errorType = null
                )
            }
            coEvery { repository.updateStatus(103L, any()) } answers {
                val st = secondArg<com.melmeligy.mediadownloader.domain.model.DownloadStatus>()
                currentItem = currentItem?.copy(status = st)
            }
            coEvery {
                hlsDownloader.download(any(), any(), any(), any(), any(), any())
            } coAnswers {
                downloadStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }

            val queueJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                downloadManager.runQueue { }
            }

            kotlinx.coroutines.withTimeout(2000L) { downloadStarted.await() }
            downloadManager.pause(103L)
            downloadManager.resume(103L)

            kotlinx.coroutines.withTimeout(2000L) { queueJob.join() }
            assertEquals(
                com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                currentItem?.status
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun adaptiveDownload_pausePreservesPartialStagingFiles_andCancelCleansHlsPartsDir() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_resume_staging_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir
            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings(maxConcurrentDownloads = 1)
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)
            coEvery { settingsRepository.current() } returns settings

            val headersJson = com.melmeligy.mediadownloader.core.util.JsonUtils.mapToJson(
                mapOf("X-Internal-Companion-Audio" to "https://cdn.example/audio.m4a")
            )
            var currentItem: DownloadItem? = DownloadItem(
                id = 104L,
                title = "Adaptive Pause Test",
                sourceUrl = "https://example.com/watch",
                remoteUrl = "https://cdn.example/video.mp4",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "720p",
                headersJson = headersJson,
                isHls = false,
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            val audioStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            var claimed = false

            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                val item = currentItem
                if (!claimed && item != null && item.status == com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED) {
                    claimed = true
                    val running = item.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    currentItem = running
                    listOf(running)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                val item = currentItem
                kotlinx.coroutines.flow.flowOf(if (item != null && item.isActive) listOf(item) else emptyList())
            }
            coEvery { repository.getById(104L) } answers { currentItem }
            coEvery { repository.updateStatus(104L, any()) } answers {
                val st = secondArg<com.melmeligy.mediadownloader.domain.model.DownloadStatus>()
                currentItem = currentItem?.copy(status = st)
            }

            coEvery {
                segmentedDownloader.download(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                val dest = arg<java.io.File>(2)
                dest.parentFile?.mkdirs()
                if (dest.name.contains("_video.")) {
                    dest.writeBytes(ByteArray(1024) { 0x11 })
                } else {
                    dest.writeBytes(ByteArray(256) { 0x22 })
                    audioStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            }

            val queueJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                downloadManager.runQueue { }
            }

            kotlinx.coroutines.withTimeout(2000L) { audioStarted.await() }
            downloadManager.pause(104L)
            kotlinx.coroutines.withTimeout(2000L) { queueJob.join() }

            val downloadsDir = java.io.File(tempDir, "Downloads")
            val videoPart = java.io.File(downloadsDir, "dl_104_video.mp4.part")
            val audioPart = java.io.File(downloadsDir, "dl_104_audio.part")
            org.junit.Assert.assertTrue("Video staging file must be preserved on Pause", videoPart.exists() && videoPart.length() == 1024L)
            org.junit.Assert.assertTrue("Audio partial staging file must be preserved on Pause", audioPart.exists() && audioPart.length() == 256L)

            // Also verify Cancel cleans up deterministic hls_parts_dl_<id>.mp4 directory
            val hlsPartsDir = java.io.File(downloadsDir, "hls_parts_dl_105.mp4").apply { mkdirs() }
            java.io.File(hlsPartsDir, "seg_0.part").writeText("SEG0")
            downloadManager.cancel(105L)
            org.junit.Assert.assertFalse("hls_parts_dl_105.mp4 must be deleted on Cancel", hlsPartsDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun thumbnailLifecycle_cachesRemoteOnStart_preservesAcrossPauseResumeComplete_andDeletesOnCancel() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_thumb_lifecycle_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir
            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings(maxConcurrentDownloads = 1)
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)
            coEvery { settingsRepository.current() } returns settings

            val capturedHeaders = java.util.concurrent.ConcurrentHashMap<String, String>()
            val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)
            val thumbClient = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    req.headers.names().forEach { name ->
                        capturedHeaders[name] = req.header(name).orEmpty()
                    }
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/jpeg")
                        .body(okhttp3.ResponseBody.Companion.let {
                            with(it) { jpegBytes.toResponseBody("image/jpeg".toMediaType()) }
                        })
                        .build()
                }
                .build()
            val thumbsDir = java.io.File(tempDir, "thumbnails")
            val thumbCache = DownloadThumbnailCache(thumbsDir, thumbClient) { _, _ -> false }
            val dm = DownloadManager(
                context = context,
                repository = repository,
                settingsRepository = settingsRepository,
                segmentedDownloader = segmentedDownloader,
                hlsDownloader = hlsDownloader,
                videoAudioMuxer = videoAudioMuxer,
                mediaStoreWriter = mediaStoreWriter,
                notifier = notifier,
                dispatchers = dispatchers,
                thumbnailCache = thumbCache
            )

            val headersJson = com.melmeligy.mediadownloader.core.util.JsonUtils.mapToJson(
                mapOf(
                    "Referer" to "https://topcinemaa.one/watch/ep5/",
                    "Cookie" to "cf_clearance=abc123",
                    "User-Agent" to "CustomUA/1.0"
                )
            )
            var currentItem: DownloadItem? = DownloadItem(
                id = 201L,
                title = "Episode 5",
                sourceUrl = "https://topcinemaa.one/watch/ep5/",
                remoteUrl = "https://cdn.example/ep5.m3u8",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "480p",
                thumbnailUrl = "https://topcinemaa.one/posters/ep5.jpg",
                headersJson = headersJson,
                isHls = true,
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                createdAt = 1000L,
                updatedAt = 1000L
            )

            val firstRunStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val thumbPersisted = kotlinx.coroutines.CompletableDeferred<String>()
            var runCount = 0

            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                val item = currentItem
                if (item != null && item.status == com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED) {
                    val running = item.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    currentItem = running
                    listOf(running)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                val item = currentItem
                kotlinx.coroutines.flow.flowOf(if (item != null && item.isActive) listOf(item) else emptyList())
            }
            coEvery { repository.getById(201L) } answers { currentItem }
            coEvery { repository.updateStatus(201L, any()) } answers {
                val st = secondArg<com.melmeligy.mediadownloader.domain.model.DownloadStatus>()
                currentItem = currentItem?.copy(status = st)
            }
            coEvery { repository.updateThumbnail(201L, any()) } answers {
                val path = secondArg<String>()
                currentItem = currentItem?.copy(thumbnailUrl = path)
                thumbPersisted.complete(path)
            }
            coEvery { repository.requeue(201L, any()) } answers {
                currentItem = currentItem?.copy(
                    status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                    errorType = null
                )
            }
            coEvery { repository.setCompleted(201L, any(), any()) } answers {
                currentItem = currentItem?.copy(
                    status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED,
                    contentUri = arg(1),
                    totalBytes = arg(2),
                    downloadedBytes = arg(2)
                )
            }
            coEvery {
                hlsDownloader.download(any(), any(), any(), any(), any(), any())
            } coAnswers {
                val dest = arg<java.io.File>(2)
                runCount++
                if (runCount == 1) {
                    firstRunStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                } else {
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(ByteArray(4096) { 0x47 })
                }
            }
            every { videoAudioMuxer.remuxTsToMp4(any(), any()) } answers {
                secondArg<java.io.File>().writeBytes(ByteArray(4096) { 0x01 })
                true
            }
            coEvery { mediaStoreWriter.publish(any(), any(), any(), any(), any()) } returns "content://media/video/201"

            // Run 1: start -> wait for remote thumbnail to cache -> pause
            val job1 = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                dm.runQueue { }
            }
            kotlinx.coroutines.withTimeout(2000L) { firstRunStarted.await() }
            val cachedPath = kotlinx.coroutines.withTimeout(2000L) { thumbPersisted.await() }
            val cachedFile = java.io.File(cachedPath)
            org.junit.Assert.assertTrue("Cached thumbnail must exist on disk", cachedFile.exists() && cachedFile.length() == jpegBytes.size.toLong())
            assertEquals("CustomUA/1.0", capturedHeaders["User-Agent"])
            assertEquals("https://topcinemaa.one/watch/ep5/", capturedHeaders["Referer"])
            assertEquals("cf_clearance=abc123", capturedHeaders["Cookie"])

            dm.pause(201L)
            kotlinx.coroutines.withTimeout(2000L) { job1.join() }
            assertEquals(com.melmeligy.mediadownloader.domain.model.DownloadStatus.PAUSED, currentItem?.status)
            assertEquals(cachedPath, currentItem?.thumbnailUrl)
            org.junit.Assert.assertTrue("Paused download must keep cached thumbnail on disk", cachedFile.exists())

            // Run 2: resume -> complete -> thumbnail path & file must remain preserved
            dm.resume(201L)
            assertEquals(cachedPath, currentItem?.thumbnailUrl)
            dm.runQueue { }
            assertEquals(com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED, currentItem?.status)
            assertEquals(cachedPath, currentItem?.thumbnailUrl)
            org.junit.Assert.assertTrue("Completed download must keep cached thumbnail on disk", cachedFile.exists())

            // Cancel a separate item and verify its cached thumbnail is deleted
            val cancelThumb = thumbCache.thumbnailFile(202L).apply {
                parentFile?.mkdirs()
                writeBytes(jpegBytes)
            }
            dm.cancel(202L)
            org.junit.Assert.assertFalse("Cancelled download must clean up cached thumbnail", cancelThumb.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun thumbnailLifecycle_fallsBackToLocalVideoFrame_whenNoRemoteThumbnailExists() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_thumb_frame_fallback_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir
            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings(maxConcurrentDownloads = 1)
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)
            coEvery { settingsRepository.current() } returns settings

            val thumbsDir = java.io.File(tempDir, "thumbnails")
            val frameExtractedSources = mutableListOf<String>()
            val thumbCache = DownloadThumbnailCache(thumbsDir, okhttp3.OkHttpClient()) { srcVideo, outJpeg ->
                frameExtractedSources += srcVideo.name
                outJpeg.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                true
            }
            val dm = DownloadManager(
                context = context,
                repository = repository,
                settingsRepository = settingsRepository,
                segmentedDownloader = segmentedDownloader,
                hlsDownloader = hlsDownloader,
                videoAudioMuxer = videoAudioMuxer,
                mediaStoreWriter = mediaStoreWriter,
                notifier = notifier,
                dispatchers = dispatchers,
                thumbnailCache = thumbCache
            )

            var currentItem: DownloadItem? = DownloadItem(
                id = 205L,
                title = "No Poster Video",
                sourceUrl = "https://cdn.example/raw.mp4",
                remoteUrl = "https://cdn.example/raw.mp4",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "720p",
                thumbnailUrl = null,
                isHls = false,
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            var claimed = false
            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                val item = currentItem
                if (!claimed && item != null) {
                    claimed = true
                    val running = item.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    currentItem = running
                    listOf(running)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                val item = currentItem
                kotlinx.coroutines.flow.flowOf(if (item != null && item.isActive) listOf(item) else emptyList())
            }
            coEvery { repository.getById(205L) } answers { currentItem }
            coEvery { repository.updateStatus(205L, any()) } answers {
                currentItem = currentItem?.copy(status = secondArg())
            }
            coEvery { repository.updateThumbnail(205L, any()) } answers {
                currentItem = currentItem?.copy(thumbnailUrl = secondArg())
            }
            coEvery { repository.setCompleted(205L, any(), any()) } answers {
                currentItem = currentItem?.copy(
                    status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED,
                    contentUri = arg(1),
                    totalBytes = arg(2),
                    downloadedBytes = arg(2)
                )
            }
            coEvery {
                segmentedDownloader.download(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                val dest = arg<java.io.File>(2)
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(2048) { 0x05 })
            }
            coEvery { mediaStoreWriter.publish(any(), any(), any(), any(), any()) } returns "content://media/video/205"

            dm.runQueue { }

            val thumbPath = currentItem?.thumbnailUrl
            org.junit.Assert.assertNotNull("Local frame thumbnail must be persisted when no remote thumbnail exists", thumbPath)
            org.junit.Assert.assertTrue("Generated frame thumbnail must exist on disk", java.io.File(thumbPath!!).exists())
            org.junit.Assert.assertTrue("Frame extractor should have run on finalized video staging", frameExtractedSources.isNotEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun thumbnailLifecycle_extractsMidDownloadVideoFrameForProgressiveMp4BeforeCompletionOrOnPause() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("dm_prog_mid_frame_test").toFile()
        try {
            every { context.getExternalFilesDir(null) } returns tempDir
            every { context.filesDir } returns tempDir
            val settings = com.melmeligy.mediadownloader.domain.model.AppSettings(maxConcurrentDownloads = 1)
            every { settingsRepository.settings } returns kotlinx.coroutines.flow.flowOf(settings)
            coEvery { settingsRepository.current() } returns settings

            val thumbsDir = java.io.File(tempDir, "thumbnails")
            val midFrameExtracted = kotlinx.coroutines.CompletableDeferred<String>()
            val thumbCache = DownloadThumbnailCache(
                thumbsDir = thumbsDir,
                client = okhttp3.OkHttpClient(),
                frameExtractor = { srcVideo, outJpeg ->
                    if (srcVideo.exists() && srcVideo.length() >= 64 * 1024L) {
                        outJpeg.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                        true
                    } else {
                        false
                    }
                },
                remoteFrameExtractor = { _, _, _ -> false }
            )
            val dm = DownloadManager(
                context = context,
                repository = repository,
                settingsRepository = settingsRepository,
                segmentedDownloader = segmentedDownloader,
                hlsDownloader = hlsDownloader,
                videoAudioMuxer = videoAudioMuxer,
                mediaStoreWriter = mediaStoreWriter,
                notifier = notifier,
                dispatchers = dispatchers,
                thumbnailCache = thumbCache
            )

            var currentItem: DownloadItem? = DownloadItem(
                id = 206L,
                title = "Progressive 720p Episode",
                sourceUrl = "https://wecima.here/watch/ep5",
                remoteUrl = "https://cdn.wecima.here/ep5-720p.mp4",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "High quality (720p)",
                thumbnailUrl = null,
                isHls = false,
                totalBytes = 1070386381L,
                status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.QUEUED,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            var claimed = false
            coEvery { repository.resetInterrupted() } returns Unit
            coEvery { repository.claimNextQueued(any()) } answers {
                val item = currentItem
                if (!claimed && item != null) {
                    claimed = true
                    val running = item.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.RUNNING)
                    currentItem = running
                    listOf(running)
                } else emptyList()
            }
            every { repository.observeActive() } answers {
                val item = currentItem
                kotlinx.coroutines.flow.flowOf(if (item != null && item.isActive) listOf(item) else emptyList())
            }
            coEvery { repository.getById(206L) } answers { currentItem }
            coEvery { repository.updateStatus(206L, any()) } answers {
                currentItem = currentItem?.copy(status = secondArg())
            }
            coEvery { repository.updateProgress(206L, any(), any(), any(), any()) } answers {
                currentItem = currentItem?.copy(
                    downloadedBytes = arg(1),
                    totalBytes = arg(2),
                    speedBytesPerSec = arg(3),
                    etaSeconds = arg(4)
                )
            }
            coEvery { repository.updateThumbnail(206L, any()) } answers {
                val path = secondArg<String>()
                currentItem = currentItem?.copy(thumbnailUrl = path)
                midFrameExtracted.complete(path)
            }
            coEvery {
                segmentedDownloader.download(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                val dest = arg<java.io.File>(2)
                val onProgress = arg<suspend (DownloadProgress) -> Unit>(6)
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(128 * 1024) { 0x11 })
                onProgress(DownloadProgress(downloaded = 128 * 1024L, total = 1070386381L, speed = 512_000L, eta = 2000L))
                // Wait until mid-download frame thumbnail is persisted while download is still active
                kotlinx.coroutines.withTimeout(2000L) { midFrameExtracted.await() }
            }
            coEvery { mediaStoreWriter.publish(any(), any(), any(), any(), any()) } returns "content://media/video/206"
            coEvery { repository.setCompleted(206L, any(), any()) } answers {
                currentItem = currentItem?.copy(status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED)
            }

            dm.runQueue { }

            val persistedPath = kotlinx.coroutines.withTimeout(2000L) { midFrameExtracted.await() }
            org.junit.Assert.assertTrue(java.io.File(persistedPath).exists())
            org.junit.Assert.assertEquals(persistedPath, currentItem?.thumbnailUrl)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
