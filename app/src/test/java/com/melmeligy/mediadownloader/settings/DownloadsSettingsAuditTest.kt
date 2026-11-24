package com.melmeligy.mediadownloader.settings

import android.content.Context
import android.os.Environment
import androidx.work.WorkManager
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.JsonUtils
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.download.DownloadNotifier
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.Jump3rMp3Encoder
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.download.VideoAudioMuxer
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import com.melmeligy.mediadownloader.extractor.HlsExtractor
import com.melmeligy.mediadownloader.extractor.YouTubeExtractor
import com.melmeligy.mediadownloader.ui.quality.prepareSnaptubeStreams
import com.melmeligy.mediadownloader.ui.quality.selectDefaultStream
import com.melmeligy.mediadownloader.ui.quality.selectPreferredAudioStream
import com.melmeligy.mediadownloader.ui.quality.selectPreferredVideoStream
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsSettingsAuditTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        every { context.getExternalFilesDir(null) } returns tempFolder.root
        every { context.filesDir } returns tempFolder.root
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // 1. Default Video Quality: Exact Match & Nearest-Available Fallbacks
    // =========================================================================

    @Test
    fun defaultVideoQuality_exactMatchSelectedAndSurfacedInQuickVideo() {
        val streams = listOf(
            MediaStream("v360", "https://cdn.test/360.mp4", MediaType.VIDEO, "mp4", "360p", height = 360, sizeBytes = 3_000_000L, headers = mapOf("X-Internal-Quick" to "true")),
            MediaStream("v480", "https://cdn.test/480.mp4", MediaType.VIDEO, "mp4", "480p", height = 480, sizeBytes = 6_000_000L),
            MediaStream("v720", "https://cdn.test/720.mp4", MediaType.VIDEO, "mp4", "720p", height = 720, sizeBytes = 12_000_000L, headers = mapOf("X-Internal-Quick" to "true")),
            MediaStream("v1080", "https://cdn.test/1080.mp4", MediaType.VIDEO, "mp4", "1080p", height = 1080, sizeBytes = 25_000_000L)
        )
        val resolved = ResolvedMedia("https://youtube.com/watch?v=abc", "Sample", streams = streams, extractor = "YouTube")

        val prepared = prepareSnaptubeStreams(resolved, preferredVideoQuality = VideoQuality.P480)
        val selected = selectDefaultStream(prepared, preferredVideoQuality = VideoQuality.P480)

        assertNotNull(selected)
        assertEquals("v480", selected?.id)
        assertEquals(480, selected?.height)
        assertTrue(
            "480p preferred stream must be visible in compact quickVideo list",
            prepared.quickVideo.any { it.id == "v480" }
        )
    }

    @Test
    fun defaultVideoQuality_fallsBackToClosestLowerOrNearestHigherWhenExactMissing() {
        // Case A: preference 480p with [360p, 720p, 1080p] -> closest lower (360p)
        val withLower = listOf(
            MediaStream("v360", "https://cdn.test/360.mp4", MediaType.VIDEO, "mp4", "360p", height = 360, sizeBytes = 3_000_000L),
            MediaStream("v720", "https://cdn.test/720.mp4", MediaType.VIDEO, "mp4", "720p", height = 720, sizeBytes = 12_000_000L),
            MediaStream("v1080", "https://cdn.test/1080.mp4", MediaType.VIDEO, "mp4", "1080p", height = 1080, sizeBytes = 25_000_000L)
        )
        assertEquals("v360", selectPreferredVideoStream(withLower, VideoQuality.P480)?.id)

        // Case B: preference 480p with [720p] only (e.g., TikTok single stream) -> nearest available (720p)
        val only720 = listOf(
            MediaStream("tt-single", "https://cdn.test/tt720.mp4", MediaType.VIDEO, "mp4", "720p HD", height = 720, sizeBytes = 8_000_000L)
        )
        assertEquals("tt-single", selectPreferredVideoStream(only720, VideoQuality.P480)?.id)

        // Case C: preference 480p with [720p, 1080p] (TikTok HD+SD) -> nearest available (720p, NOT 1080p)
        val higherOnly = listOf(
            MediaStream("tt-sd", "https://cdn.test/tt720.mp4", MediaType.VIDEO, "mp4", "720p HD", height = 720, sizeBytes = 8_000_000L),
            MediaStream("tt-hd", "https://cdn.test/tt1080.mp4", MediaType.VIDEO, "mp4", "1080p FHD", height = 1080, sizeBytes = 16_000_000L)
        )
        assertEquals("tt-sd", selectPreferredVideoStream(higherOnly, VideoQuality.P480)?.id)

        // Case D: preference HIGHEST with [360p, 720p, 1080p, 2160p] -> 2160p
        val upTo4k = withLower + MediaStream("v2160", "https://cdn.test/4k.webm", MediaType.VIDEO, "webm", "High quality (4K)", height = 2160, sizeBytes = 90_000_000L)
        val prep4k = prepareSnaptubeStreams(
            ResolvedMedia("https://youtube.com/watch?v=4k", "4K", streams = upTo4k, extractor = "YouTube"),
            preferredVideoQuality = VideoQuality.HIGHEST
        )
        assertEquals("v2160", selectDefaultStream(prep4k, preferredVideoQuality = VideoQuality.HIGHEST)?.id)
        assertTrue(prep4k.quickVideo.any { it.id == "v2160" })

        // Case E: height missing on MediaStream -> parses height from label ("480p")
        val labelOnly = listOf(
            MediaStream("l480", "https://cdn.test/a.mp4", MediaType.VIDEO, "mp4", "480p", height = null),
            MediaStream("l720", "https://cdn.test/b.mp4", MediaType.VIDEO, "mp4", "High quality (720p)", height = null)
        )
        assertEquals("l480", selectPreferredVideoStream(labelOnly, VideoQuality.P480)?.id)
    }

    // =========================================================================
    // 2. Default Audio Bitrate: Alignment, Pre-Selection & Transcoder Wiring
    // =========================================================================

    @Test
    fun defaultAudioBitrate_allSettingsOptionsMatchJump3rAndYouTubeExtractor() = runBlocking {
        // Every AudioBitrate setting must be natively supported by Jump3rMp3Encoder (no mismatch/clamping drift)
        for (option in AudioBitrate.entries) {
            assertEquals(
                "Jump3r must natively support ${option.label}",
                option.kbps,
                Jump3rMp3Encoder.clampBitrateKbps(option.kbps, 44100)
            )
        }

        // YouTubeExtractor must expose an exact MP3 option for every AudioBitrate enum value
        val innerTubeJson = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"title": "Audio Bitrate Test", "lengthSeconds": "100"},
              "streamingData": {
                "adaptiveFormats": [
                  {"itag": 137, "url": "https://googlevideo.test/v1080", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "width": 1920, "height": 1080, "bitrate": 4000000, "contentLength": "50000000"},
                  {"itag": 251, "url": "https://googlevideo.test/opus", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "contentLength": "2000000"},
                  {"itag": 140, "url": "https://googlevideo.test/m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "contentLength": "1600000"}
                ]
              }
            }
        """.trimIndent()
        val http = mockk<ExtractorHttp>()
        every { http.postJson(any(), any(), any()) } returns innerTubeJson
        val yt = YouTubeExtractor(http, HlsExtractor(http), TestDispatcherProvider())
        val resolved = yt.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")!!
        val ytMp3Bitrates = resolved.streams
            .filter { it.type == MediaType.AUDIO && it.container == "mp3" }
            .mapNotNull { it.bitrateKbps }
            .toSet()

        for (option in AudioBitrate.entries) {
            assertTrue(
                "YouTubeExtractor must include exact MP3 option for ${option.kbps} kbps, found $ytMp3Bitrates",
                option.kbps in ytMp3Bitrates
            )
        }

        // Compact sheet quickMusic must surface the user's preferred MP3 bitrate (e.g., 192 kbps or 320 kbps)
        val prep192 = prepareSnaptubeStreams(resolved, preferredAudioBitrate = AudioBitrate.KBPS192)
        assertTrue(prep192.quickMusic.any { it.container == "mp3" && it.bitrateKbps == 192 })

        val prep320 = prepareSnaptubeStreams(resolved, preferredAudioBitrate = AudioBitrate.KBPS320)
        assertTrue(prep320.quickMusic.any { it.container == "mp3" && it.bitrateKbps == 320 })
    }

    @Test
    fun defaultAudioBitrate_synthesizesAndSelectsPreferredBitrateAndWiresToMuxer() = runTest {
        // 1. Synthesized MP3 from video uses preferredAudioBitrate (e.g. 320 kbps)
        val videoOnly = ResolvedMedia(
            sourceUrl = "https://example.com/v",
            title = "Clip",
            streams = listOf(
                MediaStream(
                    id = "v1",
                    url = "https://example.com/v.mp4",
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "720p",
                    height = 720,
                    sizeBytes = 20_000_000L,
                    headers = mapOf("X-Internal-Duration-Seconds" to "100")
                )
            ),
            extractor = "Generic"
        )
        val prepared = prepareSnaptubeStreams(videoOnly, preferredAudioBitrate = AudioBitrate.KBPS320)
        val synthMp3 = prepared.music.first { it.container == "mp3" }
        assertEquals(320, synthMp3.bitrateKbps)
        assertEquals(4_000_000L, synthMp3.sizeBytes) // 320_000 * 100 / 8
        assertTrue(synthMp3.headers["X-Internal-Subtitle"]?.contains("320K") == true)

        // 2. Audio-only page selects exact preferred bitrate (or closest lower / nearest higher)
        val audioOptions = listOf(
            MediaStream("a128", "https://cdn.test/a1", MediaType.AUDIO, "mp3", "Classic MP3 (128K)", bitrateKbps = 128),
            MediaStream("a192", "https://cdn.test/a2", MediaType.AUDIO, "mp3", "Classic MP3 (192K)", bitrateKbps = 192),
            MediaStream("a320", "https://cdn.test/a3", MediaType.AUDIO, "mp3", "Classic MP3 (320K)", bitrateKbps = 320)
        )
        assertEquals("a192", selectPreferredAudioStream(audioOptions, AudioBitrate.KBPS192)?.id)
        assertEquals("a128", selectPreferredAudioStream(audioOptions, AudioBitrate.KBPS160)?.id) // closest lower

        // 3. DownloadManager wires Settings defaultAudioBitrate when stream bitrate is absent and passes it to muxer
        val fakeRepo = InMemoryDownloadRepository()
        val fakeSettings = FakeSettingsRepository(
            AppSettings(defaultAudioBitrate = AudioBitrate.KBPS320, saveSubfolder = "MyAudio")
        )
        val segmented = mockk<SegmentedDownloader>()
        val muxer = mockk<VideoAudioMuxer>()
        val writer = mockk<MediaStoreWriter>()
        val notifier = mockk<DownloadNotifier>(relaxed = true)

        coEvery { segmented.download(any(), any(), any(), any(), any(), any(), any()) } answers {
            val dest = arg<File>(2)
            dest.parentFile?.mkdirs()
            dest.writeBytes(ByteArray(1024) { 1 })
        }
        val capturedBitrate = AtomicInteger(-1)
        every { muxer.extractAudioOnly(any(), any(), any(), any(), any()) } answers {
            val out = arg<File>(1)
            capturedBitrate.set(arg<Int>(3))
            arg<((Float) -> Unit)?>(4)?.invoke(1f)
            out.parentFile?.mkdirs()
            out.writeBytes(ByteArray(512) { 2 })
            true
        }
        coEvery { writer.publish(any(), any(), any(), any(), any()) } returns "content://media/external/audio/1"

        val manager = DownloadManager(
            context = context,
            repository = fakeRepo,
            settingsRepository = fakeSettings,
            segmentedDownloader = segmented,
            hlsDownloader = mockk(relaxed = true),
            videoAudioMuxer = muxer,
            mediaStoreWriter = writer,
            notifier = notifier,
            dispatchers = TestDispatcherProvider()
        )

        val audioStreamWithoutBitrate = MediaStream(
            id = "raw-mp3",
            url = "https://cdn.test/song.m4a",
            type = MediaType.AUDIO,
            container = "mp3",
            label = "Classic MP3",
            bitrateKbps = null
        )
        val id = manager.enqueue(ResolvedMedia("https://cdn.test", "Song"), audioStreamWithoutBitrate)
        val enqueuedItem = fakeRepo.getById(id)!!
        val savedHeaders = JsonUtils.jsonToMap(enqueuedItem.headersJson)
        assertEquals("320", savedHeaders["X-Internal-Target-Bitrate-Kbps"])

        manager.runQueue { }
        assertEquals(320, capturedBitrate.get())
    }

    // =========================================================================
    // 3. Max Concurrent Downloads: Cap Enforcement & Live Dynamic Update
    // =========================================================================

    @Test
    fun maxConcurrentDownloads_enforcesCapAndReactsImmediatelyToIncreaseWithoutRestart() = runBlocking {
        val fakeRepo = InMemoryDownloadRepository()
        val fakeSettings = FakeSettingsRepository(AppSettings(maxConcurrentDownloads = 1))
        val segmented = mockk<SegmentedDownloader>()
        val writer = mockk<MediaStoreWriter>()
        val notifier = mockk<DownloadNotifier>(relaxed = true)

        val activeCount = AtomicInteger(0)
        val peakWhileCap1 = AtomicInteger(0)
        val peakOverall = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseAll = CompletableDeferred<Unit>()
        val reachedThreeConcurrent = CompletableDeferred<Unit>()

        coEvery { segmented.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val dest = arg<File>(2)
            val nowActive = activeCount.incrementAndGet()
            peakOverall.accumulateAndGet(nowActive) { a, b -> maxOf(a, b) }
            if (fakeSettings.current().maxConcurrentDownloads == 1) {
                peakWhileCap1.accumulateAndGet(nowActive) { a, b -> maxOf(a, b) }
            }
            if (!firstStarted.isCompleted) firstStarted.complete(Unit)
            if (nowActive >= 3 && !reachedThreeConcurrent.isCompleted) {
                reachedThreeConcurrent.complete(Unit)
            }
            try {
                releaseAll.await()
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(128) { 7 })
            } finally {
                activeCount.decrementAndGet()
            }
        }
        coEvery { writer.publish(any(), any(), any(), any(), any()) } returns "content://media/external/video/1"

        val manager = DownloadManager(
            context = context,
            repository = fakeRepo,
            settingsRepository = fakeSettings,
            segmentedDownloader = segmented,
            hlsDownloader = mockk(relaxed = true),
            videoAudioMuxer = mockk(relaxed = true),
            mediaStoreWriter = writer,
            notifier = notifier,
            dispatchers = TestDispatcherProvider()
        )

        repeat(4) { idx ->
            manager.enqueue(
                ResolvedMedia("https://cdn.test/$idx", "Video $idx"),
                MediaStream("v$idx", "https://cdn.test/$idx.mp4", MediaType.VIDEO, "mp4", "720p")
            )
        }

        val queueJob = launch { manager.runQueue { } }

        // Wait until first download is running under cap = 1
        firstStarted.await()
        delay(80)
        assertEquals("Only 1 download may run while maxConcurrentDownloads=1", 1, activeCount.get())
        assertEquals(1, peakWhileCap1.get())

        // Increase cap dynamically to 3 WITHOUT restarting runQueue -> must immediately launch 2 more
        fakeSettings.setMaxConcurrent(3)
        reachedThreeConcurrent.await()
        assertEquals(3, activeCount.get())

        // Let all 4 finish
        releaseAll.complete(Unit)
        queueJob.join()

        assertEquals(3, peakOverall.get())
        assertTrue(fakeRepo.allItems().all { it.status == DownloadStatus.COMPLETED })
    }

    // =========================================================================
    // 4. Auto-Retry Attempts: Configured Limit, 0 Retries & Manual Cancel Exclusion
    // =========================================================================

    @Test
    fun autoRetry_zeroRetriesFailsImmediatelyWithoutRetrying() = runTest {
        val fakeRepo = InMemoryDownloadRepository()
        val fakeSettings = FakeSettingsRepository(AppSettings(retryCount = 0))
        val segmented = mockk<SegmentedDownloader>()
        val attempts = AtomicInteger(0)

        coEvery { segmented.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            attempts.incrementAndGet()
            throw MediaException(AppError.NO_INTERNET, "Offline")
        }

        val manager = DownloadManager(
            context = context,
            repository = fakeRepo,
            settingsRepository = fakeSettings,
            segmentedDownloader = segmented,
            hlsDownloader = mockk(relaxed = true),
            videoAudioMuxer = mockk(relaxed = true),
            mediaStoreWriter = mockk(relaxed = true),
            notifier = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider()
        )

        val id = manager.enqueue(
            ResolvedMedia("https://cdn.test/fail0", "Fail Zero"),
            MediaStream("f0", "https://cdn.test/fail0.mp4", MediaType.VIDEO, "mp4", "480p")
        )
        manager.runQueue { }

        assertEquals("0 retries must execute exactly 1 initial attempt", 1, attempts.get())
        val item = fakeRepo.getById(id)!!
        assertEquals(DownloadStatus.FAILED, item.status)
        assertEquals(0, item.retryCount)
    }

    @Test
    fun autoRetry_retriesExactConfiguredCountThenFails() = runTest {
        val fakeRepo = InMemoryDownloadRepository()
        val fakeSettings = FakeSettingsRepository(AppSettings(retryCount = 2))
        val segmented = mockk<SegmentedDownloader>()
        val attempts = AtomicInteger(0)

        coEvery { segmented.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            attempts.incrementAndGet()
            throw MediaException(AppError.NO_INTERNET, "Temporary disconnect")
        }

        val manager = DownloadManager(
            context = context,
            repository = fakeRepo,
            settingsRepository = fakeSettings,
            segmentedDownloader = segmented,
            hlsDownloader = mockk(relaxed = true),
            videoAudioMuxer = mockk(relaxed = true),
            mediaStoreWriter = mockk(relaxed = true),
            notifier = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider()
        )

        val id = manager.enqueue(
            ResolvedMedia("https://cdn.test/fail2", "Fail Two"),
            MediaStream("f2", "https://cdn.test/fail2.mp4", MediaType.VIDEO, "mp4", "480p")
        )
        manager.runQueue { }

        // 1 initial attempt + 2 retries = 3 total attempts
        assertEquals(3, attempts.get())
        val item = fakeRepo.getById(id)!!
        assertEquals(DownloadStatus.FAILED, item.status)
        assertEquals(2, item.retryCount)

        // Verify backoff schedule
        assertEquals(1_000L, DownloadManager.computeRetryBackoffMs(0))
        assertEquals(2_000L, DownloadManager.computeRetryBackoffMs(1))
        assertEquals(4_000L, DownloadManager.computeRetryBackoffMs(2))
        assertEquals(30_000L, DownloadManager.computeRetryBackoffMs(10))
    }

    @Test
    fun autoRetry_doesNotRetryManuallyCancelledDownloadOnIoExceptionOrDuringBackoff() = runBlocking {
        // Case A: Manual cancel while downloader throws IOException instead of CancellationException
        val repoA = InMemoryDownloadRepository()
        val settingsA = FakeSettingsRepository(AppSettings(retryCount = 4))
        val segmentedA = mockk<SegmentedDownloader>()
        val attemptsA = AtomicInteger(0)
        val startedA = CompletableDeferred<Unit>()
        lateinit var managerA: DownloadManager

        coEvery { segmentedA.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            attemptsA.incrementAndGet()
            startedA.complete(Unit)
            // Simulate socket closed IOException triggered by manual cancellation
            delay(50)
            throw IOException("Canceled")
        }

        managerA = DownloadManager(
            context = context,
            repository = repoA,
            settingsRepository = settingsA,
            segmentedDownloader = segmentedA,
            hlsDownloader = mockk(relaxed = true),
            videoAudioMuxer = mockk(relaxed = true),
            mediaStoreWriter = mockk(relaxed = true),
            notifier = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider()
        )
        val idA = managerA.enqueue(
            ResolvedMedia("https://cdn.test/cancelA", "Cancel A"),
            MediaStream("cA", "https://cdn.test/cancelA.mp4", MediaType.VIDEO, "mp4", "720p")
        )
        val jobA = launch { managerA.runQueue { } }
        startedA.await()
        managerA.cancel(idA)
        jobA.join()

        assertEquals("Cancelled download must not auto-retry", 1, attemptsA.get())
        assertNull("Cancelled download must be deleted from repository", repoA.getById(idA))

        // Case B: Manual cancel while sleeping inside retry backoff delay
        val repoB = InMemoryDownloadRepository()
        val settingsB = FakeSettingsRepository(AppSettings(retryCount = 3))
        val segmentedB = mockk<SegmentedDownloader>()
        val attemptsB = AtomicInteger(0)
        val failedOnce = CompletableDeferred<Unit>()

        coEvery { segmentedB.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            attemptsB.incrementAndGet()
            failedOnce.complete(Unit)
            throw MediaException(AppError.NO_INTERNET, "Network flap")
        }

        val managerB = DownloadManager(
            context = context,
            repository = repoB,
            settingsRepository = settingsB,
            segmentedDownloader = segmentedB,
            hlsDownloader = mockk(relaxed = true),
            videoAudioMuxer = mockk(relaxed = true),
            mediaStoreWriter = mockk(relaxed = true),
            notifier = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider()
        )
        val idB = managerB.enqueue(
            ResolvedMedia("https://cdn.test/cancelB", "Cancel B"),
            MediaStream("cB", "https://cdn.test/cancelB.mp4", MediaType.VIDEO, "mp4", "720p")
        )
        val jobB = launch { managerB.runQueue { } }
        failedOnce.await()
        delay(60) // Inside 1000ms retry backoff
        managerB.cancel(idB)
        jobB.join()

        assertEquals("Download cancelled during backoff must NOT execute retry", 1, attemptsB.get())
        assertNull("Download cancelled during backoff must be deleted", repoB.getById(idB))
    }

    // =========================================================================
    // 5. Save Location: Sanitization & Video/Audio/Image MediaStore Resolution
    // =========================================================================

    @Test
    fun saveLocation_sanitizesPathsAndResolvesVideoAudioAndMultiImageOutputs() = runTest {
        // Subfolder sanitization against traversal, backslashes, and illegal characters
        assertEquals("MyClips", AppSettings.sanitizeSubfolder("  /MyClips/  "))
        assertEquals("MyClips/HD", AppSettings.sanitizeSubfolder("""\MyClips\..\.\HD:*?"<>|"""))
        assertEquals("MediaDownloader", AppSettings.sanitizeSubfolder("///..///"))
        assertEquals("MediaDownloader", AppSettings.sanitizeSubfolder("   "))

        // Relative path mapping for Video, Audio, and Image
        val realWriter = MediaStoreWriter(context, TestDispatcherProvider())
        assertEquals(
            "Movies/CustomVault/",
            realWriter.resolveRelativePath(MediaType.VIDEO, "/CustomVault/")
        )
        assertEquals(
            "Music/CustomVault/",
            realWriter.resolveRelativePath(MediaType.AUDIO, "CustomVault")
        )
        assertEquals(
            "Pictures/CustomVault/",
            realWriter.resolveRelativePath(MediaType.IMAGE, """\..\CustomVault\""")
        )

        // End-to-end verification through DownloadManager for video, audio, and multi-image gallery batch
        val fakeRepo = InMemoryDownloadRepository()
        val fakeSettings = FakeSettingsRepository(AppSettings(saveSubfolder = "CustomVault"))
        val segmented = mockk<SegmentedDownloader>()
        val muxer = mockk<VideoAudioMuxer>()
        val writer = mockk<MediaStoreWriter>()
        val publishedCalls = CopyOnWriteArrayList<Pair<MediaType, String>>()

        coEvery { segmented.download(any(), any(), any(), any(), any(), any(), any()) } answers {
            val dest = arg<File>(2)
            dest.parentFile?.mkdirs()
            dest.writeBytes(ByteArray(256) { 5 })
        }
        every { muxer.extractAudioOnly(any(), any(), any(), any(), any()) } answers {
            val out = arg<File>(1)
            arg<((Float) -> Unit)?>(4)?.invoke(1f)
            out.parentFile?.mkdirs()
            out.writeBytes(ByteArray(256) { 6 })
            true
        }
        coEvery { writer.publish(any(), any(), any(), any(), any()) } answers {
            val type = arg<MediaType>(2)
            val subfolder = arg<String>(4)
            publishedCalls += (type to subfolder)
            "content://media/external/${type.name.lowercase()}/${publishedCalls.size}"
        }

        val manager = DownloadManager(
            context = context,
            repository = fakeRepo,
            settingsRepository = fakeSettings,
            segmentedDownloader = segmented,
            hlsDownloader = mockk(relaxed = true),
            videoAudioMuxer = muxer,
            mediaStoreWriter = writer,
            notifier = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider()
        )

        // Enqueue 1 video, 1 audio, and 2 gallery images
        manager.enqueue(
            ResolvedMedia("https://cdn.test/v", "MyVideo"),
            MediaStream("vid", "https://cdn.test/v.mp4", MediaType.VIDEO, "mp4", "720p")
        )
        manager.enqueue(
            ResolvedMedia("https://cdn.test/a", "MySong"),
            MediaStream("aud", "https://cdn.test/a.m4a", MediaType.AUDIO, "mp3", "Classic MP3", bitrateKbps = 192)
        )
        val galleryResolved = ResolvedMedia(
            sourceUrl = "https://instagram.com/p/ABC123/",
            title = "Carousel",
            extractor = "Instagram",
            postId = "ABC123",
            gallerySlides = listOf(
                GallerySlide("s1", 0, "https://cdn.test/1.jpg", "https://cdn.test/1.jpg", MediaType.IMAGE, "jpg"),
                GallerySlide("s2", 1, "https://cdn.test/2.jpg", "https://cdn.test/2.jpg", MediaType.IMAGE, "jpg")
            )
        )
        manager.enqueueBatch(galleryResolved, galleryResolved.gallerySlides)

        manager.runQueue { }

        assertEquals(4, publishedCalls.size)
        assertTrue(
            "Every video/audio/image output must publish to CustomVault",
            publishedCalls.all { it.second == "CustomVault" }
        )
        assertEquals(1, publishedCalls.count { it.first == MediaType.VIDEO })
        assertEquals(1, publishedCalls.count { it.first == MediaType.AUDIO })
        assertEquals(2, publishedCalls.count { it.first == MediaType.IMAGE })

        // Verify updating saveSubfolder immediately applies to subsequent downloads without restart
        fakeSettings.setSaveSubfolder("  /FreshFolder/Sub/  ")
        assertEquals("FreshFolder/Sub", fakeSettings.current().saveSubfolder)
        manager.enqueue(
            ResolvedMedia("https://cdn.test/v2", "SecondVideo"),
            MediaStream("vid2", "https://cdn.test/v2.mp4", MediaType.VIDEO, "mp4", "480p")
        )
        manager.runQueue { }
        assertEquals(MediaType.VIDEO to "FreshFolder/Sub", publishedCalls.last())
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state.asStateFlow()
        override suspend fun current(): AppSettings = state.value
        override suspend fun setThemeMode(mode: com.melmeligy.mediadownloader.domain.model.ThemeMode) {
            state.value = state.value.copy(themeMode = mode)
        }
        override suspend fun setDefaultVideoQuality(quality: VideoQuality) {
            state.value = state.value.copy(defaultVideoQuality = quality)
        }
        override suspend fun setDefaultAudioBitrate(bitrate: AudioBitrate) {
            state.value = state.value.copy(defaultAudioBitrate = bitrate)
        }
        override suspend fun setMaxConcurrent(count: Int) {
            state.value = state.value.copy(
                maxConcurrentDownloads = count.coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT)
            )
        }
        override suspend fun setRetryCount(count: Int) {
            state.value = state.value.copy(
                retryCount = count.coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES)
            )
        }
        override suspend fun setSaveSubfolder(name: String) {
            state.value = state.value.copy(saveSubfolder = AppSettings.sanitizeSubfolder(name))
        }
        override suspend fun setCustomSaveTreeUri(treeUri: String?) {
            state.value = state.value.copy(customSaveTreeUri = treeUri?.trim()?.takeIf { it.isNotEmpty() })
        }
        override suspend fun setCustomSaveOrganizeByType(organize: Boolean) {
            state.value = state.value.copy(customSaveOrganizeByType = organize)
        }
        override suspend fun setAdBlockEnabledForHost(host: String, enabled: Boolean) {
            val normalized = AppSettings.normalizeAdBlockHost(host) ?: return
            val next = if (enabled) state.value.adBlockDisabledHosts - normalized
            else state.value.adBlockDisabledHosts + normalized
            state.value = state.value.copy(adBlockDisabledHosts = next)
        }
    }

    private class InMemoryDownloadRepository : DownloadRepository {
        private val seq = AtomicLong(1L)
        private val items = ConcurrentHashMap<Long, DownloadItem>()

        fun allItems(): List<DownloadItem> = items.values.toList()

        override fun observeAll(): Flow<List<DownloadItem>> = flowOf(items.values.toList())
        override fun observeRecent(limit: Int): Flow<List<DownloadItem>> = flowOf(items.values.take(limit))
        override fun observeByType(type: MediaType): Flow<List<DownloadItem>> =
            flowOf(items.values.filter { it.type == type })
        override fun observeActive(): Flow<List<DownloadItem>> =
            flowOf(
                items.values.filter {
                    it.status == DownloadStatus.RUNNING ||
                        it.status == DownloadStatus.FINALIZING ||
                        it.status == DownloadStatus.QUEUED
                }
            )
        override fun observeCompletedByType(type: MediaType): Flow<List<DownloadItem>> =
            flowOf(items.values.filter { it.type == type && it.status == DownloadStatus.COMPLETED })

        override suspend fun getById(id: Long): DownloadItem? = items[id]

        override suspend fun enqueue(item: DownloadItem): Long {
            val id = seq.getAndIncrement()
            items[id] = item.copy(id = id)
            return id
        }

        override suspend fun update(item: DownloadItem) {
            items[item.id] = item
        }

        override suspend fun updateStatus(id: Long, status: DownloadStatus) {
            items.computeIfPresent(id) { _, old -> old.copy(status = status) }
        }

        override suspend fun updateThumbnail(id: Long, thumbnailUrl: String) {
            items.computeIfPresent(id) { _, old -> old.copy(thumbnailUrl = thumbnailUrl) }
        }

        override suspend fun requeue(id: Long, resetRetryCount: Boolean) {
            items.computeIfPresent(id) { _, old ->
                old.copy(
                    status = DownloadStatus.QUEUED,
                    errorType = null,
                    retryCount = if (resetRetryCount) 0 else old.retryCount
                )
            }
        }

        override suspend fun updateProgress(id: Long, downloaded: Long, total: Long, speed: Long, eta: Long) {
            items.computeIfPresent(id) { _, old ->
                old.copy(downloadedBytes = downloaded, totalBytes = total, speedBytesPerSec = speed, etaSeconds = eta)
            }
        }

        override suspend fun setError(id: Long, error: AppError) {
            items.computeIfPresent(id) { _, old ->
                old.copy(status = DownloadStatus.FAILED, errorType = error.name)
            }
        }

        override suspend fun incrementRetry(id: Long) {
            items.computeIfPresent(id) { _, old -> old.copy(retryCount = old.retryCount + 1) }
        }

        override suspend fun setCompleted(id: Long, contentUri: String, total: Long) {
            items.computeIfPresent(id) { _, old ->
                old.copy(status = DownloadStatus.COMPLETED, contentUri = contentUri, totalBytes = total, downloadedBytes = total)
            }
        }

        override suspend fun delete(id: Long) {
            items.remove(id)
        }

        override suspend fun clearCompleted() {
            items.entries.removeIf { it.value.status == DownloadStatus.COMPLETED }
        }

        override suspend fun claimNextQueued(limit: Int): List<DownloadItem> = synchronized(this) {
            val queued = items.values
                .filter { it.status == DownloadStatus.QUEUED }
                .sortedBy { it.id }
                .take(limit)
            queued.map {
                val running = it.copy(status = DownloadStatus.RUNNING)
                items[it.id] = running
                running
            }
        }

        override suspend fun resetInterrupted() {
            items.keys.forEach { id ->
                items.computeIfPresent(id) { _, old ->
                    if (old.status == DownloadStatus.RUNNING || old.status == DownloadStatus.FINALIZING) {
                        old.copy(status = DownloadStatus.QUEUED)
                    } else old
                }
            }
        }
    }
}
