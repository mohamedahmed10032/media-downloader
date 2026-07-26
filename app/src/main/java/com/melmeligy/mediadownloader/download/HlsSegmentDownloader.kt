package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an HLS stream by fetching its MPEG-TS media segments in order and concatenating
 * them into a single .ts file (valid MPEG-TS concatenation). Progress is estimated from the
 * completed-segment ratio.
 *
 * Scope & honesty: this handles clear (non-encrypted) MPEG-TS HLS. Encrypted/DRM streams and
 * fMP4 (`#EXT-X-MAP`) segment formats are explicitly declined with a clear message rather
 * than attempting to bypass protection or produce a broken file.
 */
@Singleton
class HlsSegmentDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val http: ExtractorHttp
) {

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        dest: File,
        onProgress: ProgressCallback
    ) = coroutineScope {
        var mediaUrl = playlistUrl
        var text = http.fetchString(playlistUrl, headers)

        if (text.contains("#EXT-X-STREAM-INF")) {
            mediaUrl = firstVariantUrl(text, playlistUrl)
                ?: throw MediaException(AppError.NO_MEDIA_FOUND, "No HLS variant found")
            text = http.fetchString(mediaUrl, headers)
        }

        if (text.contains("#EXT-X-MAP")) {
            throw MediaException(
                AppError.GENERIC,
                "This HLS stream uses fMP4 segments, which can't be merged without a muxer."
            )
        }
        if (isEncrypted(text)) {
            throw MediaException(
                AppError.GENERIC,
                "This stream is encrypted (DRM) and cannot be downloaded."
            )
        }

        val segments = parseSegments(text, mediaUrl)
        if (segments.isEmpty()) throw MediaException(AppError.NO_MEDIA_FOUND, "No HLS segments found")

        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val written = AtomicLong(0)
        val completed = AtomicInteger(0)
        val totalSegments = segments.size
        val tracker = SpeedTracker(0)

        val progressJob = launch {
            while (isActive) {
                delay(Constants.PROGRESS_THROTTLE_MS)
                val bytes = written.get()
                val done = completed.get()
                val estTotal = if (done > 0) bytes * totalSegments / done else -1L
                val speed = tracker.sample(bytes)
                val eta = if (speed > 0 && estTotal > 0) ((estTotal - bytes) / speed).coerceAtLeast(0) else -1L
                onProgress(DownloadProgress(bytes, estTotal, speed, eta))
            }
        }

        try {
            FileOutputStream(dest, true).use { output ->
                val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
                for (segmentUrl in segments) {
                    currentCoroutineContext().ensureActive()
                    val request = http.buildRequest(segmentUrl, headers).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw http.mapHttpError(response.code)
                        val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty segment")
                        body.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written.addAndGet(read.toLong())
                            }
                        }
                    }
                    completed.incrementAndGet()
                }
                output.flush()
            }
        } finally {
            progressJob.cancel()
        }

        val finalBytes = written.get()
        onProgress(DownloadProgress(finalBytes, finalBytes, 0, 0))
    }

    private fun firstVariantUrl(masterText: String, baseUrl: String): String? {
        val lines = masterText.lines()
        var bestBandwidth = -1
        var bestUrl: String? = null
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                var uriLine = index + 1
                while (uriLine < lines.size &&
                    (lines[uriLine].trim().isEmpty() || lines[uriLine].trim().startsWith("#"))
                ) uriLine++
                if (uriLine < lines.size && bandwidth >= bestBandwidth) {
                    bestBandwidth = bandwidth
                    bestUrl = UrlUtils.resolveUrl(baseUrl, lines[uriLine].trim())
                }
                index = uriLine
            }
            index++
        }
        return bestUrl
    }

    private fun isEncrypted(mediaText: String): Boolean =
        mediaText.lineSequence().any {
            val line = it.trim()
            line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE")
        }

    private fun parseSegments(mediaText: String, baseUrl: String): List<String> =
        mediaText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { UrlUtils.resolveUrl(baseUrl, it) }
            .toList()
}
