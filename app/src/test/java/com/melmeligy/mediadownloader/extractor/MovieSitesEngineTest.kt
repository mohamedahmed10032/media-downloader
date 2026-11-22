package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MovieSitesEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- 1. Dean Edwards JS Unpacker & Streamtape / Obfuscation Tests ----

    @Test
    fun jsUnpacker_unpacksRadix36AndRadix62WithEscapedQuotes() {
        // Anonymized fixture modeled on OkStream PlayerJS packed script (radix 36)
        val packedPlayerJs = """
            <script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1({2:"3.4",5:"6://7.8/9/a.b",c:"6://d.e/f-g-h.i"});',19,19,'new|Playerjs|duration|5875|95|file|https|cdn-hls|example|hls2|master|m3u8|preroll|playertest|longtailvideo|vast|30s|ad|xml'.split('|')))
            </script>
        """.trimIndent()

        val unpacked = JsUnpacker.unpackAll(packedPlayerJs)
        assertEquals(1, unpacked.size)
        assertTrue(unpacked[0].contains("new Playerjs("))
        assertTrue(unpacked[0].contains("https://cdn-hls.example/hls2/master.m3u8"))

        // Player config extraction must extract main stream & duration while stripping preroll VAST URL
        val cfg = JsUnpacker.extractPlayerConfigs(unpacked[0])
        assertEquals(5876L, cfg.durationSec)
        assertEquals(1, cfg.sources.size)
        assertEquals("https://cdn-hls.example/hls2/master.m3u8", cfg.sources[0].url)

        // Radix 62 with uppercase token (`A` = 36) and escaped quotes
        val dict62 = (0..36).map { idx -> if (idx == 36) "https://media62.example/video.mp4" else "w$idx" }
            .joinToString("|")
        val packed62 = """
            eval(function(p,a,c,k,e,d){return p}('jwplayer(\'flv\').setup({file:"A",height:1080});',62,37,'$dict62'.split('|')))
        """.trimIndent()
        val unpacked62 = JsUnpacker.unpackAll(packed62)
        assertEquals(1, unpacked62.size)
        assertTrue(unpacked62[0].contains("jwplayer('flv').setup({file:\"https://media62.example/video.mp4\",height:1080});"))
    }

    @Test
    fun jsUnpacker_resolvesStreamtapeChainedSubstringAndAtob() {
        val streamtapeHtml = """
            <div id="norobotlink" style="display:none;">//streamtape.example/get_video?id=decoy&token=WRONG</div>
            <script>
              document.getElementById('norobotlink').innerHTML = '//streamtape.example/get_vi' + ('xcddeo?id=3r8yQ1oDvrCRWp&expires=1799999999&ip=1.2.3.4&token=REALTOKEN123').substring(2).substring(1);
            </script>
        """.trimIndent()

        val stUrls = JsUnpacker.extractStreamtapeUrls(streamtapeHtml)
        assertEquals(1, stUrls.size)
        assertEquals(
            "https://streamtape.example/get_video?id=3r8yQ1oDvrCRWp&expires=1799999999&ip=1.2.3.4&token=REALTOKEN123&stream=1",
            stUrls[0]
        )

        val b64 = java.util.Base64.getEncoder().encodeToString("https://cdn-b64.example/hls/stream.m3u8".toByteArray())
        val atobScript = """var u = atob("$b64");"""
        val decoded = JsUnpacker.decodeObfuscatedUrls(atobScript)
        assertEquals(listOf("https://cdn-b64.example/hls/stream.m3u8"), decoded)
    }

    // ---- 2. Full Anonymized TopCinema / WordPress Portal End-to-End Flow ----

    @Test
    fun fullMoviePortalFlow_unwrapsWatchPage_concurrentlyProbesAjaxServers_fallsThrough403_andPreservesEmbedRefererOrigin() = runTest {
        val movieUrl = "https://portal.example/%D9%81%D9%8A%D9%84%D9%85-runner-2026/"
        val watchUrl = "https://portal.example/%D9%81%D9%8A%D9%84%D9%85-runner-2026/watch/"
        val ajaxEndpoint = "https://portal.example/wp-content/themes/movies2023/Ajaxat/Single/Server.php"

        val movieInfoHtml = """
            <!doctype html>
            <html>
              <head>
                <title>Runner (2026) HD</title>
                <meta property="og:title" content="Runner (2026)" />
                <meta property="og:image" content="https://portal.example/posters/runner.jpg" />
                <script src="https://llvpn.com/tag.min.js"></script>
              </head>
              <body>
                <a class="watchBtn" href="$watchUrl">مشاهدة الفيلم</a>
              </body>
            </html>
        """.trimIndent()

        val watchPageHtml = """
            <!doctype html>
            <html>
              <head><title>Watch Runner (2026)</title></head>
              <body>
                <script>
                  var HomeURL = "https://portal.example";
                  var MyAjaxURL = "https://portal.example/wp-content/themes/movies2023/Ajaxat/";
                </script>
                <ul class="servers-list">
                  <li data-id="245244" data-server="0" class="server--item active"><span>VideoTube</span></li>
                  <li data-id="245244" data-server="1" class="server--item"><span>OkStream</span></li>
                  <li data-id="245244" data-server="2" class="server--item"><span>UpDown</span></li>
                </ul>
                <div class="player">
                  <iframe src="https://down.vidtube.example/embed-dead0.html"></iframe>
                </div>
              </body>
            </html>
        """.trimIndent()

        // Server 1 (OkStream): packed PlayerJS with cinema widescreen master.m3u8 + VAST preroll ad
        val okStreamUrl = "https://okstream.example/embed-ok1.html"
        val okStreamHtml = """
            <html><body><script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1({2:"3.4",5:"6://7.8/9/a.b",c:"6://d.e/f-g-h.i"});',19,19,'new|Playerjs|duration|5875|95|file|https|cdn-hls|example|hls2|master|m3u8|preroll|playertest|longtailvideo|vast|30s|ad|xml'.split('|')))
            </script></body></html>
        """.trimIndent()

        // Server 2 (UpDown): packed JWPlayer pointing to strict-Referer MP4 returning application/octet-stream
        val upDownUrl = "https://updown.example/embed-ud2-1280x640.html"
        val upDownHtml = """
            <html><body><script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0("1").2({3:"4://5.6/f/7.8",9:"a",b:c,d:e});',36,16,'jwplayer|flvplayer|setup|file|https|gamescdn|example|video|mp4|duration|5875|width|1280|height|640|dl'.split('|')))
            </script></body></html>
        """.trimIndent()

        val masterM3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x800,CODECS="avc1.640028,mp4a.40.2"
            index-1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1347738,RESOLUTION=1280x532,CODECS="avc1.640020,mp4a.40.2"
            index-720.m3u8
        """.trimIndent()

        val variantM3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:600
            #EXTINF:600.0,
            seg_0001.ts
            #EXTINF:600.0,
            seg_0002.ts
            #EXTINF:600.0,
            seg_0003.ts
        """.trimIndent()

        val observedMp4Headers = ConcurrentHashMap<String, String>()
        val observedHlsHeaders = ConcurrentHashMap<String, String>()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()

                fun htmlResp(body: String, code: Int = 200) = Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(code).message("OK")
                    .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()

                when {
                    url == movieUrl -> htmlResp(movieInfoHtml)
                    url == watchUrl -> htmlResp(watchPageHtml)
                    url == ajaxEndpoint -> {
                        val buf = okio.Buffer()
                        req.body?.writeTo(buf)
                        val form = buf.readUtf8()
                        assertEquals("XMLHttpRequest", req.header("X-Requested-With"))
                        when {
                            form.contains("i=1") -> htmlResp("""<iframe src="$okStreamUrl"></iframe>""")
                            form.contains("i=2") -> htmlResp("""<iframe src="$upDownUrl"></iframe>""")
                            else -> htmlResp("", 404)
                        }
                    }
                    url.contains("vidtube.example") -> htmlResp("Forbidden", 403)
                    url == okStreamUrl -> htmlResp(okStreamHtml)
                    url == upDownUrl -> htmlResp(upDownHtml)
                    url == "https://cdn-hls.example/hls2/master.m3u8" -> {
                        req.header("Referer")?.let { observedHlsHeaders["Referer"] = it }
                        req.header("Origin")?.let { observedHlsHeaders["Origin"] = it }
                        // Enforce embed Referer strictly
                        if (req.header("Referer") != okStreamUrl) {
                            htmlResp("Forbidden", 403)
                        } else {
                            Response.Builder()
                                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                                .body(masterM3u8.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                                .build()
                        }
                    }
                    url.startsWith("https://cdn-hls.example/hls2/index-") -> {
                        Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(variantM3u8.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                            .build()
                    }
                    url == "https://gamescdn.example/dl/video.mp4" -> {
                        req.header("Referer")?.let { observedMp4Headers["Referer"] = it }
                        req.header("Origin")?.let { observedMp4Headers["Origin"] = it }
                        // Strictly reject missing or outer-portal Referer (exact gamescdn.online behavior!)
                        if (req.header("Referer") != upDownUrl || req.header("Origin") != "https://updown.example") {
                            htmlResp("Forbidden", 403)
                        } else {
                            Response.Builder()
                                .request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                                .header("Content-Type", "application/octet-stream")
                                .header("Content-Range", "bytes 0-0/1927727867")
                                .header("Accept-Ranges", "bytes")
                                .body(ByteArray(1).toResponseBody("application/octet-stream".toMediaType()))
                                .build()
                        }
                    }
                    else -> htmlResp("Not found", 404)
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val headlessStub = mockk<HeadlessWebExtractor>(relaxed = true)
        val extractor = HtmlMediaExtractor(http, HlsExtractor(http), DashExtractor(http), headlessStub)

        val resolved = extractor.extractWithHeaders(movieUrl, allowHeadlessFallback = false)
        assertNotNull("Expected portal extraction to succeed despite Server 0 returning 403", resolved)
        assertEquals("Runner (2026)", resolved!!.title)
        assertEquals("https://portal.example/posters/runner.jpg", resolved.thumbnailUrl)

        // Should have 1080p (from OkStream HLS 1920x800) and 720p (from UpDown direct MP4 1280x640 preferred over HLS)
        val heights = resolved.streams.mapNotNull { it.height }.toSet()
        assertTrue("Expected 1080p stream, got $heights", 1080 in heights)
        assertTrue("Expected 720p stream, got $heights", 720 in heights)

        // Verify VAST preroll ad URL was NEVER included
        assertTrue(resolved.streams.none { it.url.contains("vast") || it.url.contains("longtailvideo") })

        // Verify exact per-hop embed Referer & Origin reached both CDNs and are persisted on returned streams
        assertEquals(okStreamUrl, observedHlsHeaders["Referer"])
        assertEquals("https://okstream.example", observedHlsHeaders["Origin"])
        assertEquals(upDownUrl, observedMp4Headers["Referer"])
        assertEquals("https://updown.example", observedMp4Headers["Origin"])

        val mp4Stream = resolved.streams.first { !it.isHls }
        assertEquals(upDownUrl, mp4Stream.headers["Referer"])
        assertEquals("https://updown.example", mp4Stream.headers["Origin"])
        assertEquals(1_927_727_867L, mp4Stream.sizeBytes)

        val hls1080 = resolved.streams.first { it.height == 1080 }
        assertEquals(okStreamUrl, hls1080.headers["Referer"])
        assertEquals("https://okstream.example", hls1080.headers["Origin"])
    }

    // ---- 3. Recursive 3-Hop Nested Iframe Unwrapping & Cycle Protection ----

    @Test
    fun recursiveEmbedResolver_unwrapsThreeHopsWithPerHopHeadersAndGuardsCycles() = runTest {
        val pageUrl = "https://portal.example/movie"
        val hop1 = "https://wrapper1.example/embed/a"
        val hop2 = "https://wrapper2.example/embed/b"
        val hop3 = "https://player3.example/embed/c"
        val finalMp4 = "https://cdn3.example/movie_1080p.mp4"

        val b64Url = java.util.Base64.getEncoder().encodeToString(finalMp4.toByteArray())

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                fun html(body: String) = Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/html".toMediaType())).build()

                when (url) {
                    pageUrl -> html("""<html><title>3-Hop Test</title><iframe src="$hop1"></iframe></html>""")
                    hop1 -> {
                        assertEquals(pageUrl, req.header("Referer"))
                        // Also include a cyclic iframe back to hop1 to verify cycle guard
                        html("""<html><iframe src="$hop1"></iframe><iframe src="$hop2"></iframe></html>""")
                    }
                    hop2 -> {
                        assertEquals(hop1, req.header("Referer"))
                        html("""<html><iframe src="$hop3"></iframe></html>""")
                    }
                    hop3 -> {
                        assertEquals(hop2, req.header("Referer"))
                        html("""<html><script>var media = atob("$b64Url");</script></html>""")
                    }
                    finalMp4 -> {
                        // CDN requires Hop 3 as Referer & Origin!
                        if (req.header("Referer") == hop3 && req.header("Origin") == "https://player3.example") {
                            Response.Builder()
                                .request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                                .header("Content-Type", "video/mp4")
                                .header("Content-Range", "bytes 0-0/850000000")
                                .body(ByteArray(1).toResponseBody("video/mp4".toMediaType()))
                                .build()
                        } else {
                            Response.Builder()
                                .request(req).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                                .body("Forbidden".toResponseBody("text/plain".toMediaType()))
                                .build()
                        }
                    }
                    else -> html("")
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val extractor = HtmlMediaExtractor(http, HlsExtractor(http), DashExtractor(http), mockk(relaxed = true))

        val resolved = extractor.extractWithHeaders(pageUrl, allowHeadlessFallback = false)
        assertNotNull(resolved)
        assertEquals(1, resolved!!.streams.size)
        assertEquals(finalMp4, resolved.streams[0].url)
        assertEquals(hop3, resolved.streams[0].headers["Referer"])
        assertEquals("https://player3.example", resolved.streams[0].headers["Origin"])
    }

    // ---- 4. Pre-Roll Ad / 15-Second Intro Bumper Rejection Guard ----

    @Test
    fun streamCandidateRanker_dropsShortPrerollBumperWhenMainFeatureExists_butKeepsShortSoloClips() {
        val introBumper = MediaStream(
            id = "bumper",
            url = "https://cdn.example/clip_intro_720p.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            label = "720p",
            height = 720,
            sizeBytes = 1_800_000L,
            headers = mapOf("X-Internal-Duration-Seconds" to "15")
        )
        val mainFeature = MediaStream(
            id = "feature",
            url = "https://cdn.example/movie_720p.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            label = "720p",
            height = 720,
            sizeBytes = 1_500_000_000L,
            headers = mapOf("X-Internal-Duration-Seconds" to "5875")
        )

        val filtered = StreamCandidateRanker.filterAndRankVideos(listOf(introBumper, mainFeature))
        assertEquals(1, filtered.size)
        assertEquals("feature", filtered[0].id)

        // When a short 12-second clip is the ONLY video (e.g., TikTok/Reels), it must be preserved!
        val shortReel = MediaStream(
            id = "reel",
            url = "https://cdn.example/reel_1080p.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            label = "1080p",
            height = 1080,
            sizeBytes = 2_400_000L,
            headers = mapOf("X-Internal-Duration-Seconds" to "12")
        )
        val soloResult = StreamCandidateRanker.filterAndRankVideos(listOf(shortReel))
        assertEquals(1, soloResult.size)
        assertEquals("reel", soloResult[0].id)
    }

    // ---- 5. HLS #EXT-X-DISCONTINUITY Ad-Block Sanitization ----

    @Test
    fun hlsPlaylistSanitizer_andDownloader_stripDiscontinuityAdBlocksAndNeverFetchAdSegments() = runTest {
        val pollutedPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-TARGETDURATION:10
            #EXTINF:5.0,
            https://ads.thirdparty.example/preroll/ad_0.ts
            #EXTINF:5.0,
            https://ads.thirdparty.example/preroll/ad_1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:30.0,
            https://cdn.movie.example/hls/seg_0.ts
            #EXTINF:30.0,
            https://cdn.movie.example/hls/seg_1.ts
            #EXTINF:30.0,
            https://cdn.movie.example/hls/seg_2.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:10.0,
            https://sponsor-cdn.example/midroll/promo_0.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:30.0,
            https://cdn.movie.example/hls/seg_3.ts
        """.trimIndent()

        val sanitized = HlsPlaylistSanitizer.parseAndSanitize(
            pollutedPlaylist,
            "https://cdn.movie.example/hls/index.m3u8"
        )
        assertEquals(2, sanitized.removedAdBlocks)
        assertEquals(3, sanitized.removedAdSegments)
        assertEquals(4, sanitized.segments.size)
        assertEquals(120.0, sanitized.totalDurationSec, 0.01)
        // Original mediaSequence preserved: ad_0(10), ad_1(11), seg_0(12), seg_1(13), seg_2(14), promo_0(15), seg_3(16)
        assertEquals(listOf(12L, 13L, 14L, 16L), sanitized.segments.map { it.mediaSequence })

        // Verify HlsSegmentDownloader only downloads clean movie segments and forwards exact headers
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)
                assertEquals("https://embed.example/p", req.header("Referer"))
                assertEquals("session=abc123", req.header("Cookie"))
                assertEquals("CustomUA/1.0", req.header("User-Agent"))
                if (url.endsWith(".m3u8")) {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(pollutedPlaylist.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                        .build()
                } else {
                    val segName = url.substringAfterLast('/')
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("[$segName]".toByteArray().toResponseBody("video/mp2t".toMediaType()))
                        .build()
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val downloader = HlsSegmentDownloader(client, http)
        val dest = File(tempFolder.root, "clean_movie.ts")
        val headers = mapOf(
            "Referer" to "https://embed.example/p",
            "Cookie" to "session=abc123",
            "User-Agent" to "CustomUA/1.0"
        )
        downloader.download("https://cdn.movie.example/hls/index.m3u8", headers, dest) {}

        assertEquals("[seg_0.ts][seg_1.ts][seg_2.ts][seg_3.ts]", dest.readText())
        assertTrue(requestedUrls.none { it.contains("ads.thirdparty.example") || it.contains("sponsor-cdn.example") })
    }

    // ---- 6. RFC 8216 Clear AES-128 Decryption + Header Forwarding + DRM Rejection ----

    @Test
    fun hlsSegmentDownloader_decryptsRfc8216Aes128WithForwardedHeaders_andRejectsSampleAesDrm() = runTest {
        val aesKeyBytes = ByteArray(16) { (it + 1).toByte() }
        val plain0 = "CLEAN_MAIN_SEG_ZERO_PAYLOAD_0001".toByteArray()
        val plain1 = "CLEAN_MAIN_SEG_ONE_PAYLOAD_00002".toByteArray()

        fun encryptAes128Cbc(plain: ByteArray, ivBytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(ivBytes))
            return cipher.doFinal(plain)
        }

        // Sequence 5 (implicit IV) after stripped ad segment at sequence 4!
        val ivSeq5 = ByteArray(16).also { ByteBuffer.wrap(it, 8, 8).putLong(5L) }
        val ivSeq6 = ByteArray(16).also { ByteBuffer.wrap(it, 8, 8).putLong(6L) }
        val enc0 = encryptAes128Cbc(plain0, ivSeq5)
        val enc1 = encryptAes128Cbc(plain1, ivSeq6)

        val aesPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA-SEQUENCE:4
            #EXT-X-TARGETDURATION:60
            #EXTINF:10.0,
            https://ads.example/preroll/ad.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-KEY:METHOD=AES-128,URI="keys/stream.key"
            #EXTINF:60.0,
            segs/s0.ts
            #EXTINF:60.0,
            segs/s1.ts
        """.trimIndent()

        val keyRequestHeaders = ConcurrentHashMap<String, String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when (url) {
                    "https://cdn.example/hls/media.m3u8" ->
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(aesPlaylist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
                    "https://cdn.example/hls/keys/stream.key" -> {
                        req.header("Referer")?.let { keyRequestHeaders["Referer"] = it }
                        req.header("Cookie")?.let { keyRequestHeaders["Cookie"] = it }
                        req.header("User-Agent")?.let { keyRequestHeaders["User-Agent"] = it }
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(aesKeyBytes.toResponseBody("application/octet-stream".toMediaType())).build()
                    }
                    "https://cdn.example/hls/segs/s0.ts" ->
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(enc0.toResponseBody("video/mp2t".toMediaType())).build()
                    "https://cdn.example/hls/segs/s1.ts" ->
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(enc1.toResponseBody("video/mp2t".toMediaType())).build()
                    else ->
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                            .body("".toResponseBody("text/plain".toMediaType())).build()
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val downloader = HlsSegmentDownloader(client, http)
        val dest = File(tempFolder.root, "decrypted.ts")
        val headers = mapOf(
            "Referer" to "https://player.example/embed-1",
            "Cookie" to "auth=xyz",
            "User-Agent" to "TestAgent/2.0"
        )

        downloader.download("https://cdn.example/hls/media.m3u8", headers, dest) {}
        assertEquals(String(plain0) + String(plain1), dest.readText())
        assertEquals("https://player.example/embed-1", keyRequestHeaders["Referer"])
        assertEquals("auth=xyz", keyRequestHeaders["Cookie"])
        assertEquals("TestAgent/2.0", keyRequestHeaders["User-Agent"])

        // Verify DRM (SAMPLE-AES) is strictly rejected
        val drmPlaylist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://fairplay-key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:10.0,
            https://cdn.example/drm/s0.ts
        """.trimIndent()
        val drmClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(drmPlaylist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
            }.build()
        val drmDownloader = HlsSegmentDownloader(drmClient, ExtractorHttp(drmClient))
        try {
            drmDownloader.download("https://cdn.example/drm/index.m3u8", emptyMap(), File(tempFolder.root, "drm.ts")) {}
            fail("Expected SAMPLE-AES DRM playlist to be rejected")
        } catch (e: MediaException) {
            assertTrue(e.message?.contains("encrypted", ignoreCase = true) == true)
        }
    }

    // ---- 7. Transient 429 / 503 Segment Retries Respecting maxRetries ----

    @Test
    fun hlsSegmentDownloader_retriesTransient429And503_andRespectsZeroRetries() = runTest {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            https://cdn.example/seg0.ts
        """.trimIndent()

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.endsWith(".m3u8")) {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
                } else {
                    val n = attempts.incrementAndGet()
                    when (n) {
                        1 -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                            .body("".toResponseBody("text/plain".toMediaType())).build()
                        2 -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                            .body("".toResponseBody("text/plain".toMediaType())).build()
                        else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("OK_SEG_BODY".toByteArray().toResponseBody("video/mp2t".toMediaType())).build()
                    }
                }
            }.build()

        val downloader = HlsSegmentDownloader(client, ExtractorHttp(client))

        // With maxRetries = 2 -> succeeds on 3rd attempt
        val okDest = File(tempFolder.root, "retry_ok.ts")
        downloader.download("https://cdn.example/p.m3u8", emptyMap(), okDest, 0L, maxRetries = 2) {}
        assertEquals("OK_SEG_BODY", okDest.readText())
        assertEquals(3, attempts.get())

        // With maxRetries = 0 -> fails immediately on first 429 without retrying
        attempts.set(0)
        val failDest = File(tempFolder.root, "retry_zero.ts")
        try {
            downloader.download("https://cdn.example/p.m3u8", emptyMap(), failDest, 0L, maxRetries = 0) {}
            fail("Expected failure when maxRetries=0 and server returns 429")
        } catch (_: MediaException) {
            assertEquals(1, attempts.get())
            assertFalse("Partial file should be deleted on failure", failDest.exists())
        }
    }

    // ---- 8. Poster-Only og:image Does Not Block Tier 2 Headless Fallback ----

    @Test
    fun htmlMediaExtractor_triggersTier2HeadlessFallbackInsteadOfReturningPosterOnlyOnJsPortal() = runTest {
        val url = "https://spa-portal.example/watch/movie-123"
        val spaHtml = """
            <html>
              <head>
                <title>SPA Movie</title>
                <meta property="og:image" content="https://spa-portal.example/poster.jpg" />
              </head>
              <body>
                <div id="app" data-server="1"></div>
                <iframe src="https://byse-spa.example/e/abc"></iframe>
              </body>
            </html>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val body = if (req.url.toString() == url) spaHtml else "<html><body><div id='root'></div></body></html>"
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/html".toMediaType())).build()
            }.build()

        val headlessMock = mockk<HeadlessWebExtractor>()
        val headlessResult = ResolvedMedia(
            sourceUrl = url,
            title = "SPA Movie",
            streams = listOf(
                MediaStream(
                    id = "hl-v-0",
                    url = "https://cdn.byse.example/hls/master.m3u8",
                    type = MediaType.VIDEO,
                    container = "ts",
                    label = "1080p FHD",
                    height = 1080,
                    isHls = true
                )
            ),
            extractor = "Smart Extractor"
        )
        coEvery { headlessMock.extract(url) } returns headlessResult

        val http = ExtractorHttp(client)
        val extractor = HtmlMediaExtractor(http, HlsExtractor(http), DashExtractor(http), headlessMock)

        val resolved = extractor.extractWithHeaders(url, allowHeadlessFallback = true)
        assertNotNull(resolved)
        assertEquals(MediaType.VIDEO, resolved!!.streams.first().type)
        assertEquals("https://cdn.byse.example/hls/master.m3u8", resolved.streams.first().url)
        coVerify(exactly = 1) { headlessMock.extract(url) }
    }
}
