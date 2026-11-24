package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.intercept.AdBlocker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
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
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end loopback HTTP & unit regression/adversarial tests for WeCima multi-server
 * extraction, redirected HLS verification, confirmedHtml poisoning prevention, SPA embed APIs
 * (Vidara & Byse/Filemoon), ABStream Referer preservation, TopCinema play.php/movies2023 AJAX,
 * and concatenated UI badge stripping (`...Viewed`).
 */
class WeCimaMultiServerAndRedirectHlsFixTest {

    @Before
    fun setUp() {
        ExtractorRegistry.clearConfirmedHtml()
    }

    @After
    fun tearDown() {
        ExtractorRegistry.clearConfirmedHtml()
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
        val extraHeaders: Map<String, String> = emptyMap()
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
                                s.soTimeout = 5_000
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
                                val reason = when (resp.status) {
                                    200 -> "OK"
                                    206 -> "Partial Content"
                                    302 -> "Found"
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

    private fun validMpegTsBytes(packets: Int = 6): ByteArray {
        val bytes = ByteArray(188 * packets) { 0x15 }
        for (i in 0 until packets) bytes[i * 188] = 0x47
        return bytes
    }

    private fun encodeWeCimaObfuscatedUrl(rawUrl: String): String {
        val b64 = Base64.getEncoder().encodeToString(rawUrl.toByteArray(StandardCharsets.UTF_8))
        val withoutPrefix = b64.removePrefix("aHR0c")
        // Insert '+' noise characters inside the payload just like WeCima's encoder
        val mid = withoutPrefix.length / 2
        return withoutPrefix.substring(0, mid) + "++" + withoutPrefix.substring(mid)
    }

    @Test
    fun urlUtils_stripsConcatenatedViewedBadgeOnArabicWeCimaUrl_andPreservesAsciiSlugs() {
        val copied = "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-rick-and-morty-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%AA%D8%A7%D8%B3%D8%B9-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9-10-%D9%88%D8%A7%D9%84%D8%A7%D8%AE%D9%8A%D8%B1%D8%A9-%D9%85%D8%AA%D8%B1%D8%AC%D9%85%D8%A9Viewed"
        val expected = "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-rick-and-morty-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%AA%D8%A7%D8%B3%D8%B9-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9-10-%D9%88%D8%A7%D9%84%D8%A7%D8%AE%D9%8A%D8%B1%D8%A9-%D9%85%D8%AA%D8%B1%D8%AC%D9%85%D8%A9"

        assertEquals(expected, UrlUtils.normalize(copied))

        // Raw Arabic text + Watched suffix
        val rawArabic = "https://wecima.here/watch/مسلسل-rick-and-morty-مترجمةWatched"
        assertFalse(UrlUtils.normalize(rawArabic)!!.endsWith("Watched"))

        // Normal ASCII slug containing "viewed" must NEVER be altered
        val normalAscii = "https://example.com/videos/most-viewed"
        assertEquals(normalAscii, UrlUtils.normalize(normalAscii))

        // m.tiktok.com/v/<id>.html is treated as a redirect URL
        assertTrue(UrlUtils.isShortOrRedirectUrl("https://m.tiktok.com/v/7500112233445566778.html"))
    }

    @Test
    fun weCimaObfuscatedBase64AndPlayPhpDecoding_andAdversarialOpaqueBlobRejection() {
        val vidaraUrl = "https://vidaraa.cc/e/rnm0910"
        val abstreamUrl = "https://abstream.to/e/rnm0910"
        val obfuscatedVidara = encodeWeCimaObfuscatedUrl(vidaraUrl)
        val obfuscatedAbstream = encodeWeCimaObfuscatedUrl(abstreamUrl)

        assertEquals(vidaraUrl, StreamingPortalResolver.decodePortalEmbedUrl(obfuscatedVidara))
        assertEquals(abstreamUrl, StreamingPortalResolver.decodePortalEmbedUrl(obfuscatedAbstream))

        // TopCinema play.php?to= unwrapping (including double-prefixed URL)
        val wrappedPlay = "https://topcinemaa.one/play.php?to=https%3A%2F%2Ftopcinemaa.one%2Fhttps%3A%2F%2Fvidtube.one%2Fembed-abc.html"
        assertEquals("https://vidtube.one/embed-abc.html", StreamingPortalResolver.decodePortalEmbedUrl(wrappedPlay))

        // Adversarial: random opaque Base64 token that does NOT decode to http(s):// must return null (never same-host URL)
        assertNull(StreamingPortalResolver.decodePortalEmbedUrl("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo="))

        // HeadlessWebExtractor.selectPromotableEmbedUrls decodes obfuscated WeCima servers seamlessly
        val promoted = HeadlessWebExtractor.selectPromotableEmbedUrls(
            outerPageUrl = "https://wecima.here/watch/ep10",
            candidates = listOf(obfuscatedVidara, obfuscatedAbstream, "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=")
        )
        assertEquals(listOf(vidaraUrl, abstreamUrl), promoted)

        // Verify JS parity markers
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("aHR0c"))
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains(".openLinkDown[data-href]"))
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("play.php?to="))
        assertTrue(HeadlessWebExtractor.PORTAL_AJAX_SCRAPE_JS.contains("/wp-content/themes/"))
    }

    @Test
    fun earnVids502DoesNotPoisonConfirmedHtml_andRedirectedHls4ResolvesRelativeVariantsAgainstFinalCdnUrl() = runBlocking {
        val tsBytes = validMpegTsBytes()
        val master502Hits = AtomicInteger(0)

        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            when (req.path) {
                "/e/ep10" -> RespInfo(
                    status = 200,
                    contentType = "text/html",
                    body = """
                        <html><body><script>
                        var links = {
                          "hls2": "${serverRef.baseUrl}/dead-edge/master.m3u8",
                          "hls4": "/stream/token123/master.txt"
                        };
                        </script></body></html>
                    """.trimIndent().toByteArray()
                )
                "/dead-edge/master.m3u8" -> {
                    master502Hits.incrementAndGet()
                    // Cloudflare/Nginx 502 HTML error page must NEVER poison confirmedHtmlUrls
                    RespInfo(
                        status = 502,
                        contentType = "text/html; charset=utf-8",
                        body = "<html><body>502 Bad Gateway</body></html>".toByteArray()
                    )
                }
                "/stream/token123/master.txt" -> {
                    // 302 redirect to a different path where relative variants live!
                    RespInfo(
                        status = 302,
                        contentType = "text/plain",
                        extraHeaders = mapOf("Location" to "${serverRef.baseUrl}/cdn/v2/master.m3u8")
                    )
                }
                "/cdn/v2/master.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=2600000,RESOLUTION=1920x1080
                        v1080/index.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
                        v720/index.m3u8
                    """.trimIndent().toByteArray()
                )
                "/cdn/v2/v1080/index.m3u8", "/cdn/v2/v720/index.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:6
                        #EXTINF:6.0,
                        seg0.jpg
                        #EXT-X-ENDLIST
                    """.trimIndent().toByteArray()
                )
                "/cdn/v2/v1080/seg0.jpg", "/cdn/v2/v720/seg0.jpg" -> {
                    if (req.headers.containsKey("range")) {
                        RespInfo(status = 400, contentType = "text/plain", body = "No Range".toByteArray())
                    } else {
                        RespInfo(status = 200, contentType = "image/jpeg", body = tsBytes)
                    }
                }
                else -> RespInfo(status = 404)
            }
        }

        serverRef.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val hls = HlsExtractor(http)

            // 1. Verify hitting a 502 HTML master playlist does NOT poison confirmedHtmlUrls
            val deadResult1 = hls.extractVerifiedDetailed("${server.baseUrl}/dead-edge/master.m3u8", emptyMap())
            assertNull(deadResult1.media)
            assertEquals("master HTTP 502", deadResult1.failureReason)
            assertFalse(
                "HTTP 502 HTML on .m3u8 must NEVER poison ExtractorRegistry.confirmedHtmlUrls",
                ExtractorRegistry.isConfirmedHtml("${server.baseUrl}/dead-edge/master.m3u8")
            )
            // Retrying MUST actually hit the network again instead of returning "master confirmed HTML"
            val hitsBeforeRetry = master502Hits.get()
            val deadResult2 = hls.extractVerifiedDetailed("${server.baseUrl}/dead-edge/master.m3u8", emptyMap())
            assertEquals("master HTTP 502", deadResult2.failureReason)
            assertTrue("Expected retry to hit network instead of confirmedHtml short-circuit", master502Hits.get() > hitsBeforeRetry)

            // 2. Verify EarnVids links={hls2:502, hls4:302->CDN} succeeds via hls4 & resolves relative variants against /cdn/v2/!
            val portal = StreamingPortalResolver(http, hls, DashExtractor(http))
            val res = portal.resolveDirectEmbedUrls(
                pageUrl = "https://wecima.here/watch/rick-and-morty-s09e10",
                embedUrls = listOf("${server.baseUrl}/e/ep10"),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertTrue("Expected redirected hls4 variants to verify cleanly, got ${res.streams}", res.streams.isNotEmpty())
            val heights = res.streams.mapNotNull { it.height }.toSet()
            assertTrue("Expected 1080p & 720p variants, got $heights", heights.containsAll(setOf(1080, 720)))
            assertTrue(
                "Variant URLs must resolve against post-redirect /cdn/v2/, got ${res.streams.map { it.url }}",
                res.streams.all { it.url.contains("/cdn/v2/") }
            )
        }
    }

    @Test
    fun streamingPortalResolver_resolvesVidaraPostApi_ByseAesGcmApi_andTopCinemaMovies2023AjaxFallback() = runBlocking {
        val tsBytes = validMpegTsBytes()

        // Generate real AES-256-GCM test vector for Byse/Filemoon /api/videos/<code>
        val keyPartA = ByteArray(16) { (it + 1).toByte() }
        val keyPartB = ByteArray(16) { (it + 33).toByte() }
        val aesKey = keyPartA + keyPartB
        val iv = ByteArray(12) { (it + 7).toByte() }

        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            when (req.path) {
                // Vidara SPA shell + POST /api/stream
                "/vidara/e/vid123" -> RespInfo(
                    status = 200,
                    contentType = "text/html",
                    body = """<html><body><div id="app"></div><script>fetch('/api/stream',{method:'POST'})</script></body></html>""".toByteArray()
                )
                "/api/stream" -> {
                    val reqJson = runCatching { JSONObject(req.body) }.getOrNull()
                    if (req.method == "POST" && reqJson?.optString("filecode") == "vid123") {
                        RespInfo(
                            status = 200,
                            contentType = "application/json",
                            body = """{"streaming_url":"${serverRef.baseUrl}/vidara-cdn/master.m3u8","title":"Rick and Morty 1080p"}""".toByteArray()
                        )
                    } else {
                        RespInfo(status = 400)
                    }
                }
                "/vidara-cdn/master.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
                        ${serverRef.baseUrl}/vidara-cdn/v1080.m3u8
                    """.trimIndent().toByteArray()
                )
                "/vidara-cdn/v1080.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n${serverRef.baseUrl}/vidara-cdn/seg0.ts\n#EXT-X-ENDLIST".toByteArray()
                )
                "/vidara-cdn/seg0.ts" -> RespInfo(status = 200, contentType = "video/mp2t", body = tsBytes)

                // Byse/Filemoon SPA shell + GET /api/videos/byse99
                "/byse/e/byse99" -> RespInfo(
                    status = 200,
                    contentType = "text/html",
                    body = """<html><body><script>const endpoint='/api/videos/byse99';</script></body></html>""".toByteArray()
                )
                "/api/videos/byse99" -> {
                    val plainJson = """{"sources":[{"url":"${serverRef.baseUrl}/byse-cdn/video_720p.mp4","label":"720p","height":720}]}"""
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
                    val encrypted = cipher.doFinal(plainJson.toByteArray(StandardCharsets.UTF_8))
                    val b64Url = Base64.getUrlEncoder().withoutPadding()
                    val keyParts = JSONArray().apply {
                        for (i in 0 until 32) {
                            when (i) {
                                1 -> put(b64Url.encodeToString(keyPartA)) // version=2 -> idxA=2 -> index 1
                                28 -> put(b64Url.encodeToString(keyPartB)) // version=2 -> idxI=29 -> index 28
                                else -> put(b64Url.encodeToString(ByteArray(16) { 0x55 }))
                            }
                        }
                    }
                    val respObj = JSONObject().put(
                        "playback",
                        JSONObject()
                            .put("version", 2)
                            .put("iv", b64Url.encodeToString(iv))
                            .put("payload", b64Url.encodeToString(encrypted))
                            .put("key_parts", keyParts)
                    )
                    RespInfo(status = 200, contentType = "application/json", body = respObj.toString().toByteArray())
                }
                "/byse-cdn/video_720p.mp4" -> RespInfo(
                    status = 206,
                    contentType = "video/mp4",
                    body = byteArrayOf(0x00),
                    extraHeaders = mapOf("Content-Range" to "bytes 0-0/180000000", "Accept-Ranges" to "bytes")
                )

                // TopCinema AJAX fallback: /Ajaxat/Single/Server.php returns 404, /wp-content/themes/movies2023/Ajaxat/Single/Server.php succeeds with play.php?to=
                "/Ajaxat/Single/Server.php" -> RespInfo(status = 404)
                "/wp-content/themes/movies2023/Ajaxat/Single/Server.php" -> RespInfo(
                    status = 200,
                    contentType = "text/html",
                    body = """<iframe src="${serverRef.baseUrl}/play.php?to=${java.net.URLEncoder.encode("${serverRef.baseUrl}/vidara/e/vid123", "UTF-8")}"></iframe>""".toByteArray()
                )
                else -> RespInfo(status = 404)
            }
        }

        serverRef.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val portal = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))

            // 1. WeCima HTML with obfuscated btn[data-url] pointing to Vidara SPA
            val wecimaHtml = """
                <html><body>
                <ul class="WatchServersList">
                  <li><btn data-url="${encodeWeCimaObfuscatedUrl("${server.baseUrl}/vidara/e/vid123")}">vidaraa</btn></li>
                </ul>
                </body></html>
            """.trimIndent()
            val wecimaPageUrl = "https://wecima.here/watch/rick-and-morty-s09e10"
            val vidaraRes = portal.resolvePortalAndEmbeds(
                pageUrl = wecimaPageUrl,
                html = wecimaHtml,
                doc = Jsoup.parse(wecimaHtml, wecimaPageUrl),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertTrue("Expected Vidara /api/stream HLS to resolve from obfuscated WeCima tab", vidaraRes.streams.isNotEmpty())
            assertEquals(1080, vidaraRes.streams.first().height)

            // 2. Byse / Filemoon AES-256-GCM playback decryption
            val byseRes = portal.resolveDirectEmbedUrls(
                pageUrl = wecimaPageUrl,
                embedUrls = listOf("${server.baseUrl}/byse/e/byse99"),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertTrue("Expected Byse AES-256-GCM stream to decrypt and verify", byseRes.streams.isNotEmpty())
            assertEquals(720, byseRes.streams.first().height)
            assertTrue(byseRes.streams.first().url.endsWith("/byse-cdn/video_720p.mp4"))

            // 3. TopCinema AJAX fallback to /wp-content/themes/movies2023/Ajaxat/Single/Server.php + play.php?to= unwrap
            val ajaxFallbackRes = portal.resolveHybridDomAndAjax(
                pageUrl = "${server.baseUrl}/watch/ep1/",
                embedUrls = emptyList(),
                ajaxServers = listOf(
                    StreamingPortalResolver.AjaxServerSpec(
                        endpointUrl = "${server.baseUrl}/Ajaxat/Single/Server.php",
                        formBody = "id=99&i=1",
                        serverName = "UpDown"
                    )
                ),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertTrue("Expected movies2023 AJAX fallback + play.php unwrap to resolve cleanly", ajaxFallbackRes.streams.isNotEmpty())
            assertEquals(1080, ajaxFallbackRes.streams.first().height)
        }
    }

    @Test
    fun jsUnpackerResolvesOpaqueRelativeHls4_stripsVidtubeRefererQuery_andAdBlockerProtectsMirrors() {
        val script = """
            var links = {
              "hls2": "https://dead-edge.example/hls2/master.m3u8",
              "hls3": "https://slow-edge.example/hls3/master.m3u8",
              "hls4": "stream/fast/master.txt"
            };
            jwplayer("v").setup({file:"https://cdn.vidtube.example/v/ep1.mp4?t=abc&referer=https://topcinemaa.one/"});
        """.trimIndent()
        val cfg = JsUnpacker.extractPlayerConfigs(script, baseUrl = "https://morencius.com/e/xyz")
        assertTrue("Expected at least 4 extracted sources, got ${cfg.sources.size}", cfg.sources.size >= 4)
        assertTrue(
            "Expected opaque relative hls4 path to resolve against baseUrl, got ${cfg.sources.map { it.url }}",
            cfg.sources.any { it.url == "https://morencius.com/e/stream/fast/master.txt" && it.isHlsHint }
        )
        val mp4Source = cfg.sources.firstOrNull { it.url.contains("vidtube.example") }
        assertNotNull(mp4Source)
        assertEquals("https://cdn.vidtube.example/v/ep1.mp4?t=abc", mp4Source!!.url)

        // Protected mirrors
        assertTrue(AdBlocker.isProtectedEmbedHost("https://morencius.com/e/abc"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://delucloud.xyz/hls/master.m3u8"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://vibuxer.com/e/abc"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://byseqekaho.com/e/abc"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://abstream.to/e/abc"))
    }
}
