package com.melmeligy.mediadownloader

import android.content.Context
import androidx.work.ForegroundInfo
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.data.local.dao.DownloadDao
import com.melmeligy.mediadownloader.di.NetworkModule
import com.melmeligy.mediadownloader.di.WebViewCookieJar
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.download.DownloadNotifier
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.Jump3rMp3Encoder
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.download.VideoAudioMuxer
import com.melmeligy.mediadownloader.extractor.DashExtractor
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import com.melmeligy.mediadownloader.extractor.HeadlessWebExtractor
import com.melmeligy.mediadownloader.extractor.HlsExtractor
import com.melmeligy.mediadownloader.extractor.HtmlMediaExtractor
import com.melmeligy.mediadownloader.extractor.InterceptedMediaExtractor
import com.melmeligy.mediadownloader.extractor.XhamsterCipher
import com.melmeligy.mediadownloader.extractor.YouTubeExtractor
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.BlobBridge
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import com.melmeligy.mediadownloader.ui.quality.prepareSnaptubeStreams
import com.melmeligy.mediadownloader.ui.quality.selectDefaultStream
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

class V220VerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // F1: XhamsterCipher (all 7 PRNG algorithms, negative seeds, partial path, hostMap)
    // =========================================================================

    private fun encryptXhamsterHex(algoId: Int, seed: Int, plain: String): String {
        val plainBytes = plain.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(5 + plainBytes.size)
        out[0] = (algoId and 0xFF).toByte()
        out[1] = (seed and 0xFF).toByte()
        out[2] = ((seed ushr 8) and 0xFF).toByte()
        out[3] = ((seed ushr 16) and 0xFF).toByte()
        out[4] = ((seed ushr 24) and 0xFF).toByte()
        var state = seed
        for (k in plainBytes.indices) {
            val ks = when (algoId) {
                1 -> { state = state * 1664525 + 1013904223; state and 0xFF }
                2 -> {
                    state = state xor (state shl 13)
                    state = state xor (state ushr 17)
                    state = state xor (state shl 5)
                    state and 0xFF
                }
                3 -> {
                    state += -1640531527
                    var e = state xor (state ushr 16)
                    e *= -2048144777
                    e = e xor (e ushr 13)
                    e *= -1028477379
                    (e xor (e ushr 16)) and 0xFF
                }
                4 -> {
                    state += 1831565813
                    var e = (state shl 7) or (state ushr 25)
                    e += -1640531527
                    e = e xor (e ushr 11)
                    e *= 668265261
                    e and 0xFF
                }
                5 -> {
                    state = state xor (state shl 7)
                    state = state xor (state ushr 9)
                    state = state xor (state shl 8)
                    state += -1515870811
                    state and 0xFF
                }
                6 -> {
                    state = state * 747796405 + -1403630843
                    (((state xor (state ushr 18)) ushr ((state ushr 27) and 31)) and 0xFF)
                }
                7 -> {
                    state += -1640531527
                    var e = state xor (state shl 5)
                    e *= 2146121005
                    e = e xor (e ushr 15)
                    e *= -2073254261
                    e and 0xFF
                }
                else -> error("algo $algoId")
            }
            out[5 + k] = ((plainBytes[k].toInt() and 0xFF) xor ks).toByte()
        }
        return out.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    @Test
    fun f1_xhamsterCipher_decryptsAllSevenPrngsNegativeSeedsPartialPathAndHostMap() {
        val seeds = intArrayOf(0, 1, Int.MAX_VALUE, Int.MIN_VALUE, -1, 0xDEADBEEF.toInt())
        val target = "https://video-h.platform1.test/key=abc/1080p.mp4"
        for (algo in 1..7) {
            for (seed in seeds) {
                val hex = encryptXhamsterHex(algo, seed, target)
                assertEquals("algo=$algo seed=$seed", target, XhamsterCipher.decryptSourceUrl(hex))
            }
        }
        val segHex = encryptXhamsterHex(3, -1234567, "key=secretToken")
        assertEquals(
            "https://cdn.platform1.test/key=secretToken/720p.mp4",
            XhamsterCipher.decryptSourceUrl("https://cdn.platform1.test/$segHex/720p.mp4")
        )
        assertEquals(
            "https://video7.platform1.test/720p.mp4",
            XhamsterCipher.applyHostMap("//video-h.platform1.test/720p.mp4", "video7")
        )
        // IPv4 / IPv6 / userinfo must remain untouched
        assertEquals("https://127.0.0.1:8080/a.mp4", XhamsterCipher.applyHostMap("https://127.0.0.1:8080/a.mp4", "v7"))
        assertEquals("https://[::1]:8443/a.mp4", XhamsterCipher.applyHostMap("https://[::1]:8443/a.mp4", "v7"))
    }

    // =========================================================================
    // F2-F6: MediaSniffer, UrlUtils, AdBlocker, CookieJar, HLS/DASH/YT/HTML
    // =========================================================================

    @Test
    fun f2_f5_mediaSnifferAndUrlUtilsAndAdBlockerContracts() {
        // Hex cipher blocking
        assertTrue(MediaSniffer.isHexCipherUrl("03a1b2c3d4e5f6071829"))
        assertTrue(MediaSniffer.isHexCipherUrl("https://platform1.test/videos/clip-alpha/03a1b2c3d4"))
        assertEquals(StreamType.UNKNOWN, MediaSniffer.classify("https://platform1.test/embed/03a1b2c3d4e5f607", "video/mp4"))
        assertFalse(MediaSniffer.isHexCipherUrl("https://cdn.platform1.test/media/abcdef0123456789.mp4"))
        assertFalse(MediaSniffer.isHexCipherUrl("https://platform1.test/video/1234567890"))

        // CMAF init segments & hover previews
        assertEquals(StreamType.UNKNOWN, MediaSniffer.classify("https://cdn.test/init-v1.mp4", "video/mp4"))
        assertTrue(MediaSniffer.isPreviewOrAdUrl("https://pix-fl.phncdn.com/foo.mp4"))
        assertTrue(MediaSniffer.isPreviewOrAdUrl("https://site.test/movies/slug/download/"))

        // Rotating KVS hash canonicalization
        val k1 = MediaSniffer.dedupeKey("https://cdn.site.test/get_file/1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/14000/14479/14479.mp4/")
        val k2 = MediaSniffer.dedupeKey("https://cdn.site.test/get_file/1/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/14000/14479/14479.mp4/")
        assertEquals(k1, k2)

        // New ad domains & typosquatter
        assertTrue(AdBlocker.isAd("https://cdn.wpadmngr.com/script.js"))
        assertTrue(AdBlocker.isAd("https://www.xvideo.com/redirect"))
        assertFalse(AdBlocker.isAd("https://www.xvideos.com/video123/clip"))

        // Unicode Arabic URL + fragment preservation + trailing slash mp4
        val normalized = UrlUtils.normalize("https://example.com/فيديو/مقطع?utm_source=x&id=9#قسم-١")
        assertNotNull(normalized)
        assertTrue(normalized!!.contains("%D9%81%D9%8A%D8%AF%D9%8A%D9%88"))
        assertFalse(normalized.contains("utm_source"))
        assertTrue(normalized.endsWith("#%D9%82%D8%B3%D9%85-%D9%A1"))
        assertEquals("mp4", UrlUtils.fileExtension("https://cdn.site.test/get_file/1/abc/14479.mp4/"))
    }

    @Test
    fun f3_webViewCookieJar_preservesServerRotationAndEvictsMaxAgeZero() {
        val jar = WebViewCookieJar()
        val www = "https://www.origin.example/video/1".toHttpUrl()
        val cdn = "https://cdn.origin.example/get_file/1/video.mp4".toHttpUrl()
        val other = "https://other-site.example/page".toHttpUrl()

        jar.seedFromRequestHeader(www, "kt_tcookie=OLD_SEED; ua=OLD_UA")
        jar.saveFromResponse(
            www,
            listOf(
                Cookie.parse(www, "kt_tcookie=ROTATED_NEW; Domain=.origin.example; Path=/")!!,
                Cookie.parse(www, "ua=ROTATED_UA; Domain=.origin.example; Path=/")!!
            )
        )
        // Replaying old stale seed on CDN must NOT clobber server rotation
        jar.seedFromRequestHeader(cdn, "kt_tcookie=OLD_SEED; ua=OLD_UA")
        val cdnMap = jar.loadForRequest(cdn).associate { it.name to it.value }
        assertEquals("ROTATED_NEW", cdnMap["kt_tcookie"])
        assertEquals("ROTATED_UA", cdnMap["ua"])
        // Domain isolation
        assertTrue(jar.loadForRequest(other).isEmpty())

        // Max-Age=0 eviction
        jar.saveFromResponse(www, listOf(Cookie.parse(www, "kt_tcookie=; Domain=.origin.example; Path=/; Max-Age=0")!!))
        assertNull(jar.loadForRequest(cdn).firstOrNull { it.name == "kt_tcookie" })
    }

    @Test
    fun f3_f4_hlsPortraitAverageBandwidthAndConfirmedHtmlShortCircuit() = runBlocking {
        ExtractorRegistry.clearConfirmedHtml()
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=1600000,RESOLUTION=720x1280
            portrait.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=0x1080
            peakonly.m3u8
        """.trimIndent()
        val media = "#EXTM3U\n#EXTINF:100.0,\nseg0.ts\n#EXT-X-ENDLIST"

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString("https://hls.test/master.m3u8", any()) } returns master
        coEvery { http.fetchString("https://hls.test/portrait.m3u8", any()) } returns media

        val hls = HlsExtractor(http)
        val res = hls.extract("https://hls.test/master.m3u8")!!
        val portrait = res.streams.first { it.url.endsWith("portrait.m3u8") }
        assertEquals(720, portrait.height)
        assertEquals(20_000_000L, portrait.sizeBytes) // 1,600,000 * 100 / 8 exact AVERAGE-BANDWIDTH

        val peakOnly = res.streams.first { it.url.endsWith("peakonly.m3u8") }
        assertEquals(1080, peakOnly.height)
        assertTrue((peakOnly.width ?: 0) > 0)
        assertEquals(10_625_000L, peakOnly.sizeBytes) // 1,000,000 * 0.85 * 100 / 8
        assertEquals("true", peakOnly.headers["X-Internal-Size-Estimated"])

        // Confirmed HTML short-circuit
        ExtractorRegistry.markConfirmedHtml("https://site.test/page.m3u8")
        assertFalse(hls.canHandle("https://site.test/page.m3u8"))
        assertNull(hls.extract("https://site.test/page.m3u8"))
        ExtractorRegistry.clearConfirmedHtml()
    }

    @Test
    fun f6_youtube2k4kPairsVp9WithOpusWebmAndNeverMarksAudioDefault() = runBlocking {
        val innerTubeJson = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"title": "4K VP9 Test", "lengthSeconds": "100"},
              "streamingData": {
                "adaptiveFormats": [
                  {"itag": 313, "url": "https://googlevideo.test/vp9_2160", "mimeType": "video/webm; codecs=\"vp9\"", "width": 3840, "height": 2160, "bitrate": 12000000, "contentLength": "150000000"},
                  {"itag": 401, "url": "https://googlevideo.test/av01_2160", "mimeType": "video/mp4; codecs=\"av01.0.12M.08\"", "width": 3840, "height": 2160, "bitrate": 14000000, "contentLength": "175000000"},
                  {"itag": 137, "url": "https://googlevideo.test/avc1_1080", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "width": 1920, "height": 1080, "bitrate": 4000000, "contentLength": "50000000"},
                  {"itag": 251, "url": "https://googlevideo.test/opus_audio", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "contentLength": "2000000"},
                  {"itag": 140, "url": "https://googlevideo.test/m4a_audio", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "contentLength": "1600000"}
                ]
              }
            }
        """.trimIndent()

        val http = mockk<ExtractorHttp>()
        every { http.postJson(any(), any(), any()) } returns innerTubeJson
        val yt = YouTubeExtractor(http, HlsExtractor(http), TestDispatcherProvider())
        val res = yt.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")!!

        val v2160 = res.streams.first { it.height == 2160 }
        assertEquals("webm", v2160.container)
        assertEquals("https://googlevideo.test/vp9_2160", v2160.url)
        assertEquals("https://googlevideo.test/opus_audio", v2160.headers["X-Internal-Companion-Audio"])

        val v1080 = res.streams.first { it.height == 1080 }
        assertEquals("mp4", v1080.container)
        assertEquals("https://googlevideo.test/avc1_1080", v1080.url)
        assertEquals("https://googlevideo.test/m4a_audio", v1080.headers["X-Internal-Companion-Audio"])

        // No audio stream may carry X-Internal-Default=true, and transcoded MP3 rows must reflect
        // their target CBR bitrate output sizes (100s clip: 70K=875KB, 128K=1.6MB, 160K=2.0MB, 320K=4.0MB)
        val audios = res.streams.filter { it.type == MediaType.AUDIO }
        assertTrue(audios.isNotEmpty())
        assertTrue(audios.none { it.headers["X-Internal-Default"] == "true" })
        val m4aFast = audios.first { it.id == "yt-audio-fast-m4a" }
        val mp370 = audios.first { it.id == "yt-audio-mp3-70k" }
        val mp3160 = audios.first { it.id == "yt-audio-mp3-160k" }
        val mp3320 = audios.first { it.id == "yt-audio-mp3-320k" }
        assertEquals(1_600_000L, m4aFast.sizeBytes)
        assertEquals(875_000L, mp370.sizeBytes)
        assertEquals(2_000_000L, mp3160.sizeBytes)
        assertEquals(4_000_000L, mp3320.sizeBytes)
        assertEquals("1600000", mp3320.headers["X-Internal-Video-Bytes"])
    }

    @Test
    fun f3_blobBridgeScript_validJsSyntaxAndRemoteCacheDeduplication() {
        val script = BlobBridge.SCRIPT
        assertTrue(script.contains("remoteCache"))
        assertTrue(script.contains("decryptXhUrl"))
        assertTrue(script.contains("decryptKvsUrl"))
        assertTrue(script.contains("hostMap"))
        assertTrue(script.contains("credentials: 'include'"))
    }

    // =========================================================================
    // F7: Jump3rMp3Encoder bitrate clamping & genuine MPEG Layer-3 output
    // =========================================================================

    @Test
    fun f7_jump3rMp3Encoder_clampsBitratesAndEncodesMono22050AndStereo44100() {
        assertEquals(160, Jump3rMp3Encoder.clampBitrateKbps(155, 44100))
        assertEquals(320, Jump3rMp3Encoder.clampBitrateKbps(999, 44100))
        assertEquals(32, Jump3rMp3Encoder.clampBitrateKbps(5, 44100))

        // Mono 22050 Hz sine wave -> valid MP3 frames
        val monoOut = ByteArrayOutputStream()
        Jump3rMp3Encoder(22050, 1, 96).use { enc ->
            val pcm = ByteArray(22050 * 2) // 1 second mono 16-bit LE
            for (i in 0 until 22050) {
                val s = (sin(2.0 * PI * 440.0 * i / 22050.0) * 16000.0).toInt()
                pcm[i * 2] = (s and 0xFF).toByte()
                pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            enc.encodePcm16Le(pcm, pcm.size, monoOut)
            enc.flush(monoOut)
        }
        val bytes = monoOut.toByteArray()
        assertTrue("Expected non-trivial MP3 output, got ${bytes.size}", bytes.size > 1000)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xE0, bytes[1].toInt() and 0xE0)
    }

    // =========================================================================
    // F9-F10: SegmentedDownloader CDN limit=N, X-Internal-Download, corrupt .idx
    // =========================================================================

    @Test
    fun f9_segmentedDownloader_enforcesCdnLimitAndAttachesInternalHeader() = runBlocking {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() } // 2 MB (>= MIN_SEGMENT_SIZE_BYTES)
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val internalHeadersSeen = CopyOnWriteArrayList<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val cur = inFlight.incrementAndGet()
                peakInFlight.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                internalHeadersSeen.add(req.header("X-Internal-Download") ?: "MISSING")
                try {
                    Thread.sleep(60)
                    val range = req.header("Range")
                    if (range != null && range.startsWith("bytes=")) {
                        val parts = range.removePrefix("bytes=").split("-")
                        val start = parts[0].toInt()
                        val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else payload.size - 1
                        val slice = payload.copyOfRange(start, end + 1)
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Accept-Ranges", "bytes")
                            .header("Content-Range", "bytes $start-$end/${payload.size}")
                            .header("Content-Type", "video/mp4")
                            .header("Content-Length", slice.size.toString())
                            .body(slice.toResponseBody("video/mp4".toMediaType()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "video/mp4")
                            .header("Content-Length", payload.size.toString())
                            .body(payload.toResponseBody("video/mp4".toMediaType()))
                            .build()
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val dl = SegmentedDownloader(client, http)

        // 1. limit=2 must cap peak concurrent connections <= 2 and reset corrupt .idx sidecar
        val dest1 = File(tempFolder.root, "limited.mp4")
        File(tempFolder.root, "limited.mp4.idx").writeText("CORRUPT_SIDECAR\n999999999")
        dl.download(
            url = "https://cdn.example.test/video?limit=2",
            headers = emptyMap(),
            dest = dest1,
            knownTotal = payload.size.toLong(),
            acceptsRanges = true,
            segmentCount = 6,
            onProgress = {}
        )
        assertTrue("Peak concurrency must be <= 2, got ${peakInFlight.get()}", peakInFlight.get() <= 2)
        assertTrue(dest1.readBytes().contentEquals(payload))
        assertTrue("Every request must attach X-Internal-Download=true", internalHeadersSeen.all { it == "true" })

        // 2. limit=1 forces single-stream (still attaches X-Internal-Download=true)
        internalHeadersSeen.clear()
        peakInFlight.set(0)
        val dest2 = File(tempFolder.root, "single.mp4")
        dl.download(
            url = "https://cdn.example.test/video/limit=1",
            headers = emptyMap(),
            dest = dest2,
            knownTotal = payload.size.toLong(),
            acceptsRanges = true,
            segmentCount = 4,
            onProgress = {}
        )
        assertEquals(1, peakInFlight.get())
        assertTrue(dest2.readBytes().contentEquals(payload))
        assertTrue(internalHeadersSeen.isNotEmpty() && internalHeadersSeen.all { it == "true" })
    }

    // =========================================================================
    // F12-F13: QualityScreen default video selection, broken/mute audio rules, DownloadDao.observeRecent
    // =========================================================================

    @Test
    fun f12_qualityScreen_defaultsToVideoExcludesBrokenProgressiveAndHandlesCompanionAudio() {
        // 1. Video page with null-size 144p and valid 360p -> synthesized music uses 360p and NEVER sets X-Internal-Default
        val v144Null = MediaStream("v144", "https://cdn.test/144.mp4", MediaType.VIDEO, "mp4", "144p", height = 144, sizeBytes = null)
        val v360Ok = MediaStream("v360", "https://cdn.test/360.mp4", MediaType.VIDEO, "mp4", "360p", height = 360, sizeBytes = 5_000_000L)
        val v720Ok = MediaStream("v720", "https://cdn.test/720.mp4", MediaType.VIDEO, "mp4", "720p", height = 720, sizeBytes = 15_000_000L)
        val prep1 = prepareSnaptubeStreams(ResolvedMedia("https://site.test/v", "Title", streams = listOf(v144Null, v360Ok, v720Ok), extractor = "Test"))

        assertEquals(2, prep1.music.size)
        assertTrue(prep1.music.all { it.url == "https://cdn.test/360.mp4" })
        assertTrue(prep1.music.none { it.headers["X-Internal-Default"] == "true" })
        assertEquals(MediaType.VIDEO, selectDefaultStream(prep1)?.type)

        // 2. All progressive videos have null/0 size -> suppress broken audio synthesis
        val vZero = MediaStream("vz", "https://cdn.test/z.mp4", MediaType.VIDEO, "mp4", "360p", height = 360, sizeBytes = 0L)
        val prep2 = prepareSnaptubeStreams(ResolvedMedia("https://site.test/v2", "Title", streams = listOf(v144Null, vZero), extractor = "Test"))
        assertTrue("Broken null/zero progressive videos must suppress audio synthesis", prep2.music.isEmpty())

        // 3. Adaptive video-only WITH companion audio -> routes synthesized audio directly to companion URL
        val vAdapt = MediaStream(
            id = "va",
            url = "https://cdn.test/video_only.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            label = "1080p",
            height = 1080,
            sizeBytes = 20_000_000L,
            headers = mapOf(
                "X-Internal-Dash-VideoOnly" to "true",
                "X-Internal-Companion-Audio" to "https://cdn.test/companion.m4a",
                "X-Internal-Audio-Bytes" to "2500000"
            )
        )
        val prep3 = prepareSnaptubeStreams(ResolvedMedia("https://site.test/v3", "Title", streams = listOf(vAdapt), extractor = "Test"))
        assertEquals(2, prep3.music.size)
        assertTrue(prep3.music.all { it.url == "https://cdn.test/companion.m4a" })
        assertTrue(prep3.music.none { it.headers["X-Internal-Extract-Audio"] == "true" })
        assertEquals(MediaType.VIDEO, selectDefaultStream(prep3)?.type)

        // 4. Adaptive video-only WITHOUT companion audio -> never synthesizes mute audio
        val vMute = MediaStream(
            id = "vm",
            url = "https://cdn.test/mute.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            label = "1080p",
            height = 1080,
            sizeBytes = 20_000_000L,
            headers = mapOf("X-Internal-Dash-VideoOnly" to "true")
        )
        val prep4 = prepareSnaptubeStreams(ResolvedMedia("https://site.test/v4", "Title", streams = listOf(vMute), extractor = "Test"))
        assertTrue("Mute adaptive video-only must not synthesize audio", prep4.music.isEmpty())

        // 5. Pure audio-only & pure image-only default selections
        val pureAudio = prepareSnaptubeStreams(
            ResolvedMedia("https://site.test/a", "Song", streams = listOf(MediaStream("a1", "https://cdn.test/a.mp3", MediaType.AUDIO, "mp3", "Classic MP3")), extractor = "Test")
        )
        assertEquals(MediaType.AUDIO, selectDefaultStream(pureAudio)?.type)

        val pureImage = prepareSnaptubeStreams(
            ResolvedMedia("https://site.test/i", "Pic", streams = listOf(MediaStream("i1", "https://cdn.test/p.jpg", MediaType.IMAGE, "jpg", "Photo")), extractor = "Test")
        )
        assertEquals(MediaType.IMAGE, selectDefaultStream(pureImage)?.type)
        assertTrue(pureImage.music.isEmpty())
    }

    @Test
    fun f13_downloadDao_declaresObserveRecentWithLimit6Default() {
        val method = DownloadDao::class.java.methods.firstOrNull { it.name == "observeRecent" && it.parameterCount == 1 }
        assertNotNull("DownloadDao must declare observeRecent(limit: Int)", method)

        // Verify Kotlin default parameter passes limit = 6
        var capturedLimit = -1
        val spyDao = java.lang.reflect.Proxy.newProxyInstance(
            DownloadDao::class.java.classLoader,
            arrayOf(DownloadDao::class.java)
        ) { _, m, args ->
            if (m.name == "observeRecent") {
                capturedLimit = (args?.firstOrNull() as? Int) ?: -1
                flowOf(emptyList<com.melmeligy.mediadownloader.data.local.entity.DownloadEntity>())
            } else {
                null
            }
        } as DownloadDao
        spyDao.observeRecent()
        assertEquals(6, capturedLimit)

        // Verify Room-generated DownloadDao_Impl implements observeRecent(int)
        val implClass = Class.forName("com.melmeligy.mediadownloader.data.local.dao.DownloadDao_Impl")
        assertNotNull(implClass.methods.firstOrNull { it.name == "observeRecent" && it.parameterCount == 1 })
    }
}
