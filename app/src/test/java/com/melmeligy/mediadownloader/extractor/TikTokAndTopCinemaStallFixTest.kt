package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.domain.model.MediaType
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end loopback HTTP & unit regression/adversarial tests for TikTok and TopCinema
 * "Detecting embedded video..." stall fixes:
 * 1. TikTok tikwm timeout arms the 60s cooldown circuit breaker (not just 403/429/5xx).
 * 2. TikTok hedged mobile HTML hydration rescues stalled/anti-bot desktop HTML without
 *    falling back to HeadlessWebExtractor, while adding 0 extra requests when desktop is fast.
 * 3. TopCinema / DooPlay AJAX server tabs scraped from live WebView DOM are parsed with
 *    non-active servers prioritized and resolved concurrently via StreamingPortalResolver.resolveHybridDomAndAjax.
 * 4. Malformed AJAX JSON inputs and wall-clock ceilings stay strictly safe and bounded.
 */
class TikTokAndTopCinemaStallFixTest {

    private data class ReqInfo(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class RespInfo(
        val status: Int = 200,
        val contentType: String = "text/html; charset=utf-8",
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
                                    val buf = CharArray(contentLen.coerceAtMost(8192))
                                    var off = 0
                                    while (off < buf.size) {
                                        val r = reader.read(buf, off, buf.size - off)
                                        if (r <= 0) break
                                        off += r
                                    }
                                    String(buf, 0, off)
                                } else ""

                                val resp = handler(ReqInfo(method, path, headers, bodyStr))
                                if (resp.delayMs > 0L) Thread.sleep(resp.delayMs)
                                val reason = when (resp.status) {
                                    200 -> "OK"
                                    206 -> "Partial Content"
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

    private fun buildTikTokMobileReflowHtml(playUrl: String, audioUrl: String): String = """
        <html><body>
        <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
        {
          "__DEFAULT_SCOPE__": {
            "webapp.reflow.video.detail": {
              "itemInfo": {
                "itemStruct": {
                  "id": "7500112233445566778",
                  "desc": "Mobile Hedged TikTok Clip",
                  "video": {
                    "width": 576,
                    "height": 1024,
                    "duration": 18,
                    "cover": "https://p19.tiktokcdn.example/mobile_cover.jpg",
                    "playAddr": "$playUrl"
                  },
                  "music": {
                    "duration": 18,
                    "playUrl": "$audioUrl"
                  }
                }
              }
            }
          }
        }
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun tikTok_hedgedMobileHtmlRescuesEmptyDesktopShell_andTikwmTimeoutArmsCooldown() = runBlocking {
        val tikwmCalls = AtomicInteger(0)
        val desktopCalls = AtomicInteger(0)
        val mobileCalls = AtomicInteger(0)
        var fakeClockMs = 1_000_000L

        val mobileHtml = buildTikTokMobileReflowHtml(
            playUrl = "https://v16-webapp-prime.us.tiktok.com/video/tos/mobile_576.mp4",
            audioUrl = "https://v16m.tiktokcdn.example/audio_track.m4a"
        )

        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val social = SocialMediaExtractor(mockHttp, mockHeadless).apply {
            cookieProvider = { null }
            clockMs = { fakeClockMs }
        }

        val ttUrl = "https://www.tiktok.com/@creator/video/7500112233445566778"
        io.mockk.coEvery { mockHttp.fetchString(match { it.contains("tikwm.com") }, any()) } coAnswers {
            tikwmCalls.incrementAndGet()
            // Simulate hanging tikwm.com exceeding TIKWM_API_TIMEOUT_MS (2000ms)
            kotlinx.coroutines.delay(2_600L)
            ""
        }
        io.mockk.coEvery { mockHttp.fetchHtmlWithCookies(ttUrl, any()) } coAnswers {
            val headers = secondArg<Map<String, String>>()
            val ua = headers["User-Agent"].orEmpty()
            if (ua.contains("Mobile", ignoreCase = true)) {
                mobileCalls.incrementAndGet()
                ExtractorHttp.HtmlFetchResult(
                    html = mobileHtml,
                    cookieHeader = "ttwid=mob1; tt_chain_token=mob2",
                    finalUrl = ttUrl
                )
            } else {
                desktopCalls.incrementAndGet()
                // Simulate desktop anti-bot empty shell (no __UNIVERSAL_DATA_FOR_REHYDRATION__)
                kotlinx.coroutines.delay(2_150L)
                ExtractorHttp.HtmlFetchResult(
                    html = "<html><head><title>TikTok - Make Your Day</title></head><body></body></html>",
                    cookieHeader = null,
                    finalUrl = ttUrl
                )
            }
        }

        // First call: desktop stalls (>1.6s) -> mobile hedge rescues immediately; tikwm times out -> arms cooldown
        val res1 = social.extract(ttUrl)
        assertNotNull("Expected mobile HTML hedge to rescue stalled desktop TikTok fetch", res1)
        assertEquals("Mobile Hedged TikTok Clip", res1!!.title)
        assertTrue(res1.streams.any { it.type == MediaType.VIDEO && it.url.contains("mobile_576.mp4") })
        assertTrue(res1.streams.any { it.type == MediaType.AUDIO })
        assertTrue("Expected mobile HTML fallback to run", mobileCalls.get() >= 1)
        coVerify(exactly = 0) { mockHeadless.extract(any(), any(), any()) }

        // Wait briefly to ensure first tikwm timeout branch armed cooldown if desktop took >2s, or test explicit timeout cooldown
        val callsAfterFirst = tikwmCalls.get()
        assertEquals(1, callsAfterFirst)

        // Second call with desktop returning empty shell immediately and tikwm hanging -> verifies timeout arms cooldown
        fakeClockMs += 5_000L
        io.mockk.coEvery { mockHttp.fetchHtmlWithCookies(ttUrl, any()) } coAnswers {
            val headers = secondArg<Map<String, String>>()
            if (headers["User-Agent"].orEmpty().contains("Mobile", ignoreCase = true)) {
                kotlinx.coroutines.delay(2_250L) // Let tikwm timeout (2s) fire and arm cooldown
                ExtractorHttp.HtmlFetchResult(html = mobileHtml, cookieHeader = "ttwid=m", finalUrl = ttUrl)
            } else {
                ExtractorHttp.HtmlFetchResult(html = "<html>empty</html>", cookieHeader = null, finalUrl = ttUrl)
            }
        }
        val res2 = social.extract(ttUrl)
        assertNotNull(res2)

        // Third call 5s later MUST skip tikwm completely (0 new tikwm calls because timeout armed 60s cooldown)
        val tikwmBeforeThird = tikwmCalls.get()
        fakeClockMs += 5_000L
        val res3 = social.extract(ttUrl)
        assertNotNull(res3)
        assertEquals("tikwm must be bypassed after timeout cooldown", tikwmBeforeThird, tikwmCalls.get())
    }

    @Test
    fun topCinema_hybridAjaxScrapingAndResolution_bypassesDeadServer0AndMergesUpDownAndUqload() = runBlocking {
        val tsBytes = ByteArray(188 * 4) { 0x11 }.apply {
            for (i in 0 until 4) this[i * 188] = 0x47
        }
        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            when (req.path) {
                "/embed-vidtube-dead" -> RespInfo(status = 403, body = "Cloudflare 403".toByteArray())
                "/Ajaxat/Single/Server.php" -> when {
                    req.body.contains("i=1") -> RespInfo(
                        status = 200,
                        body = """<iframe src="${serverRef.baseUrl}/embed-updown.html"></iframe>""".toByteArray()
                    )
                    req.body.contains("i=5") -> RespInfo(
                        status = 200,
                        body = """<iframe src="${serverRef.baseUrl}/embed-uqload.html"></iframe>""".toByteArray()
                    )
                    else -> RespInfo(status = 200, body = "".toByteArray())
                }
                "/embed-updown.html" -> RespInfo(
                    status = 200,
                    body = """
                        <html><script>
                        jwplayer("flvplayer").setup({file:"${serverRef.baseUrl}/cdn/video.mp4",width:1280,height:640,duration:"1339"});
                        </script></html>
                    """.trimIndent().toByteArray()
                )
                "/cdn/video.mp4" -> RespInfo(
                    status = 206,
                    contentType = "application/octet-stream",
                    body = byteArrayOf(0x00),
                    extraHeaders = mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes 0-0/439720822"
                    )
                )
                "/embed-uqload.html" -> RespInfo(
                    status = 200,
                    body = """
                        <html><script>
                        jwplayer("vplayer").setup({sources:[{file:"${serverRef.baseUrl}/hls2/master.m3u8"}]});
                        </script></html>
                    """.trimIndent().toByteArray()
                )
                "/hls2/master.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=547304,RESOLUTION=644x360
                        ${serverRef.baseUrl}/hls2/index.m3u8
                    """.trimIndent().toByteArray()
                )
                "/hls2/index.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:5
                        #EXTINF:5.0,
                        ${serverRef.baseUrl}/hls2/seg1.ts
                        #EXT-X-ENDLIST
                    """.trimIndent().toByteArray()
                )
                "/hls2/seg1.ts" -> RespInfo(
                    status = 206,
                    contentType = "video/mp2t",
                    body = tsBytes.copyOfRange(0, 1),
                    extraHeaders = mapOf("Content-Range" to "bytes 0-0/84600")
                )
                else -> RespInfo(status = 404)
            }
        }

        serverRef.use { server ->
            val watchUrl = "${server.baseUrl}/watch/rick-and-morty-ep1/"
            val scrapedJson = "\"[" +
                "{\\\"e\\\":\\\"${server.baseUrl}/Ajaxat/Single/Server.php\\\",\\\"b\\\":\\\"id=38195&i=0\\\",\\\"n\\\":\\\"VideoTube\\\",\\\"act\\\":1}," +
                "{\\\"e\\\":\\\"${server.baseUrl}/Ajaxat/Single/Server.php\\\",\\\"b\\\":\\\"id=38195&i=1\\\",\\\"n\\\":\\\"UpDown\\\",\\\"act\\\":0}," +
                "{\\\"e\\\":\\\"${server.baseUrl}/Ajaxat/Single/Server.php\\\",\\\"b\\\":\\\"id=38195&i=5\\\",\\\"n\\\":\\\"Uqload\\\",\\\"act\\\":0}" +
                "]\""

            val ajaxSpecs = HeadlessWebExtractor.parseScrapedAjaxServersJson(scrapedJson, watchUrl)
            assertEquals(3, ajaxSpecs.size)
            assertEquals("UpDown", ajaxSpecs[0].serverName)
            assertEquals("Uqload", ajaxSpecs[1].serverName)
            assertEquals("VideoTube", ajaxSpecs[2].serverName)

            val http = ExtractorHttp(OkHttpClient())
            val portal = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))
            val result = portal.resolveHybridDomAndAjax(
                pageUrl = watchUrl,
                embedUrls = listOf("${server.baseUrl}/embed-vidtube-dead"),
                ajaxServers = ajaxSpecs,
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertTrue(
                "Expected hybrid DOM + AJAX resolver to bypass dead 403 Server #0 and resolve UpDown/Uqload",
                result.streams.isNotEmpty()
            )
            assertTrue("Expected 720p normalized stream from UpDown (1280x640)", result.streams.any { it.height == 720 })
            assertTrue("Expected 360p HLS stream from Uqload to merge", result.streams.any { it.height == 360 && it.isHls })
        }
    }

    @Test
    fun adversarialAjaxJsonParsing_andHeadlessCeilingInvariantsHold() {
        assertTrue(HeadlessWebExtractor.parseScrapedAjaxServersJson(null, "https://topcinemaa.one/").isEmpty())
        assertTrue(HeadlessWebExtractor.parseScrapedAjaxServersJson("null", "https://topcinemaa.one/").isEmpty())
        assertTrue(HeadlessWebExtractor.parseScrapedAjaxServersJson("\"{not-an-array}\"", "https://topcinemaa.one/").isEmpty())
        assertTrue(
            HeadlessWebExtractor.parseScrapedAjaxServersJson(
                "\"[{\\\"e\\\":\\\"javascript:alert(1)\\\",\\\"b\\\":\\\"id=1&i=1\\\"}]\"",
                "https://topcinemaa.one/"
            ).isEmpty()
        )

        assertTrue(HeadlessWebExtractor.PORTAL_AJAX_SCRAPE_JS.contains("[data-id][data-server]"))
        assertTrue(HeadlessWebExtractor.PORTAL_AJAX_SCRAPE_JS.contains("MyAjaxURL"))
        assertTrue(HeadlessWebExtractor.PORTAL_AJAX_SCRAPE_JS.contains("[data-post][data-nume]"))
        assertTrue(HeadlessWebExtractor.MAX_TOTAL_EXTRACTION_MS in 10_000L..20_000L)
        assertTrue(HeadlessWebExtractor.MAX_SOCIAL_TOTAL_EXTRACTION_MS in 5_000L..9_000L)
        assertEquals(5_500L, StreamingPortalResolver.PORTAL_TOTAL_BUDGET_MS)
        assertEquals(5_200L, StreamingPortalResolver.SERVER_PROBE_TIMEOUT_MS)
    }
}
