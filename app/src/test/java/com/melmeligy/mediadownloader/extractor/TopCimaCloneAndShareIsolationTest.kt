package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import com.melmeligy.mediadownloader.ui.quality.shouldShowLivePreviewForUrl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end loopback HTTP and unit regression suite for:
 * 1. Blocking `free-wd.online` fake countdown tracker & associated ad/popunder script hosts.
 * 2. TopCinema canonical mirror failover for decoy/clone watch pages (`topcima.info`),
 *    preferring `/wp-content/themes/` `MyAjaxURL` over 404 root `ajaxURL`, unpacking JWPlayer
 *    streams, and preserving outer Arabic title/poster.
 * 3. `Cinema`-theme `getServer(this.id, <video>)` GET AJAX resolution (`temp/ajax/iframe.php`)
 *    and `HeadlessWebExtractor.parseScrapedAjaxServersJson` GET support.
 * 4. Decoy ad-tracker portal fast-fail without launching `HeadlessWebExtractor`.
 * 5. Rejecting tiny (< 2.5 MB, unknown/<=30s) third-party progressive ad clips on movie/episode
 *    portal watch/download pages while preserving short solo clips on non-portal sites.
 * 6. Cross-platform Share sheet session + URL-host isolation in `ExtractionProgressTracker` and
 *    `QualityScreen.shouldShowLivePreviewForUrl`.
 */
class TopCimaCloneAndShareIsolationTest {

    @Before
    fun setUp() {
        HostHealthRegistry.shared.reset()
        ExtractorRegistry.clearConfirmedHtml()
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
    }

    @After
    fun tearDown() {
        HostHealthRegistry.shared.reset()
        ExtractorRegistry.clearConfirmedHtml()
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
    }

