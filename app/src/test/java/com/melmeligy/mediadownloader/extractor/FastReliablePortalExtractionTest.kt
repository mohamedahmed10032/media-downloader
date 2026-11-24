package com.melmeligy.mediadownloader.extractor

import android.content.Context
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FastReliablePortalExtractionTest {

    @Before
    fun setUp() {
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
    }

    @After
    fun tearDown() {
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
    }

    @Test
    fun `runVerifiedEmbedPromotion continues to next embed server when embed 1 fails verification`() = runBlocking {
        val outerUrl = "https://wecima.here/watch/episode-9"
        val embed1 = "https://earnvids.com/e/failing1"
        val embed2 = "https://m1xdrop.net/e/working2"
        val embed3 = "https://uqload.net/embed-neverreached.html"
        val visited = mutableListOf<String>()

        val (verified, failureReasons) = HeadlessWebExtractor.runVerifiedEmbedPromotion(
            outerPageUrl = outerUrl,
            candidateEmbeds = listOf(embed1, embed2, embed3),
            maxEmbeds = 3,
            loadEmbed = { embedUrl, _ ->
                visited += embedUrl
                when (embedUrl) {
                    embed1 -> listOf(
                        MediaStreamPayload(
                            url = "https://cdn.earnvids.com/dead/master.m3u8",
                            streamType = StreamType.HLS,
                            headersMap = emptyMap(),
                            pageUrl = outerUrl
                        )
                    )
                    embed2 -> listOf(
                        MediaStreamPayload(
                            url = "https://cdn.m1xdrop.net/ok/video.mp4",
                            streamType = StreamType.PROGRESSIVE,
                            headersMap = emptyMap(),
                            pageUrl = outerUrl
                        )
                    )
                    else -> emptyList()
                }
            },
            verifyCandidate = { payload, embedUrl ->
                if (embedUrl == embed1) {
                    emptyList<MediaStream>() to "HTTP 403"
                } else {
                    listOf(
                        MediaStream(
                            id = "ok-1",
                            url = payload.url,
                            type = MediaType.VIDEO,
                            container = "mp4",
                            label = "720p",
                            height = 720,
                            headers = payload.headersMap
                        )
                    ) to null
                }
            }
        )

        assertEquals(listOf(embed1, embed2), visited)
        assertEquals(1, verified.size)
        assertEquals("https://cdn.m1xdrop.net/ok/video.mp4", verified.first().url)
        assertEquals(embed2, verified.first().headers["Referer"])
        assertEquals(listOf("HTTP 403"), failureReasons)
    }

    @Test
    fun `verifyPayloadCandidate merges live WebView cookies and UA and retries once on cold-socket timeout`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val sharedEngine = MediaInterceptionEngine()
        val http = mockk<ExtractorHttp>(relaxed = true)
        val hls = mockk<HlsExtractor>(relaxed = true)
        val dash = mockk<DashExtractor>(relaxed = true)
        val dispatchers = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }
        val tracker = ExtractionProgressTracker()

        val extractor = HeadlessWebExtractor(context, sharedEngine, http, hls, dash, dispatchers, tracker)
        val customUa = "Mozilla/5.0 (Linux; Android 14) TestWebViewUA/131.0"
        val embedUrl = "https://m1xdrop.net/e/vid123"
        val streamUrl = "https://cdn.m1xdrop.net/v/video_720p.mp4"

        extractor.cookieLookup = { url ->
            when {
                url.contains("cdn.m1xdrop.net") -> "cf_clearance=stream_tok; sid=abc"
                url.contains("m1xdrop.net") -> "embed_sess=xyz"
                else -> null
            }
        }

        val probeAttempts = AtomicInteger(0)
        val capturedHeaders = mutableListOf<Map<String, String>>()
        every { http.probe(streamUrl, any(), any()) } answers {
            val hdrs = secondArg<Map<String, String>>()
            capturedHeaders += hdrs
            if (probeAttempts.incrementAndGet() == 1) {
                throw SocketTimeoutException("cold socket timeout")
            }
            HeadInfo(
                code = 206,
                contentType = "video/mp4",
                contentLength = 120_000_000L,
                acceptsRanges = true,
                finalUrl = streamUrl
            )
        }

        val payload = MediaStreamPayload(
            url = streamUrl,
            streamType = StreamType.PROGRESSIVE,
            headersMap = mapOf("Referer" to embedUrl),
            pageUrl = embedUrl
        )

        val (streams, reason) = extractor.verifyPayloadCandidate(payload, embedUrl, customUa, idx = 0)
        assertEquals(null, reason)
        assertEquals(1, streams.size)
        assertEquals(2, probeAttempts.get())
        assertEquals(customUa, capturedHeaders.first()["User-Agent"])
        val mergedCookie = capturedHeaders.first()["Cookie"].orEmpty()
        assertTrue("Expected stream cf_clearance cookie merged, got: $mergedCookie", mergedCookie.contains("cf_clearance=stream_tok"))
        assertTrue("Expected embed cookie merged, got: $mergedCookie", mergedCookie.contains("embed_sess=xyz"))
    }

    @Test
    fun `HtmlMediaExtractor attaches WebView cookies and UA, fast-skips CF 403 hosts, and clears hint when cf_clearance arrives`() = runBlocking {
        val http = mockk<ExtractorHttp>(relaxed = true)
        val hls = mockk<HlsExtractor>(relaxed = true)
        val dash = mockk<DashExtractor>(relaxed = true)
        val headless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        val fakeNow = AtomicLong(1_000_000L)
        var currentCookie: String? = null
        val htmlExtractor = HtmlMediaExtractor(http, hls, dash, headless, tracker).apply {
            cookieProvider = { currentCookie }
            clockMs = { fakeNow.get() }
        }

        val url1 = "https://wecima.here/watch/ep-1"
        val url2 = "https://wecima.here/watch/ep-2"
        val url3 = "https://wecima.here/watch/ep-3"

        val fallbackMedia = ResolvedMedia(
            sourceUrl = url1,
            title = "Episode",
            streams = listOf(
                MediaStream("s1", "https://cdn.example/v.mp4", MediaType.VIDEO, "mp4", "720p", height = 720)
            ),
            extractor = "Smart Extractor"
        )

        // 1. First request gets HTTP 403 (no cf_clearance) -> caches short-TTL CF hint & falls back to headless
        coEvery { http.fetchString(url1, any()) } throws MediaException(AppError.ACCESS_DENIED, "HTTP 403")
        coEvery { headless.extract(url1, any()) } returns fallbackMedia
        assertNotNull(htmlExtractor.extract(url1))
        assertTrue(htmlExtractor.isCloudflareChallengeHintActive("wecima.here"))

        // 2. Second request within TTL (still no cf_clearance) -> skips plain-HTTP fetch completely!
        coEvery { headless.extract(url2, any()) } returns fallbackMedia.copy(sourceUrl = url2)
        assertNotNull(htmlExtractor.extract(url2))
        coVerify(exactly = 0) { http.fetchString(url2, any()) }
        coVerify(exactly = 1) { headless.extract(url2, any()) }

        // 3. Once cf_clearance cookie is present -> clears hint and sends Cookie + matching WebView UA via fast OkHttp
        currentCookie = "cf_clearance=valid_token_999; other=1"
        val capturedPageHeaders = mutableListOf<Map<String, String>>()
        coEvery { http.fetchString(url3, any()) } answers {
            capturedPageHeaders += secondArg<Map<String, String>>()
            "<html><title>Ep 3</title><body><video src=\"https://cdn.example/ep3.mp4\"></video></body></html>"
        }
        every { http.probe("https://cdn.example/ep3.mp4", any(), any()) } returns HeadInfo(
            code = 206,
            contentType = "video/mp4",
            contentLength = 50_000_000L,
            acceptsRanges = true,
            finalUrl = "https://cdn.example/ep3.mp4"
        )
        every { http.probe("https://cdn.example/ep3.mp4", any()) } returns HeadInfo(
            code = 206,
            contentType = "video/mp4",
            contentLength = 50_000_000L,
            acceptsRanges = true,
            finalUrl = "https://cdn.example/ep3.mp4"
        )

        val res3 = htmlExtractor.extract(url3)
        assertNotNull(res3)
        assertFalse("CF hint must be cleared once cf_clearance cookie is present", htmlExtractor.isCloudflareChallengeHintActive("wecima.here"))
        assertEquals(1, capturedPageHeaders.size)
        assertEquals(HeadlessWebExtractor.lastWebViewUserAgent, capturedPageHeaders.first()["User-Agent"])
        assertTrue(capturedPageHeaders.first()["Cookie"].orEmpty().contains("cf_clearance=valid_token_999"))
        coVerify(exactly = 0) { headless.extract(url3, any()) }
    }

    @Test
    fun `StreamingPortalResolver resolveDirectEmbedUrls unpacks DOM-scraped Base64 embeds in parallel`() = runBlocking {
        val watchUrl = "https://wecima.here/watch/ep-hybrid"
        val mixdropEmbed = "https://m1xdrop.net/e/hybrid123"
        val b64Embed = Base64.getEncoder().encodeToString(mixdropEmbed.toByteArray())

        val http = mockk<ExtractorHttp>(relaxed = true)
        val hls = mockk<HlsExtractor>(relaxed = true)
        val dash = mockk<DashExtractor>(relaxed = true)

        coEvery { http.fetchString(mixdropEmbed, any()) } returns """
            <html><script>MDCore.wurl="//cdn.m1xdrop.net/v/hybrid_720p.mp4";</script></html>
        """.trimIndent()
        every { http.probe("https://cdn.m1xdrop.net/v/hybrid_720p.mp4", any(), any()) } returns HeadInfo(
            code = 206,
            contentType = "video/mp4",
            contentLength = 180_000_000L,
            acceptsRanges = true,
            finalUrl = "https://cdn.m1xdrop.net/v/hybrid_720p.mp4"
        )

        val resolver = StreamingPortalResolver(http, hls, dash)
        val result = resolver.resolveDirectEmbedUrls(
            pageUrl = watchUrl,
            embedUrls = listOf(b64Embed),
            baseHeaders = mapOf("User-Agent" to HeadlessWebExtractor.lastWebViewUserAgent)
        )

        assertTrue("Expected hybrid DOM embed unpack to return verified video stream", result.streams.isNotEmpty())
        assertEquals("https://cdn.m1xdrop.net/v/hybrid_720p.mp4", result.streams.first().url)
        assertEquals(mixdropEmbed, result.streams.first().headers["Referer"])
    }
}
