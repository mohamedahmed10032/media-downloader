package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.ui.common.ThumbnailCandidate
import com.melmeligy.mediadownloader.ui.common.resolveThumbnailCandidates
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadThumbnailCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cacheRemoteThumbnail_sendsAuthHeadersAndSavesJpegAtomically() = runBlocking {
        val seenHeaders = ConcurrentHashMap<String, String>()
        val jpegPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                req.headers.names().forEach { seenHeaders[it] = req.header(it).orEmpty() }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/jpeg")
                    .body(jpegPayload.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()

        val thumbsDir = File(tempFolder.root, "thumbnails")
        val cache = DownloadThumbnailCache(thumbsDir, client) { _, _ -> false }

        val saved = cache.cacheRemoteThumbnail(
            downloadId = 11L,
            thumbnailUrl = "https://cdn.example/thumb.jpg",
            sourceUrl = "https://topcinemaa.one/episode-5/watch/",
            headers = mapOf(
                "Cookie" to "session=xyz",
                "X-Internal-Quick" to "true"
            )
        )

        assertNotNull(saved)
        assertTrue(saved!!.exists())
        assertEquals(jpegPayload.size.toLong(), saved.length())
        assertEquals(Constants.DEFAULT_USER_AGENT, seenHeaders["User-Agent"])
        assertEquals("https://topcinemaa.one/episode-5/watch/", seenHeaders["Referer"])
        assertEquals("https://topcinemaa.one", seenHeaders["Origin"])
        assertEquals("session=xyz", seenHeaders["Cookie"])
        assertEquals("true", seenHeaders["X-Internal-Download"])
        assertNull("Internal metadata headers must not leak", seenHeaders["X-Internal-Quick"])
    }

    @Test
    fun cacheRemoteThumbnail_rejectsHtmlChallengeAnd403Responses() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.toString().contains("challenge")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/octet-stream")
                        .body("<!DOCTYPE html><html><body>Just a moment...</body></html>".toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("Forbidden".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()

        val thumbsDir = File(tempFolder.root, "thumbnails_adv")
        val cache = DownloadThumbnailCache(thumbsDir, client) { _, _ -> false }

        assertNull(cache.cacheRemoteThumbnail(12L, "https://cdn.example/challenge.jpg", "https://example.com", emptyMap()))
        assertNull(cache.cacheRemoteThumbnail(13L, "https://cdn.example/forbidden.jpg", "https://example.com", emptyMap()))
        assertFalse(cache.hasCachedThumbnail(12L))
        assertFalse(cache.hasCachedThumbnail(13L))
    }

    @Test
    fun resolveThumbnailCandidates_prefersLocalFile_thenRemote_thenCompletedVideoContentUri() {
        val cachedFile = File(tempFolder.root, "thumb_9.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }

        val withLocal = DownloadItem(
            id = 9L,
            title = "Cached Video",
            sourceUrl = "https://example.com/watch",
            remoteUrl = "https://cdn.example/v.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            qualityLabel = "480p",
            status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED,
            thumbnailUrl = cachedFile.absolutePath,
            contentUri = "content://media/external/video/media/9"
        )
        val c1 = resolveThumbnailCandidates(withLocal)
        assertEquals(2, c1.size)
        assertTrue(c1[0] is ThumbnailCandidate.LocalFile)
        assertTrue(c1[1] is ThumbnailCandidate.ContentUri)

        val withRemoteAndUri = withLocal.copy(thumbnailUrl = "https://cdn.example/poster.jpg")
        val c2 = resolveThumbnailCandidates(withRemoteAndUri)
        assertEquals(2, c2.size)
        assertTrue(c2[0] is ThumbnailCandidate.RemoteUrl)
        assertTrue(c2[1] is ThumbnailCandidate.ContentUri)

        val missingLocalFallsBackToContentUri = withLocal.copy(thumbnailUrl = "/nonexistent/path/thumb_999.jpg")
        val c3 = resolveThumbnailCandidates(missingLocalFallsBackToContentUri)
        assertEquals(1, c3.size)
        assertTrue(c3[0] is ThumbnailCandidate.ContentUri)

        val audioItem = withLocal.copy(type = MediaType.AUDIO, thumbnailUrl = null)
        assertTrue("Audio should not treat contentUri as video frame thumbnail", resolveThumbnailCandidates(audioItem).isEmpty())
    }

    @Test
    fun evictsLeastRecentlyUsedEntriesWhenMaxEntriesOrMaxBytesExceeded() = runBlocking {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { 0x11 }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/jpeg")
                    .body(jpegHeader.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()

        val thumbsDir = File(tempFolder.root, "thumbnails_lru")
        val cache = DownloadThumbnailCache(
            thumbsDir = thumbsDir,
            client = client,
            frameExtractor = { _, _ -> false },
            remoteFrameExtractor = { _, _, _ -> false },
            maxCacheBytes = 512L,
            maxEntries = 2
        )

        cache.cacheRemoteThumbnail(1L, "https://cdn.example/1.jpg", "https://example.com", emptyMap())
        Thread.sleep(15)
        cache.cacheRemoteThumbnail(2L, "https://cdn.example/2.jpg", "https://example.com", emptyMap())
        Thread.sleep(15)
        // Touch item 1 so item 2 becomes the least-recently-used entry
        assertTrue(cache.hasCachedThumbnail(1L))
        Thread.sleep(15)
        cache.cacheRemoteThumbnail(3L, "https://cdn.example/3.jpg", "https://example.com", emptyMap())

        assertTrue("Recently touched entry 1 must survive LRU eviction", cache.hasCachedThumbnail(1L))
        assertFalse("Least recently used entry 2 should be evicted", cache.hasCachedThumbnail(2L))
        assertTrue("Newest entry 3 must be present", cache.hasCachedThumbnail(3L))
    }

    @Test
    fun clearUnusedThumbnails_keepsKnownIdsAndDeletesOrphans() {
        val thumbsDir = File(tempFolder.root, "thumbnails_clean").apply { mkdirs() }
        val valid = ByteArray(16) { 1 }
        File(thumbsDir, "thumb_10.jpg").writeBytes(valid)
        File(thumbsDir, "thumb_20.jpg").writeBytes(valid)
        File(thumbsDir, "thumb_20_remote.tmp").writeBytes(valid)

        val cache = DownloadThumbnailCache(thumbsDir, OkHttpClient()) { _, _ -> false }
        val deleted = cache.clearUnusedThumbnails(keepDownloadIds = setOf(10L))

        assertEquals(2, deleted)
        assertTrue(File(thumbsDir, "thumb_10.jpg").exists())
        assertFalse(File(thumbsDir, "thumb_20.jpg").exists())
        assertFalse(File(thumbsDir, "thumb_20_remote.tmp").exists())
    }
}
