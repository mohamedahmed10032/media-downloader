package com.melmeligy.mediadownloader.extractor

import android.content.Context
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.JsonUtils
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.download.DownloadNotifier
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.download.VideoAudioMuxer
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import com.melmeligy.mediadownloader.ui.common.formatFailedStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Regression & adversarial tests covering Batch 2 fixes:
 * 1. Browser Stream rebranding, header/cookie isolation, tokenized redirector preservation,
 *    tokenized single-stream detection, and 180-byte UTF-8-safe filename sanitization.
 * 2. StreamingPortalResolver early-exit constants, WeCima /episode/ -> /watch/ prefix swap,
 *    Base64 data-url decoding, and .List--Download--Wecima--Single direct link scraping.
 * 3. Disguised image/text MPEG-TS, fMP4, and AES-128 HLS segment byte-signature validation.
 * 4. AppError.ACCESS_DENIED / SERVER_ERROR, CODE|detail encoding/decoding, DownloadRow
 *    actionable failure messages, and DownloadManager Smart Retry re-extraction.
 */
class UnifiedBatch2RegressionTest {

    private val testDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Test
    fun interceptedMediaExtractor_isNamedBrowserStream_isolatesHeaders_andPreservesRedirectors() {
        val http = ExtractorHttp(OkHttpClient())
        val extractor = InterceptedMediaExtractor(
            engine = MediaInterceptionEngine(),
            http = http,
            hlsExtractor = HlsExtractor(http),
            dashExtractor = DashExtractor(http)
        )
        assertEquals("Browser Stream", extractor.name)

        // Header isolation: candidate B must NOT receive candidate A's cookies or Origin
        val payloadB = MediaStreamPayload(
            url = "https://cdn-b.example/video.mp4",
            streamType = StreamType.PROGRESSIVE,
            headersMap = mapOf("Referer" to "https://embed-b.example/e/1"),
            pageUrl = "https://portal.example/watch/1"
        )
        val isolated = InterceptedMediaExtractor.isolateCandidateHeaders(
            payload = payloadB,
            pageUrl = "https://portal.example/watch/1",
            sharedUserAgent = "LiveWebViewUA/131",
            pageDurationSec = 120L
        )
        assertEquals("https://embed-b.example/e/1", isolated["Referer"])
        assertEquals("LiveWebViewUA/131", isolated["User-Agent"])
        assertNull("Origin must not be synthesized", isolated["Origin"])
        assertNull("Unrelated host cookies must not bleed", isolated["Cookie"])

        // Tokenized & cross-host redirector URLs must be preserved
        assertTrue(
            InterceptedMediaExtractor.shouldPreserveRedirectorUrl(
                "https://streamtape.example/get_video?id=1&token=abc",
                "https://edge99.cdn.example/single-use-blob.mp4"
            )
        )
        assertTrue(
            InterceptedMediaExtractor.shouldPreserveRedirectorUrl(
                "https://mirror-a.example/d/movie.mp4",
                "https://edge-b.example/signed/movie.mp4"
            )
        )
        assertFalse(
            InterceptedMediaExtractor.shouldPreserveRedirectorUrl(
                "https://cdn.example/videos/movie.mp4",
                "https://cdn.example/videos/movie_hd.mp4"
            )
        )
    }

