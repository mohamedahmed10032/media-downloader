package com.melmeligy.mediadownloader.extractor

import androidx.lifecycle.SavedStateHandle
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import com.melmeligy.mediadownloader.ui.navigation.Routes
import com.melmeligy.mediadownloader.ui.quality.QualityViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * Unit, integration, and adversarial tests covering:
 * - Immediate OkHttp Call cancellation on coroutine cancel/timeout (no hanging sockets)
 * - TikTok tikwm Cloudflare 403 cooldown circuit breaker & short-URL redirect cache
 * - Parallel HLS interior-segment calibration & adversarial stalling segment timeout
 * - HeadlessWebExtractor auxiliary resource blocking & early-exit state machine
 * - ExtractorRegistry short-TTL cache, ref-counted in-flight dedup, & invalidation rules
 * - Non-blocking StreamSizeRefiner + QualityViewModel background size update & stale-URL guard
 */
class ExtractionSpeedAndCancellationTest {

    private val ioDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    @Test
    fun withCancellableHttpCalls_immediatelyAbortsBlockingOkHttpSocketOnCancel() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        val requestAccepted = CountDownLatch(1)
        val clientClosedSocket = CountDownLatch(1)
        val running = AtomicBoolean(true)

        executor.submit {
            try {
                val sock = server.accept()
                sock.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    requestAccepted.countDown()
                    // Hold connection open without sending response; detect client FIN/RST
                    s.soTimeout = 100
                    val deadline = System.currentTimeMillis() + 8_000L
                    while (running.get() && System.currentTimeMillis() < deadline) {
                        try {
                            val b = s.getInputStream().read()
                            if (b == -1) {
                                clientClosedSocket.countDown()
                                break
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // still open
                        } catch (_: Exception) {
                            clientClosedSocket.countDown()
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // shutdown
            }
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
            val http = ExtractorHttp(client)
            val stallUrl = "http://127.0.0.1:${server.localPort}/hang"

            val job = launch(Dispatchers.IO) {
                withCancellableHttpCalls {
                    http.fetchString(stallUrl)
                }
            }

            assertTrue("Server should accept request quickly", requestAccepted.await(3, TimeUnit.SECONDS))
            val cancelElapsedMs = measureTimeMillis {
                job.cancel()
                job.join()
            }

            assertTrue("Cancelling blocking OkHttp call must return immediately (was ${cancelElapsedMs}ms)", cancelElapsedMs < 800L)
            assertTrue("Server must observe client TCP close promptly", clientClosedSocket.await(2, TimeUnit.SECONDS))
        } finally {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun socialMediaExtractor_tikwm403CircuitBreaker_skipsApiDuringCooldownAndResumesAfter() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tikwmCalls = AtomicInteger(0)
        val fakeNow = AtomicLong(1_000_000L)

        every { mockHttp.cookieHeaderFor(any()) } returns null
        coEvery { mockHttp.fetchString(match { it.contains("tikwm.com") }, any()) } answers {
            tikwmCalls.incrementAndGet()
            throw MediaException(AppError.GEO_BLOCKED, "HTTP 403")
        }

        fun hydrationHtml(id: String, desc: String): String = """
            <html><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {
              "__DEFAULT_SCOPE__": {
                "webapp.video-detail": {
                  "itemInfo": {
                    "itemStruct": {
                      "id": "$id",
                      "desc": "$desc",
                      "video": {
                        "duration": 10,
                        "cover": "https://p16.tiktokcdn.com/c_$id.jpg",
                        "bitrateInfo": [
                          {
                            "GearName": "normal_1080_0",
                            "Bitrate": 2000000,
                            "PlayAddr": {
                              "Width": 1080,
                              "Height": 1920,
                              "DataSize": 3100000,
                              "UrlList": ["https://v16.tiktokcdn.com/v/$id/?mime_type=video_mp4"]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              }
            }
            </script></body></html>
        """.trimIndent()

        coEvery { mockHttp.fetchHtmlWithCookies(match { it.contains("/video/") }, any()) } answers {
            // Simulate realistic HTML page latency so the concurrent tikwm 403 arrives and arms cooldown
            Thread.sleep(80)
            val u = firstArg<String>()
            val id = u.substringAfterLast("/")
            ExtractorHttp.HtmlFetchResult(hydrationHtml(id, "Clip $id"), "ttwid=ok", u)
        }

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless).apply {
            clockMs = { fakeNow.get() }
        }

        // First call hits tikwm (403 -> arms 60s cooldown) and wins via HTML hydration
        val first = extractor.extract("https://www.tiktok.com/@u/video/111")
        assertNotNull(first)
        assertEquals("Clip 111", first!!.title)
        assertEquals(1, tikwmCalls.get())

        // Second call 10s later MUST skip tikwm completely (0 extra API calls)
        fakeNow.addAndGet(10_000L)
        val second = extractor.extract("https://www.tiktok.com/@u/video/222")
        assertNotNull(second)
        assertEquals("Clip 222", second!!.title)
        assertEquals("tikwm should stay bypassed during 60s cooldown", 1, tikwmCalls.get())

        // Third call 65s later (past cooldown) re-checks tikwm
        fakeNow.addAndGet(65_000L)
        val third = extractor.extract("https://www.tiktok.com/@u/video/333")
        assertNotNull(third)
        assertEquals(2, tikwmCalls.get())
    }

    @Test
    fun socialMediaExtractor_shortUrlRedirectCache_cachesWithinTtlAndExpiresAfter() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val fakeNow = AtomicLong(500_000L)
        val shortUrl = "https://vt.tiktok.com/ZSYCached1/"
        val resolvedTarget = "https://www.tiktok.com/@creator/video/999000111"

        every { mockHttp.probe(shortUrl, any()) } returns HeadInfo(
            code = 200,
            contentType = "text/html",
            contentLength = -1L,
            acceptsRanges = false,
            finalUrl = "$resolvedTarget?is_from_webapp=1"
        )

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless).apply {
            clockMs = { fakeNow.get() }
        }

        assertEquals(resolvedTarget, extractor.resolveRedirectUrl(shortUrl))
        fakeNow.addAndGet(30_000L)
        assertEquals(resolvedTarget, extractor.resolveRedirectUrl(shortUrl))
        verify(exactly = 1) { mockHttp.probe(shortUrl, any()) }

        // Advance past 120s TTL -> refreshes redirect probe
        fakeNow.addAndGet(125_000L)
        assertEquals(resolvedTarget, extractor.resolveRedirectUrl(shortUrl))
        verify(exactly = 2) { mockHttp.probe(shortUrl, any()) }
    }

