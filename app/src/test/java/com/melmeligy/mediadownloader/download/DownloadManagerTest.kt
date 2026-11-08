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
}