    @Test
    fun formatUtils_sanitizeFileName_capsAt180Utf8BytesWithoutSplittingArabicOrTrailingDots() {
        // Long Arabic string (> 180 UTF-8 bytes; each Arabic char is 2 bytes in UTF-8)
        val longArabic = "مسلسل الأكشن والدراما الحلقة الأخيرة مترجمة أون لاين بجودة عالية جداً ".repeat(5) + "..."
        val sanitized = FormatUtils.sanitizeFileName(longArabic)
        val utf8Bytes = sanitized.toByteArray(StandardCharsets.UTF_8)
        assertTrue("Expected <= 180 UTF-8 bytes, got ${utf8Bytes.size}", utf8Bytes.size <= 180)
        assertFalse("Must not end with trailing dot", sanitized.endsWith('.'))
        assertFalse("Must not contain Unicode replacement char from split sequence", sanitized.contains('\uFFFD'))
        // Round-trip UTF-8 decode must match exactly (proves no split code points)
        assertEquals(sanitized, String(utf8Bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun segmentedDownloader_defaultsTokenizedMovieMirrorsToSingleStream_butKeepsSocialCdnsMultiSegment() {
        assertTrue(SegmentedDownloader.isTokenizedSingleStreamUrl("https://streamtape.example/get_video?id=1&token=abc"))
        assertTrue(SegmentedDownloader.isTokenizedSingleStreamUrl("https://mirror.example/d/movie123.mp4"))
        assertTrue(SegmentedDownloader.isTokenizedSingleStreamUrl("https://cdn.movie.example/video.mp4?token=xyz"))

        assertFalse(SegmentedDownloader.isTokenizedSingleStreamUrl("https://rr1---sn.googlevideo.com/videoplayback?token=abc"))
        assertFalse(SegmentedDownloader.isTokenizedSingleStreamUrl("https://v16.tiktokcdn.com/video/tos/?token=abc"))
        assertFalse(SegmentedDownloader.isTokenizedSingleStreamUrl("https://video.xx.fbcdn.net/v/t42/clip.mp4?token=abc"))
        assertFalse(SegmentedDownloader.isTokenizedSingleStreamUrl("https://scontent.cdninstagram.com/v/reel.mp4?token=abc"))
        assertFalse(SegmentedDownloader.isTokenizedSingleStreamUrl("https://cdn.example/plain/movie.mp4"))
    }

    @Test
    fun streamingPortalResolver_supportsWecimaEpisodePrefixSwap_base64DataUrl_andDirectDownloadList() = runTest {
        assertEquals(5_500L, StreamingPortalResolver.PORTAL_TOTAL_BUDGET_MS)
        assertEquals(850L, StreamingPortalResolver.SIBLING_MERGE_GRACE_MS)

        val episodeUrl = "https://wecima.example/episode/my-show-ep-1/"
        val watchUrl = "https://wecima.example/watch/my-show-ep-1/"
        val b64Embed = Base64.getEncoder().encodeToString("https://cdn.wecima-dl.example/files/ep1-720p.mp4".toByteArray())
        val directListUrl = "https://cdn.wecima-dl.example/files/ep1-1080p.mp4"

        val episodeHtml = """
            <html><body>
              <a href="$watchUrl">Watch Episode</a>
              <a href="https://wecima.example/watch/unrelated-movie/">Other</a>
            </body></html>
        """.trimIndent()

        val watchHtml = """
            <html><body>
              <li data-url="$b64Embed">Base64 Server</li>
              <ul class="List--Download--Wecima--Single">
                <li><a href="$directListUrl">1080p Direct</a></li>
              </ul>
            </body></html>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val u = req.url.toString()
                when (u) {
                    watchUrl -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(watchHtml.toResponseBody("text/html".toMediaType()))
                        .build()
                    "https://cdn.wecima-dl.example/files/ep1-720p.mp4",
                    directListUrl -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Range", "bytes 0-0/50000000")
                        .header("Accept-Ranges", "bytes")
                        .body(ByteArray(1).toResponseBody("video/mp4".toMediaType()))
                        .build()
                    else -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val resolver = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))

        val subPages = resolver.discoverPortalSubPages(episodeUrl, Jsoup.parse(episodeHtml, episodeUrl))
        assertEquals(listOf(watchUrl), subPages)

        val result = resolver.resolvePortalAndEmbeds(episodeUrl, episodeHtml, Jsoup.parse(episodeHtml, episodeUrl), emptyMap())
        assertTrue("Expected resolved streams from Base64 data-url and .List--Download--Wecima--Single", result.streams.isNotEmpty())
        assertTrue(result.streams.any { it.url == directListUrl || it.url == "https://cdn.wecima-dl.example/files/ep1-720p.mp4" })
    }

    @Test
    fun hlsSegmentDownloader_acceptsDisguisedImageTextSegments_andRejectsHtmlJsonErrors() {
        // 1. Disguised MPEG-TS (0x47 sync byte) served as image/png -> ACCEPTED
        val tsBytes = ByteArray(200).apply { this[0] = 0x47; this[188] = 0x47 }
        assertTrue(HlsSegmentDownloader.isValidSegmentPrefix(tsBytes, tsBytes.size, "image/png", isAesEncrypted = false))
        assertTrue(HlsSegmentDownloader.isValidSegmentPrefix(tsBytes, tsBytes.size, "text/plain", isAesEncrypted = false))

        // 2. Disguised fMP4 (ftyp / moof) served as image/jpeg -> ACCEPTED
        val fmp4Bytes = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0)
        assertTrue(HlsSegmentDownloader.isValidSegmentPrefix(fmp4Bytes, fmp4Bytes.size, "image/jpeg", isAesEncrypted = false))

        // 3. AES-128 decrypted segment served as image/webp -> ACCEPTED
        val aesBytes = ByteArray(64) { (it + 1).toByte() }
        assertTrue(HlsSegmentDownloader.isValidSegmentPrefix(aesBytes, aesBytes.size, "image/webp", isAesEncrypted = true))

        // 4. Adversarial HTML and JSON error pages -> REJECTED
        val htmlErr = "<!DOCTYPE html><html><body>403 Forbidden</body></html>".toByteArray()
        val jsonErr = """{"status":403,"error":"token expired"}""".toByteArray()
        val plainTextErr = "Access denied: invalid token".toByteArray()
        assertFalse(HlsSegmentDownloader.isValidSegmentPrefix(htmlErr, htmlErr.size, "image/png", isAesEncrypted = false))
        assertFalse(HlsSegmentDownloader.isValidSegmentPrefix(jsonErr, jsonErr.size, "text/plain", isAesEncrypted = false))
        assertFalse(HlsSegmentDownloader.isValidSegmentPrefix(plainTextErr, plainTextErr.size, "text/plain", isAesEncrypted = false))
    }

    @Test
    fun errorSurfacing_encodesDecodesRoomCompatibleErrorTypes_andFormatsDownloadRowReasons() {
        val http = ExtractorHttp(OkHttpClient())
        assertEquals(AppError.ACCESS_DENIED, http.mapHttpError(403).error)
        assertEquals(AppError.ACCESS_DENIED, http.mapHttpError(401).error)
        assertEquals(AppError.SERVER_ERROR, http.mapHttpError(429).error)
        assertEquals(AppError.SERVER_ERROR, http.mapHttpError(503).error)

        // CODE|detail round-trip
        val encoded = AppError.ACCESS_DENIED.encodeForStorage("HTTP 403")
        assertEquals("ACCESS_DENIED|HTTP 403", encoded)
        assertEquals(AppError.ACCESS_DENIED to "HTTP 403", AppError.decodeFromStorage(encoded))

        // Backward compatibility with legacy bare Room errorType rows
        assertEquals(AppError.GEO_BLOCKED to null, AppError.decodeFromStorage("GEO_BLOCKED"))

        // DownloadRow actionable messages
        assertEquals("Access denied (HTTP 403) · tap retry", formatFailedStatus("ACCESS_DENIED|HTTP 403"))
        assertEquals("Link expired · tap retry", formatFailedStatus("EXPIRED_LINK|HTTP 410"))
        assertEquals("Server error (HTTP 503) · tap retry", formatFailedStatus("SERVER_ERROR|HTTP 503"))
        assertEquals("Failed · tap retry", formatFailedStatus(null))
    }

    @Test
    fun downloadManager_smartRetry_reExtractsFreshUrlAndHeadersForAccessDeniedTasks() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("smart-retry-test").toFile()
        try {
            val mockCtx = mockk<Context>(relaxed = true).apply {
                every { filesDir } returns tempDir
                every { getExternalFilesDir(null) } returns tempDir
            }
            val mockRepo = mockk<DownloadRepository>(relaxed = true)
            val mockSettings = mockk<SettingsRepository>(relaxed = true).apply {
                every { settings } returns MutableStateFlow(AppSettings())
                coEvery { current() } returns AppSettings()
            }
            val mockMediaRepo = mockk<MediaRepository>(relaxed = true)

            val failedItem = DownloadItem(
                id = 42L,
                title = "Movie 2026",
                sourceUrl = "https://portal.example/watch/movie-2026",
                remoteUrl = "https://old-cdn.example/expired.mp4",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "720p",
                status = DownloadStatus.FAILED,
                totalBytes = 10_000_000L,
                errorType = AppError.ACCESS_DENIED.encodeForStorage("HTTP 403"),
                headersJson = JsonUtils.mapToJson(mapOf("Referer" to "https://old-embed.example/e/1")),
                isHls = false
            )
            coEvery { mockRepo.getById(42L) } returns failedItem
            coEvery { mockMediaRepo.resolve("https://portal.example/watch/movie-2026") } returns ResolvedMedia(
                sourceUrl = "https://portal.example/watch/movie-2026",
                title = "Movie 2026",
                streams = listOf(
                    MediaStream(
                        id = "fresh-720",
                        url = "https://fresh-cdn.example/video-720p.mp4?token=NEW",
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = "720p",
                        height = 720,
                        sizeBytes = 12_000_000L,
                        isHls = false,
                        headers = mapOf("Referer" to "https://fresh-embed.example/e/2")
                    )
                ),
                extractor = "Smart Extractor"
            )

            val manager = DownloadManager(
                context = mockCtx,
                repository = mockRepo,
                settingsRepository = mockSettings,
                segmentedDownloader = mockk(relaxed = true),
                hlsDownloader = mockk(relaxed = true),
                videoAudioMuxer = mockk(relaxed = true),
                mediaStoreWriter = mockk(relaxed = true),
                notifier = mockk(relaxed = true),
                dispatchers = testDispatchers,
                thumbnailCache = null,
                availableBytesProvider = { 1_000_000_000L },
                mediaRepository = dagger.Lazy { mockMediaRepo }
            )

            val updatedSlot = slot<DownloadItem>()
            coEvery { mockRepo.update(capture(updatedSlot)) } returns Unit

            // WorkManager.getInstance(mockCtx) may throw in pure JVM unit test inside schedule();
            // repository.update happens BEFORE schedule() so we catch the WorkManager stub safely.
            runCatching { manager.retry(42L) }

            coVerify(exactly = 1) { mockMediaRepo.resolve("https://portal.example/watch/movie-2026") }
            coVerify(exactly = 1) { mockRepo.update(any()) }
            assertEquals("https://fresh-cdn.example/video-720p.mp4?token=NEW", updatedSlot.captured.remoteUrl)
            assertEquals(DownloadStatus.QUEUED, updatedSlot.captured.status)
            assertNull(updatedSlot.captured.errorType)
            assertEquals(
                "https://fresh-embed.example/e/2",
                JsonUtils.jsonToMap(updatedSlot.captured.headersJson)["Referer"]
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
