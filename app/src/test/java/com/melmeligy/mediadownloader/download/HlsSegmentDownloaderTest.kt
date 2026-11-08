package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HlsSegmentDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        https://example.com/seg_0.ts
        #EXTINF:10.0,
        https://example.com/seg_1.ts
        #EXTINF:10.0,
        https://example.com/seg_2.ts
        #EXTINF:10.0,
        https://example.com/seg_3.ts
        #EXTINF:10.0,
        https://example.com/seg_4.ts
    """.trimIndent()

    @Test
    fun download_concatenatesSegmentsInStrictSequentialOrder() = runTest {
        val segmentPayloads = mapOf(
            "seg_0.ts" to "SEGMENT_00_CONTENT_AAA",
            "seg_1.ts" to "SEGMENT_01_CONTENT_BBB",
            "seg_2.ts" to "SEGMENT_02_CONTENT_CCC",
            "seg_3.ts" to "SEGMENT_03_CONTENT_DDD",
            "seg_4.ts" to "SEGMENT_04_CONTENT_EEE"
        )

        val concurrentRequests = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val requestHeadersCaptured = ConcurrentHashMap<String, String>()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()

                req.header("X-Internal-Download")?.let {
                    requestHeadersCaptured["X-Internal-Download"] = it
                }

                if (url.endsWith("playlist.m3u8")) {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(mediaPlaylist.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                        .build()
                }

                val current = concurrentRequests.incrementAndGet()
                maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }

                val segmentKey = segmentPayloads.keys.find { url.endsWith(it) }
                val bodyText = segmentKey?.let { segmentPayloads[it] } ?: "UNKNOWN"
                val response = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyText.toByteArray().toResponseBody("video/mp2t".toMediaType()))
                    .build()

                concurrentRequests.decrementAndGet()
                response
            }
            .build()

        val http = ExtractorHttp(client)
        val downloader = HlsSegmentDownloader(client, http)

        val destFile = File(tempFolder.root, "output.ts")
        val progressUpdates = mutableListOf<DownloadProgress>()

        downloader.download(
            playlistUrl = "https://example.com/playlist.m3u8",
            headers = emptyMap(),
            dest = destFile,
            onProgress = { p -> progressUpdates.add(p) }
        )

        // 1. Verify output file exists and has correct concatenated content in order
        assertTrue(destFile.exists())
        val expectedContent = "SEGMENT_00_CONTENT_AAASEGMENT_01_CONTENT_BBBSEGMENT_02_CONTENT_CCCSEGMENT_03_CONTENT_DDDSEGMENT_04_CONTENT_EEE"
        assertEquals(expectedContent, destFile.readText())

        // 2. Verify all temporary parts directories were cleaned up
        val remainingPartDirs = tempFolder.root.listFiles()?.filter { it.name.startsWith("hls_parts_") } ?: emptyList()
        assertTrue("All temporary part directories should be wiped", remainingPartDirs.isEmpty())

        // 3. Verify real-time progress was emitted
        assertTrue("Progress updates should be recorded", progressUpdates.isNotEmpty())
        val lastProgress = progressUpdates.last()
        assertEquals(destFile.length(), lastProgress.downloaded)
        assertEquals(destFile.length(), lastProgress.total)

        // 4. Verify X-Internal-Download header was attached for interceptor bypass
        assertEquals("true", requestHeadersCaptured["X-Internal-Download"])
    }

    @Test
    fun download_rejectsEncryptedStreams() = runTest {
        val encryptedPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key"
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(encryptedPlaylist.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                    .build()
            }
            .build()

        val http = ExtractorHttp(client)
        val downloader = HlsSegmentDownloader(client, http)
        val destFile = File(tempFolder.root, "encrypted.ts")

        try {
            downloader.download("https://example.com/enc.m3u8", emptyMap(), destFile) {}
            fail("Expected MediaException for encrypted stream")
        } catch (e: MediaException) {
            assertTrue(e.message?.contains("encrypted") == true)
        }
    }
}
