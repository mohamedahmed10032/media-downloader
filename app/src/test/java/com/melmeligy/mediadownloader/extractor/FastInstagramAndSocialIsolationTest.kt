package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FastInstagramAndSocialIsolationTest {

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private val onlineMonitor: NetworkMonitor = mockk {
        every { isOnline() } returns true
    }

    // -------------------------------------------------------------------------
    // R1: Social platforms NEVER fall through to HtmlMediaExtractor / watch servers
    // -------------------------------------------------------------------------

    @Test
    fun `HtmlMediaExtractor rejects all dedicated social domains and ExtractorRegistry never falls through`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHls = mockk<HlsExtractor>(relaxed = true)
        val mockDash = mockk<DashExtractor>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        val htmlExtractor = HtmlMediaExtractor(mockHttp, mockHls, mockDash, mockHeadless, tracker)

        val socialUrls = listOf(
            "https://www.instagram.com/reel/Df7oqT6ItI_/",
            "https://www.instagram.com/share/reel/BAc123xyz/",
            "https://www.tiktok.com/@creator/video/7400000000000000001",
            "https://vt.tiktok.com/ZS8xyz999/",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.facebook.com/reel/1234567890",
            "https://fb.watch/abc123/",
            "https://x.com/user/status/1234567890",
            "https://www.threads.net/@user/post/AbCdEf123"
        )

        for (url in socialUrls) {
            assertTrue("Expected dedicated social URL: $url", UrlUtils.isDedicatedSocialPlatformUrl(url))
            assertFalse("HtmlMediaExtractor must NOT handle $url", htmlExtractor.canHandle(url))
        }
        assertTrue("Generic sites must remain handled by HtmlMediaExtractor", htmlExtractor.canHandle("https://wecima.here/watch/ep-6/"))

        // Even if a test registers a failing specialized extractor alongside HtmlMediaExtractor
        // (and a rogue Web page extractor that returns canHandle=true), ExtractorRegistry must NEVER run it.
        val htmlCalls = AtomicInteger(0)
        val rogueWebPageExtractor = object : MediaExtractor {
            override val name = "Web page"
            override val priority = 50
            override fun canHandle(url: String) = true
            override suspend fun extract(url: String): ResolvedMedia? {
                htmlCalls.incrementAndGet()
                return null
            }
        }
        val failingSocialExtractor = object : MediaExtractor {
            override val name = "Social Media"
            override val priority = 95
            override fun canHandle(url: String) = UrlUtils.isDedicatedSocialPlatformUrl(url)
            override suspend fun extract(url: String): ResolvedMedia? = null
        }

        val registry = ExtractorRegistry(
            extractors = setOf(failingSocialExtractor, htmlExtractor, rogueWebPageExtractor),
            networkMonitor = onlineMonitor,
            dispatchers = testDispatchers
        )

        for (url in socialUrls) {
            var caught: MediaException? = null
            try {
                registry.resolve(url)
            } catch (e: MediaException) {
                caught = e
            }
            assertNotNull("Expected MediaException for $url", caught)
            assertEquals(AppError.NO_MEDIA_FOUND, caught!!.error)
        }
        assertEquals("Generic Web page extractor must NEVER run for dedicated social URLs", 0, htmlCalls.get())
        coVerify(exactly = 0) { mockHttp.fetchString(any(), any()) }
        coVerify(exactly = 0) { mockHeadless.extract(any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // R2: Instagram /share/reel/ redirect preservation & single-hop resolution
    // -------------------------------------------------------------------------

    @Test
    fun `ExtractorHttp resolveRedirectLocation resolves Instagram share redirect via ranged GET when HEAD returns 403`() {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val seenRangeHeader = AtomicReference<String?>(null)
        val serverThread = thread(isDaemon = true) {
            repeat(2) {
                runCatching {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine().orEmpty()
                        val method = requestLine.substringBefore(' ')
                        var rangeHeader: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line.substringAfter(':').trim()
                            }
                        }
                        val out = socket.getOutputStream()
                        val resp = if (method == "HEAD") {
                            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            seenRangeHeader.set(rangeHeader)
                            "HTTP/1.1 302 Found\r\nLocation: https://www.instagram.com/reel/Df7oqT6ItI_/?igsh=xyz\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        out.write(resp.toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            }
        }
        try {
            val http = ExtractorHttp(OkHttpClient())
            val url = "http://127.0.0.1:${server.localPort}/share/reel/OpaqueToken123/"
            val loc = http.resolveRedirectLocation(url)
            assertEquals("bytes=0-0", seenRangeHeader.get())
            assertEquals("https://www.instagram.com/reel/Df7oqT6ItI_/?igsh=xyz", loc)
        } finally {
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }

    @Test
    fun `extractInstagramShortcode parses canonical paths and login next redirect parameters`() {
        val extractor = SocialMediaExtractor(mockk(relaxed = true), mockk(relaxed = true))
        assertEquals("Df7oqT6ItI_", extractor.extractInstagramShortcode("https://www.instagram.com/reel/Df7oqT6ItI_/"))
        assertEquals("Df7oqT6ItI_", extractor.extractInstagramShortcode("https://www.instagram.com/reels/Df7oqT6ItI_/?igsh=abc"))
        assertEquals("C9U3YwJMPKj", extractor.extractInstagramShortcode("https://www.instagram.com/p/C9U3YwJMPKj/"))
        assertEquals("TvCode123", extractor.extractInstagramShortcode("https://www.instagram.com/tv/TvCode123/"))
        assertEquals(
            "Df7oqT6ItI_",
            extractor.extractInstagramShortcode("https://www.instagram.com/accounts/login/?next=%2Freel%2FDf7oqT6ItI_%2F&source=logged_out")
        )
    }

    // -------------------------------------------------------------------------
    // R3: Parallel First-Valid-Wins Racing, Cookie Injection, Zero Probes & Fast-Fail
    // -------------------------------------------------------------------------

    @Test
    fun `extractInstagram races embed captioned against stalled FastDL with zero blocking probes and cancels slower siblings`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        val shareUrl = "https://www.instagram.com/share/reel/BAcOpaque99/?igsh=MTZ"
        val resolvedReelUrl = "https://www.instagram.com/reel/Df7oqT6ItI_/"

        every { mockHttp.resolveRedirectLocation(shareUrl, any()) } returns resolvedReelUrl

        // FastDL stalls for 2.4s (would block sequentially before the fix!)
        every { mockHttp.postForm(match { it.contains("fastdl.to") }, any(), any()) } answers {
            Thread.sleep(2_400L)
            ""
        }
        // GraphQL endpoints return empty quickly
        every { mockHttp.postForm(match { it.contains("instagram.com/api/graphql") }, any(), any()) } returns ""
        coEvery { mockHttp.fetchString(match { it.contains("instagram.com/graphql/query") }, any()) } returns ""

        // Public /reel/Df7oqT6ItI_/embed/captioned/ responds immediately with valid video_url & display_url
        val embedHtml = """
            <html><head><meta property="og:image" content="https://scontent.cdninstagram.com/thumb.jpg"></head>
            <body>
              <a class="CaptionUsername">creator_handle</a>
              <script>window.__additionalDataLoaded('extra', {"shortcode_media":{"is_video":true,"video_url":"https://scontent.cdninstagram.com/v/reel720.mp4?oe=123","display_url":"https://scontent.cdninstagram.com/thumb.jpg"}});</script>
            </body></html>
        """.trimIndent()
        coEvery { mockHttp.fetchString(match { it.contains("/reel/Df7oqT6ItI_/embed/captioned/") }, any()) } returns embedHtml

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless, tracker).apply {
            cookieProvider = { null }
        }

        val startMs = System.currentTimeMillis()
        val resolved = extractor.extract(shareUrl)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertNotNull("Expected Instagram Reel to resolve via /embed/captioned/", resolved)
        assertEquals("Instagram", resolved!!.extractor)
        assertEquals(shareUrl, resolved.sourceUrl)
        assertTrue("Expected video stream in resolved media", resolved.streams.any { it.type == MediaType.VIDEO && it.url.contains("reel720.mp4") })
        assertTrue("Expected M4A + MP3 audio options", resolved.streams.count { it.type == MediaType.AUDIO } >= 2)
        assertTrue("Parallel race must complete far faster than stalled 2.4s FastDL (took ${elapsedMs}ms)", elapsedMs < 1_500L)

        // Zero blocking http.probe() calls before returning ResolvedMedia
        verify(exactly = 0) { mockHttp.probe(any(), any(), any()) }
        verify(exactly = 0) { mockHttp.probe(any(), any()) }
        // Never touched watch-server stage or HeadlessWebExtractor
        assertFalse(tracker.stage.value.contains("watch servers", ignoreCase = true))
        coVerify(exactly = 0) { mockHeadless.extract(any(), any(), any()) }
    }

    @Test
    fun `extractInstagram forwards CookieManager session cookies and CSRF token to GraphQL`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val capturedGraphQlHeaders = AtomicReference<Map<String, String>?>(null)

        coEvery { mockHttp.fetchString(match { it.contains("/embed/captioned/") }, any()) } returns ""
        every { mockHttp.postForm(match { it.contains("fastdl.to") }, any(), any()) } returns ""
        every { mockHttp.postForm(match { it.contains("instagram.com/api/graphql") }, any(), any()) } returns ""
        coEvery { mockHttp.fetchString(match { it.contains("instagram.com/graphql/query") }, any()) } answers {
            capturedGraphQlHeaders.set(secondArg())
            """{"data":{"xdt_shortcode_media":{"is_video":true,"video_url":"https://scontent.cdninstagram.com/v/gql_reel.mp4","display_url":"https://scontent.cdninstagram.com/p.jpg"}}}"""
        }

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless).apply {
            cookieProvider = { "sessionid=abc123; csrftoken=csrf_tok_777" }
        }

        val resolved = extractor.extract("https://www.instagram.com/reel/Df7oqT6ItI_/")
        assertNotNull(resolved)
        val hdrs = capturedGraphQlHeaders.get()
        assertNotNull("Expected GraphQL headers to be captured", hdrs)
        assertEquals(SocialMediaExtractor.IG_WEB_APP_ID, hdrs!!["X-IG-App-ID"])
        assertEquals("sessionid=abc123; csrftoken=csrf_tok_777", hdrs["Cookie"])
        assertEquals("csrf_tok_777", hdrs["X-CSRFToken"])
    }

    @Test
    fun `extractInstagram fails fast with login-required diagnostic on private post or login wall without invoking WebView`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()
        val fallbackInvoked = AtomicInteger(0)

        coEvery { mockHttp.fetchString(match { it.contains("/embed/captioned/") }, any()) } returns
            """<html><body>Sorry, this page isn't available. <a href="/accounts/login/">Log in</a></body></html>"""
        every { mockHttp.postForm(match { it.contains("instagram.com/api/graphql") }, any(), any()) } returns
            """{"message":"login_required","require_login":true,"status":"fail"}"""
        coEvery { mockHttp.fetchString(match { it.contains("instagram.com/graphql/query") }, any()) } returns
            """{"message":"login_required","require_login":true,"status":"fail"}"""
        every { mockHttp.postForm(match { it.contains("fastdl.to") }, any(), any()) } returns ""

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless, tracker).apply {
            cookieProvider = { null }
            instagramEmbedFallbackRunner = {
                fallbackInvoked.incrementAndGet()
                null
            }
        }

        val resolved = extractor.extract("https://www.instagram.com/reel/PrivateCode123/")
        assertNull("Login-walled private post must return null", resolved)
        assertEquals("Must NOT launch WebView fallback when explicit login wall is detected", 0, fallbackInvoked.get())
        assertEquals(
            SocialMediaExtractor.INSTAGRAM_LOGIN_REQUIRED_MESSAGE,
            tracker.peekDiagnosticReason()
        )
    }

    @Test
    fun `extractInstagram uses targeted embed-only WebView fallback when HTTP paths return empty without login wall`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()
        val fallbackUrlUsed = AtomicReference<String?>(null)

        coEvery { mockHttp.fetchString(any(), any()) } returns ""
        every { mockHttp.postForm(any(), any(), any()) } returns ""

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless, tracker).apply {
            cookieProvider = { null }
            instagramEmbedFallbackRunner = { embedUrl ->
                fallbackUrlUsed.set(embedUrl)
                ResolvedMedia(
                    sourceUrl = embedUrl,
                    title = "Instagram",
                    thumbnailUrl = "https://scontent.cdninstagram.com/thumb.jpg",
                    streams = listOf(
                        MediaStream(
                            id = "hl-v-0",
                            url = "https://scontent.cdninstagram.com/v/fallback_reel.mp4",
                            type = MediaType.VIDEO,
                            container = "mp4",
                            label = "720p HD",
                            height = 720
                        )
                    ),
                    extractor = "Smart Extractor"
                )
            }
        }

        val resolved = extractor.extract("https://www.instagram.com/reel/RateLimited99/")
        assertNotNull(resolved)
        assertEquals("https://www.instagram.com/reel/RateLimited99/embed/captioned/", fallbackUrlUsed.get())
        assertEquals("Instagram", resolved!!.extractor)
        assertEquals("https://www.instagram.com/reel/RateLimited99/", resolved.sourceUrl)
        assertEquals("Instagram Reel (RateLimited99)", resolved.title)
    }
}