    private data class ReqInfo(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class RespSpec(
        val status: Int = 200,
        val statusText: String = "OK",
        val contentType: String = "text/html; charset=utf-8",
        val body: ByteArray = ByteArray(0),
        val extraHeaders: Map<String, String> = emptyMap(),
        val explicitContentLength: Long? = null
    )

    private class LoopbackHttpServer(
        private val handler: (ReqInfo) -> RespSpec
    ) : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        @Volatile
        private var running = true
        val port: Int = socket.localPort
        val baseUrl: String = "http://127.0.0.1:$port"

        init {
            pool.execute {
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    pool.execute {
                        client.use { s ->
                            s.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
                            val requestLine = reader.readLine() ?: return@execute
                            val parts = requestLine.split(' ')
                            if (parts.size < 2) return@execute
                            val method = parts[0]
                            val path = parts[1]
                            val headers = LinkedHashMap<String, String>()
                            var contentLength = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                val colon = line.indexOf(':')
                                if (colon > 0) {
                                    val k = line.substring(0, colon).trim()
                                    val v = line.substring(colon + 1).trim()
                                    headers[k] = v
                                    if (k.equals("Content-Length", ignoreCase = true)) {
                                        contentLength = v.toIntOrNull() ?: 0
                                    }
                                }
                            }
                            val bodyChars = if (contentLength > 0) {
                                CharArray(contentLength).also { buf ->
                                    var read = 0
                                    while (read < contentLength) {
                                        val n = reader.read(buf, read, contentLength - read)
                                        if (n <= 0) break
                                        read += n
                                    }
                                }
                            } else CharArray(0)
                            val resp = handler(ReqInfo(method, path, headers, String(bodyChars)))
                            val out = s.getOutputStream()
                            val declaredLength = resp.explicitContentLength ?: resp.body.size.toLong()
                            val headerBytes = buildString {
                                append("HTTP/1.1 ${resp.status} ${resp.statusText}\r\n")
                                append("Content-Type: ${resp.contentType}\r\n")
                                append("Content-Length: $declaredLength\r\n")
                                resp.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                                append("Connection: close\r\n\r\n")
                            }.toByteArray(StandardCharsets.ISO_8859_1)
                            out.write(headerBytes)
                            if (!method.equals("HEAD", ignoreCase = true) && resp.body.isNotEmpty()) {
                                out.write(resp.body)
                            }
                            out.flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            running = false
            runCatching { socket.close() }
            pool.shutdownNow()
        }
    }

    @Test
    fun `AdBlocker blocks free-wd fake countdown tracker and ad scripts while preserving legit video embed hosts`() {
        val blockedUrls = listOf(
            "https://w1.free-wd.online/watch.php?v&ref=12345",
            "https://w5.free-wd.online/player1.php?v&ref=999",
            "https://ym.frapptents.com/loader.js",
            "https://rn.kirantisalem.com/ads.js",
            "https://revayhystrix.com/popunder",
            "https://ducatflapper.com/p.js",
            "https://hampersmusaeus.com/ad.js",
            "https://rassledtutrice.com/tag.js",
            "https://gillyspencie.com/s.js"
        )
        blockedUrls.forEach { url ->
            assertTrue("Expected AdBlocker.isAd to block $url", AdBlocker.isAd(url))
        }

        val allowedEmbeds = listOf(
            "https://updown.cam/embed-9t16lmjcndtr-1280x640.html",
            "https://earnvids.pro/e/abc123",
            "https://streamwish.to/e/xyz987",
            "https://streamtape.com/e/def456",
            "https://dood.li/e/ghi789",
            "https://uqload.net/embed-jkl012.html",
            "https://vidara.so/e/mno345"
        )
        allowedEmbeds.forEach { url ->
            assertFalse("Expected AdBlocker.isAd NOT to block legitimate embed $url", AdBlocker.isAd(url))
        }
    }

    @Test
    fun `decoy topcima watch page fails over to canonical TopCinema mirror and resolves full 1_1GB episode with outer title`() = runBlocking {
        val slugPath = "/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lead-children-%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-%d8%a7%d9%84%d8%a7%d9%88%d9%84-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-3-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/watch/"
        val expectedBytes = 1_149_169_485L
        val capturedCdnReferer = AtomicReference<String?>(null)
        val capturedCdnOrigin = AtomicReference<String?>(null)

        // 1. CDN server serving the 1.1 GB MP4
        val cdnServer = LoopbackHttpServer { req ->
            capturedCdnReferer.set(req.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value)
            capturedCdnOrigin.set(req.headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value)
            if (req.method.equals("HEAD", ignoreCase = true)) {
                RespSpec(
                    status = 200,
                    contentType = "video/mp4",
                    extraHeaders = mapOf("Accept-Ranges" to "bytes"),
                    explicitContentLength = expectedBytes
                )
            } else {
                RespSpec(
                    status = 206,
                    statusText = "Partial Content",
                    contentType = "video/mp4",
                    body = ByteArray(1),
                    extraHeaders = mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes 0-0/$expectedBytes"
                    )
                )
            }
        }

        // 2. Embed server (`updown.cam` style) serving packed JWPlayer script
        val embedServer = LoopbackHttpServer {
            val videoUrl = "${cdnServer.baseUrl}/v/xyz999/video.mp4"
            val dict = "||||||||||jwplayer|vplayer|setup|sources|file|label|width|height|duration|$videoUrl"
            val embedHtml = """
                <html><head><title>Updown Embed</title></head><body>
                <script>
                eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p;}('a("b").c({d:[{e:"j",f:"720p"}],g:"1280",h:"640",i:3610});',36,20,'$dict'.split('|'),0,{}))
                </script>
                </body></html>
            """.trimIndent()
            RespSpec(body = embedHtml.toByteArray(StandardCharsets.UTF_8))
        }

        // 3. Canonical TopCinema mirror server (`topcinemaa.tv` style)
        lateinit var mirrorServer: LoopbackHttpServer
        mirrorServer = LoopbackHttpServer { req ->
            when {
                req.path.endsWith("/Ajaxat/Single/Server.php") && !req.path.contains("/wp-content/themes/") -> {
                    RespSpec(status = 404, statusText = "Not Found", body = "404".toByteArray())
                }
                req.path.contains("/wp-content/themes/movies2023/Ajaxat/Single/Server.php") -> {
                    val iframeHtml = """<iframe src="${embedServer.baseUrl}/embed-9t16lmjcndtr-1280x640.html" allowfullscreen></iframe>"""
                    RespSpec(body = iframeHtml.toByteArray(StandardCharsets.UTF_8))
                }
                req.path.equals(slugPath, ignoreCase = true) -> {
                    val html = """
                        <html><head>
                        <meta property="og:title" content="مسلسل Lead Children الموسم الاول الحلقة 3 مترجمة" />
                        <script>
                            var ajaxURL = '${mirrorServer.baseUrl}/Ajaxat/';
                            var MyAjaxURL = "${mirrorServer.baseUrl}/wp-content/themes/movies2023/Ajaxat/";
                        </script>
                        </head><body>
                        <ul class="watch-servers">
                            <li data-id="90421" data-server="1">Updown</li>
                        </ul>
                        </body></html>
                    """.trimIndent()
                    RespSpec(body = html.toByteArray(StandardCharsets.UTF_8))
                }
                else -> RespSpec(status = 404, statusText = "Not Found")
            }
        }

        // 4. Decoy clone server (`topcima.info` style with `free-wd.online` links + Arabic title/poster)
        val decoyServer = LoopbackHttpServer { req ->
            val posterUrl = "https://cdn.topcima.info/posters/lead-children.jpg"
            val html = """
                <html><head>
                <meta property="og:title" content="مسلسل Lead Children الموسم الاول الحلقة 3 مترجمة - توب سيما" />
                <meta property="og:image" content="$posterUrl" />
                </head><body>
                <div class="WatchIframe">
                    <a class="cwd-open-server" href="https://w1.free-wd.online/watch.php?v&ref=77123">Open Server</a>
                </div>
                <ul id="watch">
                    <li data-watch="https://w1.free-wd.online/watch.php?v&ref=77123">Server 1</li>
                    <li data-watch="https://w2.free-wd.online/watch.php?v&ref=77124">Server 2</li>
                </ul>
                </body></html>
            """.trimIndent()
            RespSpec(body = html.toByteArray(StandardCharsets.UTF_8))
        }

        try {
            val http = ExtractorHttp(OkHttpClient())
            val hls = HlsExtractor(http)
            val dash = DashExtractor(http)
            val headless = mockk<HeadlessWebExtractor>(relaxed = true)
            val tracker = ExtractionProgressTracker()
            val htmlExtractor = HtmlMediaExtractor(http, hls, dash, headless, tracker)
            htmlExtractor.cookieProvider = { null }
            htmlExtractor.portalResolver.topCinemaMirrorOrigins = listOf(mirrorServer.baseUrl)

            val decoyUrl = "${decoyServer.baseUrl}$slugPath"
            val result = htmlExtractor.extractWithHeaders(decoyUrl, emptyMap(), allowHeadlessFallback = true)

            assertNotNull("Expected canonical mirror failover to resolve full episode", result)
            val videos = result!!.streams.filter { it.type == MediaType.VIDEO }
            assertTrue("Expected at least one resolved video stream", videos.isNotEmpty())
            val primary = videos.first()
            assertEquals("Expected exact 1.1 GB file size from CDN probe", expectedBytes, primary.sizeBytes)
            assertTrue("Expected HD resolution (>= 640p), got ${primary.height}", (primary.height ?: 0) >= 640)
            assertEquals("Expected embed Referer on CDN request", "${embedServer.baseUrl}/embed-9t16lmjcndtr-1280x640.html", capturedCdnReferer.get())
            assertEquals("Expected embed Origin on CDN request", embedServer.baseUrl, capturedCdnOrigin.get())
            assertTrue("Expected Arabic outer title preserved, got ${result.title}", result.title.contains("Lead Children"))
            assertEquals("https://cdn.topcima.info/posters/lead-children.jpg", result.thumbnailUrl)

            coVerify(exactly = 0) { headless.extract(any(), any(), any()) }
        } finally {
            decoyServer.close()
            mirrorServer.close()
            embedServer.close()
            cdnServer.close()
        }
    }

    @Test
    fun `Cinema theme getServer GET AJAX resolves iframe endpoint and HeadlessWebExtractor parses GET specs`() = runBlocking {
        val videoSize = 420_000_000L
        val cdnServer = LoopbackHttpServer { req ->
            if (req.method.equals("HEAD", ignoreCase = true)) {
                RespSpec(
                    status = 200,
                    contentType = "video/mp4",
                    extraHeaders = mapOf("Accept-Ranges" to "bytes"),
                    explicitContentLength = videoSize
                )
            } else {
                RespSpec(
                    status = 206,
                    statusText = "Partial Content",
                    contentType = "video/mp4",
                    body = ByteArray(1),
                    extraHeaders = mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes 0-0/$videoSize"
                    )
                )
            }
        }

        val embedServer = LoopbackHttpServer {
            val mp4 = "${cdnServer.baseUrl}/stream/episode_720p.mp4"
            val html = """<html><body><video src="$mp4"></video></body></html>"""
            RespSpec(body = html.toByteArray(StandardCharsets.UTF_8))
        }

        lateinit var cinemaServer: LoopbackHttpServer
        val receivedGetMethod = AtomicReference<String?>(null)
        cinemaServer = LoopbackHttpServer { req ->
            if (req.path.startsWith("/wp-content/themes/Cinema/temp/ajax/iframe.php")) {
                receivedGetMethod.set(req.method)
                assertTrue("Expected id=312&video=8 query, got ${req.path}", req.path.contains("id=312") && req.path.contains("video=8"))
                RespSpec(body = """<iframe src="${embedServer.baseUrl}/e/movie123"></iframe>""".toByteArray(StandardCharsets.UTF_8))
            } else {
                RespSpec(status = 404, statusText = "Not Found")
            }
        }

        try {
            val pageUrl = "${cinemaServer.baseUrl}/episode-3/watch/"
            val cinemaHtml = """
                <html><head>
                <script>
                    var themeDir = "${cinemaServer.baseUrl}/wp-content/themes/Cinema";
                    var vo_postID = "312";
                </script>
                </head><body>
                <ul>
                    <li id="srv8" onClick="getServer(this.id,8);">Server 8</li>
                </ul>
                </body></html>
            """.trimIndent()

            val http = ExtractorHttp(OkHttpClient())
            val resolver = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))
            val doc = Jsoup.parse(cinemaHtml, pageUrl)
            val result = resolver.resolvePortalAndEmbeds(pageUrl, cinemaHtml, doc, emptyMap())

