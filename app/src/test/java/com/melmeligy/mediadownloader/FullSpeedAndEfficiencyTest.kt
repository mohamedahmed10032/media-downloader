package com.melmeligy.mediadownloader

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.melmeligy.mediadownloader.core.DefaultDispatcherProvider
import com.melmeligy.mediadownloader.core.util.JsonUtils
import com.melmeligy.mediadownloader.di.WebViewCookieJar
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.download.DownloadNotifier
import com.melmeligy.mediadownloader.download.DownloadThumbnailCache
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.Jump3rMp3Encoder
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.download.VideoAudioMuxer
import com.melmeligy.mediadownloader.extractor.DirectMediaExtractor
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import com.melmeligy.mediadownloader.extractor.HeadInfo
import com.melmeligy.mediadownloader.extractor.HeadlessWebExtractor
import com.melmeligy.mediadownloader.extractor.ImageExtractor
import com.melmeligy.mediadownloader.extractor.SocialMediaExtractor
import com.melmeligy.mediadownloader.extractor.SocialMediaGalleryParsers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.measureTimeMillis

/**
 * Targeted regression, adversarial, and end-to-end latency/efficiency tests covering:
 * 1. Pinterest single-image parity (no fallback API stall, no false-positive WebView on null carousel_data,
 *    no blocking probe on verbatim /originals/ URLs, while preserving carousel upgrade & 404 fallback).
 * 2. Direct image / media fast bounded probe without HTML/WebView fallthrough on timeout.
 * 3. Facebook photo URLs skipping plugins/video.php.
 * 4. Parallel adaptive video + companion audio downloads & zero-duplicate image thumbnail caching.
 * 5. Domain-family-indexed WebViewCookieJar + short-TTL CookieManager read cache.
 * 6. HLS higher concurrency (8) respecting CDN limit=N + buffered ordered assembly.
 * 7. Jump3rMp3Encoder fast profile verified via independent ffprobe consumer oracle.
 */
class FullSpeedAndEfficiencyTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    private data class LocalResponse(
        val status: Int = 200,
        val contentType: String = "application/octet-stream",
        val body: ByteArray = ByteArray(0),
        val delayMs: Long = 0L
    )

    private class LocalHttpServer(
        private val handler: (method: String, path: String) -> LocalResponse
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
                                val rawTarget = parts.getOrNull(1).orEmpty()
                                val path = rawTarget.substringBefore('?')
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                val resp = handler(method, path)
                                if (resp.delayMs > 0L) {
                                    Thread.sleep(resp.delayMs)
                                }
                                val reason = when (resp.status) {
                                    200 -> "OK"
                                    206 -> "Partial Content"
                                    404 -> "Not Found"
                                    else -> "Status"
                                }
                                val headerBytes = buildString {
                                    append("HTTP/1.1 ${resp.status} $reason\r\n")
                                    append("Content-Type: ${resp.contentType}\r\n")
                                    append("Content-Length: ${resp.body.size}\r\n")
                                    append("Connection: close\r\n\r\n")
                                }.toByteArray(Charsets.ISO_8859_1)
                                val out = s.getOutputStream()
                                out.write(headerBytes)
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

    // -------------------------------------------------------------------------
    // 1. Pinterest Photo vs. Video Parity & Adversarial Guards
    // -------------------------------------------------------------------------

    @Test
    fun pinterest_singleImagePinWithNullCarouselData_resolvesFastWithoutFallbackApisOrWebViewOrProbes() = runBlocking {
        val pinId = "998877665544332211"
        val origUrl = "https://i.pinimg.com/originals/ab/cd/ef/abcdef0123456789abcdef0123456789.jpg"
        val thumbUrl = "https://i.pinimg.com/474x/ab/cd/ef/abcdef0123456789abcdef0123456789.jpg"

        val structuredHtml = """
            <html><head><title>Sunset Poster | Pinterest</title></head><body>
            <script data-relay-response="true" type="application/json">
            {
              "response": {
                "data": {
                  "v3GetPinQuery": {
                    "data": {
                      "entityId": "$pinId",
                      "title": "Sunset Poster",
                      "carousel_data": null,
                      "story_pin_data": null,
                      "images": {
                        "orig": { "url": "$origUrl", "width": 1200, "height": 1800 },
                        "474x": { "url": "$thumbUrl", "width": 474, "height": 711 }
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        assertFalse(
            "Null carousel_data / story_pin_data must NEVER trigger multi-slide hint",
            SocialMediaGalleryParsers.pinterestHtmlHintsMultiSlide(structuredHtml)
        )

        val secondaryApiCalls = AtomicInteger(0)
        val probeCalls = AtomicInteger(0)
        val headlessCalls = AtomicInteger(0)

        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>()

        coEvery { mockHttp.fetchString("https://www.pinterest.com/pin/$pinId/", any()) } coAnswers {
            delay(25)
            structuredHtml
        }
        coEvery { mockHttp.fetchString(match { it.contains("unauth_react_main_pin") }, any()) } coAnswers {
            delay(1_500)
            ""
        }
        coEvery {
            mockHttp.fetchString(
                match { it.contains("detailed") || it.contains("auth_web_main_pin") || it.contains("/v3/pins/") },
                any()
            )
        } coAnswers {
            secondaryApiCalls.incrementAndGet()
            delay(2_000)
            ""
        }
        every { mockHttp.probe(any(), any(), any()) } answers {
            probeCalls.incrementAndGet()
            error("Verbatim /originals/ URL should not be probed before sheet render")
        }
        coEvery { mockHeadless.extractPinterestPinPayload(any()) } coAnswers {
            headlessCalls.incrementAndGet()
            error("Headless WebView must not run for confirmed single-image pin")
        }

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless)
        var resolved: com.melmeligy.mediadownloader.domain.model.ResolvedMedia? = null
        val elapsedMs = measureTimeMillis {
            resolved = extractor.extract("https://www.pinterest.com/pin/$pinId/")
        }

        assertNotNull(resolved)
        assertEquals("Pinterest", resolved!!.extractor)
        assertEquals(1, resolved!!.gallerySlides.size)
        assertEquals(origUrl, resolved!!.gallerySlides[0].mediaUrl)
        assertEquals(0, secondaryApiCalls.get())
        assertEquals(0, probeCalls.get())
        assertEquals(0, headlessCalls.get())
        assertTrue("Expected fast single-image Pinterest resolution < 500ms, took ${elapsedMs}ms", elapsedMs < 500L)
    }

    @Test
    fun pinterest_primaryPinResourceWinsRaceBeforeSlowHtml() = runBlocking {
        val pinId = "777666555444333222"
        val origUrl = "https://i.pinimg.com/originals/11/22/33/11223344556677889900aabbccddeeff.jpg"
        val apiJson = """
            {
              "resource_response": {
                "data": {
                  "id": "$pinId",
                  "title": "Fast API Pin",
                  "carousel_data": null,
                  "story_pin_data": null,
                  "images": {
                    "orig": { "url": "$origUrl", "width": 1000, "height": 1500 }
                  }
                }
              }
            }
        """.trimIndent()

        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)

        coEvery { mockHttp.fetchString("https://www.pinterest.com/pin/$pinId/", any()) } coAnswers {
            delay(2_200)
            "<html></html>"
        }
        coEvery { mockHttp.fetchString(match { it.contains("unauth_react_main_pin") }, any()) } coAnswers {
            delay(20)
            apiJson
        }

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless)
        var resolved: com.melmeligy.mediadownloader.domain.model.ResolvedMedia? = null
        val elapsedMs = measureTimeMillis {
            resolved = extractor.extract("https://www.pinterest.com/pin/$pinId/")
        }

        assertNotNull(resolved)
        assertEquals("Fast API Pin", resolved!!.title)
        assertEquals(1, resolved!!.gallerySlides.size)
        assertEquals(origUrl, resolved!!.gallerySlides[0].mediaUrl)
        assertTrue("Primary PinResource should beat slow HTML (< 400ms), took ${elapsedMs}ms", elapsedMs < 400L)
    }

    @Test
    fun pinterest_synthesizedOriginal404_stillFallsBackToPng() = runBlocking {
        val pinId = "123123123123123123"
        val sig = "abcdefabcdefabcdefabcdefabcdefab"
        val url736 = "https://i.pinimg.com/736x/ab/cd/ef/$sig.jpg"
        val synthesizedJpg = "https://i.pinimg.com/originals/ab/cd/ef/$sig.jpg"
        val realPng = "https://i.pinimg.com/originals/ab/cd/ef/$sig.png"

        val apiJson = """
            {
              "resource_response": {
                "data": {
                  "id": "$pinId",
                  "title": "Synthesized PNG Pin",
                  "images": {
                    "736x": { "url": "$url736" }
                  }
                }
              }
            }
        """.trimIndent()

        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        coEvery { mockHttp.fetchString("https://www.pinterest.com/pin/$pinId/", any()) } returns ""
        coEvery { mockHttp.fetchString(match { it.contains("unauth_react_main_pin") }, any()) } returns apiJson
        every { mockHttp.probe(synthesizedJpg, any(), any()) } returns HeadInfo(
            code = 404,
            contentType = "text/plain",
            contentLength = 0L,
            acceptsRanges = false,
            finalUrl = synthesizedJpg
        )
        every { mockHttp.probe(realPng, any(), any()) } returns HeadInfo(
            code = 200,
            contentType = "image/png",
            contentLength = 250_000L,
            acceptsRanges = true,
            finalUrl = realPng
        )

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless)
        val resolved = extractor.extract("https://www.pinterest.com/pin/$pinId/")

        assertNotNull(resolved)
        assertEquals(1, resolved!!.gallerySlides.size)
        assertEquals(realPng, resolved.gallerySlides[0].mediaUrl)
        assertEquals("png", resolved.gallerySlides[0].container)
    }

    // -------------------------------------------------------------------------
    // 2. Direct Image / Direct Media Fast Path + Adversarial Guards
    // -------------------------------------------------------------------------

    @Test
    fun imageExtractor_timeoutOnKnownJpgExtension_returnsDirectImageWithinBudget_andRejectsDisguisedHtml() = runBlocking {
        LocalHttpServer { _, path ->
            when (path) {
                "/slow_photo.jpg" -> LocalResponse(
                    status = 200,
                    contentType = "image/jpeg",
                    body = "jpeg-bytes".toByteArray(),
                    delayMs = 2_500L
                )
                "/fake_html.jpg" -> LocalResponse(
                    status = 200,
                    contentType = "text/html; charset=utf-8",
                    body = "<html><body>Not an image</body></html>".toByteArray()
                )
                "/dead.mp4" -> LocalResponse(status = 404, contentType = "text/plain", body = "Not found".toByteArray())
                else -> LocalResponse(status = 404)
            }
        }.use { server ->
            val http = ExtractorHttp(OkHttpClient.Builder().build())
            val imageExtractor = ImageExtractor(http)
            val directExtractor = DirectMediaExtractor(http)

            var resolved: com.melmeligy.mediadownloader.domain.model.ResolvedMedia? = null
            val elapsedMs = measureTimeMillis {
                resolved = imageExtractor.extract("${server.baseUrl}/slow_photo.jpg")
            }
            assertNotNull("Known .jpg should return directly on probe timeout instead of falling through to WebView", resolved)
            assertEquals(1, resolved!!.gallerySlides.size)
            assertTrue("Fast probe timeout must stay bounded (< 1.8s), took ${elapsedMs}ms", elapsedMs < 1_800L)

            val fakeHtml = imageExtractor.extract("${server.baseUrl}/fake_html.jpg")
            assertNull("Disguised HTML at .jpg URL must still be rejected", fakeHtml)

            val deadMp4 = directExtractor.extract("${server.baseUrl}/dead.mp4")
            assertNull("HTTP 404 at .mp4 URL must still be rejected", deadMp4)
        }
    }

    // -------------------------------------------------------------------------
    // 3. Facebook Photo Skips Video Plugin Endpoint
    // -------------------------------------------------------------------------

    @Test
    fun facebook_explicitPhotoUrl_skipsVideoPluginEndpoint() = runBlocking {
        val pluginCalls = AtomicInteger(0)
        val photoHtml = """
            <html><head>
              <meta property="og:title" content="Beach Photo" />
              <meta property="og:image" content="https://scontent.xx.fbcdn.net/v/t39.30808-6/123456_n.jpg" />
            </head><body></body></html>
        """.trimIndent()

        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        coEvery { mockHttp.fetchString(match { it.contains("plugins/video.php") }, any()) } coAnswers {
            pluginCalls.incrementAndGet()
            ""
        }
        coEvery { mockHttp.fetchString(match { it.contains("facebook.com/photo/") }, any()) } returns photoHtml

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless)
        extractor.cookieProvider = { null }
        val resolved = extractor.extract("https://www.facebook.com/photo/?fbid=123456789")

        assertNotNull(resolved)
        assertEquals(0, pluginCalls.get())
        assertEquals(1, resolved!!.gallerySlides.size)
    }

    // -------------------------------------------------------------------------
    // 4. Parallel Adaptive Video+Audio Download & Zero-Duplicate Image Thumbnail
    // -------------------------------------------------------------------------

    @Test
    fun downloadManager_adaptiveVideoAndAudio_downloadConcurrentlyInParallel() = runBlocking {
        val tempRoot = Files.createTempDirectory("dm-parallel-adaptive").toFile()
        val videoPayload = ByteArray(16_384) { (it % 251).toByte() }
        val audioPayload = ByteArray(8_192) { ((it * 3) % 251).toByte() }

        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)

        LocalHttpServer { _, path ->
            when (path) {
                "/video.mp4" -> {
                    val cur = inFlight.incrementAndGet()
                    peakInFlight.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                    try {
                        Thread.sleep(450)
                        LocalResponse(200, "video/mp4", videoPayload)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
                "/audio.m4a" -> {
                    val cur = inFlight.incrementAndGet()
                    peakInFlight.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                    try {
                        Thread.sleep(450)
                        LocalResponse(200, "audio/mp4", audioPayload)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
                else -> LocalResponse(404)
            }
        }.use { server ->
            try {
                val client = OkHttpClient.Builder().build()
                val http = ExtractorHttp(client)
                val segmented = SegmentedDownloader(client, http)
                val hls = HlsSegmentDownloader(client, http)

                mockkStatic(WorkManager::class)
                every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)

                val context = mockk<Context>(relaxed = true)
                every { context.getExternalFilesDir(any()) } returns tempRoot
                every { context.filesDir } returns tempRoot
                every { context.cacheDir } returns tempRoot

                val item = DownloadItem(
                    id = 101L,
                    title = "Parallel Adaptive Video",
                    sourceUrl = "https://www.youtube.com/watch?v=abcdefghijk",
                    remoteUrl = "${server.baseUrl}/video.mp4",
                    type = MediaType.VIDEO,
                    container = "mp4",
                    qualityLabel = "1080p",
                    status = DownloadStatus.RUNNING,
                    totalBytes = (videoPayload.size + audioPayload.size).toLong(),
                    headersJson = JsonUtils.mapToJson(
                        mapOf(
                            "X-Internal-Companion-Audio" to "${server.baseUrl}/audio.m4a",
                            "X-Internal-Video-Bytes" to videoPayload.size.toString(),
                            "X-Internal-Audio-Bytes" to audioPayload.size.toString(),
                            "X-Internal-Accepts-Ranges" to "true",
                            "X-Internal-Companion-Accepts-Ranges" to "true"
                        )
                    ),
                    isHls = false
                )

                var claimed = false
                var completedUri: String? = null
                val progressHistory = mutableListOf<Long>()
                val repo = mockk<DownloadRepository>(relaxed = true)
                coEvery { repo.claimNextQueued(any()) } answers {
                    if (!claimed) {
                        claimed = true
                        listOf(item)
                    } else emptyList()
                }
                coEvery { repo.getById(101L) } returns item
                coEvery { repo.updateProgress(101L, any(), any(), any(), any()) } answers {
                    synchronized(progressHistory) { progressHistory += secondArg<Long>() }
                }
                coEvery { repo.setCompleted(101L, any(), any()) } answers {
                    completedUri = secondArg()
                }

                val settingsRepo = mockk<SettingsRepository>(relaxed = true)
                every { settingsRepo.settings } returns MutableStateFlow(AppSettings())
                coEvery { settingsRepo.current() } returns AppSettings()

                val muxer = mockk<VideoAudioMuxer>()
                every { muxer.mux(any(), any(), any(), any()) } answers {
                    val vFile = firstArg<File>()
                    val aFile = secondArg<File>()
                    val outFile = thirdArg<File>()
                    assertEquals(videoPayload.size.toLong(), vFile.length())
                    assertEquals(audioPayload.size.toLong(), aFile.length())
                    outFile.writeBytes(videoPayload + audioPayload)
                    true
                }

                val writer = mockk<MediaStoreWriter>()
                coEvery { writer.publish(any(), any(), any(), any(), any()) } returns "content://media/external/video/101"

                val dm = DownloadManager(
                    context = context,
                    repository = repo,
                    settingsRepository = settingsRepo,
                    segmentedDownloader = segmented,
                    hlsDownloader = hls,
                    videoAudioMuxer = muxer,
                    mediaStoreWriter = writer,
                    notifier = mockk<DownloadNotifier>(relaxed = true),
                    dispatchers = DefaultDispatcherProvider(),
                    thumbnailCache = null,
                    availableBytesProvider = { 500L * 1024L * 1024L }
                )

                val elapsedMs = measureTimeMillis {
                    dm.runQueue { _: ForegroundInfo -> }
                }

                assertEquals("content://media/external/video/101", completedUri)
                assertEquals("Video and companion audio must be in flight concurrently", 2, peakInFlight.get())
                // Two 450ms tracks sequentially would take >= 900ms; running concurrently completes well under 850ms.
                assertTrue(
                    "Adaptive video+audio should download in parallel (< 850ms for two 450ms tracks), took ${elapsedMs}ms",
                    elapsedMs < 850L
                )
                for (i in 1 until progressHistory.size) {
                    if (progressHistory[i] == 0L) continue
                    assertTrue(
                        "Progress regressed from ${progressHistory[i - 1]} to ${progressHistory[i]}",
                        progressHistory[i] >= progressHistory[i - 1]
                    )
                }
            } finally {
                tempRoot.deleteRecursively()
            }
        }
    }

    @Test
    fun downloadManager_imageDownload_fetchesUrlOnceAndPromotesLocalThumbnail() = runBlocking {
        val tempRoot = Files.createTempDirectory("dm-dedupe-image-thumb").toFile()
        val thumbsDir = File(tempRoot, "thumbs").apply { mkdirs() }
        val httpGets = AtomicInteger(0)
        val jpegBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        ) + ByteArray(2048) { 0x42 }

        LocalHttpServer { method, path ->
            if (path == "/photo.jpg" && method == "GET") {
                httpGets.incrementAndGet()
                LocalResponse(200, "image/jpeg", jpegBytes)
            } else {
                LocalResponse(404)
            }
        }.use { server ->
            try {
                val client = OkHttpClient.Builder().build()
                val http = ExtractorHttp(client)
                val segmented = SegmentedDownloader(client, http)
                val hls = HlsSegmentDownloader(client, http)
                val thumbCache = DownloadThumbnailCache(
                    thumbsDir = thumbsDir,
                    client = client,
                    frameExtractor = { _, _ -> false },
                    remoteFrameExtractor = { _, _, _ -> false }
                )

                mockkStatic(WorkManager::class)
                every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)

                val context = mockk<Context>(relaxed = true)
                every { context.getExternalFilesDir(any()) } returns tempRoot
                every { context.filesDir } returns tempRoot
                every { context.cacheDir } returns tempRoot

                val imgUrl = "${server.baseUrl}/photo.jpg"
                val item = DownloadItem(
                    id = 202L,
                    title = "Carousel Slide 01",
                    sourceUrl = "https://www.pinterest.com/pin/123/",
                    remoteUrl = imgUrl,
                    type = MediaType.IMAGE,
                    container = "jpg",
                    qualityLabel = "JPG",
                    status = DownloadStatus.RUNNING,
                    totalBytes = jpegBytes.size.toLong(),
                    thumbnailUrl = imgUrl,
                    headersJson = JsonUtils.mapToJson(mapOf("X-Internal-Accepts-Ranges" to "true")),
                    isHls = false
                )

                var claimed = false
                var persistedThumb: String? = null
                val repo = mockk<DownloadRepository>(relaxed = true)
                coEvery { repo.claimNextQueued(any()) } answers {
                    if (!claimed) {
                        claimed = true
                        listOf(item)
                    } else emptyList()
                }
                coEvery { repo.getById(202L) } returns item
                coEvery { repo.updateThumbnail(202L, any()) } answers {
                    persistedThumb = secondArg()
                }

                val settingsRepo = mockk<SettingsRepository>(relaxed = true)
                every { settingsRepo.settings } returns MutableStateFlow(AppSettings())
                coEvery { settingsRepo.current() } returns AppSettings()

                val writer = mockk<MediaStoreWriter>()
                coEvery { writer.publish(any(), any(), any(), any(), any()) } returns "content://media/external/images/202"

                val dm = DownloadManager(
                    context = context,
                    repository = repo,
                    settingsRepository = settingsRepo,
                    segmentedDownloader = segmented,
                    hlsDownloader = hls,
                    videoAudioMuxer = mockk(relaxed = true),
                    mediaStoreWriter = writer,
                    notifier = mockk<DownloadNotifier>(relaxed = true),
                    dispatchers = DefaultDispatcherProvider(),
                    thumbnailCache = thumbCache,
                    availableBytesProvider = { 500L * 1024L * 1024L }
                )

                dm.runQueue { _: ForegroundInfo -> }

                assertEquals("Image URL must be fetched exactly once (no duplicate thumbnail download)", 1, httpGets.get())
                assertNotNull("Local thumbnail must be promoted from downloaded staging file", persistedThumb)
                val cachedThumb = File(persistedThumb!!)
                assertTrue(cachedThumb.exists())
                assertArrayEquals(jpegBytes, cachedThumb.readBytes())
            } finally {
                tempRoot.deleteRecursively()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. Domain-Family-Indexed WebViewCookieJar & Short-TTL CookieManager Cache
    // -------------------------------------------------------------------------

    @Test
    fun webViewCookieJar_domainIsolationRotationEvictionAndCookieManagerCache() {
        var fakeNow = 10_000L
        val cmReadCount = AtomicInteger(0)
        var cmValue: String? = "cf_clearance=initial_cm"

        val jar = WebViewCookieJar().apply {
            clockMs = { fakeNow }
            cookieManagerReader = {
                cmReadCount.incrementAndGet()
                cmValue
            }
        }

        for (i in 0 until 150) {
            val u = "https://site$i.example$i.com/page".toHttpUrl()
            val c = Cookie.Builder().name("s$i").value("v$i").domain("site$i.example$i.com").path("/").build()
            jar.saveFromResponse(u, listOf(c))
        }

        val targetBase = "https://cdn.moviehost.org/hls/1080p/".toHttpUrl()
        val seedHeader = "session=initial_seed"
        jar.seedFromRequestHeader(targetBase, seedHeader)

        val rotated = Cookie.Builder().name("session").value("rotated_v2").domain("moviehost.org").path("/").build()
        jar.saveFromResponse("https://edge.moviehost.org/auth".toHttpUrl(), listOf(rotated))

        jar.seedFromRequestHeader(targetBase, seedHeader)

        for (seg in 0 until 500) {
            val segUrl = "https://cdn.moviehost.org/hls/1080p/seg_$seg.ts".toHttpUrl()
            val cookies = jar.loadForRequest(segUrl).associate { it.name to it.value }
            assertEquals("rotated_v2", cookies["session"])
            assertEquals("initial_cm", cookies["cf_clearance"])
            assertNull("Unrelated domain cookies must never leak", cookies["s0"])
        }
        assertEquals("CookieManager should be read once across 500 segments in TTL window", 1, cmReadCount.get())

        fakeNow += 2_000L
        cmValue = "cf_clearance=refreshed_cm"
        val afterTtl = jar.loadForRequest("https://cdn.moviehost.org/hls/1080p/seg_501.ts".toHttpUrl())
            .associate { it.name to it.value }
        assertEquals("refreshed_cm", afterTtl["cf_clearance"])
        assertEquals(2, cmReadCount.get())

        val expired = Cookie.Builder()
            .name("session")
            .value("")
            .domain("moviehost.org")
            .path("/")
            .expiresAt(fakeNow - 1_000L)
            .build()
        jar.saveFromResponse("https://cdn.moviehost.org/logout".toHttpUrl(), listOf(expired))
        val afterEvict = jar.loadForRequest(targetBase).associate { it.name to it.value }
        assertNull("Expired cookie must be evicted across domain family", afterEvict["session"])
        assertEquals("saveFromResponse must invalidate CM cache for touched domain family", 3, cmReadCount.get())
    }

    // -------------------------------------------------------------------------
    // 6. HLS Concurrency Boost (8) Respecting CDN limit=N + Buffered Assembly
    // -------------------------------------------------------------------------

    @Test
    fun hlsSegmentDownloader_usesHigherConcurrencyAndRespectsCdnLimit() = runBlocking {
        val tempRoot = Files.createTempDirectory("hls-conc-test").toFile()
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val segCount = 12

        val mediaPlaylist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:2")
            for (i in 0 until segCount) {
                appendLine("#EXTINF:2.0,")
                appendLine("seg_$i.bin")
            }
            appendLine("#EXT-X-ENDLIST")
        }
        val playlistBytes = mediaPlaylist.toByteArray()
        val segPayloads = (0 until segCount).map { i ->
            ByteArray(512) { ((i * 17 + it) and 0xFF).toByte() }
        }

        LocalHttpServer { _, path ->
            if (path == "/stream.m3u8") {
                return@LocalHttpServer LocalResponse(200, "application/vnd.apple.mpegurl", playlistBytes)
            }
            val m = Regex("/seg_(\\d+)\\.bin").find(path)
            if (m != null) {
                val idx = m.groupValues[1].toInt()
                val cur = inFlight.incrementAndGet()
                peakInFlight.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                try {
                    Thread.sleep(100)
                    return@LocalHttpServer LocalResponse(200, "application/octet-stream", segPayloads[idx])
                } finally {
                    inFlight.decrementAndGet()
                }
            }
            LocalResponse(404)
        }.use { server ->
            try {
                val dispatcher = okhttp3.Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 32
                }
                val client = OkHttpClient.Builder().dispatcher(dispatcher).build()
                val http = ExtractorHttp(client)
                val downloader = HlsSegmentDownloader(client, http)

                val outUncapped = File(tempRoot, "uncapped.ts")
                downloader.download("${server.baseUrl}/stream.m3u8", emptyMap(), outUncapped) {}
                val expectedBytes = segPayloads.reduce { acc, b -> acc + b }
                assertArrayEquals(expectedBytes, outUncapped.readBytes())
                assertTrue("Expected > 5 concurrent HLS segments, peak was ${peakInFlight.get()}", peakInFlight.get() >= 6)

                peakInFlight.set(0)
                val outCapped = File(tempRoot, "capped.ts")
                downloader.download("${server.baseUrl}/stream.m3u8?limit=2", emptyMap(), outCapped) {}
                assertArrayEquals(expectedBytes, outCapped.readBytes())
                assertTrue("CDN limit=2 must cap concurrent segments to <= 2, peak was ${peakInFlight.get()}", peakInFlight.get() <= 2)
            } finally {
                tempRoot.deleteRecursively()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 7. Jump3rMp3Encoder Fast Profile Verified by Independent ffprobe Oracle
    // -------------------------------------------------------------------------

    @Test
    fun jump3rMp3Encoder_fastProfileProducesGenuineFfprobeVerifiedMp3() {
        val tempRoot = Files.createTempDirectory("mp3-fast-oracle").toFile()
        try {
            val sampleRate = 44_100
            val channels = 2
            val durationSec = 3
            val totalFrames = sampleRate * durationSec
            val pcm = ByteArray(totalFrames * channels * 2)
            var idx = 0
            for (i in 0 until totalFrames) {
                val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
                val lo = (sample.toInt() and 0xFF).toByte()
                val hi = ((sample.toInt() shr 8) and 0xFF).toByte()
                pcm[idx++] = lo; pcm[idx++] = hi
                pcm[idx++] = lo; pcm[idx++] = hi
            }

            val outMp3 = File(tempRoot, "tone.mp3")
            val elapsedMs = measureTimeMillis {
                Jump3rMp3Encoder(sampleRate, channels, 160).use { enc ->
                    FileOutputStream(outMp3).buffered(64 * 1024).use { out ->
                        val chunkBytes = 16_384
                        var pos = 0
                        while (pos < pcm.size) {
                            val len = minOf(chunkBytes, pcm.size - pos)
                            enc.encodePcm16Le(pcm.copyOfRange(pos, pos + len), len, out)
                            pos += len
                        }
                        enc.flush(out)
                    }
                }
            }

            assertTrue("MP3 file must exist and be non-empty", outMp3.exists() && outMp3.length() > 20_000L)
            assertTrue("3s stereo MP3 encode should complete rapidly (< 3000ms), took ${elapsedMs}ms", elapsedMs < 3_000L)

            val ffprobeCheck = runCatching {
                val proc = ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=codec_name,sample_rate,channels,duration",
                    "-of", "default=noprint_wrappers=1",
                    outMp3.absolutePath
                ).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                code to output
            }.getOrNull()

            assertNotNull("ffprobe oracle must be available in test environment", ffprobeCheck)
            assertEquals("ffprobe exited non-zero: ${ffprobeCheck!!.second}", 0, ffprobeCheck.first)
            val outText = ffprobeCheck.second
            assertTrue("ffprobe must confirm codec_name=mp3, got: $outText", outText.contains("codec_name=mp3"))
            assertTrue("ffprobe must confirm sample_rate=44100, got: $outText", outText.contains("sample_rate=44100"))
            assertTrue("ffprobe must confirm channels=2, got: $outText", outText.contains("channels=2"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
