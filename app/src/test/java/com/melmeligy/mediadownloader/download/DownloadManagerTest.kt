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
import kotlinx.coroutines.test.runTest
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
}
