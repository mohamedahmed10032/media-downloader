package com.melmeligy.mediadownloader.download

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.util.JsonUtils
import com.melmeligy.mediadownloader.core.util.UrlUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Downloads and caches remote video thumbnails to local app storage using the stream's request
 * headers (User-Agent, Referer, Origin, Cookie), and falls back to extracting a local JPEG frame
 * from playable video data when no remote thumbnail is available or fetchable.
 */
@Singleton
class DownloadThumbnailCache internal constructor(
    private val thumbsDir: File,
    private val client: OkHttpClient,
    private val frameExtractor: (File, File) -> Boolean,
    private val remoteFrameExtractor: (String, Map<String, String>, File) -> Boolean,
    private val maxCacheBytes: Long,
    private val maxEntries: Int
) {

    internal constructor(
        thumbsDir: File,
        client: OkHttpClient,
        frameExtractor: (File, File) -> Boolean,
        remoteFrameExtractor: (String, Map<String, String>, File) -> Boolean
    ) : this(
        thumbsDir = thumbsDir,
        client = client,
        frameExtractor = frameExtractor,
        remoteFrameExtractor = remoteFrameExtractor,
        maxCacheBytes = DEFAULT_MAX_CACHE_BYTES,
        maxEntries = DEFAULT_MAX_ENTRIES
    )

    internal constructor(
        thumbsDir: File,
        client: OkHttpClient,
        frameExtractor: (File, File) -> Boolean
    ) : this(
        thumbsDir = thumbsDir,
        client = client,
        frameExtractor = frameExtractor,
        remoteFrameExtractor = ::extractRemoteFrameWithRetriever,
        maxCacheBytes = DEFAULT_MAX_CACHE_BYTES,
        maxEntries = DEFAULT_MAX_ENTRIES
    )

    @Inject
    constructor(
        @ApplicationContext context: Context,
        client: OkHttpClient
    ) : this(
        thumbsDir = File(context.filesDir, "thumbnails"),
        client = client,
        frameExtractor = ::extractFrameWithRetriever,
        remoteFrameExtractor = ::extractRemoteFrameWithRetriever,
        maxCacheBytes = DEFAULT_MAX_CACHE_BYTES,
        maxEntries = DEFAULT_MAX_ENTRIES
    )

    fun thumbnailFile(downloadId: Long): File = File(thumbsDir, "thumb_$downloadId.jpg")

    fun hasCachedThumbnail(downloadId: Long): Boolean {
        val file = thumbnailFile(downloadId)
        val exists = file.exists() && file.length() > 0L
        if (exists) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
        return exists
    }

    /**
     * Ensures [thumbnailUrl] is cached locally on disk for [downloadId].
     * Returns the local [File] on success, or null if no valid image could be fetched.
     */
    suspend fun cacheRemoteThumbnail(
        downloadId: Long,
        thumbnailUrl: String?,
        sourceUrl: String,
        headers: Map<String, String>
    ): File? = withContext(Dispatchers.IO) {
        val existingLocal = resolveExistingLocalFile(thumbnailUrl)
        if (existingLocal != null) return@withContext existingLocal

        val targetFile = thumbnailFile(downloadId)
        if (targetFile.exists() && targetFile.length() > 0L) {
            return@withContext targetFile
        }

        val remoteUrl = thumbnailUrl?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: return@withContext null

        val primaryHeaders = buildThumbnailHeaders(sourceUrl, headers, remoteUrl)
        var bytes = fetchThumbnailBytes(remoteUrl, primaryHeaders)
        if (bytes == null) {
            // Some image CDNs block a cross-domain Referer; retry once with the image host's own origin as Referer
            val thumbOrigin = extractOrigin(remoteUrl)
            val sourceOrigin = extractOrigin(sourceUrl)
            if (!thumbOrigin.isNullOrBlank() && !thumbOrigin.equals(sourceOrigin, ignoreCase = true)) {
                val fallbackHeaders = LinkedHashMap(primaryHeaders).apply {
                    put("Referer", "$thumbOrigin/")
                    put("Origin", thumbOrigin)
                }
                bytes = fetchThumbnailBytes(remoteUrl, fallbackHeaders)
            }
        }
        val validBytes = bytes ?: return@withContext null

        writeAtomically(targetFile, validBytes)?.also {
            evictIfNeeded(protectedDownloadId = downloadId)
        }
    }

    private fun extractOrigin(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(UrlUtils.encodeNonAsciiUrl(rawUrl))
            val scheme = uri.scheme
            val host = uri.host
            if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
            } else null
        }.getOrNull()
    }

    private suspend fun fetchThumbnailBytes(
        remoteUrl: String,
        requestHeaders: Map<String, String>
    ): ByteArray? {
        val reqBuilder = Request.Builder()
            .url(UrlUtils.encodeNonAsciiUrl(remoteUrl))
            .header("X-Internal-Download", "true")
        requestHeaders.forEach { (k, v) ->
            if (!k.startsWith("X-Internal-", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                reqBuilder.header(k, v)
            }
        }

        return runCatching {
            client.executeCancellable(reqBuilder.build()) { response ->
                if (!response.isSuccessful) return@executeCancellable null
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/") || contentType.contains("html") || contentType.contains("json")) {
                    return@executeCancellable null
                }
                val body = response.body ?: return@executeCancellable null
                val contentLen = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLen > MAX_THUMBNAIL_BYTES) return@executeCancellable null
                val data = body.byteStream().use { input -> readBounded(input, MAX_THUMBNAIL_BYTES) }
                if (data == null || data.isEmpty()) return@executeCancellable null
                if (!contentType.startsWith("image/") && !looksLikeImageBytes(data)) {
                    return@executeCancellable null
                }
                if (looksLikeHtmlBytes(data)) return@executeCancellable null
                data
            }
        }.getOrNull()
    }

    /**
     * Extracts a JPEG frame from a remote video stream URL using [remoteFrameExtractor]
     * (backed by `MediaMetadataRetriever.setDataSource(url, headers)`) when no remote poster image is available.
     */
    suspend fun extractRemoteVideoFrameThumbnail(
        downloadId: Long,
        videoUrl: String?,
        sourceUrl: String,
        headers: Map<String, String>
    ): File? = withContext(Dispatchers.IO) {
        val remoteUrl = videoUrl?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: return@withContext null
        val targetFile = thumbnailFile(downloadId)
        if (targetFile.exists() && targetFile.length() > 0L) {
            runCatching { targetFile.setLastModified(System.currentTimeMillis()) }
            return@withContext targetFile
        }

        val requestHeaders = buildThumbnailHeaders(sourceUrl, headers, remoteUrl).toMutableMap().apply {
            remove("Accept")
        }
        thumbsDir.mkdirs()
        val tmpFile = File(thumbsDir, "thumb_${downloadId}_remote.tmp")
        runCatching { tmpFile.delete() }
        val ok = runCatching {
            remoteFrameExtractor(UrlUtils.encodeNonAsciiUrl(remoteUrl), requestHeaders, tmpFile)
        }.getOrDefault(false)
        if (!ok || !tmpFile.exists() || tmpFile.length() <= 0L) {
            runCatching { tmpFile.delete() }
            return@withContext null
        }
        if (!tmpFile.renameTo(targetFile)) {
            runCatching {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }.onFailure {
                runCatching { tmpFile.delete() }
                return@withContext null
            }
        }
        targetFile.takeIf { it.exists() && it.length() > 0L }?.also {
            evictIfNeeded(protectedDownloadId = downloadId)
        }
    }

    /**
     * Extracts a JPEG frame from [videoFile] into the local thumbnail cache for [downloadId].
     */
    suspend fun extractVideoFrameThumbnail(
        downloadId: Long,
        videoFile: File
    ): File? = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() <= 0L) return@withContext null
        val targetFile = thumbnailFile(downloadId)
        if (targetFile.exists() && targetFile.length() > 0L) {
            runCatching { targetFile.setLastModified(System.currentTimeMillis()) }
            return@withContext targetFile
        }

        thumbsDir.mkdirs()
        val tmpFile = File(thumbsDir, "thumb_${downloadId}.tmp")
        runCatching { tmpFile.delete() }
        val ok = runCatching { frameExtractor(videoFile, tmpFile) }.getOrDefault(false)
        if (!ok || !tmpFile.exists() || tmpFile.length() <= 0L) {
            runCatching { tmpFile.delete() }
            return@withContext null
        }
        if (!tmpFile.renameTo(targetFile)) {
            runCatching {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }.onFailure {
                runCatching { tmpFile.delete() }
                return@withContext null
            }
        }
        targetFile.takeIf { it.exists() && it.length() > 0L }?.also {
            evictIfNeeded(protectedDownloadId = downloadId)
        }
    }

    fun deleteThumbnail(downloadId: Long) {
        runCatching { thumbnailFile(downloadId).delete() }
        runCatching { File(thumbsDir, "thumb_${downloadId}.tmp").delete() }
        runCatching { File(thumbsDir, "thumb_${downloadId}_remote.tmp").delete() }
    }

    /**
     * Enforces LRU size and entry-count bounds on [thumbsDir] by deleting oldest cached thumbnails
     * first while preserving [protectedDownloadId] when supplied.
     */
    @Synchronized
    fun evictIfNeeded(protectedDownloadId: Long? = null): Int {
        if (!thumbsDir.exists() || !thumbsDir.isDirectory) return 0
        val protectedName = protectedDownloadId?.let { "thumb_$it.jpg" }
        val files = thumbsDir.listFiles { file ->
            file.isFile && file.name.startsWith("thumb_") && file.name.endsWith(".jpg")
        }?.toMutableList() ?: return 0

        if (files.isEmpty()) return 0
        files.sortWith(compareBy<File>({ it.lastModified() }, { it.name }))

        var totalBytes = files.sumOf { it.length().coerceAtLeast(0L) }
        var count = files.size
        var deleted = 0

        for (candidate in files) {
            if (count <= maxEntries && totalBytes <= maxCacheBytes) break
            if (protectedName != null && candidate.name == protectedName) continue
            val len = candidate.length().coerceAtLeast(0L)
            if (runCatching { candidate.delete() }.getOrDefault(false)) {
                totalBytes = (totalBytes - len).coerceAtLeast(0L)
                count--
                deleted++
            }
        }
        return deleted
    }

    /**
     * Deletes any cached thumbnails in [thumbsDir] whose download ID is not in [keepDownloadIds],
     * along with leftover `.tmp` files.
     */
    @Synchronized
    fun clearUnusedThumbnails(keepDownloadIds: Set<Long>): Int {
        if (!thumbsDir.exists() || !thumbsDir.isDirectory) return 0
        val children = thumbsDir.listFiles() ?: return 0
        var deleted = 0
        for (file in children) {
            if (!file.isFile) continue
            val match = THUMB_ID_REGEX.matchEntire(file.name)
            val id = match?.groupValues?.getOrNull(1)?.toLongOrNull()
            val shouldDelete = when {
                file.name.endsWith(".tmp") -> true
                id != null -> id !in keepDownloadIds
                else -> false
            }
            if (shouldDelete && runCatching { file.delete() }.getOrDefault(false)) {
                deleted++
            }
        }
        return deleted
    }

    private fun writeAtomically(targetFile: File, bytes: ByteArray): File? {
        thumbsDir.mkdirs()
        val tmpFile = File(thumbsDir, "${targetFile.nameWithoutExtension}.tmp")
        return runCatching {
            FileOutputStream(tmpFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            targetFile.takeIf { it.exists() && it.length() > 0L }
        }.onFailure {
            runCatching { tmpFile.delete() }
        }.getOrNull()
    }

    companion object {
        private const val MAX_THUMBNAIL_BYTES = 8 * 1024 * 1024 // 8 MB safety cap
        private const val MAX_FRAME_DIM_PX = 320
        internal const val DEFAULT_MAX_CACHE_BYTES = 50L * 1024L * 1024L
        internal const val DEFAULT_MAX_ENTRIES = 200
        private val THUMB_ID_REGEX = Regex("""^thumb_(\d+)\.jpg$""")

        fun resolveExistingLocalFile(pathOrUrl: String?): File? {
            if (pathOrUrl.isNullOrBlank()) return null
            val trimmed = pathOrUrl.trim()
            val file = when {
                trimmed.startsWith("file://", ignoreCase = true) ->
                    runCatching { File(URI(trimmed)) }.getOrNull()
                trimmed.startsWith("/") -> File(trimmed)
                else -> null
            } ?: return null
            return file.takeIf { it.exists() && it.isFile && it.length() > 0L }
        }

        fun buildThumbnailHeaders(
            sourceUrl: String,
            headers: Map<String, String>,
            thumbnailUrl: String? = null
        ): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var userAgent: String? = null
            var referer: String? = null
            var origin: String? = null
            var cookie: String? = null

            headers.forEach { (k, v) ->
                if (k.startsWith("X-Internal-", ignoreCase = true) || v.isBlank()) return@forEach
                when {
                    k.equals("User-Agent", ignoreCase = true) -> userAgent = v
                    k.equals("Referer", ignoreCase = true) -> referer = v
                    k.equals("Origin", ignoreCase = true) -> origin = v
                    k.equals("Cookie", ignoreCase = true) -> cookie = v
                }
            }

            out["User-Agent"] = userAgent ?: Constants.DEFAULT_USER_AGENT
            val effectiveReferer = referer?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: sourceUrl.takeIf { it.startsWith("http", ignoreCase = true) }
            if (effectiveReferer != null) {
                out["Referer"] = effectiveReferer
                if (origin == null) {
                    runCatching {
                        val uri = URI(UrlUtils.encodeNonAsciiUrl(effectiveReferer))
                        val scheme = uri.scheme
                        val host = uri.host
                        if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                            origin = if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
                        }
                    }
                }
            }
            if (!origin.isNullOrBlank()) {
                out["Origin"] = origin!!
            }

            val effectiveCookie = cookie ?: runCatching {
                val target = thumbnailUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: effectiveReferer
                if (target != null) CookieManager.getInstance().getCookie(target) else null
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (!effectiveCookie.isNullOrBlank()) {
                out["Cookie"] = effectiveCookie
            }

            out["Accept"] = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
            return out
        }

        fun buildThumbnailHeadersFromJson(
            sourceUrl: String,
            headersJson: String?,
            thumbnailUrl: String? = null
        ): Map<String, String> =
            buildThumbnailHeaders(sourceUrl, JsonUtils.jsonToMap(headersJson), thumbnailUrl)

        internal fun looksLikeImageBytes(data: ByteArray): Boolean {
            if (data.size < 4) return false
            // JPEG: FF D8 FF
            if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) return true
            // PNG: 89 50 4E 47
            if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) return true
            // GIF8
            if (data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte()) return true
            // RIFF....WEBP
            if (data.size >= 12 &&
                data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
                data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()
            ) return true
            // BMP: BM
            if (data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte()) return true
            // ISO BMFF (AVIF / HEIC): ....ftyp
            if (data.size >= 12 &&
                data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
                data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte()
            ) return true
            return false
        }

        private fun looksLikeHtmlBytes(data: ByteArray): Boolean {
            val prefix = data.take(64).toByteArray().decodeToString().trimStart().lowercase()
            return prefix.startsWith("<!doctype") || prefix.startsWith("<html") || prefix.startsWith("<head")
        }

        private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }

        private fun extractFrameWithRetriever(videoFile: File, destJpeg: File): Boolean {
            return extractWithRetriever(destJpeg) { retriever ->
                retriever.setDataSource(videoFile.absolutePath)
            }
        }

        private fun extractRemoteFrameWithRetriever(
            videoUrl: String,
            headers: Map<String, String>,
            destJpeg: File
        ): Boolean {
            return extractWithRetriever(destJpeg) { retriever ->
                retriever.setDataSource(videoUrl, headers)
            }
        }

        private inline fun extractWithRetriever(
            destJpeg: File,
            configureDataSource: (MediaMetadataRetriever) -> Unit
        ): Boolean {
            val retriever = MediaMetadataRetriever()
            var bitmap: Bitmap? = null
            var scaled: Bitmap? = null
            return try {
                configureDataSource(retriever)
                bitmap = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                val frame = bitmap ?: return false
                val maxSide = max(frame.width, frame.height)
                val toSave = if (maxSide > MAX_FRAME_DIM_PX && maxSide > 0) {
                    val scale = MAX_FRAME_DIM_PX.toFloat() / maxSide.toFloat()
                    val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
                    val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(frame, w, h, true).also { scaled = it }
                } else {
                    frame
                }
                FileOutputStream(destJpeg).use { out ->
                    toSave.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.flush()
                }
                destJpeg.exists() && destJpeg.length() > 0L
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { if (scaled != null && scaled !== bitmap) scaled?.recycle() }
                runCatching { bitmap?.recycle() }
                runCatching { retriever.release() }
            }
        }
    }
}
