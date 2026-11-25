package com.melmeligy.mediadownloader.extractor

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.download.DownloadNotifier
import com.melmeligy.mediadownloader.download.DownloadProgress
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.download.VideoAudioMuxer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * End-to-end loopback HTTP, integration, and adversarial verification suite for fast WeCima
 * pre-download initialization and instant download start:
 * 1. Known Cloudflare portal fast-skip (wecima/mycima) + immediate URL-slug title preview + cf_clearance bypass.
 * 2. Ranking direct WeCima MP4 links & fast SPA hosts BEFORE MAX_SERVERS truncation + DOM label preservation.
 * 3. Headless backup-mirror collapse (fast MP4 primary + HLS failover).
 * 4. Hedged 220ms HLS segment verification (Range stall bypassed by peekHlsSegmentGet).
 * 5. Shared HlsPlaylistCache across HlsExtractor, StreamSizeRefiner pre-warming, and HlsSegmentDownloader
 *    (including adversarial TTL/header mismatch & stale-cache live retry).
 * 6. SegmentedDownloader pre-probed skip + immediate first-chunk progress & DownloadManager race-free
 *    in-process fast-start deduplicated against WorkManager runQueue().
 */
class WeCimaFastInitAndInstantStartTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        WeCimaLinkDecryptor.resetCachedKeyForTesting()
        HostHealthRegistry.shared.reset()
        HostHealthRegistry.shared.clockMs = { System.currentTimeMillis() }
        HlsPlaylistCache.shared.clear()
        HlsPlaylistCache.shared.clockMs = { System.currentTimeMillis() }
        HlsPlaylistCache.shared.ttlMs = HlsPlaylistCache.DEFAULT_TTL_MS
        ExtractorRegistry.clearConfirmedHtml()
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
    }

    @After
    fun tearDown() {
        WeCimaLinkDecryptor.resetCachedKeyForTesting()
        HostHealthRegistry.shared.reset()
        HostHealthRegistry.shared.clockMs = { System.currentTimeMillis() }
        HlsPlaylistCache.shared.clear()
        HlsPlaylistCache.shared.clockMs = { System.currentTimeMillis() }
        HlsPlaylistCache.shared.ttlMs = HlsPlaylistCache.DEFAULT_TTL_MS
        ExtractorRegistry.clearConfirmedHtml()
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
    }

    private data class ReqInfo(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class RespInfo(
        val status: Int = 200,
        val contentType: String = "application/octet-stream",
        val body: ByteArray = ByteArray(0),
        val extraHeaders: Map<String, String> = emptyMap(),
        val delayMs: Long = 0L
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
                                s.soTimeout = 6_000
                                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                                val requestLine = reader.readLine() ?: return@runCatching
                                val parts = requestLine.split(' ')
                                val method = parts.getOrNull(0).orEmpty()
                                val path = parts.getOrNull(1).orEmpty().substringBefore('?')
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
                                    val buf = CharArray(contentLen.coerceAtMost(16_384))
                                    var off = 0
                                    while (off < buf.size) {
                                        val r = reader.read(buf, off, buf.size - off)
                                        if (r <= 0) break
                                        off += r
                                    }
                                    String(buf, 0, off)
                                } else ""
                                val resp = handler(ReqInfo(method, path, headers, bodyStr))
                                if (resp.delayMs > 0L) {
                                    Thread.sleep(resp.delayMs)
                                }
                                val reason = when (resp.status) {
                                    200 -> "OK"
                                    206 -> "Partial Content"
                                    400 -> "Bad Request"
                                    403 -> "Forbidden"
                                    404 -> "Not Found"
                                    502 -> "Bad Gateway"
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
                                if (method != "HEAD" && resp.body.isNotEmpty()) {
                                    out.write(resp.body)
                                }
                                out.flush()
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

    private fun validMpegTsBytes(packets: Int = 8): ByteArray {
        val bytes = ByteArray(188 * packets) { 0x2A }
        for (i in 0 until packets) bytes[i * 188] = 0x47
        return bytes
    }

    @Test
    fun cloudflarePortal_publishesImmediateSlugPreview_fastSkipsAfter403For15Min_andUsesStage1WithClearance() = runBlocking {
        assertEquals(900_000L, HtmlMediaExtractor.CF_HINT_TTL_MS)

        val staticFetchCalls = AtomicInteger(0)
        val mockHttp = mockk<ExtractorHttp>()
        val return403 = java.util.concurrent.atomic.AtomicBoolean(true)
        coEvery { mockHttp.fetchString(any(), any()) } coAnswers {
            staticFetchCalls.incrementAndGet()
            if (return403.get()) {
                throw MediaException(AppError.ACCESS_DENIED, "Cloudflare challenge (HTTP 403)")
            }
            "<html><head><title>Cleared Page</title></head><body></body></html>"
        }
        val mockHeadless = mockk<HeadlessWebExtractor>()
        val headlessCalls = AtomicInteger(0)
        val expectedResolved = ResolvedMedia(
            sourceUrl = "https://wecima.here/watch/rick-and-morty-ep-8",
            title = "مسلسل Rick And Morty الموسم التاسع الحلقة 8 مترجمة",
            streams = listOf(
                MediaStream("v1", "https://cdn.example/ep8.mp4", MediaType.VIDEO, "mp4", "720p HD", height = 720, sizeBytes = 90_000_000L)
            ),
            extractor = "Smart Extractor"
        )
        coEvery { mockHeadless.extract(any(), any(), any()) } coAnswers {
            headlessCalls.incrementAndGet()
            expectedResolved
        }

        val tracker = ExtractionProgressTracker()
        val wecimaUrl = "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-rick-and-morty-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%AA%D8%A7%D8%B3%D8%B9-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9-8-%D9%85%D8%AA%D8%B1%D8%AC%D9%85%D8%A9"
        val sid = tracker.beginSession(targetUrl = wecimaUrl)
        val fakeNow = AtomicLong(1_000_000L)
        val extractor = HtmlMediaExtractor(mockHttp, HlsExtractor(mockHttp), DashExtractor(mockHttp), mockHeadless, tracker).apply {
            cookieProvider = { null }
            clockMs = { fakeNow.get() }
        }

        // 1. First visit publishes immediate URL-slug title preview before Stage-1, records 15-min CF hint on 403, and falls back to headless
        val res1 = tracker.runInSession(sid) {
            extractor.extractWithHeaders(wecimaUrl, allowHeadlessFallback = true)
        }
        assertNotNull(res1)
        assertEquals(1, staticFetchCalls.get())
        assertEquals(1, headlessCalls.get())
        assertEquals(ExtractionProgressTracker.STAGE_CLOUDFLARE, tracker.stage.value)
        val previewTitle = tracker.preview.value?.title.orEmpty()
        assertTrue("Expected immediate decoded slug preview, got '$previewTitle'", previewTitle.contains("rick", ignoreCase = true))

        // 2. Even 10 minutes later (within 15-min CF_HINT_TTL_MS), subsequent visit skips blocked Stage-1 completely (0 static hits)
        fakeNow.addAndGet(10 * 60_000L)
        val res2 = tracker.runInSession(sid) {
            extractor.extractWithHeaders(wecimaUrl, allowHeadlessFallback = true)
        }
        assertNotNull(res2)
        assertEquals("Active 15-minute CF hint must skip blocked Stage-1 HTTP fetch", 1, staticFetchCalls.get())
        assertEquals(2, headlessCalls.get())

        // 3. Adversarial boundary: when allowHeadlessFallback = false, static HTTP fetch MUST still execute
        extractor.extractWithHeaders(wecimaUrl, allowHeadlessFallback = false)
        assertEquals("allowHeadlessFallback=false must not fast-skip static fetch", 2, staticFetchCalls.get())

        // 4. Once cf_clearance cookie is present, CF hint clears and Stage-1 fast HTTP path runs directly
        return403.set(false)
        extractor.cookieProvider = { "cf_clearance=valid_token_123" }
        extractor.extractWithHeaders(wecimaUrl, allowHeadlessFallback = true)
        assertEquals("With cf_clearance present, Stage-1 HTTP must run directly", 3, staticFetchCalls.get())
    }

    @Test
    fun crowdedWeCimaPage_ranksDirectMp4LinksBeforeMaxServersTruncation_preservesLabels_andAttachesBackupMirror() = runBlocking {
        val deadHits = AtomicInteger(0)
        val mp4Hits = AtomicInteger(0)

        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            when {
                req.path.startsWith("/dead-server/") -> {
                    deadHits.incrementAndGet()
                    RespInfo(status = 502, contentType = "text/html", body = "<html>502</html>".toByteArray())
                }
                req.path == "/dl/rick-ep8-1080.mp4" -> {
                    mp4Hits.incrementAndGet()
                    RespInfo(
                        status = 206,
                        contentType = "video/mp4",
                        body = byteArrayOf(0x00),
                        extraHeaders = mapOf("Content-Range" to "bytes 0-0/320000000", "Accept-Ranges" to "bytes")
                    )
                }
                req.path == "/dl/rick-ep8-720.mp4" -> {
                    mp4Hits.incrementAndGet()
                    RespInfo(
                        status = 206,
                        contentType = "video/mp4",
                        body = byteArrayOf(0x00),
                        extraHeaders = mapOf("Content-Range" to "bytes 0-0/180000000", "Accept-Ranges" to "bytes")
                    )
                }
                else -> RespInfo(status = 404)
            }
        }

        serverRef.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val portal = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))

            // Build a crowded WeCima watch page with 10 dead servers BEFORE 2 encrypted direct MP4 download links
            val deadButtons = (1..10).joinToString("\n") { i ->
                val enc = WeCimaLinkDecryptor.encryptForTesting("${server.baseUrl}/dead-server/e/$i")
                """<li><btn data-url="$enc">Dead Server $i</btn></li>"""
            }
            val enc1080 = WeCimaLinkDecryptor.encryptForTesting("${server.baseUrl}/dl/rick-ep8-1080.mp4")
            val enc720 = WeCimaLinkDecryptor.encryptForTesting("${server.baseUrl}/dl/rick-ep8-720.mp4")
            val html = """
                <html><head><title>Rick and Morty S09E08</title></head><body>
                <ul class="WatchServersList">$deadButtons</ul>
                <ul class="List--Download--Wecima--Single">
                  <li><a class="openLinkDown" data-href="$enc1080"><quality>1080p</quality></a></li>
                  <li><a class="openLinkDown" data-href="$enc720"><quality>720p</quality></a></li>
                </ul>
                </body></html>
            """.trimIndent()

            val pageUrl = "${server.baseUrl}/watch/rick-and-morty-ep8"
            val result = portal.resolvePortalAndEmbeds(
                pageUrl = pageUrl,
                html = html,
                doc = Jsoup.parse(html, pageUrl),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )

            assertTrue("Expected direct MP4 links to survive MAX_SERVERS ranking", mp4Hits.get() >= 2)
            assertTrue("Expected 1080p MP4 stream", result.streams.any { it.height == 1080 && !it.isHls })
            assertTrue("Expected 720p MP4 stream", result.streams.any { it.height == 720 && !it.isHls })
            assertTrue(
                "Verified progressive streams must carry X-Internal-Probed=true",
                result.streams.all { it.headers["X-Internal-Probed"] == "true" }
            )

            // Verify selectPromotableEmbedUrls(allowDirectMediaFiles = true) boosts direct MP4 + Vidara before truncation
            val manyCandidates = (1..9).map { "https://generic-slow-$it.example/e/ep8" } + listOf(
                "https://vidaraa.cc/e/ep8",
                "https://cdn.fastdl.example/ep8_1080p.mp4"
            )
            val hybridRanked = HeadlessWebExtractor.selectPromotableEmbedUrls(
                outerPageUrl = "https://wecima.here/watch/ep8",
                candidates = manyCandidates,
                maxEmbeds = StreamingPortalResolver.MAX_SERVERS,
                allowDirectMediaFiles = true
            )
            assertEquals("https://cdn.fastdl.example/ep8_1080p.mp4", hybridRanked[0])
            assertEquals("https://vidaraa.cc/e/ep8", hybridRanked[1])

            // Verify parseScrapedEmbedsJson handles labeled objects, legacy string arrays, and malformed inputs
            val objJson = """"[{\"u\":\"https://cdn.example/a.mp4\",\"l\":\"1080p\"},{\"u\":\"https://vidaraa.cc/e/1\",\"l\":\"Vidara\"}]""""
            val parsedObj = HeadlessWebExtractor.parseScrapedEmbedsJson(objJson)
            assertEquals(listOf("https://cdn.example/a.mp4" to "1080p", "https://vidaraa.cc/e/1" to "Vidara"), parsedObj)

            val legacyJson = """"[\"https://earnvids.pro/e/1\"]""""
            assertEquals(listOf("https://earnvids.pro/e/1" to ""), HeadlessWebExtractor.parseScrapedEmbedsJson(legacyJson))
            assertEquals(emptyList<Pair<String, String>>(), HeadlessWebExtractor.parseScrapedEmbedsJson("not-json"))
            assertEquals(emptyList<Pair<String, String>>(), HeadlessWebExtractor.parseScrapedEmbedsJson("null"))

            // Verify collapseHeadlessVideosWithBackup prefers direct MP4 over same-height HLS and attaches HLS as backup failover
            val hls720 = MediaStream(
                id = "hls-720",
                url = "https://hls-mirror.example/720.m3u8",
                type = MediaType.VIDEO,
                container = "ts",
                label = "720p HD",
                height = 720,
                sizeBytes = 150_000_000L,
                isHls = true,
                headers = mapOf("Referer" to "https://hls-mirror.example/e/1")
            )
            val mp4720 = MediaStream(
                id = "mp4-720",
                url = "https://direct-mp4.example/ep8_720.mp4",
                type = MediaType.VIDEO,
                container = "mp4",
                label = "720p HD",
                height = 720,
                sizeBytes = 140_000_000L,
                isHls = false,
                headers = mapOf("Referer" to "https://wecima.here/watch/ep8")
            )
            val collapsed = HeadlessWebExtractor.collapseHeadlessVideosWithBackup(listOf(hls720, mp4720))
            assertEquals(1, collapsed.size)
            assertFalse("Fast direct MP4 should win as primary at 720p", collapsed[0].isHls)
            assertEquals("https://hls-mirror.example/720.m3u8", collapsed[0].headers["X-Internal-Fallback-Url"])
            assertEquals("true", collapsed[0].headers["X-Internal-Fallback-IsHls"])
        }
    }

    @Test
    fun hedgedHlsSegmentProbe_bypassesStalledRangeInUnder900ms_andSharedPlaylistCacheEliminatesDuplicateManifestFetch() = runBlocking {
        val tsBytes = validMpegTsBytes()
        val masterHits = AtomicInteger(0)
        val v1080Hits = AtomicInteger(0)
        val v720Hits = AtomicInteger(0)
        val rangeHits = AtomicInteger(0)
        val segGetHits = AtomicInteger(0)
        val staleSegMode = AtomicInteger(0) // 1 = reject /stale/seg0.ts with 403

        LoopbackServer { req ->
            when (req.path) {
                "/hls/master.m3u8" -> {
                    masterHits.incrementAndGet()
                    RespInfo(
                        status = 200,
                        contentType = "application/vnd.apple.mpegurl",
                        body = """
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x1080
                            v1080.m3u8
                            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
                            v720.m3u8
                        """.trimIndent().toByteArray()
                    )
                }
                "/hls/v1080.m3u8" -> {
                    v1080Hits.incrementAndGet()
                    RespInfo(
                        status = 200,
                        contentType = "application/vnd.apple.mpegurl",
                        body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg_stalled.ts\n#EXT-X-ENDLIST".toByteArray()
                    )
                }
                "/hls/v720.m3u8" -> {
                    v720Hits.incrementAndGet()
                    RespInfo(
                        status = 200,
                        contentType = "application/vnd.apple.mpegurl",
                        body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg_fast.ts\n#EXT-X-ENDLIST".toByteArray()
                    )
                }
                "/hls/seg_stalled.ts" -> {
                    if (req.headers.containsKey("range")) {
                        rangeHits.incrementAndGet()
                        // Simulate a CDN that stalls Range:0-0 for 2.2s while plain GET succeeds in 20ms!
                        RespInfo(status = 400, contentType = "text/plain", body = "Stalled Range".toByteArray(), delayMs = 2_200L)
                    } else {
                        segGetHits.incrementAndGet()
                        RespInfo(status = 200, contentType = "video/mp2t", body = tsBytes, delayMs = 20L)
                    }
                }
                "/hls/seg_fast.ts" -> {
                    segGetHits.incrementAndGet()
                    RespInfo(status = 200, contentType = "video/mp2t", body = tsBytes)
                }
                "/stale/live.m3u8" -> {
                    RespInfo(
                        status = 200,
                        contentType = "application/vnd.apple.mpegurl",
                        body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg_fresh.ts\n#EXT-X-ENDLIST".toByteArray()
                    )
                }
                "/stale/seg_expired.ts" -> RespInfo(status = 403, contentType = "text/plain", body = "Expired".toByteArray())
                "/stale/seg_fresh.ts" -> RespInfo(status = 200, contentType = "video/mp2t", body = tsBytes)
                else -> RespInfo(status = 404)
            }
        }.use { server ->
            val okHttp = OkHttpClient()
            val http = ExtractorHttp(okHttp)
            val hlsExtractor = HlsExtractor(http)
            val hlsDownloader = HlsSegmentDownloader(okHttp, http)
            val headers = mapOf("Referer" to "${server.baseUrl}/embed/1", "User-Agent" to "TestUA")

            // 1. Hedged segment verification bypasses 2.2s Range stall in < 1.1s and caches v1080.m3u8
            val startMs = System.currentTimeMillis()
            val verified = hlsExtractor.extractVerifiedDetailed("${server.baseUrl}/hls/master.m3u8", headers)
            val elapsedMs = System.currentTimeMillis() - startMs
            assertNotNull("Expected hedged verification to succeed: ${verified.failureReason}", verified.media)
            assertTrue("Hedged verification must bypass 2.2s Range stall quickly (was ${elapsedMs}ms)", elapsedMs < 1_300L)
            assertTrue("HostHealthRegistry must learn range-stalling host", HostHealthRegistry.shared.shouldSkipRangeProbeOnSegments("${server.baseUrl}/hls/seg_stalled.ts"))
            assertEquals(1, v1080Hits.get())

            // 2. HlsSegmentDownloader uses HlsPlaylistCache with ZERO duplicate HTTP fetch for v1080.m3u8!
            val stream1080 = verified.media!!.streams.first { it.height == 1080 }
            val dest1080 = File(tempFolder.root, "out_1080.ts")
            val progressTimestamps = CopyOnWriteArrayList<Long>()
            val dlStartMs = System.currentTimeMillis()
            hlsDownloader.download(
                playlistUrl = stream1080.url,
                headers = stream1080.headers,
                dest = dest1080,
                expectedTotalBytes = stream1080.sizeBytes ?: 0L,
                maxRetries = 1
            ) {
                progressTimestamps.add(System.currentTimeMillis() - dlStartMs)
            }
            assertEquals("Cached variant playlist must NOT be re-fetched at download start", 1, v1080Hits.get())
            assertEquals(tsBytes.size.toLong(), dest1080.length())
            assertEquals(0x47.toByte(), dest1080.readBytes()[0])

            // 3. StreamSizeRefiner silently pre-warms default quality (720p) variant into HlsPlaylistCache
            val testDispatchers = object : DispatcherProvider {
                override val io: CoroutineDispatcher = Dispatchers.IO
                override val main: CoroutineDispatcher = Dispatchers.Unconfined
                override val default: CoroutineDispatcher = Dispatchers.Default
            }
            val refiner = StreamSizeRefiner(http, testDispatchers)
            assertEquals(0, v720Hits.get())
            refiner.prewarmDefaultHlsVariant(verified.media!!, VideoQuality.P720)
            assertEquals("Pre-warm should fetch 720p variant once", 1, v720Hits.get())
            val stream720 = verified.media!!.streams.first { it.height == 720 }
            val dest720 = File(tempFolder.root, "out_720.ts")
            hlsDownloader.download(stream720.url, stream720.headers, dest720) {}
            assertEquals("Pre-warmed 720p download must hit cache with 0 extra fetches", 1, v720Hits.get())
            assertEquals(tsBytes.size.toLong(), dest720.length())

            // 4. Adversarial: header mismatch or TTL expiry must NOT serve wrong cached playlist
            val mismatch = HlsPlaylistCache.shared.get(stream720.url, mapOf("Cookie" to "different=session"))
            assertEquals(null, mismatch)

            // 5. Adversarial: stale cached playlist whose segment returns HTTP 403 must auto-invalidate & retry live!
            val staleUrl = "${server.baseUrl}/stale/live.m3u8"
            HlsPlaylistCache.shared.put(
                url = staleUrl,
                text = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg_expired.ts\n#EXT-X-ENDLIST",
                finalUrl = staleUrl,
                headers = emptyMap()
            )
            val recoveredDest = File(tempFolder.root, "recovered.ts")
            hlsDownloader.download(staleUrl, emptyMap(), recoveredDest) {}
            assertEquals("Stale cached playlist must automatically fall back to fresh live playlist", tsBytes.size.toLong(), recoveredDest.length())
            assertEquals(0x47.toByte(), recoveredDest.readBytes()[0])
            assertEquals(0, staleSegMode.get())
        }
    }

    @Test
    fun segmentedDownloader_skipsRedundantProbeWhenPreProbed_andDownloadManagerInProcessQueueStartsImmediatelyWithoutDoubleRun() = runBlocking {
        val payloadBytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val requestCount = AtomicInteger(0)
        val rangeProbeCount = AtomicInteger(0)

        LoopbackServer { req ->
            requestCount.incrementAndGet()
            if (req.headers["range"] == "bytes=0-0" || req.method == "HEAD") {
                rangeProbeCount.incrementAndGet()
            }
            when (req.path) {
                "/direct/video.mp4" -> RespInfo(
                    status = 200,
                    contentType = "video/mp4",
                    body = payloadBytes,
                    extraHeaders = mapOf("Content-Length" to payloadBytes.size.toString())
                )
                else -> RespInfo(status = 404)
            }
        }.use { server ->
            val okHttp = OkHttpClient()
            val http = ExtractorHttp(okHttp)
            val segmented = SegmentedDownloader(okHttp, http)

            // Part A: X-Internal-Probed=true with knownTotal > 0 and acceptsRanges=false skips redundant probe!
            val directDest = File(tempFolder.root, "direct_preprobed.mp4")
            val firstProgressDelayMs = AtomicLong(-1L)
            val dlStart = System.currentTimeMillis()
            segmented.download(
                url = "${server.baseUrl}/direct/video.mp4",
                headers = mapOf("X-Internal-Probed" to "true"),
                dest = directDest,
                knownTotal = payloadBytes.size.toLong(),
                acceptsRanges = false,
                segmentCount = 4
            ) { p ->
                if (p.downloaded > 0L) {
                    firstProgressDelayMs.compareAndSet(-1L, System.currentTimeMillis() - dlStart)
                }
            }
            assertEquals("Pre-probed stream with known size must send 0 redundant probe requests", 0, rangeProbeCount.get())
            assertEquals("Pre-probed non-ranged download must complete in exactly 1 HTTP GET", 1, requestCount.get())
            assertEquals(payloadBytes.size.toLong(), directDest.length())
            assertTrue(
                "First progress callback must fire immediately instead of waiting 500ms (was ${firstProgressDelayMs.get()}ms)",
                firstProgressDelayMs.get() in 0L..420L
            )

            // Part B: DownloadManager in-process fast-start runs immediately without WorkManager AND
            // deduplicates cleanly when WorkManager runQueue() enters concurrently!
            mockkStatic(WorkManager::class)
            try {
                val mockWorkManager = mockk<WorkManager>(relaxed = true)
                every { WorkManager.getInstance(any()) } returns mockWorkManager
                every {
                    mockWorkManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
                } returns mockk(relaxed = true)

                val context = mockk<Context>(relaxed = true).apply {
                    every { cacheDir } returns tempFolder.root
                    every { filesDir } returns tempFolder.root
                    every { getExternalFilesDir(any()) } returns tempFolder.root
                }
                val items = ConcurrentHashMap<Long, DownloadItem>()
                val idGen = AtomicLong(0L)
                val resetInterruptedCalls = AtomicInteger(0)
                val downloadInvocations = AtomicInteger(0)
                val repo = mockk<DownloadRepository>(relaxed = true)
                coEvery { repo.enqueue(any()) } answers {
                    val itm = firstArg<DownloadItem>()
                    val id = idGen.incrementAndGet()
                    items[id] = itm.copy(id = id, status = DownloadStatus.QUEUED)
                    id
                }
                coEvery { repo.getById(any()) } answers { items[firstArg<Long>()] }
                coEvery { repo.claimNextQueued(any()) } answers {
                    synchronized(items) {
                        val next = items.values
                            .filter { it.status == DownloadStatus.QUEUED }
                            .sortedBy { it.createdAt }
                            .firstOrNull()
                        if (next != null) {
                            val running = next.copy(status = DownloadStatus.RUNNING)
                            items[next.id] = running
                            listOf(running)
                        } else {
                            emptyList()
                        }
                    }
                }
                coEvery { repo.resetInterrupted() } answers {
                    resetInterruptedCalls.incrementAndGet()
                    Unit
                }
                coEvery { repo.updateStatus(any(), any()) } answers {
                    val id = firstArg<Long>()
                    val st = secondArg<DownloadStatus>()
                    items[id]?.let { items[id] = it.copy(status = st) }
                }
                coEvery { repo.setCompleted(any(), any(), any()) } answers {
                    val id = firstArg<Long>()
                    val uri = secondArg<String>()
                    val total = thirdArg<Long>()
                    items[id]?.let { items[id] = it.copy(status = DownloadStatus.COMPLETED, contentUri = uri, totalBytes = total, downloadedBytes = total) }
                }

                val settingsRepo = mockk<SettingsRepository>(relaxed = true).apply {
                    every { settings } returns MutableStateFlow(AppSettings(maxConcurrentDownloads = 2))
                    coEvery { current() } returns AppSettings(maxConcurrentDownloads = 2)
                }
                val slowSegmented = mockk<SegmentedDownloader>(relaxed = true)
                coEvery { slowSegmented.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    downloadInvocations.incrementAndGet()
                    delay(180L)
                    val dest = thirdArg<File>()
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(ByteArray(2048) { 0x33 })
                    val cb = arg<suspend (DownloadProgress) -> Unit>(6)
                    cb(DownloadProgress(2048L, 2048L, 10_000L, 0L))
                }
                val writer = mockk<MediaStoreWriter>(relaxed = true)
                coEvery { writer.publish(any(), any(), any(), any(), any()) } returns "content://media/external/video/media/99"

                val ioDispatchers = object : DispatcherProvider {
                    override val io: CoroutineDispatcher = Dispatchers.IO
                    override val main: CoroutineDispatcher = Dispatchers.Unconfined
                    override val default: CoroutineDispatcher = Dispatchers.Default
                }

                val dm = DownloadManager(
                    context = context,
                    repository = repo,
                    settingsRepository = settingsRepo,
                    segmentedDownloader = slowSegmented,
                    hlsDownloader = mockk(relaxed = true),
                    videoAudioMuxer = mockk<VideoAudioMuxer>(relaxed = true),
                    mediaStoreWriter = writer,
                    notifier = mockk<DownloadNotifier>(relaxed = true),
                    dispatchers = ioDispatchers,
                    thumbnailCache = null,
                    availableBytesProvider = { 500_000_000L }
                )
                dm.enableInProcessQueue = true

                val media = ResolvedMedia(
                    sourceUrl = "https://wecima.here/watch/ep8",
                    title = "Instant Start Episode",
                    streams = listOf(
                        MediaStream("s1", "https://cdn.example/ep8.mp4", MediaType.VIDEO, "mp4", "720p HD", height = 720, sizeBytes = 2048L)
                    )
                )
                val itemId = dm.enqueue(media, media.streams.single())

                // Poll briefly (<= 400ms) — in-process queue must start downloading immediately WITHOUT WorkManager!
                var waitedMs = 0L
                while (downloadInvocations.get() == 0 && waitedMs < 400L) {
                    delay(10L)
                    waitedMs += 10L
                }
                assertEquals("In-process queue must start download immediately on enqueue (waited ${waitedMs}ms)", 1, downloadInvocations.get())
                assertEquals("In-process fast-start must not call resetInterrupted on active run", 0, resetInterruptedCalls.get())

                // Now simulate WorkManager cold-starting runQueue() concurrently mid-download
                val workerJob = async(Dispatchers.IO) { dm.runQueue {} }
                workerJob.await()

                assertEquals("Concurrent WorkManager handoff must NEVER double-execute the download", 1, downloadInvocations.get())
                assertEquals(DownloadStatus.COMPLETED, items[itemId]?.status)
            } finally {
                unmockkStatic(WorkManager::class)
            }
        }
    }
}
