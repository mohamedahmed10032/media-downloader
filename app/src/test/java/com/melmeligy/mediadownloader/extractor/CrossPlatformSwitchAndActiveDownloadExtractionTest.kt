package com.melmeligy.mediadownloader.extractor

import android.content.Context
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.ExtractionPriorityCoordinator
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.di.WebViewCookieJar
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-layer (unit, integration, loopback E2E, adversarial) verification for cross-platform
 * switching and active-download extraction stability:
 * 1. Cooperative download yielding during interactive extraction + exact byte integrity after completion.
 * 2. Strict cross-domain cookie isolation (outer portal cf_clearance never leaks into embed/CDN hosts
 *    or contaminates WebViewCookieJar, while embed-issued Set-Cookie tokens are preserved).
 * 3. Session-token isolation in ExtractionProgressTracker & silent background retries.
 * 4. Contention-aware tikwm circuit breaker during active downloads.
 * 5. TopCinema relative MyAjaxURL, Clappr/Uqload string-array sources, Streamtape redirector preservation,
 *    encoded Arabic /watch/ discovery, WebView UA sanitization, and lease auto-expiry invariants.
 */
class CrossPlatformSwitchAndActiveDownloadExtractionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class ReqInfo(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class RespInfo(
        val status: Int = 200,
        val contentType: String = "text/html; charset=utf-8",
        val body: ByteArray = ByteArray(0),
        val extraHeaders: Map<String, String> = emptyMap(),
        val chunkDelayMs: Long = 0L,
        val chunkSizeBytes: Int = 65_536
    )

    private class LoopbackServer(
        private val handler: (ReqInfo) -> RespInfo
    ) : Closeable {
        private val serverSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()

        @Volatile
        private var running = true
        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        init {
            pool.submit {
                while (running) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    pool.submit {
                        socket.use { s ->
                            runCatching {
                                s.soTimeout = 10_000
                                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                                val requestLine = reader.readLine() ?: return@runCatching
                                val parts = requestLine.split(' ')
                                val method = parts.getOrNull(0).orEmpty()
                                val rawTarget = parts.getOrNull(1).orEmpty()
                                val path = rawTarget.substringBefore('?')
                                val query = rawTarget.substringAfter('?', "")
                                val headers = LinkedHashMap<String, String>()
                                var contentLen = 0
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    val colon = line.indexOf(':')
                                    if (colon > 0) {
                                        val k = line.substring(0, colon).trim().lowercase()
                                        val v = line.substring(colon + 1).trim()
                                        headers[k] = v
                                        if (k == "content-length") contentLen = v.toIntOrNull() ?: 0
                                    }
                                }
                                val bodyStr = if (contentLen > 0) {
                                    val buf = CharArray(contentLen.coerceAtMost(8192))
                                    var off = 0
                                    while (off < buf.size) {
                                        val r = reader.read(buf, off, buf.size - off)
                                        if (r <= 0) break
                                        off += r
                                    }
                                    String(buf, 0, off)
                                } else ""

                                val resp = handler(ReqInfo(method, path, query, headers, bodyStr))
                                val reason = when (resp.status) {
                                    200 -> "OK"
                                    206 -> "Partial Content"
                                    302 -> "Found"
                                    403 -> "Forbidden"
                                    404 -> "Not Found"
                                    else -> "Status"
                                }
                                val hdrBytes = buildString {
                                    append("HTTP/1.1 ${resp.status} $reason\r\n")
                                    append("Content-Type: ${resp.contentType}\r\n")
                                    append("Content-Length: ${resp.body.size}\r\n")
                                    resp.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                                    append("Connection: close\r\n\r\n")
                                }.toByteArray(Charsets.ISO_8859_1)
                                val out = s.getOutputStream()
                                out.write(hdrBytes)
                                out.flush()
                                if (method != "HEAD" && resp.body.isNotEmpty()) {
                                    if (resp.chunkDelayMs > 0L) {
                                        var pos = 0
                                        while (pos < resp.body.size) {
                                            val len = minOf(resp.chunkSizeBytes, resp.body.size - pos)
                                            out.write(resp.body, pos, len)
                                            out.flush()
                                            pos += len
                                            if (pos < resp.body.size) Thread.sleep(resp.chunkDelayMs)
                                        }
                                    } else {
                                        out.write(resp.body)
                                        out.flush()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            running = false
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }

    private val ioDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val extractionIo: CoroutineDispatcher = Dispatchers.IO
    }

    @Test
    fun activeSegmentedAndHlsDownloads_yieldDuringInteractivePortalExtraction_andCompleteByteAccurately() = runBlocking {
        // 1.5 MB deterministic binary payload for active segmented download
        val fullPayload = ByteArray(1_536 * 1024) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        // 4 valid MPEG-TS packets per HLS segment
        val tsSeg = ByteArray(188 * 64) { idx -> if (idx % 188 == 0) 0x47 else ((idx * 13) and 0xFF).toByte() }

        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            when (req.path) {
                "/active-platform-video.mp4" -> {
                    val rangeHdr = req.headers["range"]
                    if (rangeHdr != null && rangeHdr.startsWith("bytes=")) {
                        val parts = rangeHdr.removePrefix("bytes=").split('-')
                        val start = parts[0].toLong().toInt()
                        val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (fullPayload.size - 1)
                        val slice = fullPayload.copyOfRange(start, end + 1)
                        RespInfo(
                            status = 206,
                            contentType = "video/mp4",
                            body = slice,
                            extraHeaders = mapOf(
                                "Accept-Ranges" to "bytes",
                                "Content-Range" to "bytes $start-$end/${fullPayload.size}"
                            ),
                            chunkDelayMs = 8L,
                            chunkSizeBytes = 32 * 1024
                        )
                    } else {
                        RespInfo(
                            status = 200,
                            contentType = "video/mp4",
                            body = fullPayload,
                            extraHeaders = mapOf("Accept-Ranges" to "bytes"),
                            chunkDelayMs = 8L,
                            chunkSizeBytes = 32 * 1024
                        )
                    }
                }
                "/active-hls/index.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = buildString {
                        append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
                        for (i in 1..6) {
                            append("#EXTINF:4.0,\n${serverRef.baseUrl}/active-hls/seg$i.ts\n")
                        }
                        append("#EXT-X-ENDLIST\n")
                    }.toByteArray()
                )
                "/active-hls/seg1.ts", "/active-hls/seg2.ts", "/active-hls/seg3.ts",
                "/active-hls/seg4.ts", "/active-hls/seg5.ts", "/active-hls/seg6.ts" -> RespInfo(
                    status = 200,
                    contentType = "video/mp2t",
                    body = tsSeg,
                    chunkDelayMs = 8L,
                    chunkSizeBytes = 4096
                )
                "/watch/rick-morty-ep1/" -> RespInfo(
                    status = 200,
                    contentType = "text/html; charset=utf-8",
                    body = """
                        <!doctype html>
                        <html>
                          <head><title>Rick and Morty Ep 1</title></head>
                          <body>
                            <script>var MyAjaxURL = "/wp-content/themes/movies2023/Ajaxat/";</script>
                            <ul>
                              <li data-id="9910" data-server="0" class="active">VideoTube</li>
                              <li data-id="9910" data-server="1">Uqload</li>
                              <li data-id="9910" data-server="2">Streamtape</li>
                            </ul>
                            <iframe src="${serverRef.baseUrl}/embed-dead-vidtube"></iframe>
                          </body>
                        </html>
                    """.trimIndent().toByteArray()
                )
                "/embed-dead-vidtube" -> RespInfo(status = 403, body = "Cloudflare challenge".toByteArray())
                "/wp-content/themes/movies2023/Ajaxat/Single/Server.php" -> when {
                    req.body.contains("i=1") -> RespInfo(
                        status = 200,
                        body = """<iframe src="${serverRef.baseUrl}/embed-uqload.html"></iframe>""".toByteArray()
                    )
                    req.body.contains("i=2") -> RespInfo(
                        status = 200,
                        body = """<iframe src="${serverRef.baseUrl}/embed-streamtape.html"></iframe>""".toByteArray()
                    )
                    else -> RespInfo(status = 404)
                }
                "/embed-uqload.html" -> {
                    // Simulate slow portal embed so active download definitely hits yield checks while extraction is active
                    Thread.sleep(180L)
                    RespInfo(
                        status = 200,
                        body = """
                            <html><script>
                            var player = new Clappr.Player({
                              sources: ["${serverRef.baseUrl}/cdn/uqload_720p.mp4"],
                              poster: "${serverRef.baseUrl}/posters/rick.jpg",
                              width: 1280,
                              height: 720
                            });
                            </script></html>
                        """.trimIndent().toByteArray()
                    )
                }
                "/cdn/uqload_720p.mp4" -> RespInfo(
                    status = 206,
                    contentType = "video/mp4",
                    body = byteArrayOf(0x00),
                    extraHeaders = mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes 0-0/180000000"
                    )
                )
                "/embed-streamtape.html" -> RespInfo(
                    status = 200,
                    body = """
                        <html><body>
                        <div id="norobotlink"></div>
                        <script>
                        document.getElementById('norobotlink').innerHTML = '${serverRef.baseUrl}/get_vi' + ('xxdeo?id=clip42&token=ST_TOK_99').substring(2);
                        </script>
                        </body></html>
                    """.trimIndent().toByteArray()
                )
                "/get_video" -> RespInfo(
                    status = 302,
                    extraHeaders = mapOf("Location" to "${serverRef.baseUrl}/ephemeral-single-use-cdn/video_480p.mp4")
                )
                "/ephemeral-single-use-cdn/video_480p.mp4" -> RespInfo(
                    status = 206,
                    contentType = "video/mp4",
                    body = byteArrayOf(0x00),
                    extraHeaders = mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes 0-0/95000000"
                    )
                )
                else -> RespInfo(status = 404)
            }
        }

        serverRef.use { server ->
            val client = OkHttpClient()
            val http = ExtractorHttp(client)
            val coordinator = ExtractionPriorityCoordinator(
                clockMs = { System.currentTimeMillis() },
                maxLeaseDurationMs = 15_000L,
                chunkYieldDelayMs = 20L
            )
            val tracker = ExtractionProgressTracker()
            val segmented = SegmentedDownloader(client, http, coordinator)
            val hlsDl = HlsSegmentDownloader(client, http, coordinator)
            val hlsExtractor = HlsExtractor(http)
            val dashExtractor = DashExtractor(http)
            val headless = mockk<HeadlessWebExtractor>(relaxed = true)
            val htmlExtractor = HtmlMediaExtractor(http, hlsExtractor, dashExtractor, headless, tracker).apply {
                cookieProvider = { null }
            }
            val network = mockk<NetworkMonitor> { every { isOnline() } returns true }
            val registry = ExtractorRegistry(setOf(htmlExtractor), network, ioDispatchers, coordinator, tracker)

            val segDest = File(tempFolder.root, "active_prog.mp4")
            val hlsDest = File(tempFolder.root, "active_hls.ts")

            coordinator.setActiveDownloadCount(2)
            val progJob = async(Dispatchers.IO) {
                segmented.download(
                    url = "${server.baseUrl}/active-platform-video.mp4",
                    headers = emptyMap(),
                    dest = segDest,
                    knownTotal = fullPayload.size.toLong(),
                    acceptsRanges = true,
                    segmentCount = 4,
                    onProgress = {}
                )
            }
            val hlsJob = async(Dispatchers.IO) {
                hlsDl.download(
                    playlistUrl = "${server.baseUrl}/active-hls/index.m3u8",
                    headers = emptyMap(),
                    dest = hlsDest,
                    onProgress = {}
                )
            }

            // Let bulk downloads get rolling, then interactively switch to TopCinema watch URL
            delay(35L)
            val resolved = registry.resolve("${server.baseUrl}/watch/rick-morty-ep1/")

            assertFalse(
                "Priority lease must be released immediately after interactive extraction returns",
                coordinator.isInteractiveExtractionActive()
            )
            assertTrue(
                "Active bulk downloads must cooperatively yield during interactive extraction (yields=${coordinator.yieldEventCount()})",
                coordinator.yieldEventCount() > 0L
            )
            assertTrue(
                "Expected Clappr string-array Uqload 720p stream to resolve",
                resolved.streams.any { it.url.contains("/cdn/uqload_720p.mp4") && it.height == 720 }
            )
            assertTrue(
                "Expected Streamtape tokenized /get_video URL to be preserved with 480p inferred from redirect target",
                resolved.streams.any { it.url.contains("/get_video?") && it.url.contains("token=ST_TOK_99") && it.height == 480 }
            )

            // Wait for background downloads to finish cleanly at full speed and verify exact bytes
            progJob.await()
            hlsJob.await()
            coordinator.setActiveDownloadCount(0)

            assertArrayEquals("Progressive download bytes must remain intact after cooperative yielding", fullPayload, segDest.readBytes())
            assertEquals("HLS download must assemble all 6 segments intact", tsSeg.size * 6L, hlsDest.length())
        }
    }

    @Test
    fun crossDomainCookieIsolation_blocksOuterPortalCookieBleedIntoEmbeds_whilePreservingEmbedSetCookie() = runBlocking {
        val cookieJar = WebViewCookieJar().apply {
            cookieManagerReader = { null }
        }
        val observedRequests = CopyOnWriteArrayList<Pair<String, String?>>()

        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            val host = req.headers["host"].orEmpty().substringBefore(':').lowercase()
            val sentCookie = req.headers["cookie"]
            observedRequests += "http://$host${req.path}" to sentCookie

            when {
                host == "updown.example" && req.path == "/embed-ok.html" -> {
                    if (sentCookie.orEmpty().contains("OUTER_PORTAL_CF")) {
                        RespInfo(status = 403, body = "Cross-domain cf_clearance rejected".toByteArray())
                    } else {
                        val port = serverRef.baseUrl.substringAfterLast(':')
                        RespInfo(
                            status = 200,
                            body = """<html><script>jwplayer("v").setup({file:"http://cdn.updown.example:$port/video_720p.mp4",height:720});</script></html>""".toByteArray(),
                            extraHeaders = mapOf("Set-Cookie" to "embed_tok=VALID_EMBED_99; Domain=updown.example; Path=/")
                        )
                    }
                }
                host == "cdn.updown.example" && req.path == "/video_720p.mp4" -> {
                    if (sentCookie.orEmpty().contains("OUTER_PORTAL_CF")) {
                        RespInfo(status = 403, body = "Leaked outer cookie rejected".toByteArray())
                    } else if (!sentCookie.orEmpty().contains("embed_tok=VALID_EMBED_99")) {
                        RespInfo(status = 403, body = "Missing embed session cookie".toByteArray())
                    } else {
                        RespInfo(
                            status = 206,
                            contentType = "video/mp4",
                            body = byteArrayOf(0x00),
                            extraHeaders = mapOf(
                                "Accept-Ranges" to "bytes",
                                "Content-Range" to "bytes 0-0/140000000"
                            )
                        )
                    }
                }
                else -> RespInfo(status = 404, body = "Not found".toByteArray())
            }
        }

        serverRef.use { server ->
            val port = server.baseUrl.substringAfterLast(':')
            val client = OkHttpClient.Builder()
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName("127.0.0.1"))
                })
                .cookieJar(cookieJar)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val manualCookie = req.header("Cookie")
                    if (!manualCookie.isNullOrBlank()) {
                        cookieJar.seedFromRequestHeader(req.url, manualCookie)
                        chain.proceed(req.newBuilder().removeHeader("Cookie").build())
                    } else {
                        chain.proceed(req)
                    }
                }
                .build()

            val http = ExtractorHttp(client)
            val portal = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))
            val pageUrl = "http://topcinemaa.one:$port/episode-1/watch/"
            val embedUrl = "http://updown.example:$port/embed-ok.html"
            val html = """<html><body><iframe src="$embedUrl"></iframe></body></html>"""
            val doc = Jsoup.parse(html, pageUrl)

            val result = portal.resolvePortalAndEmbeds(
                pageUrl = pageUrl,
                html = html,
                doc = doc,
                baseHeaders = mapOf(
                    "User-Agent" to Constants.DEFAULT_USER_AGENT,
                    "Cookie" to "cf_clearance=OUTER_PORTAL_CF; wp_sess=outer1"
                )
            )

            assertEquals(1, result.streams.size)
            assertEquals("http://cdn.updown.example:$port/video_720p.mp4", result.streams[0].url)
            assertTrue(
                "Expected embed-issued cookie preserved on resolved stream",
                result.streams[0].headers["Cookie"].orEmpty().contains("embed_tok=VALID_EMBED_99")
            )
            assertFalse(
                "Outer portal cookie must NEVER leak into resolved embed stream headers",
                result.streams[0].headers["Cookie"].orEmpty().contains("OUTER_PORTAL_CF")
            )
            assertTrue(
                "No wire request to updown.example may ever carry OUTER_PORTAL_CF",
                observedRequests.none { it.second.orEmpty().contains("OUTER_PORTAL_CF") }
            )
            assertTrue(
                "WebViewCookieJar for updown.example must remain clean of outer portal cookies",
                cookieJar.loadForRequest(embedUrl.toHttpUrl())
                    .none { it.value == "OUTER_PORTAL_CF" }
            )

            // Also verify HeadlessWebExtractor.mergeWebViewCookiesAndUa domain-family gating
            val headless = HeadlessWebExtractor(
                context = mockk<Context>(relaxed = true),
                sharedEngine = MediaInterceptionEngine(),
                http = http,
                hlsExtractor = HlsExtractor(http),
                dashExtractor = DashExtractor(http),
                dispatchers = ioDispatchers,
                progressTracker = ExtractionProgressTracker()
            ).apply {
                cookieLookup = { target ->
                    when {
                        target.contains("topcinemaa.one") -> "cf_clearance=OUTER_PORTAL_CF"
                        target.contains("updown.example") -> "embed_only=E1"
                        target.contains("othercdn.net") -> "cdn_only=C1"
                        else -> null
                    }
                }
            }
            val crossHostMerged = headless.mergeWebViewCookiesAndUa(
                streamUrl = "https://media.othercdn.net/v.mp4",
                headers = emptyMap(),
                embedUrl = "https://topcinemaa.one/watch/1"
            )
            assertEquals("cdn_only=C1", crossHostMerged["Cookie"])

            val sameFamilyMerged = headless.mergeWebViewCookiesAndUa(
                streamUrl = "https://cdn.updown.example/v.mp4",
                headers = emptyMap(),
                embedUrl = "https://updown.example/e/1"
            )
            assertTrue(sameFamilyMerged["Cookie"].orEmpty().contains("embed_only=E1"))
        }
    }

    @Test
    fun extractionSessionIsolation_andBackgroundRetry_neverClobberActiveUiStageOrDiagnostics() = runBlocking {
        val tracker = ExtractionProgressTracker()
        val coordinator = ExtractionPriorityCoordinator()
        val network = mockk<NetworkMonitor> { every { isOnline() } returns true }

        val session1 = tracker.beginSession()
        val session2 = tracker.beginSession()

        // Stale session 1 writes must be ignored once session 2 is active
        tracker.runInSession(session1) {
            tracker.update("Stale Stage From Session 1")
            tracker.setDiagnosticReason("Stale error from cancelled TikTok")
        }
        assertEquals(ExtractionProgressTracker.STAGE_DEFAULT, tracker.stage.value)
        assertNull(tracker.peekDiagnosticReasonForSession(session2))

        // Active session 2 writes succeed normally
        tracker.runInSession(session2) {
            tracker.update(ExtractionProgressTracker.STAGE_SERVERS)
            tracker.setDiagnosticReason("Current TopCinema diagnostic")
        }
        assertEquals(ExtractionProgressTracker.STAGE_SERVERS, tracker.stage.value)
        assertEquals("Current TopCinema diagnostic", tracker.peekDiagnosticReasonForSession(session2))

        // Background retry extraction via ExtractorRegistry.resolveBackground must NOT overwrite UI stage
        // and must NOT activate the interactive download-yielding lease.
        var leaseActiveDuringBackground = false
        val bgExtractor = object : MediaExtractor {
            override val name = "BG"
            override val priority = 100
            override fun canHandle(url: String) = true
            override suspend fun extract(url: String): ResolvedMedia {
                leaseActiveDuringBackground = coordinator.isInteractiveExtractionActive()
                tracker.update("Background Retry Stage Should Be Ignored")
                tracker.setDiagnosticReason("Background Retry Error Should Be Ignored")
                return ResolvedMedia(
                    sourceUrl = url,
                    title = "Refreshed",
                    thumbnailUrl = null,
                    streams = listOf(MediaStream("1", "https://cdn.example/refreshed.mp4", MediaType.VIDEO, "mp4", "720p")),
                    extractor = name
                )
            }
        }
        val registry = ExtractorRegistry(setOf(bgExtractor), network, ioDispatchers, coordinator, tracker)
        val refreshed = registry.resolveBackground("https://example.com/retry-item")
        assertEquals("Refreshed", refreshed.title)
        assertFalse("Background retry must never throttle active downloads", leaseActiveDuringBackground)
        assertEquals(ExtractionProgressTracker.STAGE_SERVERS, tracker.stage.value)
        assertEquals("Current TopCinema diagnostic", tracker.peekDiagnosticReasonForSession(session2))
    }

    @Test
    fun tikwmCircuitBreaker_underActiveDownloadContention_toleratesSingleTimeoutAndArmsOnSecond() = runBlocking {
        val tikwmCalls = AtomicInteger(0)
        var fakeClockMs = 2_000_000L
        val coordinator = ExtractionPriorityCoordinator().apply {
            setActiveDownloadCount(1)
        }
        val mobileHtml = """
            <html><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"__DEFAULT_SCOPE__":{"webapp.reflow.video.detail":{"itemInfo":{"itemStruct":{
              "id":"7500998877665544332","desc":"Contention Resilient Clip",
              "video":{"width":576,"height":1024,"duration":12,"playAddr":"https://v16.tiktokcdn.example/c.mp4"},
              "music":{"duration":12,"playUrl":"https://v16m.tiktokcdn.example/a.m4a"}
            }}}}}
            </script></body></html>
        """.trimIndent()

        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val social = SocialMediaExtractor(mockHttp, mockHeadless, ExtractionProgressTracker(), coordinator).apply {
            cookieProvider = { null }
            clockMs = { fakeClockMs }
        }

        val ttUrl = "https://www.tiktok.com/@creator/video/7500998877665544332"
        coEvery { mockHttp.fetchString(match { it.contains("tikwm.com") }, any()) } coAnswers {
            tikwmCalls.incrementAndGet()
            delay(2_400L) // Exceeds TIKWM_API_TIMEOUT_MS (2000ms)
            ""
        }
        coEvery { mockHttp.fetchHtmlWithCookies(ttUrl, any()) } coAnswers {
            val ua = secondArg<Map<String, String>>()["User-Agent"].orEmpty()
            if (ua.contains("Mobile", ignoreCase = true)) {
                delay(2_200L) // Let tikwm timeout complete while downloads are active
                ExtractorHttp.HtmlFetchResult(mobileHtml, "ttwid=ok", ttUrl)
            } else {
                ExtractorHttp.HtmlFetchResult("<html>empty</html>", null, ttUrl)
            }
        }

        // Timeout #1 under active download contention -> must NOT blacklist tikwm for 60s
        assertNotNull(social.extract(ttUrl))
        assertEquals(1, tikwmCalls.get())

        // Call #2 (5s later) -> MUST still try tikwm; second consecutive timeout arms the 60s breaker
        fakeClockMs += 5_000L
        assertNotNull(social.extract(ttUrl))
        assertEquals("Single contention timeout must not prematurely blacklist tikwm", 2, tikwmCalls.get())

        // Call #3 (5s later) -> now bypassed by 60s breaker after 2 consecutive timeouts
        fakeClockMs += 5_000L
        assertNotNull(social.extract(ttUrl))
        assertEquals("Second consecutive timeout should arm cooldown", 2, tikwmCalls.get())
    }

    @Test
    fun adversarialInvariants_webViewUaSanitizer_leaseAutoExpiry_clapprArray_andEncodedArabicWatchLinks() = runBlocking {
        // 1. WebView UA sanitization preserves real Chromium version & removes "; wv" and "Version/4.0"
        val rawAndroidWvUa =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP3A.241005.015; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/139.0.7258.94 Mobile Safari/537.36"
        val sanitized = HeadlessWebExtractor.sanitizeWebViewUserAgent(rawAndroidWvUa)
        assertFalse("Sanitized UA must strip '; wv'", sanitized.contains("; wv"))
        assertFalse("Sanitized UA must strip 'Version/4.0'", sanitized.contains("Version/4.0"))
        assertTrue("Sanitized UA must preserve exact installed Chrome version", sanitized.contains("Chrome/139.0.7258.94"))
        assertEquals(Constants.DEFAULT_USER_AGENT, HeadlessWebExtractor.sanitizeWebViewUserAgent(null))
        assertEquals(Constants.DEFAULT_USER_AGENT, HeadlessWebExtractor.sanitizeWebViewUserAgent("   "))
        assertEquals(Constants.DEFAULT_USER_AGENT, HeadlessWebExtractor.sanitizeWebViewUserAgent("CustomBot/1.0"))

        // 2. Lease auto-expiry & exception cleanup
        var clock = 10_000L
        val coordinator = ExtractionPriorityCoordinator(
            clockMs = { clock },
            maxLeaseDurationMs = 5_000L,
            chunkYieldDelayMs = 10L
        )
        runCatching {
            coordinator.withInteractiveExtraction<Unit> {
                assertTrue(coordinator.isInteractiveExtractionActive())
                clock += 6_000L
                assertFalse("Expired lease must auto-clear even if extraction hangs", coordinator.isInteractiveExtractionActive())
                throw MediaException(AppError.GENERIC, "Simulated extraction crash")
            }
        }
        assertFalse("Lease must remain inactive after exception", coordinator.isInteractiveExtractionActive())

        // 3. Clappr/Uqload string-array sources + adversarial malformed array
        val clapprJs = """
            new Clappr.Player({
              sources: ["https://cdn.uqload.example/v_720p.mp4", "https://doubleclick.net/ad.mp4"],
              poster: "https://cdn.uqload.example/thumb.jpg"
            });
            sources: ["unclosed-malformed
        """.trimIndent()
        val cfg = JsUnpacker.extractPlayerConfigs(clapprJs, baseUrl = "https://uqload.example/embed-1.html")
        assertEquals(1, cfg.sources.size)
        assertEquals("https://cdn.uqload.example/v_720p.mp4", cfg.sources[0].url)

        // 4. Encoded vs unencoded Arabic TopCinema /watch/ sub-page discovery
        val http = ExtractorHttp(OkHttpClient())
        val portal = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))
        val rawArabicPage = "https://topcinemaa.one/مسلسل-rick-and-morty-الموسم-1-الحلقة-1/"
        val encodedWatchHref = "https://topcinemaa.one/%D9%85%D8%B3%D9%84%D8%B3%D9%84-rick-and-morty-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-1-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9-1/watch/"
        val doc = Jsoup.parse("""<html><body><a href="$encodedWatchHref">مشاهدة</a></body></html>""", rawArabicPage)
        val subPages = portal.discoverPortalSubPages(rawArabicPage, doc)
        assertEquals(listOf(encodedWatchHref), subPages)
    }
}