    @Test
    fun hlsExtractor_parallelCalibration_runsConcurrentlyAndTimesOutStallingSegmentsWithoutDoubleRetry() = runBlocking {
        // Part A: 3 slow probes (320ms each) must execute concurrently (<750ms, not ~960ms)
        val mockHttp = mockk<ExtractorHttp>()
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:10")
            for (i in 0 until 12) {
                appendLine("#EXTINF:10.0,")
                appendLine("seg_$i.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }
        coEvery { mockHttp.fetchString("https://hls.test/720.m3u8", any()) } returns playlist
        every { mockHttp.probe(match { it.contains("seg_") }, any(), any()) } answers {
            Thread.sleep(320)
            HeadInfo(206, "video/mp2t", 1_500_000L, true, firstArg())
        }

        val hls = HlsExtractor(mockHttp)
        var result: ResolvedMedia? = null
        val parallelMs = measureTimeMillis {
            result = hls.extract("https://hls.test/720.m3u8")
        }
        assertNotNull(result)
        // 12 segments * 1,500,000 bytes * 0.94 (TS-to-MP4 container calibration factor) = 16,920,000 bytes
        assertEquals(16_920_000L, result!!.streams.first().sizeBytes)
        assertTrue("3x320ms probes must run in parallel (was ${parallelMs}ms)", parallelMs < 750L)

        // Part B (adversarial): Real loopback server where every HLS segment stalls for 8s.
        // HlsExtractor must time out once (~1.8s) and NEVER perform a 3.2s double retry, falling back cleanly to declared BANDWIDTH.
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        val running = AtomicBoolean(true)
        val port = server.localPort
        val segmentHits = AtomicInteger(0)

        executor.submit {
            while (running.get()) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                executor.submit {
                    handleStallingHlsRequest(sock, segmentHits)
                }
            }
        }

        try {
            val realHttp = ExtractorHttp(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            )
            val realHls = HlsExtractor(realHttp)
            var masterResult: ResolvedMedia? = null
            val stallMs = measureTimeMillis {
                masterResult = realHls.extract("http://127.0.0.1:$port/master.m3u8")
            }
            assertNotNull(masterResult)
            assertEquals(2, masterResult!!.streams.size)
            // Declared BANDWIDTH fallback remains accurate (2,000,000 bps * 120s / 8 = 30,000,000 bytes)
            val hd = masterResult!!.streams.first { it.height == 1080 }
            assertEquals(30_000_000L, hd.sizeBytes)
            assertTrue("Stalling HLS calibration must abort cleanly within bounded timeout (was ${stallMs}ms)", stallMs < 2_600L)
            assertTrue("No double retry per segment (segmentHits=${segmentHits.get()})", segmentHits.get() <= 2)
        } finally {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    private fun handleStallingHlsRequest(sock: Socket, segmentHits: AtomicInteger) {
        sock.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val reqLine = reader.readLine() ?: return
            val path = reqLine.split(' ').getOrNull(1).orEmpty()
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
            }
            val body: String? = when (path) {
                "/master.m3u8" -> """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080
                    1080.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                    720.m3u8
                """.trimIndent()
                "/1080.m3u8", "/720.m3u8" -> buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-TARGETDURATION:10")
                    for (i in 0 until 12) {
                        appendLine("#EXTINF:10.0,")
                        appendLine("stall_$i.ts")
                    }
                    appendLine("#EXT-X-ENDLIST")
                }
                else -> null
            }
            if (body != null) {
                val bytes = body.toByteArray()
                val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(header.toByteArray())
                s.getOutputStream().write(bytes)
                s.getOutputStream().flush()
            } else if (path.startsWith("/stall_")) {
                segmentHits.incrementAndGet()
                try {
                    Thread.sleep(8_000)
                } catch (_: InterruptedException) {
                    // shutdown
                }
            }
        }
    }

