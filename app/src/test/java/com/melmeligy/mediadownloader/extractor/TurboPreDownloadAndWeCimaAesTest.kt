package com.melmeligy.mediadownloader.extractor

import androidx.lifecycle.SavedStateHandle
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.ExtractionPriorityCoordinator
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.intercept.BlobBridge
import com.melmeligy.mediadownloader.ui.quality.QualityViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end loopback HTTP, unit, and adversarial verification suite for:
 * 1. WeCima CMS v2.8 AES-256-GCM server/download link decryption (built-in key + rotated `mains.js` auto-heal).
 * 2. Adversarial ciphertext tampering, truncated blobs, and opaque Base64 rejection.
 * 3. Cloudflare 403 header fast-detection, `STAGE_CLOUDFLARE`, and interstitial lifecycle guard.
 * 4. `HostHealthRegistry` ranking, TTL decay, and single-RTT HLS segment verification (disguised + learned).
 * 5. Hedged 403 HLS header relaxation.
 * 6. Session-scoped live `PreviewMetadata` isolation and speculative clipboard prefetch 0ms cache hit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurboPreDownloadAndWeCimaAesTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        WeCimaLinkDecryptor.resetCachedKeyForTesting()
        HostHealthRegistry.shared.reset()
        HostHealthRegistry.shared.clockMs = { System.currentTimeMillis() }
        ExtractorRegistry.clearConfirmedHtml()
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
    }

    @After
    fun tearDown() {
        WeCimaLinkDecryptor.resetCachedKeyForTesting()
        HostHealthRegistry.shared.reset()
        HostHealthRegistry.shared.clockMs = { System.currentTimeMillis() }
        ExtractorRegistry.clearConfirmedHtml()
        HtmlMediaExtractor.clearAllCloudflareHintsForTests()
        Dispatchers.resetMain()
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

    /** Encodes a string into WeCima mains.js swapped-case Base64 obfuscator table format. */
    private fun encodeMainsJsTableString(plain: String): String {
        val b64 = Base64.getEncoder().encodeToString(plain.toByteArray(StandardCharsets.UTF_8)).trimEnd('=')
        return buildString(b64.length) {
            for (c in b64) {
                append(
                    when {
                        c.isUpperCase() -> c.lowercaseChar()
                        c.isLowerCase() -> c.uppercaseChar()
                        else -> c
                    }
                )
            }
        }
    }

    /** Builds a realistic javascript-obfuscator mains.js payload with rotated table + checksum loop + LINK_KEY. */
    private fun buildObfuscatedMainsJs(linkKey: String): String {
        val partA = linkKey.substring(0, linkKey.length / 2)
        val partB = linkKey.substring(linkKey.length / 2)
        // Aligned (unrotated) table at offset 0x100:
        // [0] = "120abc" (parseInt = 120), [1] = "20xyz" (parseInt = 20), [2] = partA, [3] = partB, [4] = "noise"
        val aligned = listOf("120abc", "20xyz", partA, partB, "noise").map(::encodeMainsJsTableString)
        // Rotate by 2 positions so the extractor must actually solve the checksum rotation loop!
        val rotated = aligned.drop(2) + aligned.take(2)
        val tableLiteral = rotated.joinToString(",") { "'$it'" }
        // target = 120 * 2 + 20 = 260 = 0x104
        return """
            function _0x1a2b(){var _0x3c4d=[$tableLiteral];_0x1a2b=function(){return _0x3c4d;};return _0x1a2b();}
            function _0x5e6f(_0x11,_0x22){_0x11=_0x11-0x100;return _0x1a2b()[_0x11];}
            (function(_0xa,_0xb){var _0xc=_0xa();while(!![]){try{var _0xd=parseInt(_0x5e6f(0x100))*2+parseInt(_0x5e6f(0x101));if(_0xd===_0xb)break;else _0xc['push'](_0xc['shift']());}catch(_0xe){_0xc['push'](_0xc['shift']());}}}(_0x1a2b,0x104));
            var LINK_KEY=_0x5e6f(0x102)+_0x5e6f(0x103);
        """.trimIndent()
    }

    @Test
    fun weCimaTips_aes256GcmBuiltInKey_andRotatedMainsJsAutoHeal_resolveWatchServersAndDownloadsEndToEnd() = runBlocking {
        val tsBytes = validMpegTsBytes()
        val rotatedKey = "Z9kLmN2pQrStUvWxYz0123456789AbCdEfGhIjKl"
        val obfuscatedMainsJs = buildObfuscatedMainsJs(rotatedKey)
        val mainsJsHits = AtomicInteger(0)

        // Verify pure-Kotlin obfuscated mains.js key extraction directly first
        assertEquals(rotatedKey, WeCimaLinkDecryptor.extractLinkKeyFromMainsJs(obfuscatedMainsJs))

        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            when (req.path) {
                "/static/js/mains.js" -> {
                    mainsJsHits.incrementAndGet()
                    RespInfo(status = 200, contentType = "application/javascript", body = obfuscatedMainsJs.toByteArray())
                }
                "/dead-server/e/1" -> RespInfo(status = 502, contentType = "text/html", body = "<html>502</html>".toByteArray())
                "/vidara/e/reckoning26" -> RespInfo(
                    status = 200,
                    contentType = "text/html",
                    body = """<html><body><script>fetch('/api/stream',{method:'POST'})</script></body></html>""".toByteArray()
                )
                "/api/stream" -> RespInfo(
                    status = 200,
                    contentType = "application/json",
                    body = """{"streaming_url":"${serverRef.baseUrl}/cdn/reckoning/master.m3u8","title":"The Social Reckoning 2026"}""".toByteArray()
                )
                "/cdn/reckoning/master.m3u8" -> RespInfo(
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
                "/cdn/reckoning/v1080.m3u8", "/cdn/reckoning/v720.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.jpg\n#EXT-X-ENDLIST".toByteArray()
                )
                "/cdn/reckoning/seg0.jpg" -> {
                    if (req.headers.containsKey("range")) {
                        RespInfo(status = 400, contentType = "text/plain", body = "No range".toByteArray())
                    } else {
                        RespInfo(status = 200, contentType = "image/jpeg", body = tsBytes)
                    }
                }
                "/downloads/social-reckoning-720p.mp4" -> RespInfo(
                    status = 206,
                    contentType = "video/mp4",
                    body = byteArrayOf(0x00),
                    extraHeaders = mapOf("Content-Range" to "bytes 0-0/845000000", "Accept-Ranges" to "bytes")
                )
                else -> RespInfo(status = 404)
            }
        }

        serverRef.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val portal = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))

            // Part A: Built-in mains.js v12.4 LINK_KEY (zero extra mains.js fetch!)
            val encDead = WeCimaLinkDecryptor.encryptForTesting("${server.baseUrl}/dead-server/e/1")
            val encVidara = WeCimaLinkDecryptor.encryptForTesting("${server.baseUrl}/vidara/e/reckoning26")
            val encDownload = WeCimaLinkDecryptor.encryptForTesting("${server.baseUrl}/downloads/social-reckoning-720p.mp4")

            val watchHtmlBuiltIn = """
                <html><head><title>مشاهدة فيلم The Social Reckoning 2026 مترجم | وى سيما</title></head><body>
                <ul class="WatchServersList">
                  <li><btn data-url="$encDead">Server 1</btn></li>
                  <li><btn data-url="$encVidara">Server 2</btn></li>
                </ul>
                <ul class="List--Download--Wecima--Single">
                  <li><a class="openLinkDown" data-href="$encDownload">720p Direct</a></li>
                </ul>
                </body></html>
            """.trimIndent()

            val builtInPageUrl = "${server.baseUrl}/watch/the-social-reckoning-2026-movie"
            val builtInRes = portal.resolvePortalAndEmbeds(
                pageUrl = builtInPageUrl,
                html = watchHtmlBuiltIn,
                doc = Jsoup.parse(watchHtmlBuiltIn, builtInPageUrl),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertEquals("Built-in key should not require fetching mains.js", 0, mainsJsHits.get())
            assertTrue("Expected resolved streams from AES-GCM WeCima watch page, got ${builtInRes.streams}", builtInRes.streams.isNotEmpty())
            assertTrue(builtInRes.streams.any { it.height == 1080 && it.isHls })

            // Part B: Rotated LINK_KEY in mains.js -> auto-heals via pure-Kotlin obfuscator solver!
            WeCimaLinkDecryptor.resetCachedKeyForTesting()
            val encRotatedVidara = WeCimaLinkDecryptor.encryptForTesting("${server.baseUrl}/vidara/e/reckoning26", linkKey = rotatedKey)
            val watchHtmlRotated = """
                <html><head><script src="/static/js/mains.js?v=13.0"></script></head><body>
                <ul class="WatchServersList">
                  <li><btn data-url="$encRotatedVidara">Server 1</btn></li>
                </ul>
                </body></html>
            """.trimIndent()

            val rotatedRes = portal.resolvePortalAndEmbeds(
                pageUrl = builtInPageUrl,
                html = watchHtmlRotated,
                doc = Jsoup.parse(watchHtmlRotated, builtInPageUrl),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertTrue("Expected auto-heal fetch of mains.js", mainsJsHits.get() >= 1)
            assertEquals("Cached LINK_KEY must update to rotated key", rotatedKey, WeCimaLinkDecryptor.currentLinkKey())
            assertTrue("Expected 1080p stream after mains.js key rotation recovery", rotatedRes.streams.any { it.height == 1080 })

            // Part C: Hybrid DOM path (from HeadlessWebExtractor) also auto-heals rotated key!
            WeCimaLinkDecryptor.resetCachedKeyForTesting()
            val hitsBeforeHybrid = mainsJsHits.get()
            val hybridRes = portal.resolveHybridDomAndAjax(
                pageUrl = builtInPageUrl,
                embedUrls = listOf(encRotatedVidara),
                ajaxServers = emptyList(),
                baseHeaders = mapOf("User-Agent" to "TestUA"),
                mainsJsUrl = "${server.baseUrl}/static/js/mains.js?v=13.0"
            )
            assertTrue("Expected hybrid path to fetch mains.js on rotated key", mainsJsHits.get() > hitsBeforeHybrid)
            assertTrue("Expected hybrid path to resolve 1080p stream", hybridRes.streams.any { it.height == 1080 })
        }
    }

    @Test
    fun adversarial_tamperedAesGcmTag_truncatedCiphertext_andOpaqueTokens_neverProduceSameHostUrls() {
        val validUrl = "https://vidaraa.cc/e/reckoning2026"
        val validCipher = WeCimaLinkDecryptor.encryptForTesting(validUrl)
        assertEquals(validUrl, WeCimaLinkDecryptor.decodePortalEmbedUrl(validCipher))

        // 1. Flip a byte inside the AES-GCM auth tag -> decryption MUST reject (return null)
        val raw = Base64.getDecoder().decode(validCipher)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0xFF).toByte()
        val tampered = Base64.getEncoder().encodeToString(raw)
        assertNull("Tampered AES-GCM tag must return null", WeCimaLinkDecryptor.decodePortalEmbedUrl(tampered))

        // 2. Truncated <= 28-byte Base64 blob -> must return null
        val truncated = Base64.getEncoder().encodeToString(ByteArray(24) { 0x42 })
        assertNull("Truncated ciphertext must return null", WeCimaLinkDecryptor.decodePortalEmbedUrl(truncated))

        // 3. Random 64-byte opaque Base64 blob -> must return null (never same-host URL)
        val opaque = Base64.getEncoder().encodeToString(ByteArray(64) { (it * 7 + 3).toByte() })
        assertNull("Random opaque Base64 blob must return null", WeCimaLinkDecryptor.decodePortalEmbedUrl(opaque))
        assertTrue(WeCimaLinkDecryptor.looksLikeEncryptedWeCimaValue(opaque))

        // 4. HeadlessWebExtractor.selectPromotableEmbedUrls keeps decrypted AES-GCM embed and drops tampered/opaque tokens
        val promoted = HeadlessWebExtractor.selectPromotableEmbedUrls(
            outerPageUrl = "https://wecima.tips/watch/the-social-reckoning-2026-movie",
            candidates = listOf(tampered, opaque, validCipher)
        )
        assertEquals(listOf(validUrl), promoted)

        // 5. JS parity invariants for raw encrypted Base64 pass-through and SubtleCrypto observer
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("raw.length>=40"))
        assertTrue(BlobBridge.SCRIPT.contains("crypto.subtle.decrypt"))
    }

    @Test
    fun cloudflareChallenge_fastSkipsStage1WithHeadersOnly_andInterstitialGuardProtectsHeadless() = runBlocking {
        val pageHits = AtomicInteger(0)
        LoopbackServer { req ->
            pageHits.incrementAndGet()
            RespInfo(
                status = 403,
                contentType = "text/html; charset=UTF-8",
                body = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>cf challenge</body></html>".toByteArray(),
                extraHeaders = mapOf("cf-mitigated" to "challenge", "server" to "cloudflare")
            )
        }.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val targetUrl = "${server.baseUrl}/watch/the-social-reckoning-2026-movie"

            // 1. ExtractorHttp throws ACCESS_DENIED immediately on cf-mitigated header and does NOT poison confirmedHtmlUrls
            val err = runCatching { http.fetchTextWithFinalUrl(targetUrl) }.exceptionOrNull() as? MediaException
            assertNotNull("Expected MediaException on Cloudflare 403 challenge", err)
            assertEquals(AppError.ACCESS_DENIED, err!!.error)
            assertTrue(err.message.orEmpty().contains("Cloudflare", ignoreCase = true))
            assertFalse(
                "Cloudflare challenge must not poison confirmedHtmlUrls",
                ExtractorRegistry.isConfirmedHtml(targetUrl)
            )

            // 2. HtmlMediaExtractor sets STAGE_CLOUDFLARE and fast-skips static HTTP on subsequent call
            val tracker = ExtractionProgressTracker()
            val mockHeadless = mockk<HeadlessWebExtractor>()
            coEvery { mockHeadless.extract(any(), any(), any()) } returns null
            val htmlExtractor = HtmlMediaExtractor(http, HlsExtractor(http), DashExtractor(http), mockHeadless, tracker)
            htmlExtractor.cookieProvider = { null }

            val hitsBeforeFirst = pageHits.get()
            htmlExtractor.extractWithHeaders(targetUrl, allowHeadlessFallback = true)
            assertEquals(hitsBeforeFirst + 1, pageHits.get())
            assertEquals(ExtractionProgressTracker.STAGE_CLOUDFLARE, tracker.stage.value)

            // Second call must skip blocked static HTTP completely via CF challenge hint
            val hitsBeforeSecond = pageHits.get()
            htmlExtractor.extractWithHeaders(targetUrl, allowHeadlessFallback = true)
            assertEquals("Expected CF challenge fast-skip with 0 static HTTP hits", hitsBeforeSecond, pageHits.get())

            // 3. Interstitial page detection catches Cloudflare titles & URLs without false positives
            assertTrue(HeadlessWebExtractor.isCloudflareInterstitialPage(targetUrl, "Just a moment..."))
            assertTrue(HeadlessWebExtractor.isCloudflareInterstitialPage("$targetUrl?__cf_chl_tk=abc", ""))
            assertFalse(
                HeadlessWebExtractor.isCloudflareInterstitialPage(
                    "https://wecima.tips/watch/the-social-reckoning-2026-movie",
                    "مشاهدة فيلم The Social Reckoning 2026 مترجم | وى سيما"
                )
            )
        }
    }

    @Test
    fun hostHealthRegistry_demotesFailingMirror_andSingleRttHlsSkipsRangeOnDisguisedAndRangeRejectingCdns() = runBlocking {
        val tsBytes = validMpegTsBytes()
        val disguisedRangeHits = AtomicInteger(0)
        val disguisedTotalHits = AtomicInteger(0)
        val tsRangeHits = AtomicInteger(0)
        val tsTotalHits = AtomicInteger(0)

        LoopbackServer { req ->
            when (req.path) {
                "/disguised/master.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.jpg\n#EXT-X-ENDLIST".toByteArray()
                )
                "/disguised/seg0.jpg" -> {
                    disguisedTotalHits.incrementAndGet()
                    if (req.headers.containsKey("range")) {
                        disguisedRangeHits.incrementAndGet()
                        RespInfo(status = 400, contentType = "text/plain", body = "No Range".toByteArray())
                    } else {
                        RespInfo(status = 200, contentType = "image/jpeg", body = tsBytes)
                    }
                }
                "/learned/master.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST".toByteArray()
                )
                "/learned/seg0.ts" -> {
                    tsTotalHits.incrementAndGet()
                    if (req.headers.containsKey("range")) {
                        tsRangeHits.incrementAndGet()
                        RespInfo(status = 400, contentType = "text/plain", body = "Range unsupported".toByteArray())
                    } else {
                        RespInfo(status = 200, contentType = "video/mp2t", body = tsBytes)
                    }
                }
                else -> RespInfo(status = 404)
            }
        }.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val hls = HlsExtractor(http)

            // Part A: Disguised .jpg HLS segment skips Range:0-0 completely -> exactly 1 RTT!
            val resDisguised = hls.extractVerifiedDetailed("${server.baseUrl}/disguised/master.m3u8", emptyMap())
            assertNotNull("Expected disguised segment to verify", resDisguised.media)
            assertEquals("Disguised .jpg segment must skip Range:0-0 probe completely", 0, disguisedRangeHits.get())
            assertEquals("Expected single-RTT segment verification", 1, disguisedTotalHits.get())

            // Part B: Standard .ts segment rejects Range on 1st call -> HostHealthRegistry learns -> 2nd call skips Range (0 Range hits)!
            val resTs1 = hls.extractVerifiedDetailed("${server.baseUrl}/learned/master.m3u8", emptyMap())
            assertNotNull(resTs1.media)
            assertEquals(1, tsRangeHits.get())
            assertTrue(HostHealthRegistry.shared.shouldSkipRangeProbeOnSegments("${server.baseUrl}/learned/seg0.ts"))

            val rangeBeforeSecond = tsRangeHits.get()
            val totalBeforeSecond = tsTotalHits.get()
            val resTs2 = hls.extractVerifiedDetailed("${server.baseUrl}/learned/master.m3u8", emptyMap())
            assertNotNull(resTs2.media)
            assertEquals("2nd verification on range-rejecting host must send 0 Range requests", rangeBeforeSecond, tsRangeHits.get())
            assertEquals("2nd verification must complete in 1 segment RTT", totalBeforeSecond + 1, tsTotalHits.get())
        }

        // Part C: HostHealthRegistry ranking & TTL expiry (no permanent blacklisting)
        var fakeNow = 10_000L
        HostHealthRegistry.shared.reset()
        HostHealthRegistry.shared.clockMs = { fakeNow }

        HostHealthRegistry.shared.recordFailure("https://dead-mirror.example/e/1")
        HostHealthRegistry.shared.recordFailure("https://dead-mirror.example/e/1")
        HostHealthRegistry.shared.recordSuccess("https://fast-mirror.example/e/2", latencyMs = 180L)

        assertTrue(HostHealthRegistry.shared.isDegraded("https://dead-mirror.example/e/1"))
        val ranked = HeadlessWebExtractor.selectPromotableEmbedUrls(
            outerPageUrl = "https://wecima.tips/watch/movie",
            candidates = listOf(
                "https://dead-mirror.example/e/1",
                "https://neutral-mirror.example/e/3",
                "https://fast-mirror.example/e/2"
            ),
            maxEmbeds = 3
        )
        assertEquals(
            listOf(
                "https://fast-mirror.example/e/2",
                "https://neutral-mirror.example/e/3",
                "https://dead-mirror.example/e/1"
            ),
            ranked
        )

        // Advance clock past TTL -> stale failure state decays automatically
        fakeNow += HostHealthRegistry.HOST_STATE_TTL_MS + 1_000L
        assertFalse(HostHealthRegistry.shared.isDegraded("https://dead-mirror.example/e/1"))
        assertEquals(0, HostHealthRegistry.shared.scoreForRanking("https://dead-mirror.example/e/1"))
    }

    @Test
    fun hedgedHls403HeaderRelaxation_winsViaFastestRelaxedVariant_andPropagatesHeaders() = runBlocking {
        val tsBytes = validMpegTsBytes()
        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            when (req.path) {
                "/hedged/master.m3u8" -> {
                    val ref = req.headers["referer"].orEmpty()
                    val origin = req.headers["origin"].orEmpty()
                    when {
                        // Primary full-path referer -> immediate 403
                        ref.contains("/embed/page") -> RespInfo(status = 403, contentType = "text/plain")
                        // Variant 0 (origin-only Referer + Origin) -> slow 403 after 450ms
                        ref.isNotEmpty() && origin.isNotEmpty() -> RespInfo(status = 403, delayMs = 450L)
                        // Variant 1 (origin-only Referer without Origin) -> instant 200 valid playlist!
                        ref.isNotEmpty() && origin.isEmpty() -> RespInfo(
                            status = 200,
                            contentType = "application/vnd.apple.mpegurl",
                            body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.jpg\n#EXT-X-ENDLIST".toByteArray()
                        )
                        else -> RespInfo(status = 403)
                    }
                }
                "/hedged/seg0.jpg" -> {
                    if (!req.headers["referer"].isNullOrBlank() && req.headers["origin"].isNullOrBlank()) {
                        RespInfo(status = 200, contentType = "image/jpeg", body = tsBytes)
                    } else {
                        RespInfo(status = 403)
                    }
                }
                else -> RespInfo(status = 404)
            }
        }
        serverRef.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val hls = HlsExtractor(http)
            val start = System.currentTimeMillis()
            val res = hls.extractVerifiedDetailed(
                url = "${server.baseUrl}/hedged/master.m3u8",
                headers = mapOf(
                    "Referer" to "${server.baseUrl}/embed/page",
                    "Origin" to server.baseUrl,
                    "User-Agent" to "TestUA"
                )
            )
            val elapsed = System.currentTimeMillis() - start
            assertNotNull("Expected hedged 403 relaxation to succeed, reason=${res.failureReason}", res.media)
            assertTrue("Expected hedged race to finish well under slow variant + sequential overhead (was ${elapsed}ms)", elapsed < 1_500L)
            val winningHeaders = res.media!!.streams.first().headers
            assertEquals("${server.baseUrl}/", winningHeaders["Referer"])
            assertNull("Winning variant must not carry rejected Origin header", winningHeaders["Origin"])
        }
    }

    @Test
    fun speculativeClipboardPrefetch_andSessionScopedPreviewMetadata_preventStaleLeaksAndHitZeroMsCache() = runBlocking {
        // Part A: Session-scoped PreviewMetadata isolation
        val tracker = ExtractionProgressTracker()
        val s1 = tracker.beginSession()
        tracker.runInSession(s1) {
            tracker.updatePreview(
                title = "The Social Reckoning (2026)",
                thumbnailUrl = "https://wecima.tips/poster.jpg",
                sourceHost = "wecima.tips"
            )
        }
        assertEquals("The Social Reckoning (2026)", tracker.preview.value?.title)
        assertEquals("https://wecima.tips/poster.jpg", tracker.preview.value?.thumbnailUrl)

        // Starting Session 2 immediately clears preview, and late Session 1 / background writes are rejected
        val s2 = tracker.beginSession()
        assertNull("New session must clear previous preview metadata", tracker.preview.value)
        tracker.runInSession(s1) {
            tracker.updatePreview(title = "STALE TITLE", thumbnailUrl = "https://stale.example/a.jpg")
        }
        tracker.runSilentBackground {
            tracker.updatePreview(title = "BG TITLE", thumbnailUrl = "https://bg.example/a.jpg")
        }
        assertNull("Superseded session & silent background must never leak preview metadata", tracker.preview.value)
        tracker.runInSession(s2) {
            tracker.updatePreview(title = "Fresh Video", sourceHost = "youtube.com")
        }
        assertEquals("Fresh Video", tracker.preview.value?.title)

        // Part B: Speculative clipboard prefetch warms fast-API cache so QualityViewModel resolves in 0ms
        val ytUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val portalUrl = "https://wecima.tips/watch/the-social-reckoning-2026-movie"
        assertTrue(ExtractorRegistry.isSpeculativeApiPrefetchEligible(ytUrl))
        assertFalse(ExtractorRegistry.isSpeculativeApiPrefetchEligible(portalUrl))

        val extractCalls = AtomicInteger(0)
        val fakeYtExtractor = object : MediaExtractor {
            override val name = "YouTube"
            override val priority = 100
            override fun canHandle(url: String) = url.contains("youtube.com")
            override suspend fun extract(url: String): ResolvedMedia {
                extractCalls.incrementAndGet()
                return ResolvedMedia(
                    sourceUrl = url,
                    title = "Prefetched Clip",
                    thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                    streams = listOf(
                        MediaStream(
                            id = "yt-720",
                            url = "https://cdn.example/clip_720.mp4",
                            type = MediaType.VIDEO,
                            container = "mp4",
                            label = "720p HD",
                            height = 720,
                            sizeBytes = 12_000_000L
                        )
                    ),
                    extractor = name
                )
            }
        }
        val fakePortalExtractor = object : MediaExtractor {
            override val name = "Web page"
            override val priority = 50
            override fun canHandle(url: String) = url.contains("wecima.tips")
            override suspend fun extract(url: String): ResolvedMedia? {
                extractCalls.incrementAndGet()
                return null
            }
        }

        val testDispatchers = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Default
        }
        val netMonitor = mockk<NetworkMonitor>().apply { every { isOnline() } returns true }
        val coordinator = ExtractionPriorityCoordinator()
        val registry = ExtractorRegistry(
            extractors = setOf(fakeYtExtractor, fakePortalExtractor),
            networkMonitor = netMonitor,
            dispatchers = testDispatchers,
            priorityCoordinator = coordinator,
            progressTracker = tracker
        )
        val repo = MediaRepositoryImpl(registry)

        // Portal URL must NOT invoke heavy WebView/HTML extractor during speculative prefetch
        repo.prefetchSpeculative(portalUrl)
        delay(120L)
        assertEquals("Speculative prefetch must not extract non-API streaming portals", 0, extractCalls.get())

        // Fast-API YouTube URL prefetches silently into cache
        repo.prefetchSpeculative(ytUrl)
        var waited = 0
        while (repo.peekCached(ytUrl) == null && waited < 40) {
            delay(25L)
            waited++
        }
        assertNotNull("Expected speculative prefetch to warm ExtractorRegistry cache", repo.peekCached(ytUrl))
        assertEquals(1, extractCalls.get())

        // QualityViewModel.resolve() hits synchronous peekCached() immediately with zero extra extraction calls!
        val settingsRepo = mockk<SettingsRepository>().apply {
            every { settings } returns MutableStateFlow(AppSettings())
            coEvery { current() } returns AppSettings()
        }
        val dm = mockk<DownloadManager>(relaxed = true)
        val vm = QualityViewModel(repo, dm, settingsRepo, SavedStateHandle())
        vm.resolve(ytUrl)
        val state = vm.state.value
        assertTrue("Expected synchronous Resource.Success from warm speculative cache, got $state", state is Resource.Success)
        assertEquals("Prefetched Clip", (state as Resource.Success).data.title)
        assertEquals("Synchronous cache hit must make 0 additional extractor calls", 1, extractCalls.get())
    }
}
