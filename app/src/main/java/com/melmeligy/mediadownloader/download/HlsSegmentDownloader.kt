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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads clear HLS streams (both MPEG-TS `.ts` and fragmented MP4 `#EXT-X-MAP` /
 * `#EXT-X-BYTERANGE` playlists) concurrently and concatenates segments in strict playlist order.
 *
 * Progress & total-byte estimation:
 * - Uses exact byte sum when `#EXT-X-BYTERANGE` lengths are advertised on every segment.
 * - Otherwise tracks completed-segment byte & duration totals atomically upon each segment's
 *   completion (isolated from partial in-flight worker bytes and `#EXT-X-MAP` init headers) so
 *   concurrent workers never inflate the estimated total size by 5x.
 */
@Singleton
class HlsSegmentDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val http: ExtractorHttp
) {

    internal data class HlsSegment(
        val url: String,
        val durationSec: Double,
        val byteRangeOffset: Long? = null,
        val byteRangeLength: Long? = null,
        val isInitMap: Boolean = false
    )

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        dest: File,
        onProgress: ProgressCallback
    ) = download(playlistUrl, headers, dest, 0L, onProgress)

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        dest: File,
        expectedTotalBytes: Long,
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

        val exactByteRangeTotal = if (segments.all { it.byteRangeLength != null && it.byteRangeLength > 0L }) {
            segments.sumOf { it.byteRangeLength ?: 0L }
        } else 0L

        val hintedTotal = expectedTotalBytes.takeIf { it > 0L }
            ?: headers["X-Internal-Expected-Bytes"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: 0L

        val totalMediaSegments = segments.count { !it.isInitMap }
        val totalDurationMs = segments
            .filter { !it.isInitMap && it.durationSec > 0.0 }
            .sumOf { (it.durationSec * 1000.0).toLong() }

        val partsDir = File(
            dest.parentFile,
            "hls_parts_${dest.nameWithoutExtension}_${System.currentTimeMillis()}"
        ).apply { mkdirs() }

        val written = AtomicLong(0L)
        val initMapBytes = AtomicLong(0L)
        val completedMediaSegments = AtomicInteger(0)
        val completedMediaBytes = AtomicLong(0L)
        val completedMediaDurationMs = AtomicLong(0L)
        val smoothedEstimate = AtomicLong(hintedTotal.coerceAtLeast(0L))
        val tracker = SpeedTracker(0L)

        fun computeEstimatedTotal(currentWritten: Long): Long {
            if (exactByteRangeTotal > 0L) {
                return maxOf(exactByteRangeTotal, currentWritten)
            }
            val doneSegs = completedMediaSegments.get()
            val doneBytes = completedMediaBytes.get()
            val doneDurMs = completedMediaDurationMs.get()
            val mapBytes = initMapBytes.get()

            val rawEstimate = when {
                doneSegs > 0 && doneDurMs > 0L && totalDurationMs > 0L ->
                    mapBytes + (doneBytes * totalDurationMs) / doneDurMs
                doneSegs > 0 && totalMediaSegments > 0 ->
                    mapBytes + (doneBytes * totalMediaSegments) / doneSegs
                hintedTotal > 0L ->
                    hintedTotal
                else ->
                    -1L
            }

            if (rawEstimate <= 0L) return -1L

            val blended = if (hintedTotal > 0L && doneSegs in 1..3 && totalMediaSegments > 4) {
                (rawEstimate * doneSegs + hintedTotal * (4 - doneSegs)) / 4L
            } else {
                rawEstimate
            }

            // Smooth VBR oscillations using EMA and clamp >= currentWritten so progress never exceeds 100%
            val prev = smoothedEstimate.get()
            val next = if (prev <= 0L) {
                blended
            } else {
                ((prev * 7L) + (blended * 3L)) / 10L
            }
            val remainingSegs = (totalMediaSegments - doneSegs).coerceAtLeast(0)
            val floorWithRemaining = if (remainingSegs > 0 && doneSegs > 0) {
                maxOf(currentWritten + 1L, next)
            } else {
                maxOf(currentWritten, next)
            }
            smoothedEstimate.set(floorWithRemaining)
            return floorWithRemaining
        }

        val progressJob = launch {
            while (isActive) {
                delay(Constants.PROGRESS_THROTTLE_MS)
                val bytes = written.get()
                val estTotal = computeEstimatedTotal(bytes)
                val speed = tracker.sample(bytes)
                val eta = if (speed > 0 && estTotal > 0) ((estTotal - bytes) / speed).coerceAtLeast(0) else -1L
                onProgress(DownloadProgress(bytes, estTotal, speed, eta))
            }
        }

        val cdnLimit = Regex("""(?:[?&/,;])limit=(\d+)(?:[&#/,;]|$)""", RegexOption.IGNORE_CASE)
            .find(mediaUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        val maxConcurrent = if (cdnLimit != null) minOf(CONCURRENT_SEGMENTS, cdnLimit) else CONCURRENT_SEGMENTS
        val semaphore = Semaphore(maxConcurrent)
        val tasks = segments.mapIndexed { index, segment ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    val partFile = File(partsDir, "seg_$index.part")
                    val segBytes = downloadSegmentToPart(segment, headers, partFile, written)
                    if (segment.isInitMap) {
                        initMapBytes.addAndGet(segBytes)
                    } else {
                        completedMediaBytes.addAndGet(segBytes)
                        if (segment.durationSec > 0.0) {
                            completedMediaDurationMs.addAndGet((segment.durationSec * 1000.0).toLong())
                        }
                        completedMediaSegments.incrementAndGet()
                    }
                    partFile
                }
            }
        }
        var succeeded = false
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
                }
                output.flush()
            }
            succeeded = true
        } finally {
            progressJob.cancel()
            tasks.forEach { it.cancel() }
            runCatching { partsDir.deleteRecursively() }
            if (!succeeded) {
                runCatching { dest.delete() }
            }
        }

        val finalBytes = dest.length()
        onProgress(DownloadProgress(finalBytes, finalBytes, 0, 0))
    }

    private suspend fun downloadSegmentToPart(
        segment: HlsSegment,
        headers: Map<String, String>,
        partFile: File,
        written: AtomicLong
    ): Long {
        var attempt = 0
        val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            var attemptBytes = 0L
            try {
                val reqBuilder = http.buildRequest(segment.url, headers)
                    .header("X-Internal-Download", "true")

                val rangeLen = segment.byteRangeLength
                val rangeStart = segment.byteRangeOffset ?: 0L
                if (rangeLen != null && rangeLen > 0L) {
                    val rangeEnd = rangeStart + rangeLen - 1L
                    reqBuilder.header("Range", "bytes=$rangeStart-$rangeEnd")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        throw http.mapHttpError(response.code)
                    }
                    val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty segment")
                    val serverIgnoredRange = rangeLen != null && rangeLen > 0L && response.code == 200

                    partFile.parentFile?.mkdirs()
                    FileOutputStream(partFile).use { output ->
                        body.byteStream().use { input ->
                            if (serverIgnoredRange && rangeStart > 0L) {
                                skipFully(input, rangeStart)
                            }
                            var remaining = if (rangeLen != null && rangeLen > 0L) rangeLen else Long.MAX_VALUE
                            while (remaining > 0L) {
                                currentCoroutineContext().ensureActive()
                                val maxToRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = input.read(buffer, 0, maxToRead)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                attemptBytes += read
                                remaining -= read
                                written.addAndGet(read.toLong())
                            }
                        }
                        output.flush()
                    }
                }
                return attemptBytes
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (attemptBytes > 0) {
                    written.addAndGet(-attemptBytes)
                }
                runCatching { partFile.delete() }
                throw e
            } catch (e: MediaException) {
                if (attemptBytes > 0) {
                    written.addAndGet(-attemptBytes)
                }
                runCatching { partFile.delete() }
                if (e.error == AppError.GEO_BLOCKED || e.error == AppError.NO_MEDIA_FOUND || e.error == AppError.EXPIRED_LINK) {
                    throw e
                }
                attempt++
                if (attempt >= Constants.SEGMENT_MAX_RETRIES) throw e
                delay(400L * attempt)
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

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(8192)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read == -1) break
                remaining -= read
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

    internal fun parseSegments(mediaText: String, baseUrl: String): List<HlsSegment> {
        val result = mutableListOf<HlsSegment>()
        val mapUriRegex = Regex("""URI="([^"]+)"""")
        val mapRangeRegex = Regex("""BYTERANGE="(\d+)(?:@(\d+))?"""")
        val byteRangeRegex = Regex("""#EXT-X-BYTERANGE:\s*(\d+)(?:@(\d+))?""", RegexOption.IGNORE_CASE)
        val extInfRegex = Regex("""#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

        var pendingDuration = 0.0
        var pendingRangeLen: Long? = null
        var pendingRangeOffset: Long? = null
        val nextImplicitOffsetByUri = HashMap<String, Long>()
        var lastInitKey: String? = null

        for (rawLine in mediaText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val mapUri = mapUriRegex.find(line)?.groupValues?.getOrNull(1)
                    if (!mapUri.isNullOrBlank()) {
                        val resolvedMap = UrlUtils.resolveUrl(baseUrl, mapUri)
                        val rangeMatch = mapRangeRegex.find(line)
                        val rLen = rangeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                        val rOff = rangeMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                            ?: if (rLen != null) 0L else null
                        val initKey = "$resolvedMap|${rOff ?: -1}|${rLen ?: -1}"
                        if (initKey != lastInitKey) {
                            lastInitKey = initKey
                            result.add(
                                HlsSegment(
                                    url = resolvedMap,
                                    durationSec = 0.0,
                                    byteRangeOffset = rOff,
                                    byteRangeLength = rLen,
                                    isInitMap = true
                                )
                            )
                        }
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = extInfRegex.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val m = byteRangeRegex.find(line)
                    pendingRangeLen = m?.groupValues?.getOrNull(1)?.toLongOrNull()
                    pendingRangeOffset = m?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                }
                !line.startsWith("#") -> {
                    val resolvedUri = UrlUtils.resolveUrl(baseUrl, line)
                    val len = pendingRangeLen
                    val offset = if (len != null) {
                        val explicit = pendingRangeOffset ?: nextImplicitOffsetByUri[resolvedUri] ?: 0L
                        nextImplicitOffsetByUri[resolvedUri] = explicit + len
                        explicit
                    } else null

                    result.add(
                        HlsSegment(
                            url = resolvedUri,
                            durationSec = pendingDuration,
                            byteRangeOffset = offset,
                            byteRangeLength = len,
                            isInitMap = false
                        )
                    )
                    pendingDuration = 0.0
                    pendingRangeLen = null
                    pendingRangeOffset = null
                }
            }
        }
        return result
    }

    private companion object {
        const val CONCURRENT_SEGMENTS = 5
    }
}