    @Test
    fun headlessWebExtractor_resourceBlocker_doesNotBlockSubResources_soAntiAdblockNeverTriggers() {
        // HeadlessWebExtractor must NOT block page/ad/bait sub-resources so anti-adblock overlays never block players
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://www.google-analytics.com/analytics.js", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://www.googletagmanager.com/gtag/js?id=G-123", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://fonts.gstatic.com/s/cairo/v28/font.woff2", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://cdn.example.com/assets/banner.jpg", "image/webp,*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://cdn.example.com/thumb/no-ext", "image/avif,image/webp,*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://doubleclick.net/pagead/js/adsbygoogle.js", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "*/*", false))

        // Allowed critical navigations, player scripts, and real media streams
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://wecima.here/watch/ep1/", "text/html", true))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://cdn.example.com/hls/master.m3u8", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://cdn.example.com/video/1080p.mp4", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://v16-webapp-prime.us.tiktok.com/video/tos/useast5/?a=1988&mime_type=video_mp4", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://player.example.com/assets/jwplayer.js", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("blob:https://wecima.here/abc-123", "*/*", false))
    }

    @Test
    fun headlessWebExtractor_earlyExitPolicy_exitsPromptlyWithStreamAndMetadata_andGuardsAdversarialBlobs() {
        val hlsPayload = MediaStreamPayload(
            url = "https://cdn.example.com/master.m3u8",
            streamType = StreamType.HLS,
            mimeType = "application/vnd.apple.mpegurl",
            isAuthoritativePlayer = false
        )
        val mp4Payload = MediaStreamPayload(
            url = "https://cdn.example.com/movie.mp4",
            streamType = StreamType.PROGRESSIVE,
            mimeType = "video/mp4",
            isAuthoritativePlayer = false
        )
        val blobOnly = MediaStreamPayload(
            url = "blob:https://example.com/123",
            streamType = StreamType.BLOB,
            mimeType = "video/mp4"
        )
        val splitVideo = MediaStreamPayload(
            url = "https://rr1.googlevideo.com/videoplayback?mime=video%2Fmp4",
            streamType = StreamType.PROGRESSIVE,
            mimeType = "video/mp4"
        )
        val splitAudio = MediaStreamPayload(
            url = "https://rr1.googlevideo.com/videoplayback?mime=audio%2Fmp4",
            streamType = StreamType.PROGRESSIVE,
            mimeType = "audio/mp4"
        )

        // 1. Adversarial: blob-only capture must NOT exit early
        assertEquals(
            HeadlessWebExtractor.EarlyExitDecision.CONTINUE_WAITING,
            HeadlessWebExtractor.evaluateEarlyExit(
                captured = listOf(blobOnly),
                elapsedMs = 500L,
                firstVideoCapturedAtMs = null,
                hasGoodTitle = true,
                hasThumbnail = true,
                metadataScrapeCompleted = true,
                pageFinished = true
            )
        )

        // 2. Valid HLS stream with prefetched title + thumbnail exits immediately even before pageFinished
        assertEquals(
            HeadlessWebExtractor.EarlyExitDecision.EXIT_NOW,
            HeadlessWebExtractor.evaluateEarlyExit(
                captured = listOf(hlsPayload),
                elapsedMs = 450L,
                firstVideoCapturedAtMs = 420L,
                hasGoodTitle = true,
                hasThumbnail = true,
                metadataScrapeCompleted = false,
                pageFinished = false
            )
        )

        // 3. Valid stream without metadata triggers immediate metadata scrape first (instead of dropping title/thumb)
        assertEquals(
            HeadlessWebExtractor.EarlyExitDecision.TRIGGER_METADATA_SCRAPE,
            HeadlessWebExtractor.evaluateEarlyExit(
                captured = listOf(hlsPayload),
                elapsedMs = 500L,
                firstVideoCapturedAtMs = 420L,
                hasGoodTitle = false,
                hasThumbnail = false,
                metadataScrapeCompleted = false,
                pageFinished = false
            )
        )

        // 4. Once metadata scrape finishes, progressive MP4 exits after tiny settle window (220ms), not 3.2s
        assertEquals(
            HeadlessWebExtractor.EarlyExitDecision.EXIT_NOW,
            HeadlessWebExtractor.evaluateEarlyExit(
                captured = listOf(mp4Payload),
                elapsedMs = 700L,
                firstVideoCapturedAtMs = 450L,
                hasGoodTitle = true,
                hasThumbnail = false,
                metadataScrapeCompleted = true,
                pageFinished = false
            )
        )

        // 5. Split video-only stream waits briefly (<750ms) for sibling audio, then exits once audio arrives
        assertEquals(
            HeadlessWebExtractor.EarlyExitDecision.CONTINUE_WAITING,
            HeadlessWebExtractor.evaluateEarlyExit(
                captured = listOf(splitVideo),
                elapsedMs = 600L,
                firstVideoCapturedAtMs = 400L,
                hasGoodTitle = true,
                hasThumbnail = true,
                metadataScrapeCompleted = true,
                pageFinished = false
            )
        )
        assertEquals(
            HeadlessWebExtractor.EarlyExitDecision.EXIT_NOW,
            HeadlessWebExtractor.evaluateEarlyExit(
                captured = listOf(splitVideo, splitAudio),
                elapsedMs = 650L,
                firstVideoCapturedAtMs = 400L,
                hasGoodTitle = true,
                hasThumbnail = true,
                metadataScrapeCompleted = true,
                pageFinished = false
            )
        )

        // 6. Prefetched metadata hint round-trip (single-consume)
        HeadlessWebExtractor.hintPageMetadata("https://wecima.here/watch/test", "Test Movie", "https://wecima.here/poster.jpg")
        assertEquals("Test Movie" to "https://wecima.here/poster.jpg", HeadlessWebExtractor.consumePrefetchedMetadataHint("https://wecima.here/watch/test"))
        assertNull(HeadlessWebExtractor.consumePrefetchedMetadataHint("https://wecima.here/watch/test"))
    }

