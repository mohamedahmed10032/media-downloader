package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import com.melmeligy.mediadownloader.extractor.HlsExtractor
import com.melmeligy.mediadownloader.ui.quality.prepareSnaptubeStreams
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Verifies exact MB / byte counting without 5x–6x hallucinations across:
 * 1. Single-file `#EXT-X-MAP` + `#EXT-X-BYTERANGE` HLS playlists (both explicit and implicit offsets).
 * 2. Multi-worker HLS segment downloads with uneven segment sizes (monotonic `downloaded <= total`).
 * 3. `SegmentedDownloader` servers that ignore `Range` headers and return HTTP `200 OK` (clean 1x fallback).
 * 4. Synthesized audio (`M4A` / `MP3`) picking the lightest progressive video source rather than 1080p/4K.
 */
class DownloadCounterAccuracyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun byteRangeHls_computesExactSize_sendsRangeHeaders_andNeverHallucinatesFullFileSize() = runTest {
        val backingMp4 = ByteArray(5_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        // Exact slices: init map 100@0 + seg0 200@100 + seg1 300 (implicit @300) + seg2 250 (implicit @600) = 850 bytes total
        val playlistUrl = "https://cdn.tube.example/hls/1080p.h264.mp4.m3u8"
        val mediaUrl = "https://cdn.tube.example/hls/1080p.h264.mp4"
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="1080p.h264.mp4",BYTERANGE="100@0"
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:200@100
            1080p.h264.mp4
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:300
            1080p.h264.mp4
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:250
            1080p.h264.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val observedRanges = Collections.synchronizedList(mutableListOf<String>())

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url == playlistUrl) {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                        .build()
                }
                val range = req.header("Range")
                if (range != null) observedRanges += range
                val m = Regex("""bytes=(\d+)-(\d+)""").find(range.orEmpty())
                if (m != null) {
                    val start = m.groupValues[1].toInt()
                    val end = m.groupValues[2].toInt()
                    val slice = backingMp4.copyOfRange(start, end + 1)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/${backingMp4.size}")
                        .body(slice.toResponseBody("video/mp4".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(backingMp4.toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val hlsExtractor = HlsExtractor(http)

        // 1. HlsExtractor must report exact 850 bytes (100 + 200 + 300 + 250), NEVER 4 * 5000 = 20000 bytes!
        val extracted = hlsExtractor.extract(playlistUrl, emptyMap(), heightHint = 1080)
        assertEquals(850L, extracted?.streams?.single()?.sizeBytes)

        // 2. HlsSegmentDownloader must download exact 850 bytes and keep progress monotonically <= total
        val downloader = HlsSegmentDownloader(client, http)
        val dest = File(tempFolder.root, "byterange_out.mp4")
        val updates = Collections.synchronizedList(mutableListOf<DownloadProgress>())

        downloader.download(
            playlistUrl = playlistUrl,
            headers = emptyMap(),
            dest = dest,
            expectedTotalBytes = 850L,
            onProgress = { updates += it }
        )

        assertEquals(850L, dest.length())
        assertArrayEquals(backingMp4.copyOfRange(0, 850), dest.readBytes())
        assertTrue(observedRanges.containsAll(listOf("bytes=0-99", "bytes=100-299", "bytes=300-599", "bytes=600-849")))

        var prevDownloaded = 0L
        for (p in updates) {
            assertTrue("Downloaded (${p.downloaded}) must never decrease below $prevDownloaded", p.downloaded >= prevDownloaded)
            assertTrue("Downloaded (${p.downloaded}) must never exceed total (${p.total})", p.downloaded <= p.total)
            prevDownloaded = p.downloaded
        }
        assertEquals(850L, updates.last().downloaded)
        assertEquals(850L, updates.last().total)
    }

    @Test
    fun multiWorkerHls_neverSpikesCounterOrExceedsTotalAcrossUnevenSegments() = runTest {
        val segmentSizes = listOf(1200, 4800, 900, 7500, 3100, 2200, 6400, 1500, 5100, 2800)
        val expectedTotal = segmentSizes.sum().toLong()
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:6")
            segmentSizes.forEachIndexed { idx, _ ->
                appendLine("#EXTINF:6.0,")
                appendLine("seg_$idx.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.endsWith(".m3u8")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                        .build()
                } else {
                    val idx = Regex("""seg_(\d+)\.ts""").find(url)!!.groupValues[1].toInt()
                    val payload = ByteArray(segmentSizes[idx]) { (idx + 1).toByte() }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(payload.toResponseBody("video/mp2t".toMediaType()))
                        .build()
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val downloader = HlsSegmentDownloader(client, http)
        val dest = File(tempFolder.root, "uneven.ts")
        val updates = Collections.synchronizedList(mutableListOf<DownloadProgress>())

        downloader.download("https://cdn.example.com/uneven.m3u8", emptyMap(), dest, onProgress = { updates += it })

        assertEquals(expectedTotal, dest.length())
        var lastDown = 0L
        for (u in updates) {
            assertTrue("Progress went backwards: $lastDown -> ${u.downloaded}", u.downloaded >= lastDown)
            assertTrue("Progress exceeded total: ${u.downloaded} > ${u.total}", u.downloaded <= u.total)
            lastDown = u.downloaded
        }
        assertEquals(expectedTotal, updates.last().downloaded)
        assertEquals(expectedTotal, updates.last().total)
    }

    @Test
    fun segmentedDownloader_fallsBackToSingleStreamWhenServerIgnoresRangeWithHttp200_no6xHallucination() = runTest {
        // Must exceed Constants.MIN_SEGMENT_SIZE_BYTES (1 MiB) so segmented mode is entered
        val payloadSize = 1_200_000
        val fullPayload = ByteArray(payloadSize) { (it % 251).toByte() }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                // Misbehaving CDN: always returns 200 OK + full file even when `Range: bytes=...` is sent!
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", payloadSize.toString())
                    .header("Accept-Ranges", "bytes")
                    .body(fullPayload.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val http = ExtractorHttp(client)
        val segmented = SegmentedDownloader(client, http)
        val dest = File(tempFolder.root, "misbehaving_cdn.mp4")
        val updates = Collections.synchronizedList(mutableListOf<DownloadProgress>())

        segmented.download(
            url = "https://cdn.example.com/misbehaving_cdn.mp4",
            headers = emptyMap(),
            dest = dest,
            knownTotal = payloadSize.toLong(),
            acceptsRanges = true,
            segmentCount = 6,
            onProgress = { updates += it }
        )

        // Must equal 1x payloadSize (1.2 MB), NEVER 6x (7.2 MB)!
        assertEquals(payloadSize.toLong(), dest.length())
        assertArrayEquals(fullPayload, dest.readBytes())
        assertTrue(updates.all { it.downloaded <= it.total })
        assertEquals(payloadSize.toLong(), updates.last().downloaded)
        assertEquals(payloadSize.toLong(), updates.last().total)
    }

    @Test
    fun prepareSnaptubeStreams_picksLightestProgressiveVideoForSynthesizedAudio_andEstimatesAudioSize() {
        val resolved = ResolvedMedia(
            sourceUrl = "https://www.pornhub.com/view_video.php?viewkey=ph123",
            title = "Sample Video",
            streams = listOf(
                MediaStream(
                    id = "v1080",
                    url = "https://cdn.example.com/1080p.m3u8",
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "1080p",
                    height = 1080,
                    sizeBytes = 180_000_000L,
                    isHls = true,
                    headers = mapOf("X-Internal-Duration-Seconds" to "300")
                ),
                MediaStream(
                    id = "v720",
                    url = "https://cdn.example.com/720p.mp4",
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "720p",
                    height = 720,
                    sizeBytes = 75_000_000L,
                    isHls = false,
                    headers = mapOf("X-Internal-Duration-Seconds" to "300")
                ),
                MediaStream(
                    id = "v240",
                    url = "https://cdn.example.com/240p.mp4",
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "240p",
                    height = 240,
                    sizeBytes = 14_000_000L,
                    isHls = false,
                    headers = mapOf("X-Internal-Duration-Seconds" to "300")
                )
            ),
            extractor = "Web page"
        )

        val prepared = prepareSnaptubeStreams(resolved)
        assertEquals(2, prepared.music.size)
        // Must pick progressive 240p (14 MB), NEVER 1080p HLS (180 MB)!
        assertTrue(prepared.music.all { it.url == "https://cdn.example.com/240p.mp4" && !it.isHls })

        val m4a = prepared.music.first { it.container == "m4a" }
        val mp3 = prepared.music.first { it.container == "mp3" }
        // 300s * 128 kbps = 4,800,000 bytes (~4.8 MB); 300s * 160 kbps = 6,000,000 bytes (~6.0 MB)
        assertEquals(4_800_000L, m4a.sizeBytes)
        assertEquals(6_000_000L, mp3.sizeBytes)
    }
}
