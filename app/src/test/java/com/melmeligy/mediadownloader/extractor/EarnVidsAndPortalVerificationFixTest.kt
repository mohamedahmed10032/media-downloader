package com.melmeligy.mediadownloader.extractor

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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
import kotlinx.coroutines.runBlocking

/**
 * Regression & adversarial tests for:
 * 1. EarnVids/WeCima HLS CDNs that reject `Range: bytes=0-0` (HTTP 400/403/500) on disguised
 *    `image/jpeg` MPEG-TS segments while serving valid segments to plain unranged `GET`.
 * 2. Strict rejection of HTTP 200 HTML/JSON error pages during unranged `GET` prefix peek.
 * 3. Mid-segment 404 fallback to `seg[0]` during HLS verification.
 * 4. TopCinema unknown-height single-stream merge grace allowing multi-quality HLS sibling servers
 *    arriving around 1.0–1.4s to merge into `More formats`.
 * 5. HeadlessWebExtractor `data-link`/`data-player`/`data-src` scraping & 4-embed failover budget.
 */
class EarnVidsAndPortalVerificationFixTest {

    private data class ReqInfo(
        val method: String,
        val path: String,
        val headers: Map<String, String>
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
                                if (resp.delayMs > 0L) Thread.sleep(resp.delayMs)
                                val reason = when (resp.status) {
                                    200 -> "OK"
                                    206 -> "Partial Content"
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

    private fun validMpegTsBytes(): ByteArray {
        val bytes = ByteArray(188 * 4) { 0x11 }
        for (pkt in 0 until 4) {
            bytes[pkt * 188] = 0x47
        }
        return bytes
    }

    @Test
    fun hlsExtractor_earnvidsStyleRange400AndDisguisedImageSegment_verifiesViaUnrangedGetFallback() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x1080
            index-1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
            index-720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=750000,RESOLUTION=854x480
            index-480.m3u8
        """.trimIndent().toByteArray()

        val variant = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg-0.jpg
            #EXTINF:6.0,
            seg-1.jpg
            #EXTINF:6.0,
            seg-2.jpg
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val tsBytes = validMpegTsBytes()

        LoopbackServer { req ->
            when (req.path) {
                "/master.txt" -> RespInfo(200, "text/plain", master)
                "/index-1080.m3u8", "/index-720.m3u8", "/index-480.m3u8" -> RespInfo(200, "application/vnd.apple.mpegurl", variant)
                "/seg-0.jpg", "/seg-1.jpg", "/seg-2.jpg" -> {
                    // Simulate EarnVids CDN rejecting Range: bytes=0-0 with HTTP 400 while serving valid TS bytes on plain GET
                    if (req.headers.containsKey("range")) {
                        RespInfo(400, "text/plain", "Range not supported".toByteArray())
                    } else {
                        RespInfo(200, "image/jpeg", tsBytes)
                    }
                }
                else -> RespInfo(404)
            }
        }.use { server ->
            val http = ExtractorHttp(OkHttpClient.Builder().build())
            val hls = HlsExtractor(http)

            val resolved = hls.extractVerified(
                url = "${server.baseUrl}/master.txt",
                headers = mapOf("Referer" to "https://earnvids.example/e/abc")
            )

            assertNotNull("EarnVids HLS stream with Range-400 + disguised image/jpeg TS segments must verify cleanly", resolved)
            val heights = resolved!!.streams.mapNotNull { it.height }.toSet()
            assertTrue("Expected 1080p, 720p, 480p variants, got $heights", heights.containsAll(setOf(1080, 720, 480)))
        }
    }

    @Test
    fun hlsExtractor_unrangedGetReturningHtmlErrorPage200_isStrictlyRejected() = runBlocking {
        val variant = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg-0.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val htmlBlock = "<!DOCTYPE html><html><body>Cloudflare Access Denied</body></html>".toByteArray()

        LoopbackServer { req ->
            when (req.path) {
                "/playlist.m3u8" -> RespInfo(200, "application/vnd.apple.mpegurl", variant)
                "/seg-0.ts" -> {
                    if (req.headers.containsKey("range")) {
                        RespInfo(400, "text/plain", "Bad Request".toByteArray())
                    } else {
                        // Adversarial: HTTP 200 masquerading as video/mp2t but body is HTML!
                        RespInfo(200, "video/mp2t", htmlBlock)
                    }
                }
                else -> RespInfo(404)
            }
        }.use { server ->
            val http = ExtractorHttp(OkHttpClient.Builder().build())
            val hls = HlsExtractor(http)

            val resolved = hls.extractVerified("${server.baseUrl}/playlist.m3u8", emptyMap())
            assertNull("HTML error page served as HTTP 200 segment must be strictly rejected", resolved)
        }
    }

    @Test
    fun hlsExtractor_midSegment404_fallsBackToFirstSegmentCleanly() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            index-720.m3u8
        """.trimIndent().toByteArray()
        val variant = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg-0.ts
            #EXTINF:6.0,
            seg-1.ts
            #EXTINF:6.0,
            seg-2.ts
            #EXTINF:6.0,
            seg-3.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        val tsBytes = validMpegTsBytes()

        LoopbackServer { req ->
            when (req.path) {
                "/master.m3u8" -> RespInfo(200, "application/vnd.apple.mpegurl", master)
                "/index-720.m3u8" -> RespInfo(200, "application/vnd.apple.mpegurl", variant)
                "/seg-2.ts" -> RespInfo(404, "text/plain", "Not ready".toByteArray())
                "/seg-0.ts" -> RespInfo(200, "video/mp2t", tsBytes)
                else -> RespInfo(404)
            }
        }.use { server ->
            val http = ExtractorHttp(OkHttpClient.Builder().build())
            val hls = HlsExtractor(http)

            val resolved = hls.extractVerified("${server.baseUrl}/master.m3u8", emptyMap())
            assertNotNull("Should fall back to seg-0 when middle segment returns 404", resolved)
            assertEquals(listOf(720), resolved!!.streams.mapNotNull { it.height })
        }
    }

    @Test
    fun streamingPortalResolver_unknownHeightSingleStream_mergesMultiQualityHlsSiblingWithinExtendedGrace() = runBlocking {
        val tsBytes = validMpegTsBytes()
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            v1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            v720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=854x480
            v480.m3u8
        """.trimIndent().toByteArray()
        val variant = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        var baseUrl = ""
        LoopbackServer { req ->
            when (req.path) {
                "/watch/episode-1/" -> {
                    val html = """
                        <html><head><title>Rick and Morty S01E01</title></head><body>
                          <iframe src="$baseUrl/embed-fast-mp4"></iframe>
                          <button data-embed="$baseUrl/embed-multi-hls">LuluStream</button>
                        </body></html>
                    """.trimIndent().toByteArray()
                    RespInfo(200, "text/html", html)
                }
                "/embed-fast-mp4" -> {
                    val html = """<html><body><video src="$baseUrl/direct/video.mp4"></video></body></html>""".toByteArray()
                    RespInfo(200, "text/html", html, delayMs = 20L)
                }
                "/direct/video.mp4" -> {
                    RespInfo(
                        status = 206,
                        contentType = "video/mp4",
                        body = byteArrayOf(0x00),
                        extraHeaders = mapOf("Content-Range" to "bytes 0-0/439772774", "Accept-Ranges" to "bytes")
                    )
                }
                "/embed-multi-hls" -> {
                    // Arrives at 1000ms (past legacy 850ms grace, inside new 1450ms unknown-height grace)
                    val html = """<html><body><script>jwplayer("p").setup({file:"$baseUrl/hls/master.m3u8"});</script></body></html>""".toByteArray()
                    RespInfo(200, "text/html", html, delayMs = 1_000L)
                }
                "/hls/master.m3u8" -> RespInfo(200, "application/vnd.apple.mpegurl", master)
                "/hls/v1080.m3u8", "/hls/v720.m3u8", "/hls/v480.m3u8" -> RespInfo(200, "application/vnd.apple.mpegurl", variant)
                "/hls/seg0.ts" -> RespInfo(200, "video/mp2t", tsBytes)
                else -> RespInfo(404)
            }
        }.use { server ->
            baseUrl = server.baseUrl
            val http = ExtractorHttp(OkHttpClient.Builder().build())
            val hls = HlsExtractor(http)
            val dash = DashExtractor(http)
            val resolver = StreamingPortalResolver(http, hls, dash)

            val pageUrl = "${server.baseUrl}/watch/episode-1/"
            val html = http.fetchString(pageUrl)
            val doc = org.jsoup.Jsoup.parse(html, pageUrl)
            val result = resolver.resolvePortalAndEmbeds(pageUrl, html, doc, mapOf("Referer" to pageUrl))

            val heights = result.streams.mapNotNull { it.height }.toSet()
            assertTrue(
                "Expected multi-quality sibling HLS (1080p/720p/480p) to merge when first server only had unknown-height MP4, got heights=$heights labels=${result.streams.map { it.label }}",
                heights.containsAll(setOf(1080, 720, 480))
            )
        }
    }

    @Test
    fun headlessWebExtractor_embedScrapeIncludesDataLinkAndDataSrc_andFailoverBudgetIsFour() {
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("[data-link]"))
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("[data-player]"))
        assertTrue(HeadlessWebExtractor.EMBED_IFRAME_SCRAPE_JS.contains("[data-src]"))
        assertEquals(4, HeadlessWebExtractor.MAX_PROMOTED_EMBEDS)
    }
}