    @Test
    fun extractorRegistry_ttlCache_invalidation_freshInterceptionBypass_and_refCountedDedupCancellation() = runBlocking {
        val networkMonitor = mockk<NetworkMonitor>().apply { every { isOnline() } returns true }
        val fakeNow = AtomicLong(10_000L)
        val extractCount = AtomicInteger(0)
        val interceptedAvailable = AtomicBoolean(false)
        val failNext = AtomicBoolean(false)

        val baseExtractor = object : MediaExtractor {
            override val name = "FakeHTML"
            override val priority = 50
            override fun canHandle(url: String) = true
            override suspend fun extract(url: String): ResolvedMedia {
                extractCount.incrementAndGet()
                if (failNext.get()) throw MediaException(AppError.GENERIC, "Transient network timeout")
                return ResolvedMedia(
                    sourceUrl = url,
                    title = "Base #${extractCount.get()}",
                    streams = listOf(
                        MediaStream("s1", "https://cdn.test/v.mp4", MediaType.VIDEO, "mp4", "720p", height = 720, sizeBytes = 1024L)
                    ),
                    extractor = name
                )
            }
        }

        val interceptedExtractor = object : MediaExtractor {
            override val name = "Live Stream"
            override val priority = 120
            override fun canHandle(url: String) = interceptedAvailable.get()
            override suspend fun extract(url: String): ResolvedMedia? {
                if (!interceptedAvailable.get()) return null
                return ResolvedMedia(
                    sourceUrl = url,
                    title = "Live Browser Capture",
                    streams = listOf(
                        MediaStream("live1", "https://cdn.test/live.m3u8", MediaType.VIDEO, "mp4", "1080p", height = 1080, isHls = true)
                    ),
                    extractor = name
                )
            }
        }

        val registry = ExtractorRegistry(setOf(baseExtractor, interceptedExtractor), networkMonitor, ioDispatchers).apply {
            clockMs = { fakeNow.get() }
            cacheTtlMs = 90_000L
        }

        val targetUrl = "https://example.com/watch/1"

        // 1. Transient errors are NEVER cached
        failNext.set(true)
        val err = runCatching { registry.resolve(targetUrl) }.exceptionOrNull()
        assertTrue(err is MediaException)
        failNext.set(false)
        val firstOk = registry.resolve(targetUrl)
        assertEquals("Base #2", firstOk.title)

        // 2. Cache hit within TTL (<90s)
        fakeNow.addAndGet(45_000L)
        val cachedHit = registry.resolve(targetUrl)
        assertEquals("Base #2", cachedHit.title)
        assertEquals(2, extractCount.get())

        // 3. Fresh live browser interception MUST bypass stale non-intercepted cache
        interceptedAvailable.set(true)
        val liveHit = registry.resolve(targetUrl)
        assertEquals("Live Browser Capture", liveHit.title)
        interceptedAvailable.set(false)

        // 4. Explicit invalidate() forces fresh extraction
        registry.invalidate(targetUrl)
        val afterInvalidate = registry.resolve(targetUrl)
        assertEquals("Base #3", afterInvalidate.title)

        // 5. Cache expires cleanly after TTL (91s)
        fakeNow.addAndGet(91_000L)
        val afterExpiry = registry.resolve(targetUrl)
        assertEquals("Base #4", afterExpiry.title)

        // 6. Ref-counted in-flight deduplication:
        //    Case A: Caller 1 cancels, Caller 2 stays alive -> underlying extractor completes for Caller 2!
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val dedupCalls = AtomicInteger(0)
        val cancelledUnderlying = AtomicBoolean(false)

        val slowExtractor = object : MediaExtractor {
            override val name = "SlowDedup"
            override val priority = 80
            override fun canHandle(url: String) = true
            override suspend fun extract(url: String): ResolvedMedia {
                dedupCalls.incrementAndGet()
                started.complete(Unit)
                try {
                    gate.await()
                } catch (ce: CancellationException) {
                    cancelledUnderlying.set(true)
                    throw ce
                }
                return ResolvedMedia(
                    sourceUrl = url,
                    title = "Shared Result",
                    streams = listOf(MediaStream("d1", "https://cdn.test/d.mp4", MediaType.VIDEO, "mp4", "1080p", height = 1080))
                )
            }
        }
        val dedupRegistry = ExtractorRegistry(setOf(slowExtractor), networkMonitor, ioDispatchers)

        val caller1 = async(Dispatchers.IO) { dedupRegistry.resolve("https://example.com/shared") }
        val caller2 = async(Dispatchers.IO) { dedupRegistry.resolve("https://example.com/shared") }
        started.await()
        delay(60)
        caller1.cancel()
        gate.complete(Unit)
        val res2 = caller2.await()
        assertEquals("Shared Result", res2.title)
        assertEquals("Both waiters must share 1 underlying extraction", 1, dedupCalls.get())
        assertFalse("Cancelling 1 of 2 waiters must NOT kill underlying extraction", cancelledUnderlying.get())

        //    Case B: ALL waiters cancel -> underlying extractor is cancelled immediately (no leak)
        val gate2 = CompletableDeferred<Unit>()
        val started2 = CompletableDeferred<Unit>()
        val underlyingCancelled2 = CompletableDeferred<Unit>()
        val cancelExtractor = object : MediaExtractor {
            override val name = "CancelAll"
            override val priority = 80
            override fun canHandle(url: String) = true
            override suspend fun extract(url: String): ResolvedMedia {
                started2.complete(Unit)
                try {
                    gate2.await()
                } catch (ce: CancellationException) {
                    underlyingCancelled2.complete(Unit)
                    throw ce
                }
                error("should have been cancelled")
            }
        }
        val cancelRegistry = ExtractorRegistry(setOf(cancelExtractor), networkMonitor, ioDispatchers)
        val w1 = async(Dispatchers.IO) { cancelRegistry.resolve("https://example.com/cancel-all") }
        val w2 = async(Dispatchers.IO) { cancelRegistry.resolve("https://example.com/cancel-all") }
        started2.await()
        delay(60)
        w1.cancel()
        w2.cancel()
        val cancelDetectMs = measureTimeMillis {
            underlyingCancelled2.await()
        }
        assertTrue("Cancelling all waiters must cancel underlying job immediately (was ${cancelDetectMs}ms)", cancelDetectMs < 500L)
    }

