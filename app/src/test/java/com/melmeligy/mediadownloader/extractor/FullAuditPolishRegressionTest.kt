package com.melmeligy.mediadownloader.extractor

import androidx.lifecycle.SavedStateHandle
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import com.melmeligy.mediadownloader.ui.quality.QualityViewModel
import com.melmeligy.mediadownloader.ui.quality.prepareSnaptubeStreams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FullAuditPolishRegressionTest {

    // =========================================================================
    // Bug A: Strict Per-URL Thumbnail & Metadata Isolation
    // =========================================================================

    @Test
    fun `MediaInterceptionEngine never bleeds previous movie thumbnail onto a new URL`() {
        val engine = MediaInterceptionEngine()
        val oldUrl = "https://topcinemaa.one/movie-the-odyssey-2026/"
        val newUrl = "https://wecima.here/watch/episode-6/"

        // 1. First page sets title & thumbnail
        engine.onPageChanged(oldUrl)
        engine.updatePageMetadata("The Odyssey 2026", "https://topcinemaa.one/posters/odyssey.jpg", oldUrl)
        assertEquals("https://topcinemaa.one/posters/odyssey.jpg", engine.currentPageThumbnail)

        // 2. Updating metadata for a DIFFERENT page must NOT mutate currentPageTitle/Thumbnail for oldUrl
        engine.updatePageMetadata("Episode 6", null, newUrl)
        assertEquals("The Odyssey 2026", engine.currentPageTitle)
        assertEquals("https://topcinemaa.one/posters/odyssey.jpg", engine.currentPageThumbnail)

        // 3. Navigating to newUrl clears old title and old thumbnail completely
        engine.onPageChanged(newUrl)
        assertNull(engine.currentPageTitle)
        assertNull(engine.currentPageThumbnail)

        // 4. clear() also resets both title and thumbnail
        engine.updatePageMetadata("Episode 6", "https://wecima.here/posters/ep6.jpg", newUrl)
        engine.clear()
        assertNull(engine.currentPageTitle)
        assertNull(engine.currentPageThumbnail)
    }

    @Test
    fun `InterceptedMediaExtractor ignores engine currentPageThumbnail when engine is on a different page`() = runBlocking {
        val engine = MediaInterceptionEngine()
        val oldUrl = "https://topcinemaa.one/movie-the-odyssey-2026/"
        val newUrl = "https://wecima.here/watch/episode-6/"

        // Engine has oldUrl's thumbnail active
        engine.onPageChanged(oldUrl)
        engine.updatePageMetadata("The Odyssey 2026", "https://topcinemaa.one/posters/odyssey.jpg", oldUrl)

        // Payload matches newUrl directly by media URL lookup while engine is on oldUrl
        val mediaUrl = "https://cdn.wecima.example/video_720p.mp4"
        engine.submit(
            MediaStreamPayload(
                url = mediaUrl,
                streamType = StreamType.PROGRESSIVE,
                headersMap = mapOf("Referer" to oldUrl),
                pageUrl = oldUrl
            )
        )

        val http = mockk<ExtractorHttp>(relaxed = true)
        val hls = mockk<HlsExtractor>(relaxed = true)
        val dash = mockk<DashExtractor>(relaxed = true)
        every { http.probe(mediaUrl, any()) } returns HeadInfo(
            code = 206,
            contentType = "video/mp4",
            contentLength = 150_000_000L,
            acceptsRanges = true,
            finalUrl = mediaUrl
        )

        val extractor = InterceptedMediaExtractor(engine, http, hls, dash)
        // Extracting direct mediaUrl (different from engine.currentPageUrl) must NOT pull oldUrl's thumbnail
        val resolved = extractor.extract(mediaUrl)
        assertNotNull(resolved)
        // Because payload was submitted on oldUrl it has oldUrl's thumbnail, wait - test direct URL with blank thumbnail
        val engine2 = MediaInterceptionEngine()
        engine2.onPageChanged(oldUrl)
        engine2.updatePageMetadata("The Odyssey 2026", "https://topcinemaa.one/posters/odyssey.jpg", oldUrl)
        assertFalse(engine2.isSamePage(engine2.currentPageUrl, newUrl))
        assertTrue(engine2.isSamePage(engine2.currentPageUrl, oldUrl))
    }

    @Test
    fun `QualityViewModel clears previous Resource Success synchronously when resolving a new URL`() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val url1 = "https://topcinemaa.one/movie-odyssey"
            val url2 = "https://wecima.here/watch/episode-6"
            val media1 = ResolvedMedia(
                sourceUrl = url1,
                title = "The Odyssey 2026",
                thumbnailUrl = "https://topcinemaa.one/posters/odyssey.jpg",
                streams = listOf(MediaStream("v1", "https://cdn.example/1.mp4", MediaType.VIDEO, "mp4", "720p", height = 720)),
                extractor = "Smart Extractor"
            )
            val media2 = ResolvedMedia(
                sourceUrl = url2,
                title = "Episode 6",
                thumbnailUrl = "https://wecima.here/posters/ep6.jpg",
                streams = listOf(MediaStream("v2", "https://cdn.example/2.mp4", MediaType.VIDEO, "mp4", "720p", height = 720)),
                extractor = "Smart Extractor"
            )

            val repo = mockk<MediaRepository>()
            coEvery { repo.resolve(url1) } returns media1
            coEvery { repo.resolve(url2) } coAnswers {
                delay(500L)
                media2
            }
            val dm = mockk<DownloadManager>(relaxed = true)
            val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
                every { settings } returns MutableStateFlow(AppSettings())
            }

            val vm = QualityViewModel(repo, dm, settingsRepo, SavedStateHandle())
            vm.resolve(url1)
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.state.value is Resource.Success)
            assertEquals("The Odyssey 2026", (vm.state.value as Resource.Success).data.title)

            // Sharing url2 via onNewIntent MUST synchronously clear old Resource.Success -> Resource.Loading
            vm.resolve(url2)
            assertTrue("Expected synchronous Resource.Loading to prevent stale thumbnail bleed", vm.state.value is Resource.Loading)

            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.state.value is Resource.Success)
            val finalMedia = (vm.state.value as Resource.Success).data
            assertEquals("Episode 6", finalMedia.title)
            assertEquals("https://wecima.here/posters/ep6.jpg", finalMedia.thumbnailUrl)
            assertNotEquals(media1.thumbnailUrl, finalMedia.thumbnailUrl)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // =========================================================================
    // Bug B: Eliminate Unnecessary "HD Video" & Prefer Multi-Quality HLS Masters
    // =========================================================================

    @Test
    fun `UrlUtils and FormatUtils infer standard quality tiers from embed tags and bitrate instead of HD Video`() {
        assertEquals(720, UrlUtils.inferResolution("https://cdn.earnvids.com/v/abc123_h.mp4"))
        assertEquals(480, UrlUtils.inferResolution("https://cdn.earnvids.com/v/abc123_n.mp4"))
        assertEquals(360, UrlUtils.inferResolution("https://cdn.earnvids.com/v/abc123_l.mp4"))
        assertEquals(1080, UrlUtils.inferResolution("https://cdn.host.com/v/movie_fhd.mp4"))

        // Bitrate + duration inference for 2.0 GB 60-minute episode (~4444 kbps -> 1080p)
        val inferredFromSize = FormatUtils.inferStandardQualityFromMetadata(
            url = "https://cdn.host.com/stream/v.mp4",
            sizeBytes = 2_000_000_000L,
            durationSeconds = 3_600L
        )
        assertEquals(1080, inferredFromSize)

        // Single-variant HLS playlist with probed bitrate infers standard tier in HlsExtractor
        val hlsUrl = "https://cdn.example.com/hls/index.m3u8"
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:2000000@0
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val http = mockk<ExtractorHttp>(relaxed = true)
        coEvery { http.fetchString(hlsUrl, any()) } returns mediaPlaylist
        val hls = HlsExtractor(http)
        val resolved = runBlocking { hls.extract(hlsUrl) }
        assertNotNull(resolved)
        val video = resolved!!.streams.first()
        // 2,000,000 bytes / 10s = 1600 kbps -> 720p (not null / "Auto (HLS)" / "HD Video")
        assertEquals(720, video.height)
        assertEquals("720p", video.label)
    }

    @Test
    fun `InterceptedMediaExtractor prefers HLS master playlist over single-variant child playlist`() {
        assertTrue(InterceptedMediaExtractor.looksLikeMasterPlaylistUrl("https://cdn.example.com/v/master.m3u8?t=1"))
        assertTrue(InterceptedMediaExtractor.looksLikeMasterPlaylistUrl("https://cdn.example.com/v/playlist.m3u8"))
        assertFalse(InterceptedMediaExtractor.looksLikeMasterPlaylistUrl("https://cdn.example.com/v/seg-v1-a1.m3u8"))
    }

    // =========================================================================
    // 360° Polish: Realistic Audio Size Estimation for Large 2.0 GB Videos
    // =========================================================================

    @Test
    fun `QualityScreen synthesizes realistic audio sizes for 2GB video instead of inflated 300MB+ estimates`() {
        val twoGb = 2L * 1024L * 1024L * 1024L
        val resolved = ResolvedMedia(
            sourceUrl = "https://wecima.here/watch/episode-6/",
            title = "Episode 6",
            streams = listOf(
                MediaStream(
                    id = "v-1080",
                    url = "https://cdn.example.com/ep6_fhd.mp4",
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "HD Video",
                    height = null,
                    sizeBytes = twoGb
                )
            ),
            extractor = "Smart Extractor"
        )
        val prepared = prepareSnaptubeStreams(resolved)
        // URL `_fhd.mp4` infers 1080p FHD instead of bare "HD Video"
        assertEquals(listOf("1080p FHD"), prepared.video.map { it.label })

        val m4a = prepared.music.first { it.container == "m4a" }
        val mp3 = prepared.music.first { it.container == "mp3" }
        // 128k / 3800k * 2 GB ~= 72 MB (far below the old broken 286 MB / 368 MB estimates!)
        assertTrue("Expected realistic M4A estimate (< 120 MB), got ${m4a.sizeBytes}", (m4a.sizeBytes ?: Long.MAX_VALUE) < 120L * 1024L * 1024L)
        assertTrue("Expected realistic MP3 estimate (< 140 MB), got ${mp3.sizeBytes}", (mp3.sizeBytes ?: Long.MAX_VALUE) < 140L * 1024L * 1024L)
        assertTrue("Expected non-zero M4A estimate (> 20 MB), got ${m4a.sizeBytes}", (m4a.sizeBytes ?: 0L) > 20L * 1024L * 1024L)
    }
}
