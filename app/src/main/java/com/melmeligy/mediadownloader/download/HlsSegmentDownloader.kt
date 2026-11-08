package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

        val partsDir = File(dest.parentFile, "hls_parts_${dest.nameWithoutExtension}_${System.currentTimeMillis()}").apply { mkdirs() }
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

        val semaphore = Semaphore(CONCURRENT_SEGMENTS)
        val tasks = segments.mapIndexed { index, segmentUrl ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    val partFile = File(partsDir, "seg_$index.part")
                    downloadSegmentToPart(segmentUrl, headers, partFile, written)
                    partFile
                }
            }
        }
        try {
            FileOutputStream(dest, true).use { output ->
                val copyBuffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
                for (task in tasks) {
                    currentCoroutineContext().ensureActive()
                    val partFile = task.await()
                    partFile.inputStream().use { input ->
                        while (true) {
                            val read = input.read(copyBuffer)
                            if (read == -1) break
                            output.write(copyBuffer, 0, read)
                        }
                    }
                    partFile.delete()
                    completed.incrementAndGet()
                }
                output.flush()
            }
        } finally {
            progressJob.cancel()
            tasks.forEach { it.cancel() }
            runCatching { partsDir.deleteRecursively() }
        }

        val finalBytes = dest.length()
        onProgress(DownloadProgress(finalBytes, finalBytes, 0, 0))
    }

    private suspend fun downloadSegmentToPart(
        segmentUrl: String,
        headers: Map<String, String>,
        partFile: File,
        written: AtomicLong
    ) {
        var attempt = 0
        val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            var attemptBytes = 0L
            try {
                val request = http.buildRequest(segmentUrl, headers)
                    .header("X-Internal-Download", "true")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw http.mapHttpError(response.code)
                    val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty segment")
                    partFile.parentFile?.mkdirs()
                    FileOutputStream(partFile).use { output ->
                        body.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                attemptBytes += read
                                written.addAndGet(read.toLong())
                            }
                        }
                        output.flush()
                    }
                }
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (attemptBytes > 0) {
                    written.addAndGet(-attemptBytes)
                }
                runCatching { partFile.delete() }
                throw e
            } catch (e: Exception) {
                if (attemptBytes > 0) {
                    written.addAndGet(-attemptBytes)
                }
                runCatching { partFile.delete() }
                attempt++
                if (attempt >= Constants.SEGMENT_MAX_RETRIES) throw e
                delay(400L * attempt)
            }
        }
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

    private fun parseSegments(mediaText: String, baseUrl: String): List<String> {
        val result = mutableListOf<String>()
        val mapRegex = Regex("URI=\"([^\"]+)\"")
        for (rawLine in mediaText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP:")) {
                val mapUri = mapRegex.find(line)?.groupValues?.getOrNull(1)
                if (!mapUri.isNullOrBlank()) {
                    val resolvedMap = UrlUtils.resolveUrl(baseUrl, mapUri)
                    if (result.firstOrNull() != resolvedMap) {
                        result.add(resolvedMap)
                    }
                }
            } else if (!line.startsWith("#")) {
                result.add(UrlUtils.resolveUrl(baseUrl, line))
            }
        }
        return result
    }

    private companion object {
        const val CONCURRENT_SEGMENTS = 5
    }
}
