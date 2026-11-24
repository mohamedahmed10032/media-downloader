package com.melmeligy.mediadownloader.extractor

import androidx.lifecycle.SavedStateHandle
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.ui.common.formatDownloadPrimaryStatus
import com.melmeligy.mediadownloader.ui.quality.QualityViewModel
import com.melmeligy.mediadownloader.ui.quality.prepareSnaptubeStreams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class InstantTikTokQualityAndWeCimaRegressionTest {

    // =========================================================================
    // Issue 1: Near-Instantaneous TikTok & Social Share Sheet
    // =========================================================================

    @Test
    fun `TikTok short URL races tikwm immediately without waiting for redirect or blocking on size probes`() = runBlocking {
        val http = mockk<ExtractorHttp>(relaxed = true)
        val headless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        val shortUrl = "https://vt.tiktok.com/ZS9FastTest/"
        val tikwmJson = """
            {
              "code": 0,
              "data": {
                "title": "Instant TikTok Clip",
                "cover": "https://p16.tiktokcdn.com/cover.jpg",
                "hdplay": "https://cdn.tikwm.com/video_hd.mp4",
                "play": "https://cdn.tikwm.com/video_sd.mp4",
                "music": "https://cdn.tikwm.com/music.mp3"
              }
            }
        """.trimIndent()

        // Simulate a slow HTML branch (2s cancellable delay) while tikwm responds immediately
        every { http.resolveRedirectLocation(any(), any()) } returns "https://www.tiktok.com/@creator/video/7400000000000000001"
        coEvery { http.fetchString(match { it.contains("tikwm.com/api") }, any()) } returns tikwmJson
        coEvery { http.fetchHtmlWithCookies(any(), any()) } coAnswers {
            delay(2_000L)
            ExtractorHttp.HtmlFetchResult("", null, shortUrl)
        }

        val extractor = SocialMediaExtractor(http, headless, tracker)
        val startMs = System.currentTimeMillis()
        val result = extractor.extract(shortUrl)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertNotNull("Expected tikwm fast-path to win immediately", result)
        assertEquals("Instant TikTok Clip", result!!.title)
        assertTrue("Expected video streams", result.streams.any { it.type == MediaType.VIDEO })
        assertTrue("Expected fast return (< 1000ms) cancelling slow sibling, took ${elapsedMs}ms", elapsedMs < 1_000L)
        // Zero blocking http.probe() calls before returning ResolvedMedia
        verify(exactly = 0) { http.probe(any(), any()) }
        verify(exactly = 0) { http.probe(any(), any(), any()) }
    }

    @Test
    fun `ExtractorHttp resolveRedirectLocation reads Location header with followRedirects=false and zero body`() {
        val server = ServerSocket(0)
        val requestCount = AtomicInteger(0)
        val lastMethod = AtomicReference<String>()
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val firstLine = reader.readLine().orEmpty()
                    lastMethod.set(firstLine.substringBefore(' '))
                    requestCount.incrementAndGet()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val resp = "HTTP/1.1 302 Found\r\n" +
                        "Location: https://www.tiktok.com/@user/video/1234567890\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    sock.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                    sock.getOutputStream().flush()
                }
            }
        }
        try {
            val http = ExtractorHttp(OkHttpClient())
            val resolved = http.resolveRedirectLocation("http://127.0.0.1:${server.localPort}/short")
            assertEquals("https://www.tiktok.com/@user/video/1234567890", resolved)
            assertEquals("HEAD", lastMethod.get())
            assertEquals(1, requestCount.get())
        } finally {
            runCatching { server.close() }
            worker.join(500L)
        }
    }

    @Test
    fun `QualityViewModel renders cached URL synchronously without flashing Resource Loading`() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val url = "https://www.tiktok.com/@creator/video/7400000000000000002"
            val cachedMedia = ResolvedMedia(
                sourceUrl = url,
                title = "Cached TikTok",
                streams = listOf(
                    MediaStream(
                        id = "v1",
                        url = "https://cdn.example.com/v.mp4",
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = "1080p FHD",
                        height = 1080,
                        sizeBytes = 5_000_000L
                    )
                ),
                extractor = "TikTok"
            )
            val dummyExtractor = object : MediaExtractor {
                override val name = "TikTok"
                override val priority = 95
                override fun canHandle(url: String) = true
                override suspend fun extract(url: String) = cachedMedia
            }
            val network = mockk<NetworkMonitor> { every { isOnline() } returns true }
            val dispatchers = object : DispatcherProvider {
                override val io: CoroutineDispatcher = Dispatchers.Unconfined
                override val default: CoroutineDispatcher = Dispatchers.Unconfined
                override val main: CoroutineDispatcher = Dispatchers.Unconfined
            }
            val registry = ExtractorRegistry(setOf(dummyExtractor), network, dispatchers)
            registry.updateCache(cachedMedia)
            val repo = MediaRepositoryImpl(registry)

            val dm = mockk<DownloadManager>(relaxed = true)
            val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
                every { settings } returns MutableStateFlow(AppSettings())
            }

            val vm = QualityViewModel(repo, dm, settingsRepo, SavedStateHandle())
            vm.resolve(url)

            // Must be Resource.Success synchronously BEFORE any coroutine advances!
            val state = vm.state.value
            assertTrue("Expected immediate Resource.Success without Loading flash, got $state", state is Resource.Success)
            assertEquals("Cached TikTok", (state as Resource.Success).data.title)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // =========================================================================
    // Issue 2: Standard Video Quality-Tier Normalization & Deduplication
    // =========================================================================

    @Test
    fun `FormatUtils normalizeQualityHeight snaps widescreen cinema and portrait dimensions to standard tiers`() {
        assertEquals(144, FormatUtils.normalizeQualityHeight(256, 144))
        assertEquals(240, FormatUtils.normalizeQualityHeight(426, 192))
        assertEquals(360, FormatUtils.normalizeQualityHeight(640, 272))
        assertEquals(360, FormatUtils.normalizeQualityHeight(682, 384))
        assertEquals(480, FormatUtils.normalizeQualityHeight(854, 384))
        assertEquals(720, FormatUtils.normalizeQualityHeight(1280, 536))
        assertEquals(1080, FormatUtils.normalizeQualityHeight(1920, 800))
        assertEquals(720, FormatUtils.normalizeQualityHeight(720, 1280))
        // Height-only fallback
        assertEquals(240, FormatUtils.normalizeQualityHeight(null, 192))
        assertEquals(360, FormatUtils.normalizeQualityHeight(null, 384))
    }

    @Test
    fun `HlsExtractor normalizes widescreen variants and deduplicates colliding tiers keeping higher bitrate`() = runBlocking {
        val masterUrl = "https://cdn.example.com/movie/master.m3u8"
        val masterBody = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=320000,RESOLUTION=426x192,CODECS="avc1.42e01e"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x272,CODECS="avc1.4d401e"
            mid_a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=850000,RESOLUTION=682x384,CODECS="avc1.4d401f"
            mid_b.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=854x384,CODECS="avc1.4d401f"
            sd480.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2600000,RESOLUTION=1280x536,CODECS="avc1.64001f"
            hd720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x800,CODECS="avc1.640028"
            fhd1080.m3u8
        """.trimIndent()
        val mediaBody = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val http = mockk<ExtractorHttp>(relaxed = true)
        coEvery { http.fetchString(masterUrl, any()) } returns masterBody
        coEvery { http.fetchString(match { it.endsWith(".m3u8") && it != masterUrl }, any()) } returns mediaBody

        val hls = HlsExtractor(http)
        val resolved = hls.extract(masterUrl)
        assertNotNull(resolved)
        val videos = resolved!!.streams.filter { it.type == MediaType.VIDEO }.sortedBy { it.height }
        assertEquals(listOf(240, 360, 480, 720, 1080), videos.map { it.height })
        // Colliding 360p variants (640x272 @ 600k vs 682x384 @ 850k) must keep the higher bitrate variant
        val v360 = videos.first { it.height == 360 }
        assertTrue("Expected higher-bitrate 360p variant mid_b.m3u8", v360.url.endsWith("mid_b.m3u8"))
        assertEquals(682, v360.width)
    }

    @Test
    fun `QualityScreen and DownloadRow never show 192p or 384p labels`() {
        val resolved = ResolvedMedia(
            sourceUrl = "https://topcinema.example/watch/1",
            title = "Cinema Widescreen",
            streams = listOf(
                MediaStream("v1", "https://cdn.example/192.mp4", MediaType.VIDEO, "mp4", "192p", width = 426, height = 192, bitrateKbps = 300, sizeBytes = 90_000_000L),
                MediaStream("v2", "https://cdn.example/272.mp4", MediaType.VIDEO, "mp4", "272p", width = 640, height = 272, bitrateKbps = 500, sizeBytes = 120_000_000L),
                MediaStream("v3", "https://cdn.example/384.mp4", MediaType.VIDEO, "mp4", "384p", width = 682, height = 384, bitrateKbps = 800, sizeBytes = 159_000_000L),
                MediaStream("v4", "https://cdn.example/536.mp4", MediaType.VIDEO, "mp4", "536p", width = 1280, height = 536, bitrateKbps = 2200, sizeBytes = 462_000_000L)
            ),
            extractor = "Web page"
        )
        val prepared = prepareSnaptubeStreams(resolved)
        val videoLabels = prepared.video.map { it.label }
        assertEquals(listOf("Fast (240p)", "Fast (360p)", "High quality (720p)"), videoLabels)
        assertFalse(videoLabels.any { it.contains("192p") || it.contains("384p") || it.contains("536p") })

        val legacyItem = DownloadItem(
            id = 1L,
            title = "The Full Monty",
            sourceUrl = "https://topcinema.example/watch/1",
            remoteUrl = "https://cdn.example/384.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            qualityLabel = "384p",
            status = DownloadStatus.RUNNING,
            downloadedBytes = 12_900_000L,
            totalBytes = 180_000_000L
        )
        val primaryStatus = formatDownloadPrimaryStatus(legacyItem)
        assertTrue("Expected normalized 360p in DownloadRow status, got: $primaryStatus", primaryStatus.startsWith("360p ·"))
    }

    // =========================================================================
    // Issue 3: WeCima /watch/ Multi-Server Extraction & Diagnostic Detail
    // =========================================================================

    @Test
    fun `StreamingPortalResolver resolves WeCima watch page with Base64 data-url Doodstream pass_md5 server`() = runBlocking {
        val watchUrl = "https://wecima.here/watch/episode-7"
        val doodEmbed = "https://dood.li/e/abc123xyz"
        val doodB64 = Base64.getEncoder().encodeToString(doodEmbed.toByteArray())
        val wecimaHtml = """
            <html><body>
              <ul class="WatchServersList">
                <li><btn data-url="$doodB64">doodstream</btn></li>
              </ul>
            </body></html>
        """.trimIndent()
        val doodHtml = """
            <html><script>
              $.get('/pass_md5/98765-abcdef/tok999', function(data) {
                var src = data + makePlay() + "?token=tok999&expiry=" + Date.now();
              });
            </script></html>
        """.trimIndent()

        val http = mockk<ExtractorHttp>(relaxed = true)
        val hls = mockk<HlsExtractor>(relaxed = true)
        val dash = mockk<DashExtractor>(relaxed = true)

        coEvery { http.fetchString(doodEmbed, any()) } returns doodHtml
        coEvery { http.fetchString("https://dood.li/pass_md5/98765-abcdef/tok999", any()) } returns "https://cdn9.doodcdn.com/u5k/video_stream"
        every { http.probe(match { it.startsWith("https://cdn9.doodcdn.com/u5k/video_stream") }, any(), any()) } returns HeadInfo(
            code = 200,
            contentType = "video/mp4",
            contentLength = 250_000_000L,
            acceptsRanges = true,
            finalUrl = "https://cdn9.doodcdn.com/u5k/video_stream_resolved"
        )

        val resolver = StreamingPortalResolver(http, hls, dash)
        val result = resolver.resolvePortalAndEmbeds(
            pageUrl = watchUrl,
            html = wecimaHtml,
            doc = Jsoup.parse(wecimaHtml, watchUrl),
            baseHeaders = emptyMap()
        )
        assertTrue("Expected Doodstream /pass_md5/ stream to resolve", result.streams.isNotEmpty())
        assertEquals(MediaType.VIDEO, result.streams.first().type)
        assertEquals(250_000_000L, result.streams.first().sizeBytes)
        assertEquals(doodEmbed, result.streams.first().headers["Referer"])
    }

    @Test
    fun `HeadlessWebExtractor scripts scrape WeCima data-url server tabs and HtmlMediaExtractor surfaces Cloudflare 403 diagnostic`() = runBlocking {
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("[data-url]"))
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("atob("))
        assertTrue(HeadlessWebExtractor.TRY_NEXT_SERVER_JS.contains(".WatchServersList"))
        assertTrue(HeadlessWebExtractor.TRY_NEXT_SERVER_JS.contains("[data-url]"))

        val tracker = ExtractionProgressTracker()
        val http = mockk<ExtractorHttp>(relaxed = true)
        val hls = mockk<HlsExtractor>(relaxed = true)
        val dash = mockk<DashExtractor>(relaxed = true)
        val headless = mockk<HeadlessWebExtractor>(relaxed = true)

        val wecimaUrl = "https://wecima.here/watch/test-episode-7"
        coEvery { http.fetchString(wecimaUrl, any()) } throws MediaException(AppError.ACCESS_DENIED, "HTTP 403")
        coEvery { headless.extract(wecimaUrl, any()) } returns null

        val htmlExtractor = HtmlMediaExtractor(http, hls, dash, headless, tracker)
        val result = htmlExtractor.extract(wecimaUrl)
        assertEquals(null, result)
        coVerify(exactly = 1) { headless.extract(wecimaUrl, any()) }
        val diag = tracker.peekDiagnosticReason()
        assertNotNull("Expected Cloudflare/403 diagnostic reason for ShareDialogActivity", diag)
        assertTrue("Diagnostic should mention Cloudflare / HTTP 403, got: $diag", diag!!.contains("403") || diag.contains("Cloudflare"))
    }
}
