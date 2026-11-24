package com.melmeligy.mediadownloader.ui

import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.download.DownloadThumbnailCache
import com.melmeligy.mediadownloader.extractor.HeadlessWebExtractor
import com.melmeligy.mediadownloader.extractor.HtmlThumbnailExtractor
import com.melmeligy.mediadownloader.ui.common.formatCompactQualityBadge
import com.melmeligy.mediadownloader.ui.common.formatDownloadPrimaryStatus
import com.melmeligy.mediadownloader.ui.quality.formatCleanSourceLabel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UiAndThumbnailPolishTest {

    @After
    fun tearDown() {
        DownloadThumbnailCache.cookieLookupForTests = null
    }

    // -------------------------------------------------------------------------
    // R1: Clean source label in CompactVideoHeader (no internal engine names)
    // -------------------------------------------------------------------------

    @Test
    fun `formatCleanSourceLabel maps known platforms and strips www for generic domains`() {
        assertEquals("YouTube", formatCleanSourceLabel("https://www.youtube.com/watch?v=abc", "Smart Extractor"))
        assertEquals("TikTok", formatCleanSourceLabel("https://vm.tiktok.com/ZM123/", "Smart Extractor"))
        assertEquals("Instagram", formatCleanSourceLabel("https://www.instagram.com/reel/abc/", "Browser Stream"))
        assertEquals("Facebook", formatCleanSourceLabel("https://fb.watch/xyz/", "Smart Extractor"))
        assertEquals("X", formatCleanSourceLabel("https://x.com/user/status/123", "Smart Extractor"))
        assertEquals("Vimeo", formatCleanSourceLabel("https://vimeo.com/12345", "Smart Extractor"))
        assertEquals("Dailymotion", formatCleanSourceLabel("https://www.dailymotion.com/video/x8abc", "Smart Extractor"))
        assertEquals("SoundCloud", formatCleanSourceLabel("https://m.soundcloud.com/artist/track", "Smart Extractor"))
        assertEquals("Internet Archive", formatCleanSourceLabel("https://archive.org/details/sample", "Smart Extractor"))

        assertEquals("wecima.here", formatCleanSourceLabel("https://www.wecima.here/watch/ep-6/", "Smart Extractor"))
        assertEquals(
            "wecima.here · 45:12",
            formatCleanSourceLabel("https://wecima.here/watch/ep-6/", "Browser Stream", durationSeconds = 45 * 60 + 12)
        )
        assertEquals(
            "YouTube · 1:02:05",
            formatCleanSourceLabel("https://youtu.be/abc", "YouTube", durationSeconds = 3725L)
        )
    }

    @Test
    fun `formatCleanSourceLabel never leaks Smart Extractor or Browser Stream on invalid URLs`() {
        assertEquals("Web", formatCleanSourceLabel("", "Smart Extractor"))
        assertEquals("Web", formatCleanSourceLabel("not-a-url", "Browser Stream"))
        assertEquals("Web", formatCleanSourceLabel("about:blank", "Live Stream"))
    }

    // -------------------------------------------------------------------------
    // R2: Outer-page CSS/lazy poster scraping, authenticated headers & HLS frame
    // -------------------------------------------------------------------------

    @Test
    fun `HtmlThumbnailExtractor extracts WeCima CSS --img and BG--Poster and Poster--Single-begin posters`() {
        val htmlWithCssVar = """
            <html><body>
              <div class="Poster--Single-begin" style="--img:url('https://cdn.wecima.here/posters/ep6.webp')"></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://cdn.wecima.here/posters/ep6.webp",
            HtmlThumbnailExtractor.extractThumbnail(
                org.jsoup.Jsoup.parse(htmlWithCssVar, "https://wecima.here/watch/ep-6/"),
                "https://wecima.here/watch/ep-6/"
            )
        )

        val htmlWithBgPoster = """
            <html><body>
              <div class="BG--Poster" data-lazy-style="background-image: url('/uploads/series-poster.jpg')"></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://wecima.here/uploads/series-poster.jpg",
            HtmlThumbnailExtractor.extractThumbnail(
                org.jsoup.Jsoup.parse(htmlWithBgPoster, "https://wecima.here/watch/ep-6/"),
                "https://wecima.here/watch/ep-6/"
            )
        )

        // Ensure HeadlessWebExtractor JS scraper also includes WeCima CSS classes, --img, and srcset
        assertTrue(HeadlessWebExtractor.METADATA_SCRAPE_JS.contains(".Poster--Single-begin"))
        assertTrue(HeadlessWebExtractor.METADATA_SCRAPE_JS.contains(".BG--Poster"))
        assertTrue(HeadlessWebExtractor.METADATA_SCRAPE_JS.contains("--img"))
        assertTrue(HeadlessWebExtractor.METADATA_SCRAPE_JS.contains("srcset"))
    }

    @Test
    fun `DownloadThumbnailCache attaches CookieManager cf_clearance and outer page Referer on cross-origin embed streams`() {
        val prevUa = HeadlessWebExtractor.lastWebViewUserAgent
        try {
            HeadlessWebExtractor.lastWebViewUserAgent = "Mozilla/5.0 TestWebView/139.0"
            DownloadThumbnailCache.cookieLookupForTests = { url ->
                if (url.contains("wecima.here")) "cf_clearance=abc123; session=xyz" else null
            }

            // Stream headers came from a promoted cross-origin embed iframe (vidhide), NOT wecima.here
            val embedStreamHeaders = mapOf(
                "Referer" to "https://vidhide.example/e/video99",
                "Origin" to "https://vidhide.example",
                "Cookie" to "embed_only=bad"
            )
            val thumbHeaders = DownloadThumbnailCache.buildThumbnailHeaders(
                sourceUrl = "https://wecima.here/watch/ep-6/",
                headers = embedStreamHeaders,
                thumbnailUrl = "https://cdn.wecima.here/posters/ep6.jpg"
            )

            assertEquals("https://wecima.here/watch/ep-6/", thumbHeaders["Referer"])
            assertEquals("https://wecima.here", thumbHeaders["Origin"])
            assertEquals("cf_clearance=abc123; session=xyz", thumbHeaders["Cookie"])
            assertEquals("Mozilla/5.0 TestWebView/139.0", thumbHeaders["User-Agent"])
        } finally {
            HeadlessWebExtractor.lastWebViewUserAgent = prevUa
        }
    }

    @Test
    fun `HLS mid-download frame fallback concatenates fMP4 init plus media segment and cleans up temp probe`() = runBlocking {
        val tempRoot = Files.createTempDirectory("hls_fmp4_thumb_test").toFile()
        try {
            val thumbsDir = File(tempRoot, "thumbs").apply { mkdirs() }
            val partsDir = File(tempRoot, "hls_parts_dl_42_raw.ts").apply { mkdirs() }

            // Write an fMP4 init segment (ftyp + moov, NO mdat)
            val initBytes = ByteArray(64)
            "ftyp".toByteArray(Charsets.US_ASCII).copyInto(initBytes, destinationOffset = 4)
            "moov".toByteArray(Charsets.US_ASCII).copyInto(initBytes, destinationOffset = 24)
            val seg0 = File(partsDir, "seg_0.part").apply { writeBytes(initBytes) }

            assertTrue("seg_0.part with ftyp+moov and no mdat must be detected as fMP4 init", DownloadManager.looksLikeFmp4InitSegment(seg0))
            assertFalse("Should wait until seg_1.part exists before probing init-only fMP4", DownloadManager.hasHlsThumbProbeCandidate(listOf(partsDir)))

            // Write seg_1.part with moof + mdat payload
            val mediaBytes = ByteArray(128)
            "moof".toByteArray(Charsets.US_ASCII).copyInto(mediaBytes, destinationOffset = 4)
            "mdat".toByteArray(Charsets.US_ASCII).copyInto(mediaBytes, destinationOffset = 32)
            File(partsDir, "seg_1.part").writeBytes(mediaBytes)

            assertTrue("Should be ready once seg_1.part exists alongside fMP4 init", DownloadManager.hasHlsThumbProbeCandidate(listOf(partsDir)))

            var probedSize = 0L
            var probedName = ""
            val cache = DownloadThumbnailCache(
                thumbsDir = thumbsDir,
                client = OkHttpClient(),
                frameExtractor = { videoFile, destJpeg ->
                    probedSize = videoFile.length()
                    probedName = videoFile.name
                    val bytes = videoFile.readBytes()
                    // Verify concatenated probe contains BOTH ftyp (from init) and mdat (from seg_1)
                    val asText = bytes.toString(Charsets.ISO_8859_1)
                    if (asText.contains("ftyp") && asText.contains("mdat")) {
                        destJpeg.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                        true
                    } else {
                        false
                    }
                }
            )

            val extracted = DownloadManager.extractHlsMidDownloadFrame(42L, listOf(partsDir), cache)
            assertNotNull("Expected concatenated fMP4 probe to produce cached thumbnail", extracted)
            assertTrue(extracted!!.exists() && extracted.length() > 0L)
            assertEquals(initBytes.size + mediaBytes.size.toLong(), probedSize)
            assertTrue("Temp probe must use .mp4 extension", probedName.endsWith(".mp4"))

            val lingeringTemps = partsDir.listFiles()?.filter { it.name.startsWith(".thumb_probe_") }.orEmpty()
            assertTrue("Temporary probe file must be deleted after extraction", lingeringTemps.isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `HLS mid-download frame fallback retries later TS segment when seg_0 fails`() = runBlocking {
        val tempRoot = Files.createTempDirectory("hls_ts_thumb_test").toFile()
        try {
            val thumbsDir = File(tempRoot, "thumbs").apply { mkdirs() }
            val partsDir = File(tempRoot, "hls_parts_dl_99_raw.ts").apply { mkdirs() }

            // Write 2 MPEG-TS segments (sync byte 0x47 at offsets 0 and 188)
            val tsBytes0 = ByteArray(376).apply { this[0] = 0x47; this[188] = 0x47; this[10] = 0x01 }
            val tsBytes1 = ByteArray(376).apply { this[0] = 0x47; this[188] = 0x47; this[10] = 0x02 }
            File(partsDir, "seg_0.part").writeBytes(tsBytes0)
            File(partsDir, "seg_1.part").writeBytes(tsBytes1)

            val attemptedFiles = mutableListOf<String>()
            val cache = DownloadThumbnailCache(
                thumbsDir = thumbsDir,
                client = OkHttpClient(),
                frameExtractor = { videoFile, destJpeg ->
                    attemptedFiles += videoFile.name
                    val bytes = videoFile.readBytes()
                    // Fail seg_0 (marker 0x01), succeed on seg_1 (marker 0x02) when probed with .ts extension
                    if (bytes.size > 10 && bytes[10] == 0x02.toByte() && videoFile.name.endsWith(".ts")) {
                        destJpeg.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                        true
                    } else {
                        false
                    }
                }
            )

            val extracted = DownloadManager.extractHlsMidDownloadFrame(99L, listOf(partsDir), cache)
            assertNotNull("Expected fallback to seg_1 .ts probe to succeed", extracted)
            assertTrue("Should have tried seg_0 before falling back to seg_1: $attemptedFiles", attemptedFiles.size >= 3)
            assertTrue("Temp probes must be cleaned up", partsDir.listFiles()?.none { it.name.startsWith(".thumb_probe_") } == true)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // R3: Compact DownloadRow quality prefix (no "High quality (720p)" clipping)
    // -------------------------------------------------------------------------

    @Test
    fun `formatCompactQualityBadge and formatDownloadPrimaryStatus use compact quality labels`() {
        assertEquals("720p", formatCompactQualityBadge("High quality (720p)", MediaType.VIDEO, "mp4"))
        assertEquals("1080p", formatCompactQualityBadge("1080p FHD", MediaType.VIDEO, "mp4"))
        assertEquals("480p", formatCompactQualityBadge("Fast (480p)", MediaType.VIDEO, "mp4"))
        assertEquals("360p", formatCompactQualityBadge("384p", MediaType.VIDEO, "mp4"))
        assertEquals("MP3", formatCompactQualityBadge("Classic MP3", MediaType.AUDIO, "mp3"))
        assertEquals("M4A", formatCompactQualityBadge("Fast", MediaType.AUDIO, "m4a"))
        assertEquals("MP4", formatCompactQualityBadge("", MediaType.VIDEO, "mp4"))

        val pausedItem = DownloadItem(
            id = 1L,
            title = "Episode 6",
            sourceUrl = "https://wecima.here/watch/ep-6/",
            remoteUrl = "https://cdn.example/master.m3u8",
            type = MediaType.VIDEO,
            container = "mp4",
            qualityLabel = "High quality (720p)",
            status = DownloadStatus.PAUSED,
            downloadedBytes = 17_800_000L,
            totalBytes = 161_800_000L,
            isHls = true
        )
        assertEquals("720p · Paused (11%)", formatDownloadPrimaryStatus(pausedItem))

        val runningHlsItem = pausedItem.copy(status = DownloadStatus.RUNNING)
        val runningPrimary = formatDownloadPrimaryStatus(runningHlsItem)
        assertTrue("Expected compact '720p · ' prefix, got: $runningPrimary", runningPrimary.startsWith("720p · "))
        assertFalse("Verbose 'High quality' must not appear in DownloadRow status: $runningPrimary", runningPrimary.contains("High quality"))
        assertTrue("Expected percentage visible in DownloadRow status: $runningPrimary", runningPrimary.endsWith("· 11%"))
    }
}
