package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.di.WebViewCookieJar
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.model.MediaType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Real loopback TCP ServerSocket HTTP/1.1 latency benchmark reproducing production bottlenecks:
 * 1. Movie embed portals (wecima.here-like) with mixed fast/dead/hanging embed servers + HLS calibration.
 * 2. TikTok short link with slow/403 tikwm.com + HTML __UNIVERSAL_DATA_FOR_REHYDRATION__ + bitrateInfo.
 * 3. HLS media playlist with slow interior segment probes.
 * 4. Repeated / concurrent resolve of the same URL.
 */
class ExtractionLatencyBenchmarkTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var executor: ExecutorService
    private val running = AtomicBoolean(false)
    private var port: Int = 0

    private val tikwmRequests = AtomicInteger(0)
    private val tiktokHtmlRequests = AtomicInteger(0)
    private val tiktokVideoProbes = AtomicInteger(0)
    private val slowEmbedStarted = AtomicInteger(0)
    private val slowEmbedCompleted = AtomicInteger(0)

    private val ioDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    @Before
    fun setUp() {
        tikwmRequests.set(0)
        tiktokHtmlRequests.set(0)
        tiktokVideoProbes.set(0)
        slowEmbedStarted.set(0)
        slowEmbedCompleted.set(0)

        serverSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        port = serverSocket.localPort
        executor = Executors.newCachedThreadPool()
        running.set(true)

        executor.submit {
            while (running.get()) {
                val clientSock = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                executor.submit { handleClient(clientSock) }
            }
        }
    }

    @After
    fun tearDown() {
        running.set(false)
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            if (requestLine.isBlank()) return
            var origUrl = ""
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (name.equals("X-Bench-Orig-Url", ignoreCase = true)) {
                        origUrl = value
                    } else if (name.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }
            if (contentLength > 0) {
                val skipBuf = CharArray(contentLength.coerceAtMost(8192))
                var remaining = contentLength
                while (remaining > 0) {
                    val read = reader.read(skipBuf, 0, minOf(remaining, skipBuf.size))
                    if (read <= 0) break
                    remaining -= read
                }
            }

            try {
                when {
                    // ---- Scenario 1: Movie portal (wecima.here) ----
                    origUrl == "https://wecima.here/watch/series-ep-1/" -> {
                        Thread.sleep(120)
                        val html = """
                            <!doctype html>
                            <html>
                            <head>
                              <title>مسلسل محمد الفاتح الحلقة 1 - WeCima</title>
                              <meta property="og:title" content="مسلسل محمد الفاتح الحلقة 1" />
                              <meta property="og:image" content="https://wecima.here/posters/ep1.jpg" />
                            </head>
                            <body>
                              <ul class="WatchServersList">
                                <li data-url="https://dead0.embed.example/e/1">Server 0</li>
                                <li data-url="https://fast1.embed.example/e/1">FastStream</li>
                                <li data-url="https://hang2.embed.example/e/1">SlowEmbed2</li>
                                <li data-url="https://hang3.embed.example/e/1">SlowEmbed3</li>
                                <li data-url="https://hang4.embed.example/e/1">SlowEmbed4</li>
                                <li data-url="https://hang5.embed.example/e/1">SlowEmbed5</li>
                              </ul>
                            </body>
                            </html>
                        """.trimIndent()
                        writeHttp(sock, 200, "OK", mapOf("Content-Type" to "text/html; charset=utf-8"), html.toByteArray())
                    }
                    origUrl.startsWith("https://dead0.embed.example/") -> {
                        Thread.sleep(220)
                        writeHttp(sock, 403, "Forbidden", mapOf("Content-Type" to "text/html"), "Cloudflare 403".toByteArray())
                    }
                    origUrl.startsWith("https://fast1.embed.example/") -> {
                        Thread.sleep(240)
                        val embedHtml = """
                            <html><body><script>
                            new Playerjs({id:"player", file:"https://cdn-hls.fast1.example/hls/master.m3u8", poster:"https://cdn-hls.fast1.example/poster.jpg"});
                            </script></body></html>
                        """.trimIndent()
                        writeHttp(sock, 200, "OK", mapOf("Content-Type" to "text/html"), embedHtml.toByteArray())
                    }
                    origUrl.startsWith("https://hang") -> {
                        slowEmbedStarted.incrementAndGet()
                        Thread.sleep(4_500)
                        slowEmbedCompleted.incrementAndGet()
                        writeHttp(sock, 504, "Gateway Timeout", mapOf("Content-Type" to "text/html"), "Timeout".toByteArray())
                    }
                    origUrl == "https://cdn-hls.fast1.example/hls/master.m3u8" -> {
                        Thread.sleep(160)
                        val master = """
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
                            1080.m3u8
                            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
                            720.m3u8
                            #EXT-X-STREAM-INF:BANDWIDTH=750000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2"
                            480.m3u8
                        """.trimIndent()
                        writeHttp(sock, 200, "OK", mapOf("Content-Type" to "application/vnd.apple.mpegurl"), master.toByteArray())
                    }
                    origUrl.startsWith("https://cdn-hls.fast1.example/hls/") && origUrl.endsWith(".m3u8") -> {
                        Thread.sleep(150)
                        val variant = buildString {
                            appendLine("#EXTM3U")
                            appendLine("#EXT-X-TARGETDURATION:10")
                            for (i in 0 until 12) {
                                appendLine("#EXTINF:10.0,")
                                appendLine("seg_$i.ts")
                            }
                            appendLine("#EXT-X-ENDLIST")
                        }
                        writeHttp(sock, 200, "OK", mapOf("Content-Type" to "application/vnd.apple.mpegurl"), variant.toByteArray())
                    }
                    origUrl.startsWith("https://cdn-hls.fast1.example/hls/seg_") -> {
                        Thread.sleep(320)
                        writeHttp(
                            sock, 206, "Partial Content",
                            mapOf(
                                "Content-Type" to "video/mp2t",
                                "Accept-Ranges" to "bytes",
                                "Content-Range" to "bytes 0-0/4000000"
                            ),
                            byteArrayOf(0x47)
                        )
                    }

                    // ---- Scenario 2: TikTok ----
                    origUrl.startsWith("https://vt.tiktok.com/") -> {
                        Thread.sleep(180)
                        writeHttp(
                            sock, 302, "Found",
                            mapOf("Location" to "https://www.tiktok.com/@creator/video/7106594312292453675"),
                            ByteArray(0)
                        )
                    }
                    origUrl.startsWith("https://www.tikwm.com/api/") -> {
                        tikwmRequests.incrementAndGet()
                        Thread.sleep(3_000)
                        writeHttp(sock, 403, "Forbidden", mapOf("Content-Type" to "text/html"), "<html>Just a moment...</html>".toByteArray())
                    }
                    origUrl.startsWith("https://www.tiktok.com/@creator/video/7106594312292453675") -> {
                        tiktokHtmlRequests.incrementAndGet()
                        Thread.sleep(280)
                        val jsonPayload = """
                            {
                              "__DEFAULT_SCOPE__": {
                                "webapp.video-detail": {
                                  "statusCode": 0,
                                  "itemInfo": {
                                    "itemStruct": {
                                      "id": "7106594312292453675",
                                      "desc": "Benchmark TikTok Clip #speed",
                                      "video": {
                                        "duration": 24,
                                        "width": 576,
                                        "height": 1024,
                                        "cover": "https://p19.tiktokcdn.example/cover.jpg",
                                        "playAddr": "https://v16.tiktok.example/video/tos/sd576/?mime_type=video_mp4",
                                        "bitrateInfo": [
                                          {
                                            "GearName": "normal_1080_0",
                                            "Bitrate": 2100000,
                                            "CodecType": "h264",
                                            "PlayAddr": {
                                              "Width": 1080,
                                              "Height": 1920,
                                              "DataSize": 6400000,
                                              "UrlList": ["https://v16.tiktok.example/video/tos/hd1080/?mime_type=video_mp4"]
                                            }
                                          },
                                          {
                                            "GearName": "normal_576_0",
                                            "Bitrate": 1046000,
                                            "CodecType": "h264",
                                            "PlayAddr": {
                                              "Width": 576,
                                              "Height": 1024,
                                              "DataSize": 3288875,
                                              "UrlList": ["https://v16.tiktok.example/video/tos/sd576/?mime_type=video_mp4"]
                                            }
                                          }
                                        ]
                                      },
                                      "music": {
                                        "duration": 24,
                                        "playUrl": "https://v16m.tiktok.example/audio/track.m4a"
                                      }
                                    }
                                  }
                                }
                              }
                            }
                        """.trimIndent()
                        val html = """
                            <html><head><title>TikTok - Make Your Day</title></head><body>
                            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">$jsonPayload</script>
                            </body></html>
                        """.trimIndent()
                        writeHttp(
                            sock, 200, "OK",
                            mapOf("Content-Type" to "text/html; charset=utf-8"),
                            html.toByteArray(),
                            extraHeaders = listOf(
                                "Set-Cookie: ttwid=bench1; Path=/; Domain=.tiktok.com",
                                "Set-Cookie: tt_chain_token=bench2; Path=/; Domain=.tiktok.com"
                            )
                        )
                    }
                    origUrl.startsWith("https://v16.tiktok.example/video/") -> {
                        tiktokVideoProbes.incrementAndGet()
                        Thread.sleep(500)
                        val len = if (origUrl.contains("hd1080")) 6_400_000L else 3_288_875L
                        writeHttp(
                            sock, 206, "Partial Content",
                            mapOf(
                                "Content-Type" to "video/mp4",
                                "Accept-Ranges" to "bytes",
                                "Content-Range" to "bytes 0-0/$len"
                            ),
                            byteArrayOf(0x00)
                        )
                    }
                    origUrl.startsWith("https://v16m.tiktok.example/audio/") -> {
                        Thread.sleep(320)
                        writeHttp(
                            sock, 206, "Partial Content",
                            mapOf(
                                "Content-Type" to "audio/mp4",
                                "Accept-Ranges" to "bytes",
                                "Content-Range" to "bytes 0-0/384000"
                            ),
                            byteArrayOf(0x00)
                        )
                    }

                    // ---- Scenario 3: HLS Media Playlist with 3 interior segments ----
                    origUrl == "https://slow-hls.example/stream-720p.m3u8" -> {
                        Thread.sleep(100)
                        val playlist = buildString {
                            appendLine("#EXTM3U")
                            appendLine("#EXT-X-TARGETDURATION:10")
                            for (i in 0 until 12) {
                                appendLine("#EXTINF:10.0,")
                                appendLine("seg_$i.ts")
                            }
                            appendLine("#EXT-X-ENDLIST")
                        }
                        writeHttp(sock, 200, "OK", mapOf("Content-Type" to "application/vnd.apple.mpegurl"), playlist.toByteArray())
                    }
                    origUrl.startsWith("https://slow-hls.example/seg_") -> {
                        Thread.sleep(450)
                        writeHttp(
                            sock, 206, "Partial Content",
                            mapOf(
                                "Content-Type" to "video/mp2t",
                                "Accept-Ranges" to "bytes",
                                "Content-Range" to "bytes 0-0/1800000"
                            ),
                            byteArrayOf(0x47)
                        )
                    }

                    else -> writeHttp(sock, 404, "Not Found", mapOf("Content-Type" to "text/plain"), "Not Found: $origUrl".toByteArray())
                }
            } catch (_: InterruptedException) {
                // Expected during shutdown
            } catch (_: Exception) {
                // Socket closed by client cancellation
            }
        }
    }

    private fun writeHttp(
        sock: Socket,
        code: Int,
        reason: String,
        headers: Map<String, String>,
        body: ByteArray,
        extraHeaders: List<String> = emptyList()
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        extraHeaders.forEach { h -> sb.append(h).append("\r\n") }
        sb.append("\r\n")
        val out = sock.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun buildLoopbackClient(): OkHttpClient {
        val cookieJar = WebViewCookieJar()
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                var logicalReq = chain.request()
                var hops = 0
                while (true) {
                    val origUrl = logicalReq.url.toString()
                    val cookies = cookieJar.loadForRequest(logicalReq.url)
                    val cookieHdr = cookies.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ") { "${it.name}=${it.value}" }
                    val rewrittenUrl = "http://127.0.0.1:$port/proxy".toHttpUrl()
                    val proxiedBuilder = logicalReq.newBuilder()
                        .url(rewrittenUrl)
                        .header("X-Bench-Orig-Url", origUrl)
                    if (!cookieHdr.isNullOrBlank() && logicalReq.header("Cookie").isNullOrBlank()) {
                        proxiedBuilder.header("Cookie", cookieHdr)
                    }
                    val resp = chain.proceed(proxiedBuilder.build())
                    val setCookies = okhttp3.Cookie.parseAll(logicalReq.url, resp.headers)
                    if (setCookies.isNotEmpty()) {
                        cookieJar.saveFromResponse(logicalReq.url, setCookies)
                    }
                    val location = resp.header("Location")
                    if (resp.code in listOf(301, 302, 303, 307, 308) && !location.isNullOrBlank() && hops < 5) {
                        val nextUrl = logicalReq.url.resolve(location)
                        if (nextUrl != null) {
                            resp.close()
                            logicalReq = logicalReq.newBuilder().url(nextUrl).build()
                            hops++
                            continue
                        }
                    }
                    return@addInterceptor resp.newBuilder().request(logicalReq).build()
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            .build()
    }

    @Test
    fun benchmark_extractionPipelineLatencies() = runBlocking {
        val client = buildLoopbackClient()
        val http = ExtractorHttp(client)
        val hls = HlsExtractor(http)
        val dash = DashExtractor(http)
        val headless = mockk<HeadlessWebExtractor>(relaxed = true)
        val htmlExtractor = HtmlMediaExtractor(http, hls, dash, headless)
        val socialExtractor = SocialMediaExtractor(http, headless)
        val networkMonitor = mockk<NetworkMonitor>().apply { every { isOnline() } returns true }
        val registry = ExtractorRegistry(setOf(socialExtractor, hls, dash, htmlExtractor), networkMonitor, ioDispatchers)

        // 1. Movie portal (wecima.here) with 1 dead + 1 fast + 4 hanging embeds
        var wecimaResult: com.melmeligy.mediadownloader.domain.model.ResolvedMedia? = null
        val wecimaMs = measureTimeMillis {
            wecimaResult = registry.resolve("https://wecima.here/watch/series-ep-1/")
        }
        val wecimaSlowDone = slowEmbedCompleted.get()
        val wecimaSlowStarted = slowEmbedStarted.get()
        assertNotNull(wecimaResult)
        assertTrue(wecimaResult!!.streams.any { it.height == 1080 })
        assertTrue(wecimaResult!!.streams.any { it.height == 720 })
        assertTrue(wecimaResult!!.streams.any { it.height == 480 })
        assertTrue(wecimaResult!!.title.contains("محمد الفاتح"))
        assertTrue(wecimaResult!!.thumbnailUrl == "https://wecima.here/posters/ep1.jpg")
        assertTrue("Movie portal should not wait for 4.5s hanging embeds (was ${wecimaMs}ms)", wecimaMs < 2_500L)
        assertTrue("Hanging embeds should be cancelled before completion (completed=$wecimaSlowDone)", wecimaSlowDone == 0)

        // 2. TikTok short link with slow 403 tikwm + HTML hydration
        val probesBeforeTikTok = tiktokVideoProbes.get()
        var tiktokResult: com.melmeligy.mediadownloader.domain.model.ResolvedMedia? = null
        val tiktokMs = measureTimeMillis {
            tiktokResult = registry.resolve("https://vt.tiktok.com/ZSYFast01/")
        }
        val tiktokProbesUsed = tiktokVideoProbes.get() - probesBeforeTikTok
        assertNotNull(tiktokResult)
        val ttVideos = tiktokResult!!.streams.filter { it.type == MediaType.VIDEO }
        assertTrue(ttVideos.any { it.height == 1080 && it.sizeBytes == 6_400_000L })
        assertTrue(ttVideos.any { it.height == 576 && it.sizeBytes == 3_288_875L })
        assertTrue(tiktokResult!!.title.contains("Benchmark TikTok Clip"))
        assertTrue(tiktokResult!!.thumbnailUrl == "https://p19.tiktokcdn.example/cover.jpg")
        assertTrue(ttVideos.first().headers["Cookie"]?.contains("tt_chain_token=bench2") == true)
        assertTrue(ttVideos.first().headers["Referer"] == "https://www.tiktok.com/")
        assertTrue("TikTok race should beat 3s slow tikwm 403 (was ${tiktokMs}ms)", tiktokMs < 1_800L)
        assertTrue("TikTok should skip redundant video probes when DataSize exists (probes=$tiktokProbesUsed)", tiktokProbesUsed == 0)

        // 3. HLS media playlist with 3 interior segment probes (450ms each)
        var hlsResult: com.melmeligy.mediadownloader.domain.model.ResolvedMedia? = null
        val hlsMs = measureTimeMillis {
            hlsResult = hls.extract("https://slow-hls.example/stream-720p.m3u8")
        }
        assertNotNull(hlsResult)
        assertTrue((hlsResult!!.streams.first().sizeBytes ?: 0L) > 0L)
        assertTrue("HLS 3x450ms probes should run in parallel (~550ms, not ~1450ms; was ${hlsMs}ms)", hlsMs < 1_000L)

        // 4. Repeat lookup of same wecima URL (short-TTL cache hit)
        var repeatWecimaResult: com.melmeligy.mediadownloader.domain.model.ResolvedMedia? = null
        val repeatWecimaMs = measureTimeMillis {
            repeatWecimaResult = registry.resolve("https://wecima.here/watch/series-ep-1/")
        }
        assertNotNull(repeatWecimaResult)
        assertTrue("Cached repeat lookup should be near-instant (was ${repeatWecimaMs}ms)", repeatWecimaMs < 50L)

        // 5. Concurrent duplicate requests for same TikTok URL (in-flight deduplication)
        val htmlCountBeforeConcurrent = tiktokHtmlRequests.get()
        var concurrentResults: List<com.melmeligy.mediadownloader.domain.model.ResolvedMedia> = emptyList()
        val concurrentMs = measureTimeMillis {
            concurrentResults = listOf(
                async { registry.resolve("https://www.tiktok.com/@creator/video/7106594312292453675") },
                async { registry.resolve("https://www.tiktok.com/@creator/video/7106594312292453675") }
            ).awaitAll()
        }
        val htmlCallsDuringConcurrent = tiktokHtmlRequests.get() - htmlCountBeforeConcurrent
        assertTrue(concurrentResults.size == 2)
        assertTrue("Concurrent duplicate requests must coalesce to 1 HTML fetch (was $htmlCallsDuringConcurrent)", htmlCallsDuringConcurrent == 1)
        assertTrue("Concurrent TikTok resolve should complete rapidly (was ${concurrentMs}ms)", concurrentMs < 1_800L)

        println("================ EXTRACTION BENCHMARK ================")
        println("1. Movie portal (wecima.here 6 embeds): ${wecimaMs}ms (slowEmbedsCompleted=$wecimaSlowDone/$wecimaSlowStarted)")
        println("2. TikTok (short + slow 403 tikwm + hydration): ${tiktokMs}ms (videoProbes=$tiktokProbesUsed)")
        println("3. HLS 12-seg media playlist (3 probes @ 450ms): ${hlsMs}ms")
        println("4. Repeat wecima.here resolve: ${repeatWecimaMs}ms")
        println("5. 2x Concurrent TikTok resolve: ${concurrentMs}ms (htmlFetches=$htmlCallsDuringConcurrent)")
        println("======================================================")
    }
}
