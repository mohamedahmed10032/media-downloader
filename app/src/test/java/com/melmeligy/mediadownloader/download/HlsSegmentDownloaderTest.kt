package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import kotlinx.coroutines.launch
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

    @Test
    fun download_cancelsPromptlyWhenTcpStreamStallsMidRead() = kotlinx.coroutines.runBlocking {
        val segRequestReceived = java.util.concurrent.CountDownLatch(1)
        val releaseStalledServer = java.util.concurrent.CountDownLatch(1)
        val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort

        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread {
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            val requestLine = reader.readLine() ?: return@use
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val out = s.getOutputStream()
                            if (requestLine.contains("playlist.m3u8")) {
                                val body = """
                                    #EXTM3U
                                    #EXT-X-TARGETDURATION:10
                                    #EXTINF:10.0,
                                    http://127.0.0.1:$port/seg_0.ts
                                """.trimIndent().toByteArray()
                                out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                                )
                                out.write(body)
                                out.flush()
                            } else {
                                out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n" +
                                        "Content-Length: 1048576\r\nConnection: close\r\n\r\n").toByteArray()
                                )
                                out.write(ByteArray(128) { 0x47 })
                                out.flush()
                                segRequestReceived.countDown()
                                // Stall indefinitely without closing socket until test ends
                                releaseStalledServer.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            }
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            // Use a long 30s read timeout so passing proves OkHttp call cancellation, not timeout expiry.
            val client = OkHttpClient.Builder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val http = ExtractorHttp(client)
            val downloader = HlsSegmentDownloader(client, http)
            val destFile = File(tempFolder.root, "stalled.ts")

            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                downloader.download("http://127.0.0.1:$port/playlist.m3u8", emptyMap(), destFile) {}
            }

            assertTrue("Segment request should reach stalled server", segRequestReceived.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Give InputStream.read() a moment to enter blocking wait
            Thread.sleep(50)

            val startNs = System.nanoTime()
            kotlinx.coroutines.withTimeout(2000L) {
                job.cancel()
                job.join()
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue("Expected prompt cancellation (< 1500ms) instead of 30s read timeout, took ${elapsedMs}ms", elapsedMs < 1500L)

            val remainingPartDirs = tempFolder.root.listFiles()?.filter { it.name.startsWith("hls_parts_") } ?: emptyList()
            assertTrue("Temporary part directories should be cleaned up after cancel", remainingPartDirs.isEmpty())
        } finally {
            releaseStalledServer.countDown()
            runCatching { server.close() }
        }
    }
}