    @Test
    fun streamSizeRefiner_and_qualityViewModel_refinesMissingSizesAsynchronouslyAndDiscardsStaleUrlUpdates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val testDispatchers = object : DispatcherProvider {
                override val io: CoroutineDispatcher = testDispatcher
                override val main: CoroutineDispatcher = testDispatcher
                override val default: CoroutineDispatcher = testDispatcher
            }

            val mockHttp = mockk<ExtractorHttp>()
            val probeGate = CompletableDeferred<Unit>()

            every { mockHttp.probe("https://cdn.test/v720.mp4", any(), any()) } returns HeadInfo(
                code = 206,
                contentType = "video/mp4",
                contentLength = 12_500_000L,
                acceptsRanges = true,
                finalUrl = "https://cdn.test/v720.mp4"
            )
            every { mockHttp.probe("https://cdn.test/a128.m4a", any(), any()) } returns HeadInfo(
                code = 206,
                contentType = "audio/mp4",
                contentLength = 1_600_000L,
                acceptsRanges = true,
                finalUrl = "https://cdn.test/a128.m4a"
            )
            // Adversarial: server returns text/html error page with Content-Length > 0 -> must be rejected
            every { mockHttp.probe("https://cdn.test/broken.mp4", any(), any()) } returns HeadInfo(
                code = 200,
                contentType = "text/html; charset=utf-8",
                contentLength = 54_321L,
                acceptsRanges = false,
                finalUrl = "https://cdn.test/broken.mp4"
            )

