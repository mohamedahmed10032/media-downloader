package com.melmeligy.mediadownloader.extractor

import android.content.Context
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.JsonUtils
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
import com.melmeligy.mediadownloader.download.DownloadThumbnailCache
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.download.VideoAudioMuxer
import com.melmeligy.mediadownloader.ui.common.ThumbnailCandidate
import com.melmeligy.mediadownloader.ui.common.resolveThumbnailCandidates
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression & adversarial tests for movie-portal download failures (`Failed · tap retry`)
 * and missing thumbnails (Clapperboard fallback) on portals such as wecima.here.
 */
class MoviePortalDownloadAndThumbnailFixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val ioDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private val sampleWecimaUrl =
        "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-%D9%85%D8%AD%D9%85%D8%AF-%D8%A7%D9%84%D9%81%D8%A7%D8%AA%D8%AD-%D8%B3%D9%84%D8%B7%D8%A7%D9%86-%D8%A7%D9%84%D9%81%D8%AA%D9%88%D8%AD%D8%A7%D8%AA-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%B1%D8%A7%D8%A8%D8%B9-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9-9-%D9%85%D8%AA%D8%B1%D8%AC%D9%85%D8%A9"

    private fun generateTinyRealTsSegment(dest: File): ByteArray {
        val pb = ProcessBuilder(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=duration=1:size=160x90:rate=15",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "64k",
            "-f", "mpegts", dest.absolutePath
        )
        val proc = pb.start()
        val rc = proc.waitFor()
        assertEquals("ffmpeg must generate real MPEG-TS fixture cleanly", 0, rc)
        return dest.readBytes()
    }

    @Test
    fun wecimaEndToEnd_skips403AndDrmHlsServers_relaxesOriginHeader_downloadsRealTsAndCachesPoster() = runBlocking {
        val realTsBytes = generateTinyRealTsSegment(File(tempFolder.root, "fixture_seg.ts"))
        val jpegPosterBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        )

        val wecimaHtml = """
            <!doctype html>
            <html>
            <head>
              <title>مسلسل محمد الفاتح سلطان الفتوحات الموسم الرابع الحلقة 9 مترجمة - WeCima</title>
            </head>
            <body>
              <div class="BG--Mycima-Single" data-lazy-style="--image:url('https://wecima.here/wp-content/uploads/2026/03/محمد الفاتح.jpg');"></div>
              <ul class="WatchServersList">
                <li data-url="https://broken-seg.embed.example/e/1">BrokenSegServer</li>
                <li data-url="https://drm-only.embed.example/e/2">DrmServer</li>
                <li data-url="https://origin-strict.embed.example/e/3">WorkingServer</li>
              </ul>
            </body>
            </html>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                fun resp(code: Int, body: ByteArray, ct: String, extra: Map<String, String> = emptyMap()) =
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code in 200..299) "OK" else "ERR")
                        .header("Content-Type", ct)
                        .apply { extra.forEach { (k, v) -> header(k, v) } }
                        .body(body.toResponseBody(ct.toMediaType()))
                        .build()

                when {
                    url == sampleWecimaUrl -> resp(200, wecimaHtml.toByteArray(), "text/html; charset=utf-8")

                    // Server 0: master + variant return 200, but segments return 403 Forbidden!
                    url == "https://broken-seg.embed.example/e/1" ->
                        resp(200, """<script>new Playerjs({file:"https://cdn-broken.example/master.m3u8"});</script>""".toByteArray(), "text/html")
                    url == "https://cdn-broken.example/master.m3u8" ->
                        resp(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080\n1080.m3u8\n".toByteArray(), "application/vnd.apple.mpegurl")
                    url == "https://cdn-broken.example/1080.m3u8" ->
                        resp(200, "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg0.ts\n#EXT-X-ENDLIST\n".toByteArray(), "application/vnd.apple.mpegurl")
                    url == "https://cdn-broken.example/seg0.ts" ->
                        resp(403, "Forbidden".toByteArray(), "text/plain")

                    // Server 1: DRM SAMPLE-AES playlist (un-downloadable!)
                    url == "https://drm-only.embed.example/e/2" ->
                        resp(200, """<script>new Playerjs({file:"https://cdn-drm.example/index.m3u8"});</script>""".toByteArray(), "text/html")
                    url == "https://cdn-drm.example/index.m3u8" ->
                        resp(
                            200,
                            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://drm\"\n#EXTINF:10.0,\nseg0.ts\n#EXT-X-ENDLIST\n".toByteArray(),
                            "application/vnd.apple.mpegurl"
                        )

                    // Server 2: Healthy server whose CDN rejects `Origin` header with 403 but accepts Referer without Origin
                    url == "https://origin-strict.embed.example/e/3" -> {
                        Thread.sleep(100) // Slightly slower than broken Server 0 to prove broken server doesn't win!
                        resp(200, """<script>new Playerjs({file:"https://cdn-good.example/master.m3u8"});</script>""".toByteArray(), "text/html")
                    }
                    url == "https://cdn-good.example/master.m3u8" -> {
                        if (req.header("Origin") != null) {
                            resp(403, "Origin Not Allowed".toByteArray(), "text/plain")
                        } else {
                            resp(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720\n720.m3u8\n".toByteArray(), "application/vnd.apple.mpegurl")
                        }
                    }
                    url == "https://cdn-good.example/720.m3u8" -> {
                        if (req.header("Origin") != null) {
                            resp(403, "Origin Not Allowed".toByteArray(), "text/plain")
                        } else {
                            resp(200, "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1.0,\nseg0.ts\n#EXT-X-ENDLIST\n".toByteArray(), "application/vnd.apple.mpegurl")
                        }
                    }
                    url == "https://cdn-good.example/seg0.ts" -> {
                        if (req.header("Origin") != null || req.header("Referer")?.contains("origin-strict.embed.example") != true) {
                            resp(403, "Bad Headers".toByteArray(), "text/plain")
                        } else if (req.header("Range") == "bytes=0-0") {
                            resp(
                                206,
                                byteArrayOf(realTsBytes[0]),
                                "video/mp2t",
                                mapOf("Content-Range" to "bytes 0-0/${realTsBytes.size}", "Accept-Ranges" to "bytes")
                            )
                        } else {
                            resp(200, realTsBytes, "video/mp2t")
                        }
                    }

                    // Poster on wecima.here: rejects embed Referer or Origin header; succeeds ONLY with wecima.here Referer & no Origin
                    url.startsWith("https://wecima.here/wp-content/uploads/2026/03/") -> {
                        val ref = req.header("Referer").orEmpty()
                        val origin = req.header("Origin")
                        if (!ref.startsWith("https://wecima.here/") || origin != null) {
                            resp(403, "Hotlink/Origin Blocked".toByteArray(), "text/html")
                        } else {
                            resp(200, jpegPosterBytes, "image/jpeg")
                        }
                    }

                    else -> resp(404, "Not found".toByteArray(), "text/plain")
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val hlsExtractor = HlsExtractor(http)
        val htmlExtractor = HtmlMediaExtractor(http, hlsExtractor, DashExtractor(http), mockk(relaxed = true))

        // 1. Extract: broken Server 0 & DRM Server 1 must be rejected; WorkingServer wins with stripped Origin
        val resolved = htmlExtractor.extractWithHeaders(sampleWecimaUrl, allowHeadlessFallback = false)
        assertNotNull("Working backup server must be selected when earlier servers have 403 segments / DRM", resolved)
        requireNotNull(resolved)
        assertTrue(resolved.title.contains("محمد الفاتح"))
        assertEquals(
            "https://wecima.here/wp-content/uploads/2026/03/%D9%85%D8%AD%D9%85%D8%AF%20%D8%A7%D9%84%D9%81%D8%A7%D8%AA%D8%AD.jpg",
            resolved.thumbnailUrl
        )
        val winner = resolved.streams.first { it.type == MediaType.VIDEO }
        assertEquals("https://cdn-good.example/720.m3u8", winner.url)
        assertNull("Origin must be stripped after 403 relaxation", winner.headers["Origin"])
        assertTrue(winner.headers["Referer"]?.contains("origin-strict.embed.example") == true)

        // 2. Download real HLS segment & validate with independent ffprobe consumer oracle
        val tsDest = File(tempFolder.root, "wecima_ep9.ts")
        val hlsDownloader = HlsSegmentDownloader(client, http)
        hlsDownloader.download(winner.url, winner.headers, tsDest) {}
        assertTrue("Downloaded TS file must be non-empty", tsDest.length() > 1000L)

        val ffprobeProc = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_entries", "stream=codec_type",
            "-of", "default=noprint_wrappers=1:nokey=1", tsDest.absolutePath
        ).start()
        val probeOut = ffprobeProc.inputStream.bufferedReader().readText()
        assertEquals(0, ffprobeProc.waitFor())
        assertTrue("ffprobe must detect real video stream (got: $probeOut)", probeOut.contains("video"))

        // 3. Cache poster locally (despite stream carrying origin-strict.embed.example Referer!)
        val thumbsDir = File(tempFolder.root, "thumbs")
        val thumbCache = DownloadThumbnailCache(thumbsDir, client) { _, _ -> false }
        val previewFile = thumbCache.cachePreviewThumbnail(resolved.thumbnailUrl, resolved.sourceUrl, winner.headers)
        assertNotNull("WeCima poster with Arabic filename & spaces must download & cache locally", previewFile)
        assertArrayEquals(jpegPosterBytes, previewFile!!.readBytes())

        // Promote preview into download ID 42 -> LocalFile candidate ready immediately
        val thumb42 = thumbCache.cacheRemoteThumbnail(42L, previewFile.absolutePath, resolved.sourceUrl, winner.headers)
        assertNotNull(thumb42)
        assertEquals("thumb_42.jpg", thumb42!!.name)
    }

    @Test
    fun segmentedDownloader_singleConnectionMovieCdnRejectingParallelRangesWith403_fallsBackToSingleStream() = runBlocking {
        val payload = ByteArray(1_300_000) { (it % 241).toByte() } // > MIN_SEGMENT_SIZE_BYTES (1 MiB)
        val parallelRejections = AtomicInteger(0)
        val originRejections = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val range = req.header("Range")
                when {
                    // Single-byte probe succeeds with 206 + Accept-Ranges: bytes
                    range == "bytes=0-0" ->
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                            .header("Content-Type", "video/mp4")
                            .header("Accept-Ranges", "bytes")
                            .header("Content-Range", "bytes 0-0/${payload.size}")
                            .body(byteArrayOf(payload[0]).toResponseBody("video/mp4".toMediaType())).build()

                    // Any ranged segment request fails with 403 (single-connection movie CDN limit!)
                    range != null -> {
                        parallelRejections.incrementAndGet()
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(403).message("Too Many Connections")
                            .body("Limit 1".toByteArray().toResponseBody("text/plain".toMediaType())).build()
                    }

                    // Single-stream GET rejects Origin header with 403 -> proves header relaxation strips Origin
                    req.header("Origin") != null -> {
                        originRejections.incrementAndGet()
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(403).message("No Origin")
                            .body("Forbidden".toByteArray().toResponseBody("text/plain".toMediaType())).build()
                    }

                    // Clean single-stream GET without Origin succeeds with full payload
                    else ->
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .header("Content-Type", "video/mp4")
                            .header("Content-Length", payload.size.toString())
                            .body(payload.toResponseBody("video/mp4".toMediaType())).build()
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val downloader = SegmentedDownloader(client, http)
        val dest = File(tempFolder.root, "single_conn_movie.mp4")

        downloader.download(
            url = "https://single-conn-cdn.example/movie.mp4",
            headers = mapOf(
                "Referer" to "https://embed.example/e/abc",
                "Origin" to "https://embed.example"
            ),
            dest = dest,
            knownTotal = payload.size.toLong(),
            acceptsRanges = true,
            segmentCount = 6,
            onProgress = {}
        )

        assertTrue("Parallel ranges should have triggered 403 fallback", parallelRejections.get() > 0)
        assertTrue("Single-stream fallback should have relaxed Origin after 403", originRejections.get() > 0)
        assertEquals(payload.size.toLong(), dest.length())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun downloaders_adversarialHtmlErrorPage_rejectedInsteadOfCorruptingMediaFile() = runBlocking {
        val htmlClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.endsWith(".m3u8")) {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Type", "application/vnd.apple.mpegurl")
                        .body("#EXTM3U\n#EXTINF:5.0,\nseg0.ts\n#EXT-X-ENDLIST\n".toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                        .build()
                } else {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body("<html><body>Token expired</body></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                }
            }
            .build()

        val http = ExtractorHttp(htmlClient)
        val segErr = runCatching {
            SegmentedDownloader(htmlClient, http).download(
                url = "https://cdn.example/expired.mp4",
                headers = emptyMap(),
                dest = File(tempFolder.root, "bad.mp4"),
                knownTotal = 0L,
                acceptsRanges = false,
                segmentCount = 4,
                onProgress = {}
            )
        }.exceptionOrNull()
        assertTrue("SegmentedDownloader must reject HTML 200 responses as EXPIRED_LINK (got $segErr)", (segErr as? MediaException)?.error == AppError.EXPIRED_LINK)

        val hlsErr = runCatching {
            HlsSegmentDownloader(htmlClient, http).download(
                playlistUrl = "https://cdn.example/bad.m3u8",
                headers = emptyMap(),
                dest = File(tempFolder.root, "bad.ts"),
                expectedTotalBytes = 0L,
                maxRetries = 0,
                onProgress = {}
            )
        }.exceptionOrNull()
        assertTrue("HlsSegmentDownloader must reject HTML segment responses as EXPIRED_LINK (got $hlsErr)", (hlsErr as? MediaException)?.error == AppError.EXPIRED_LINK)
    }

    @Test
    fun downloadManager_preservesCachedThumbnailOnFailure_and_automaticallyFailsOverToBackupMirror() = runBlocking {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)
        try {
            val context = mockk<Context>(relaxed = true)
            val appDir = tempFolder.newFolder("dm_app")
            every { context.getExternalFilesDir(null) } returns appDir
            every { context.filesDir } returns appDir

            val items = ConcurrentHashMap<Long, DownloadItem>()
            val idGen = AtomicInteger(0)
            val repo = mockk<DownloadRepository>(relaxed = true)
            coEvery { repo.enqueue(any()) } answers {
                val id = idGen.incrementAndGet().toLong()
                items[id] = firstArg<DownloadItem>().copy(id = id)
                id
            }
            coEvery { repo.getById(any()) } answers { items[firstArg()] }
            coEvery { repo.claimNextQueued(any()) } answers {
                val next = items.values.firstOrNull { it.status == DownloadStatus.QUEUED }
                if (next != null) {
                    items[next.id] = next.copy(status = DownloadStatus.RUNNING)
                    listOf(items[next.id]!!)
                } else emptyList()
            }
            coEvery { repo.updateThumbnail(any(), any()) } answers {
                val id = firstArg<Long>()
                val path = secondArg<String>()
                items[id]?.let { items[id] = it.copy(thumbnailUrl = path) }
            }
            coEvery { repo.setError(any(), any()) } answers {
                val id = firstArg<Long>()
                val err = secondArg<AppError>()
                items[id]?.let { items[id] = it.copy(status = DownloadStatus.FAILED, errorType = err.name) }
            }
            coEvery { repo.setCompleted(any(), any(), any()) } answers {
                val id = firstArg<Long>()
                val uri = secondArg<String>()
                val bytes = thirdArg<Long>()
                items[id]?.let { items[id] = it.copy(status = DownloadStatus.COMPLETED, contentUri = uri, totalBytes = bytes, downloadedBytes = bytes) }
            }
            coEvery { repo.delete(any()) } answers { items.remove(firstArg()) }
            coEvery { repo.resetInterrupted() } returns Unit

            val settingsRepo = mockk<SettingsRepository>()
            every { settingsRepo.settings } returns MutableStateFlow(AppSettings(retryCount = 0))
            coEvery { settingsRepo.current() } returns AppSettings(retryCount = 0)

            val mediaStoreWriter = mockk<MediaStoreWriter>()
            coEvery { mediaStoreWriter.publish(any(), any(), any(), any(), any()) } returns "content://media/external/video/99"

            val thumbsDir = File(appDir, "thumbnails")
            val previewThumb = File(thumbsDir, "preview_deadbeef.jpg").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4))
            }
            val thumbCache = DownloadThumbnailCache(thumbsDir, OkHttpClient()) { _, _ -> false }

            val mockSegmented = mockk<SegmentedDownloader>()
            // Primary URL #1 fails permanently (404) with no backup
            coEvery { mockSegmented.download("https://dead.cdn/fail.mp4", any(), any(), any(), any(), any(), any()) } throws
                MediaException(AppError.NO_MEDIA_FOUND, "HTTP 404")
            // Primary URL #2 fails with 403, Backup URL #2 succeeds!
            coEvery { mockSegmented.download("https://primary.cdn/expired.mp4", any(), any(), any(), any(), any(), any()) } throws
                MediaException(AppError.GEO_BLOCKED, "HTTP 403")
            coEvery { mockSegmented.download("https://backup.cdn/working.mp4", any(), any(), any(), any(), any(), any()) } answers {
                val dest = thirdArg<File>()
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(4096) { 0x22 })
            }

            val dm = DownloadManager(
                context = context,
                repository = repo,
                settingsRepository = settingsRepo,
                segmentedDownloader = mockSegmented,
                hlsDownloader = mockk(relaxed = true),
                videoAudioMuxer = mockk<VideoAudioMuxer>(relaxed = true),
                mediaStoreWriter = mediaStoreWriter,
                notifier = mockk<DownloadNotifier>(relaxed = true),
                dispatchers = ioDispatchers,
                thumbnailCache = thumbCache,
                availableBytesProvider = { 500_000_000L }
            )

            // Part A: Failed download MUST preserve cached local thumbnail (never Clapperboard!)
            val failedMedia = ResolvedMedia(
                sourceUrl = sampleWecimaUrl,
                title = "Failed Episode",
                thumbnailUrl = previewThumb.absolutePath,
                streams = listOf(MediaStream("s1", "https://dead.cdn/fail.mp4", MediaType.VIDEO, "mp4", "720p"))
            )
            val failId = dm.enqueue(failedMedia, failedMedia.streams.single())
            assertTrue("Thumbnail must be promoted immediately on enqueue", thumbCache.hasCachedThumbnail(failId))

            // Part B: Primary 403 + Backup mirror -> automatic failover to COMPLETED!
            val failoverHeaders = mapOf(
                "Referer" to "https://primary.embed/e/1",
                "X-Internal-Fallback-Url" to "https://backup.cdn/working.mp4",
                "X-Internal-Fallback-IsHls" to "false",
                "X-Internal-Fallback-Container" to "mp4",
                "X-Internal-Fallback-Headers" to JsonUtils.mapToJson(mapOf("Referer" to "https://backup.embed/e/2"))
            )
            val failoverMedia = ResolvedMedia(
                sourceUrl = sampleWecimaUrl,
                title = "Failover Episode",
                thumbnailUrl = previewThumb.absolutePath,
                streams = listOf(MediaStream("s2", "https://primary.cdn/expired.mp4", MediaType.VIDEO, "mp4", "720p", headers = failoverHeaders))
            )
            val okId = dm.enqueue(failoverMedia, failoverMedia.streams.single())

            dm.runQueue {}

            // Verify Part A: Failed item STILL has its real cached thumbnail on disk!
            val failedRow = items[failId]!!
            assertEquals(DownloadStatus.FAILED, failedRow.status)
            assertTrue("Cached thumb file MUST NOT be deleted when download fails", thumbCache.hasCachedThumbnail(failId))
            val candidates = resolveThumbnailCandidates(failedRow)
            assertTrue("Failed card must resolve to LocalFile poster, not Clapperboard", candidates.firstOrNull() is ThumbnailCandidate.LocalFile)

            // Verify Part B: Backup mirror succeeded automatically!
            val okRow = items[okId]!!
            assertEquals("Primary 403 must automatically fail over to healthy backup mirror", DownloadStatus.COMPLETED, okRow.status)
            assertEquals(4096L, okRow.totalBytes)

            // Explicit cancel DOES delete the thumbnail cleanly
            dm.cancel(failId)
            assertFalse("Explicit cancel should remove thumbnail file", thumbCache.hasCachedThumbnail(failId))
        } finally {
            unmockkStatic(WorkManager::class)
        }
    }

    @Test
    fun htmlThumbnailExtractor_and_buildThumbnailHeaders_handleInlineStyleArabicUrlsAndPreventEmbedLeak() {
        val rawArabicSourceUrl =
            "https://wecima.here/watch/مسلسل-محمد-الفاتح-سلطان-الفتوحات-الموسم-الرابع-الحلقة-9-مترجمة"
        val html = """
            <html>
            <head>
              <style>.BG--Mycima-Single { --image: url('https://wecima.here/wp-content/uploads/2026/03/محمد الفاتح.jpg'); }</style>
            </head>
            <body><div class="BG--Mycima-Single"></div></body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html, rawArabicSourceUrl)
        val poster = HtmlThumbnailExtractor.extractThumbnail(doc, rawArabicSourceUrl, html)
        assertEquals(
            "https://wecima.here/wp-content/uploads/2026/03/%D9%85%D8%AD%D9%85%D8%AF%20%D8%A7%D9%84%D9%81%D8%A7%D8%AA%D8%AD.jpg",
            poster
        )

        // Embed stream headers from vidbom.com must NOT leak onto wecima.here poster request,
        // and unencoded Arabic sourceUrl must be percent-encoded to valid ASCII for OkHttp headers.
        val embedStreamHeaders = mapOf(
            "Referer" to "https://vidbom.com/embed-xyz123.html",
            "Origin" to "https://vidbom.com",
            "Cookie" to "vidbom_token=secret"
        )
        val thumbHeaders = DownloadThumbnailCache.buildThumbnailHeaders(
            sourceUrl = rawArabicSourceUrl,
            headers = embedStreamHeaders,
            thumbnailUrl = poster
        )
        val ref = thumbHeaders["Referer"]
        assertNotNull(ref)
        assertTrue("Referer must point to wecima.here, never vidbom.com (was $ref)", ref!!.startsWith("https://wecima.here/watch/"))
        assertTrue("Referer must be valid ASCII for OkHttp (was $ref)", ref.all { it.code in 0x20..0x7E })
        assertEquals("https://wecima.here", thumbHeaders["Origin"])
        assertNull("Embed server cookie must never leak onto portal poster", thumbHeaders["Cookie"])
    }
}