            assertEquals("Expected GET request to Cinema iframe.php", "GET", receivedGetMethod.get())
            assertTrue("Expected stream from Cinema GET AJAX server", result.streams.isNotEmpty())
            assertEquals(videoSize, result.streams.first().sizeBytes)

            // Verify HeadlessWebExtractor.parseScrapedAjaxServersJson preserves GET specs with empty body
            val json = """[{"e":"${cinemaServer.baseUrl}/wp-content/themes/Cinema/temp/ajax/iframe.php?id=312&video=8","b":"","n":"Server 8","m":"GET","act":0}]"""
            val parsed = HeadlessWebExtractor.parseScrapedAjaxServersJson(json, pageUrl)
            assertEquals(1, parsed.size)
            assertEquals("GET", parsed.first().method)
            assertEquals("", parsed.first().formBody)
            assertTrue(parsed.first().endpointUrl.contains("temp/ajax/iframe.php?id=312&video=8"))
        } finally {
            cinemaServer.close()
            embedServer.close()
            cdnServer.close()
        }
    }

    @Test
    fun `decoy ad-tracker portal fast-fails without launching HeadlessWebExtractor when canonical mirrors return 404`() = runBlocking {
        val missingMirror = LoopbackHttpServer {
            RespSpec(status = 404, statusText = "Not Found", body = "Not Found".toByteArray())
        }
        val decoyServer = LoopbackHttpServer {
            val html = """
                <html><head><title>Unreleased Episode - TopCima</title></head><body>
                <div class="WatchIframe">
                    <a class="cwd-open-server" href="https://w1.free-wd.online/watch.php?v&ref=999">Watch</a>
                </div>
                <ul id="watch">
                    <li data-watch="https://w1.free-wd.online/watch.php?v&ref=999">Server 1</li>
                </ul>
                </body></html>
            """.trimIndent()
            RespSpec(body = html.toByteArray(StandardCharsets.UTF_8))
        }

        try {
            val http = ExtractorHttp(OkHttpClient())
            val headless = mockk<HeadlessWebExtractor>(relaxed = true)
            coEvery { headless.extract(any(), any(), any()) } returns null
            val tracker = ExtractionProgressTracker()
            val htmlExtractor = HtmlMediaExtractor(http, HlsExtractor(http), DashExtractor(http), headless, tracker)
            htmlExtractor.cookieProvider = { null }
            htmlExtractor.portalResolver.topCinemaMirrorOrigins = listOf(missingMirror.baseUrl)

            val result = htmlExtractor.extractWithHeaders(
                url = "${decoyServer.baseUrl}/unreleased-show/watch/",
                customHeaders = emptyMap(),
                allowHeadlessFallback = true
            )

            assertNull("Expected null when decoy portal has no real mirror streams", result)
            coVerify(exactly = 0) { headless.extract(any(), any(), any()) }
            val diag = tracker.peekDiagnosticReason().orEmpty()
            assertTrue("Expected decoy tracker diagnostic message, got: $diag", diag.contains("ad-redirect trackers", ignoreCase = true))
        } finally {
            decoyServer.close()
            missingMirror.close()
        }
    }

    @Test
    fun `tiny 1008_6KB third-party ad clip is rejected on portal watch pages but preserved on non-portal sites`() = runBlocking {
        val adClipBytes = 1_032_806L // 1008.6 KB
        val adClipStream = MediaStream(
            id = "ad-1",
            url = "https://cdn.randomadhost.net/promo/clip.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            label = "HD Video",
            sizeBytes = adClipBytes,
            isHls = false
        )

        // 1. HeadlessWebExtractor.filterPortalTinyAdClips drops tiny clip on portal /watch/ URL
        val portalWatchUrl = "https://topcima.info/lead-children-ep3/watch/"
        val afterHeadlessPortal = HeadlessWebExtractor.filterPortalTinyAdClips(
            targetUrl = portalWatchUrl,
            videos = listOf(adClipStream),
            hasPortalIndicators = false
        )
        assertTrue("Expected 1008.6 KB ad clip dropped on portal watch page", afterHeadlessPortal.isEmpty())

        // 2. HeadlessWebExtractor.filterPortalTinyAdClips keeps solo short clip on non-portal blog URL
        val nonPortalUrl = "https://blog.example.com/posts/cool-short-clip"
        val afterHeadlessNonPortal = HeadlessWebExtractor.filterPortalTinyAdClips(
            targetUrl = nonPortalUrl,
            videos = listOf(adClipStream),
            hasPortalIndicators = false
        )
        assertEquals("Expected short solo clip preserved on non-portal site", 1, afterHeadlessNonPortal.size)

        // 3. InterceptedMediaExtractor end-to-end returns null for solo 1008.6 KB ad clip on portal watch page
        val adCdn = LoopbackHttpServer { req ->
            if (req.method.equals("HEAD", ignoreCase = true)) {
                RespSpec(
                    status = 200,
                    contentType = "video/mp4",
                    extraHeaders = mapOf("Accept-Ranges" to "bytes"),
                    explicitContentLength = adClipBytes
                )
            } else {
                RespSpec(
                    status = 206,
                    statusText = "Partial Content",
                    contentType = "video/mp4",
                    body = ByteArray(1),
                    extraHeaders = mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes 0-0/$adClipBytes"
                    )
                )
            }
        }
        try {
            val http = ExtractorHttp(OkHttpClient())
            val engine = MediaInterceptionEngine()
            engine.onPageChanged(portalWatchUrl)
            engine.submit(
                MediaStreamPayload(
                    url = "${adCdn.baseUrl}/media/clip_1008kb.mp4",
                    streamType = StreamType.PROGRESSIVE,
                    headersMap = mapOf("Referer" to portalWatchUrl),
                    pageUrl = portalWatchUrl,
                    mimeType = "video/mp4",
                    sizeBytes = adClipBytes
                )
            )
            val interceptedExtractor = InterceptedMediaExtractor(
                engine = engine,
                http = http,
                hlsExtractor = HlsExtractor(http),
                dashExtractor = DashExtractor(http)
            )
            val portalResult = interceptedExtractor.extract(portalWatchUrl)
            assertNull("Expected InterceptedMediaExtractor to reject solo 1008.6 KB ad clip on portal watch URL", portalResult)

            // Non-portal page with the exact same 1008.6 KB clip MUST still resolve
            val nonPortalEngine = MediaInterceptionEngine()
            nonPortalEngine.onPageChanged(nonPortalUrl)
            nonPortalEngine.submit(
                MediaStreamPayload(
                    url = "${adCdn.baseUrl}/media/real_short_clip.mp4",
                    streamType = StreamType.PROGRESSIVE,
                    headersMap = mapOf("Referer" to nonPortalUrl),
                    pageUrl = nonPortalUrl,
                    mimeType = "video/mp4",
                    sizeBytes = adClipBytes
                )
            )
            val nonPortalExtractor = InterceptedMediaExtractor(
                engine = nonPortalEngine,
                http = http,
                hlsExtractor = HlsExtractor(http),
                dashExtractor = DashExtractor(http)
            )
            val nonPortalResult = nonPortalExtractor.extract(nonPortalUrl)
            assertNotNull("Expected solo 1008.6 KB clip on non-portal site to resolve", nonPortalResult)
            assertEquals(adClipBytes, nonPortalResult!!.streams.first().sizeBytes)
        } finally {
            adCdn.close()
        }
    }

    @Test
    fun `cross-platform share session rejects late portal WebView callbacks and incompatible preview headers`() {
        val tracker = ExtractionProgressTracker()

        // Session 1: TopCima movie watch page
        val topCimaUrl = "https://topcima.info/lead-children/watch/"
        val s1 = tracker.beginSession(targetUrl = topCimaUrl)
        assertEquals(s1, tracker.captureSessionId())
        tracker.update(ExtractionProgressTracker.STAGE_SERVERS, sessionId = s1)
        tracker.updatePreview(
            title = "مسلسل Lead Children الحلقة 3",
            thumbnailUrl = "https://cdn.topcima.info/poster.jpg",
            sourceHost = "topcima.info",
            sourceUrl = topCimaUrl,
            sessionId = s1
        )
        assertEquals(ExtractionProgressTracker.STAGE_SERVERS, tracker.stage.value)
        assertEquals("مسلسل Lead Children الحلقة 3", tracker.preview.value?.title)

        // Session 2: User switches to Instagram and shares a reel
        val instaUrl = "https://www.instagram.com/reel/Cx123456789/"
        val s2 = tracker.beginSession(targetUrl = instaUrl)
        assertEquals(ExtractionProgressTracker.STAGE_DEFAULT, tracker.stage.value)
        assertNull("Preview must reset synchronously when new session begins", tracker.preview.value)

        // Simulate late main-Looper WebViewClient callbacks from Session 1 arriving after Session 2 started
        tracker.update(ExtractionProgressTracker.STAGE_SERVERS, sessionId = s1)
        tracker.updatePreview(
            title = "مسلسل Lead Children الحلقة 3",
            thumbnailUrl = "https://cdn.topcima.info/poster.jpg",
            sourceHost = "topcima.info",
            sourceUrl = topCimaUrl,
            sessionId = s1
        )
        tracker.setDiagnosticReason("Late portal error", sessionId = s1)

        assertEquals(
            "Late session 1 STAGE_SERVERS must never overwrite session 2 stage",
            ExtractionProgressTracker.STAGE_DEFAULT,
            tracker.stage.value
        )
        assertNull("Late session 1 preview must never overwrite session 2 preview", tracker.preview.value)
        assertNull("Late session 1 diagnostic must never leak into session 2", tracker.peekDiagnosticReason())

        // Even on the active social session, portal-only stages must be blocked
        tracker.update(ExtractionProgressTracker.STAGE_SERVERS, sessionId = s2)
        tracker.update(ExtractionProgressTracker.STAGE_EMBEDS, sessionId = s2)
        tracker.update(ExtractionProgressTracker.STAGE_CLOUDFLARE, sessionId = s2)
        assertEquals(
            "Portal-only stages must be ignored during dedicated social extraction",
            ExtractionProgressTracker.STAGE_DEFAULT,
            tracker.stage.value
        )

        // Legitimate social stage & preview updates still succeed
        tracker.update(ExtractionProgressTracker.STAGE_QUALITIES, sessionId = s2)
        assertEquals(ExtractionProgressTracker.STAGE_QUALITIES, tracker.stage.value)

        // UI-level preview compatibility guard
        val stalePortalPreview = ExtractionProgressTracker.PreviewMetadata(
            title = "Lead Children",
            thumbnailUrl = "https://cdn.topcima.info/poster.jpg",
            sourceHost = "topcima.info",
            sourceUrl = topCimaUrl
        )
        assertFalse(
            "Mismatched topcima preview must not render on Instagram share sheet",
            shouldShowLivePreviewForUrl(stalePortalPreview, instaUrl)
        )
        assertTrue(
            "Matching topcima preview must render on topcima URL",
            shouldShowLivePreviewForUrl(stalePortalPreview, topCimaUrl)
        )

        // Shortener / platform-family compatibility checks (youtu.be <-> youtube.com, vm.tiktok.com <-> tiktok.com)
        val ytPreview = ExtractionProgressTracker.PreviewMetadata(
            title = "Demo Video",
            thumbnailUrl = "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            sourceHost = "youtube.com"
        )
        assertTrue(shouldShowLivePreviewForUrl(ytPreview, "https://youtu.be/abc"))
        assertFalse(shouldShowLivePreviewForUrl(ytPreview, instaUrl))

        val igPreview = ExtractionProgressTracker.PreviewMetadata(
            title = "Reel",
            thumbnailUrl = "https://scontent.cdninstagram.com/t.jpg",
            sourceHost = "instagram.com",
            sourceUrl = instaUrl
        )
        assertTrue(shouldShowLivePreviewForUrl(igPreview, "https://instagr.am/reel/Cx123456789/"))
    }
}