            val refiner = StreamSizeRefiner(mockHttp, testDispatchers)
            val initialMedia = ResolvedMedia(
                sourceUrl = "https://site.test/ep1",
                title = "Fast Video",
                streams = listOf(
                    MediaStream("v1", "https://cdn.test/v720.mp4", MediaType.VIDEO, "mp4", "720p", height = 720, sizeBytes = null),
                    MediaStream(
                        "a1", "https://cdn.test/a128.m4a", MediaType.AUDIO, "mp3", "MP3 160k",
                        bitrateKbps = 160, sizeBytes = null,
                        headers = mapOf("X-Internal-Duration-Seconds" to "100")
                    ),
                    MediaStream("v-bad", "https://cdn.test/broken.mp4", MediaType.VIDEO, "mp4", "480p", height = 480, sizeBytes = null),
                    MediaStream("hls1", "https://cdn.test/master.m3u8", MediaType.VIDEO, "mp4", "1080p", height = 1080, isHls = true, sizeBytes = null)
                )
            )

            val refined = refiner.refineMissingSizes(initialMedia)
            assertNotNull(refined)
            // Preserves stream IDs and order while updating valid sizes & rejecting text/html
            assertEquals(listOf("v1", "a1", "v-bad", "hls1"), refined!!.streams.map { it.id })
            assertEquals(12_500_000L, refined.streams[0].sizeBytes)
            assertEquals("true", refined.streams[0].headers["X-Internal-Accepts-Ranges"])
            assertTrue("Transcoded MP3 size should be computed from probed audio bytes", (refined.streams[1].sizeBytes ?: 0L) > 1_500_000L)
            assertNull("HTML response must not poison stream size", refined.streams[2].sizeBytes)
            assertNull("HLS streams are skipped by progressive refiner", refined.streams[3].sizeBytes)

