package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.intercept.AdBlocker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * End-to-end loopback HTTP and unit regression/adversarial tests for WeCima EarnVids/Vinovo
 * HLS verification fixes:
 * 1. Post-redirect embed Referer/Origin preservation in HeadlessWebExtractor and StreamingPortalResolver.
 * 2. Origin-only `Referer` + `Origin` CORS header variant in ExtractorHttp.buildRelaxedHeaderVariants.
 * 3. Disguised `image/png` and `text/plain` HLS segments with >64-byte (256B) fake PNG headers
 *    validated cleanly via 4096B prefix scan, while rejecting tiny 43-byte tracking GIFs and HTML/text errors.
 * 4. Exact stage+status diagnostic reporting via HlsExtractor.extractVerifiedDetailed.
 * 5. WeCima server host protection in AdBlocker and direct-download scraping / iframe-promotion filtering.
 */
class WeCimaEmbedRedirectAndDisguisedSegmentTest {

    private data class ReqInfo(
        val method: String,
        val path: String,
        val headers: Map<String, String>
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
                                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                val requestLine = reader.readLine() ?: return@runCatching
                                val parts = requestLine.split(' ')
                                val method = parts.getOrNull(0).orEmpty()
                                val path = parts.getOrNull(1).orEmpty().substringBefore('?')
                                val headers = LinkedHashMap<String, String>()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    val colon = line.indexOf(':')
                                    if (colon > 0) {
                                        headers[line.substring(0, colon).trim().lowercase()] =
                                            line.substring(colon + 1).trim()
                                    }
                                }
                                val resp = handler(ReqInfo(method, path, headers))
                                val reason = when (resp.status) {
                                    200 -> "OK"
                                    206 -> "Partial Content"
                                    302 -> "Found"
                                    400 -> "Bad Request"
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

    private fun fakePngWrappedMpegTs(wrapperBytes: Int = 256, tsPackets: Int = 10): ByteArray {
        val total = wrapperBytes + (tsPackets * 188)
        val data = ByteArray(total) { 0x22 }
        // Standard PNG 8-byte magic header + dummy IHDR/IDAT chunks filling wrapperBytes
        val pngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        System.arraycopy(pngMagic, 0, data, 0, pngMagic.size)
        for (pkt in 0 until tsPackets) {
            data[wrapperBytes + pkt * 188] = 0x47
        }
        return data
    }

    @Test
    fun applyPromotedEmbedReferer_preservesPostRedirectEmbedOrigin_andRewritesLeakedOuterPortal() {
        val outer = "https://wecima.here/watch/rick-and-morty-ep-9"
        val preRedirectEmbed = "https://earnvids.pro/e/abc123"
        val postRedirectReferer = "https://dintezuvio.com/"
        val postRedirectOrigin = "https://dintezuvio.com"

        // Case 1: Chromium already sent real post-redirect mirror Referer & Origin -> MUST preserve!
        val preserved = HeadlessWebExtractor.applyPromotedEmbedReferer(
            headers = mapOf(
                "Referer" to postRedirectReferer,
                "Origin" to postRedirectOrigin,
                "User-Agent" to "ChromeUA"
            ),
            promotedEmbedUrl = preRedirectEmbed,
            fallbackPageUrl = outer,
            outerPageUrl = outer
        )
        assertEquals(postRedirectReferer, preserved["Referer"])
        assertEquals(postRedirectOrigin, preserved["Origin"])

        // Case 2: Payload leaked outer portal (wecima.here) as Referer -> MUST rewrite to promoted embed!
        val rewritten = HeadlessWebExtractor.applyPromotedEmbedReferer(
            headers = mapOf("Referer" to outer, "User-Agent" to "ChromeUA"),
            promotedEmbedUrl = preRedirectEmbed,
            fallbackPageUrl = outer,
            outerPageUrl = outer
        )
        assertEquals(preRedirectEmbed, rewritten["Referer"])
        assertEquals("https://earnvids.pro", rewritten["Origin"])

        // Case 3: Missing Referer -> MUST fill promoted embed & its Origin!
        val filled = HeadlessWebExtractor.applyPromotedEmbedReferer(
            headers = mapOf("User-Agent" to "ChromeUA"),
            promotedEmbedUrl = preRedirectEmbed,
            fallbackPageUrl = outer,
            outerPageUrl = outer
        )
        assertEquals(preRedirectEmbed, filled["Referer"])
        assertEquals("https://earnvids.pro", filled["Origin"])
    }

    @Test
    fun streamingPortalResolver_followsEmbed302Redirect_preservesFinalUrlAndCookieForHlsVerification() = runBlocking {
        val tsSeg = fakePngWrappedMpegTs(wrapperBytes = 256, tsPackets = 8)
        LoopbackServer { req ->
            when (req.path) {
                "/embed-pre/abc" -> RespInfo(
                    status = 302,
                    contentType = "text/html",
                    extraHeaders = mapOf(
                        "Location" to "/mirror/v/abc",
                        "Set-Cookie" to "mirror_tok=ok123; Path=/"
                    )
                )
                "/mirror/v/abc" -> RespInfo(
                    status = 200,
                    contentType = "text/html",
                    body = """
                        <html><body><script>
                        var links = { "hls2": "hls2/master.m3u8" };
                        </script></body></html>
                    """.trimIndent().toByteArray()
                )
                "/mirror/v/hls2/master.m3u8" -> {
                    val ref = req.headers["referer"].orEmpty()
                    val cookie = req.headers["cookie"].orEmpty()
                    if (!ref.contains("/mirror/v/abc") || !cookie.contains("mirror_tok=ok123")) {
                        RespInfo(status = 403, contentType = "text/plain", body = "Forbidden".toByteArray())
                    } else {
                        RespInfo(
                            status = 200,
                            contentType = "application/vnd.apple.mpegurl",
                            body = """
                                #EXTM3U
                                #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
                                index-v1.m3u8
                            """.trimIndent().toByteArray()
                        )
                    }
                }
                "/mirror/v/hls2/index-v1.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:6
                        #EXTINF:6.0,
                        seg0.png
                        #EXT-X-ENDLIST
                    """.trimIndent().toByteArray()
                )
                "/mirror/v/hls2/seg0.png" -> {
                    if (req.headers.containsKey("range")) {
                        RespInfo(status = 400, contentType = "text/plain", body = "Range unsupported".toByteArray())
                    } else {
                        RespInfo(status = 200, contentType = "image/png", body = tsSeg)
                    }
                }
                else -> RespInfo(status = 404)
            }
        }.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val hls = HlsExtractor(http)
            val dash = DashExtractor(http)
            val portal = StreamingPortalResolver(http, hls, dash)

            val res = portal.resolveDirectEmbedUrls(
                pageUrl = "https://wecima.here/watch/ep9",
                embedUrls = listOf("${server.baseUrl}/embed-pre/abc"),
                baseHeaders = mapOf("User-Agent" to "TestUA")
            )
            assertTrue(
                "Expected post-redirect mirror HLS stream to resolve and verify cleanly",
                res.streams.isNotEmpty()
            )
            val stream = res.streams.first()
            assertEquals(720, stream.height)
            assertTrue(
                "Stream URL must resolve relative to post-redirect /mirror/v/ path: ${stream.url}",
                stream.url.contains("/mirror/v/hls2/index-v1.m3u8")
            )
            assertTrue(
                "Stream must retain Set-Cookie from embed redirect",
                stream.headers["Cookie"].orEmpty().contains("mirror_tok=ok123")
            )
        }
    }

    @Test
    fun hlsExtractor_strictCorsCdnRequiringOriginOnlyRefererAndOrigin_verifiesViaRelaxedCorsVariant() = runBlocking {
        val tsSeg = fakePngWrappedMpegTs(wrapperBytes = 256, tsPackets = 6)
        lateinit var serverRef: LoopbackServer
        serverRef = LoopbackServer { req ->
            val expectedRef = "${serverRef.baseUrl}/"
            val expectedOrigin = serverRef.baseUrl
            val hasStrictCorsHeaders =
                req.headers["referer"] == expectedRef && req.headers["origin"] == expectedOrigin

            when (req.path) {
                "/hls2/master.m3u8" -> {
                    if (!hasStrictCorsHeaders) {
                        RespInfo(status = 403, contentType = "text/plain", body = "CORS origin mismatch".toByteArray())
                    } else {
                        RespInfo(
                            status = 200,
                            contentType = "application/vnd.apple.mpegurl",
                            body = """
                                #EXTM3U
                                #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1920x1080
                                /hls2/index.m3u8
                            """.trimIndent().toByteArray()
                        )
                    }
                }
                "/hls2/index.m3u8" -> {
                    if (!hasStrictCorsHeaders) {
                        RespInfo(status = 403)
                    } else {
                        RespInfo(
                            status = 200,
                            contentType = "application/vnd.apple.mpegurl",
                            body = """
                                #EXTM3U
                                #EXT-X-TARGETDURATION:6
                                #EXTINF:6.0,
                                /hls2/seg0.txt
                                #EXT-X-ENDLIST
                            """.trimIndent().toByteArray()
                        )
                    }
                }
                "/hls2/seg0.txt" -> {
                    if (!hasStrictCorsHeaders) {
                        RespInfo(status = 403)
                    } else if (req.headers.containsKey("range")) {
                        RespInfo(status = 400, contentType = "text/plain", body = "No range".toByteArray())
                    } else {
                        // Disguised text/plain segment with a 256-byte fake PNG prefix before MPEG-TS sync
                        RespInfo(status = 200, contentType = "text/plain", body = tsSeg)
                    }
                }
                else -> RespInfo(status = 404)
            }
        }
        serverRef.use { server ->
            val http = ExtractorHttp(OkHttpClient())
            val hls = HlsExtractor(http)

            // Simulate BlobBridge-captured stream: full-path Referer and NO Origin header!
            val primaryHeaders = mapOf(
                "Referer" to "${server.baseUrl}/e/xyz987",
                "User-Agent" to "TestUA"
            )
            val result = hls.extractVerifiedDetailed("${server.baseUrl}/hls2/master.m3u8", primaryHeaders)
            assertNull("Expected zero failureReason, got: ${result.failureReason}", result.failureReason)
            assertNotNull("Expected HLS stream to verify via origin-only Referer+Origin variant", result.media)
            val stream = result.media!!.streams.first()
            assertEquals(1080, stream.height)
            assertEquals("${server.baseUrl}/", stream.headers["Referer"])
            assertEquals(server.baseUrl, stream.headers["Origin"])
        }
    }

    @Test
    fun disguisedSegmentPrefixValidation_accepts256BytePngWrapper_andRejectsAdversarialPayloads() {
        val wrappedTs = fakePngWrappedMpegTs(wrapperBytes = 256, tsPackets = 6)
        assertTrue(
            "256B PNG-wrapped MPEG-TS served as image/png must pass 4096B prefix validation",
            HlsSegmentDownloader.isValidSegmentPrefix(wrappedTs, wrappedTs.size, "image/png", isAesEncrypted = false)
        )
        assertTrue(
            "256B PNG-wrapped MPEG-TS served as text/plain must pass 4096B prefix validation",
            HlsSegmentDownloader.isValidSegmentPrefix(wrappedTs, wrappedTs.size, "text/plain", isAesEncrypted = false)
        )

        // Adversarial: tiny 43-byte 1x1 tracking GIF89a (starts with 'G'=0x47!) served as image/gif must be REJECTED
        val tinyGif = ByteArray(43) { 0x00 }.apply {
            val magic = "GIF89a".toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(magic, 0, this, 0, magic.size)
        }
        assertFalse(
            "Tiny 43-byte GIF89a tracking pixel must not false-positive on ASCII 'G' (0x47)",
            HlsSegmentDownloader.isValidSegmentPrefix(
                bytes = tinyGif,
                length = tinyGif.size,
                contentType = "image/gif",
                isAesEncrypted = false,
                totalContentLength = 43L
            )
        )
        val tinyPng = ByteArray(68) { 0x00 }.apply {
            val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            System.arraycopy(magic, 0, this, 0, magic.size)
        }
        assertFalse(
            "Tiny 68-byte PNG error/tracking placeholder without TS sync must be rejected",
            HlsSegmentDownloader.isValidSegmentPrefix(
                bytes = tinyPng,
                length = tinyPng.size,
                contentType = "image/png",
                isAesEncrypted = false,
                totalContentLength = 68L
            )
        )

        // Adversarial: HTML error page disguised as image/png must be REJECTED
        val htmlErr = "<!DOCTYPE html><html><body>Access Denied</body></html>".toByteArray()
        assertFalse(
            HlsSegmentDownloader.isValidSegmentPrefix(htmlErr, htmlErr.size, "image/png", isAesEncrypted = false)
        )

        // Adversarial: plain text error message served as text/plain must be REJECTED
        val textErr = "Expired token: please reload player".toByteArray()
        assertFalse(
            HlsSegmentDownloader.isValidSegmentPrefix(textErr, textErr.size, "text/plain", isAesEncrypted = false)
        )
    }

    @Test
    fun hlsExtractor_extractVerifiedDetailed_surfacesExactStageAndStatusDiagnostics() = runBlocking {
        LoopbackServer { req ->
            when (req.path) {
                "/master-403.m3u8" -> RespInfo(status = 403, contentType = "text/plain", body = "Forbidden".toByteArray())
                "/master-html.m3u8" -> RespInfo(status = 200, contentType = "text/html", body = "<html>denied</html>".toByteArray())
                "/master-var403.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n/var-403.m3u8\n".toByteArray()
                )
                "/var-403.m3u8" -> RespInfo(status = 403, contentType = "text/plain", body = "Forbidden".toByteArray())
                "/master-seg403.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n/var-seg403.m3u8\n".toByteArray()
                )
                "/var-seg403.m3u8" -> RespInfo(
                    status = 200,
                    contentType = "application/vnd.apple.mpegurl",
                    body = "#EXTM3U\n#EXTINF:5.0,\n/seg-403.ts\n#EXT-X-ENDLIST\n".toByteArray()
                )
                "/seg-403.ts" -> RespInfo(status = 403, contentType = "text/plain", body = "Denied".toByteArray())
                else -> RespInfo(status = 404)
            }
        }.use { server ->
            val hls = HlsExtractor(ExtractorHttp(OkHttpClient()))

            val rMaster403 = hls.extractVerifiedDetailed("${server.baseUrl}/master-403.m3u8", emptyMap())
            assertNull(rMaster403.media)
            assertEquals("master HTTP 403", rMaster403.failureReason)

            val rMasterHtml = hls.extractVerifiedDetailed("${server.baseUrl}/master-html.m3u8", emptyMap())
            assertNull(rMasterHtml.media)
            assertTrue(
                "Expected master HTML/non-HLS reason, got: ${rMasterHtml.failureReason}",
                rMasterHtml.failureReason?.contains("master", ignoreCase = true) == true
            )

            val rVar403 = hls.extractVerifiedDetailed("${server.baseUrl}/master-var403.m3u8", emptyMap())
            assertNull(rVar403.media)
            assertEquals("variant HTTP 403", rVar403.failureReason)

            val rSeg403 = hls.extractVerifiedDetailed("${server.baseUrl}/master-seg403.m3u8", emptyMap())
            assertNull(rSeg403.media)
            assertEquals("seg HTTP 403", rSeg403.failureReason)
        }
    }

    @Test
    fun weCimaHostProtection_andDirectDownloadLinkFilteringWorkCleanly() {
        assertTrue(AdBlocker.isProtectedEmbedHost("https://vinovo.si/e/abc123"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://vidaraa.com/e/abc123"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://fastved.net/e/abc123"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://dintezuvio.com/v/abc123"))
        assertTrue(AdBlocker.isProtectedEmbedHost("https://earnvids.pro/e/abc123"))

        assertTrue(
            "EMBED_IFRAME_SCRAPE_JS must collect WeCima direct download links",
            HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains(".List--Download--Wecima--Single a[href]")
        )

        val candidates = listOf(
            "https://cdn.directdl.example/files/episode9.mp4",
            "https://vinovo.si/e/ep9",
            "https://earnvids.pro/e/ep9"
        )
        val forWebViewPromotion = HeadlessWebExtractor.selectPromotableEmbedUrls(
            outerPageUrl = "https://wecima.here/watch/ep9",
            candidates = candidates,
            allowDirectMediaFiles = false
        )
        assertEquals(listOf("https://vinovo.si/e/ep9", "https://earnvids.pro/e/ep9"), forWebViewPromotion)

        val forHybridPortal = HeadlessWebExtractor.selectPromotableEmbedUrls(
            outerPageUrl = "https://wecima.here/watch/ep9",
            candidates = candidates,
            allowDirectMediaFiles = true
        )
        assertEquals(3, forHybridPortal.size)
        assertTrue(forHybridPortal.contains("https://cdn.directdl.example/files/episode9.mp4"))
    }
}