            // QualityViewModel integration: displays Success immediately, then refines smoothly in background
            val mediaRepo = mockk<MediaRepository>()
            val downloadManager = mockk<DownloadManager>(relaxed = true)
            val settingsRepo = mockk<SettingsRepository>()
            every { settingsRepo.settings } returns MutableStateFlow(AppSettings())
            coEvery { settingsRepo.current() } returns AppSettings()
            coEvery { mediaRepo.resolve("https://site.test/ep1") } returns initialMedia

            val gatedRefiner = mockk<StreamSizeRefiner>()
            every { gatedRefiner.needsRefinement(initialMedia) } returns true
            coEvery { gatedRefiner.refineMissingSizes(initialMedia, any(), any()) } coAnswers {
                probeGate.await()
                refined
            }

            val vm = QualityViewModel(
                mediaRepository = mediaRepo,
                downloadManager = downloadManager,
                settingsRepository = settingsRepo,
                streamSizeRefiner = gatedRefiner,
                savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_URL to "https://site.test/ep1"))
            )
            // Advance until resolve() succeeds while background refinement is still waiting on probeGate
            advanceUntilIdle()
            val immediateState = vm.state.value
            assertTrue("Qualities must be shown immediately without blocking on size probe", immediateState is Resource.Success)
            assertNull((immediateState as Resource.Success).data.streams[0].sizeBytes)

            // Complete background probe -> UI state updates smoothly with exact byte sizes
            probeGate.complete(Unit)
            advanceUntilIdle()
            val refinedState = vm.state.value as Resource.Success
            assertEquals(12_500_000L, refinedState.data.streams[0].sizeBytes)
            assertEquals(listOf("v1", "a1", "v-bad", "hls1"), refinedState.data.streams.map { it.id })

            // Adversarial stale-URL guard: start ep2, switch to ep3 before ep2 refinement returns -> ep3 must not be overwritten
            val ep2Media = ResolvedMedia(
                sourceUrl = "https://site.test/ep2",
                title = "Episode 2",
                streams = listOf(MediaStream("v2", "https://cdn.test/ep2.mp4", MediaType.VIDEO, "mp4", "720p", height = 720, sizeBytes = null))
            )
            val ep2Refined = ep2Media.copy(
                streams = listOf(ep2Media.streams.first().copy(sizeBytes = 9_999_999L))
            )
            val ep3Media = ResolvedMedia(
                sourceUrl = "https://site.test/ep3",
                title = "Episode 3",
                streams = listOf(MediaStream("v3", "https://cdn.test/ep3.mp4", MediaType.VIDEO, "mp4", "1080p", height = 1080, sizeBytes = 20_000_000L))
            )
            val ep2Gate = CompletableDeferred<Unit>()
            coEvery { mediaRepo.resolve("https://site.test/ep2") } returns ep2Media
            coEvery { mediaRepo.resolve("https://site.test/ep3") } returns ep3Media
            every { gatedRefiner.needsRefinement(ep2Media) } returns true
            every { gatedRefiner.needsRefinement(ep3Media) } returns false
            coEvery { gatedRefiner.refineMissingSizes(ep2Media, any(), any()) } coAnswers {
                ep2Gate.await()
                ep2Refined
            }

            vm.resolve("https://site.test/ep2")
            advanceUntilIdle()
            assertEquals("Episode 2", (vm.state.value as Resource.Success).data.title)

            // Switch to ep3 while ep2 refinement is pending
            vm.resolve("https://site.test/ep3")
            advanceUntilIdle()
            ep2Gate.complete(Unit)
            advanceUntilIdle()

            val finalState = vm.state.value as Resource.Success
            assertEquals("Episode 3", finalState.data.title)
            assertEquals(20_000_000L, finalState.data.streams.single().sizeBytes)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
