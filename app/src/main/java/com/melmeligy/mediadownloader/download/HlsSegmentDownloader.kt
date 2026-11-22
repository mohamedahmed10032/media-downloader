package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import com.melmeligy.mediadownloader.extractor.HlsEncryptionMethod
import com.melmeligy.mediadownloader.extractor.HlsKeySpec
import com.melmeligy.mediadownloader.extractor.HlsParsedSegment
import com.melmeligy.mediadownloader.extractor.HlsPlaylistSanitizer
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads HLS streams (MPEG-TS `.ts`, fragmented MP4 `#EXT-X-MAP` / `#EXT-X-BYTERANGE`,
 * and standard RFC 8216 clear `#EXT-X-KEY:METHOD=AES-128` playlists) concurrently and
 * concatenates segments in strict playlist order.
 *
 * - Strips injected `#EXT-X-DISCONTINUITY` ad/bumper blocks via [HlsPlaylistSanitizer].
 * - Strictly rejects DRM (`SAMPLE-AES*`, non-identity `KEYFORMAT`) and missing/invalid keys.
 * - Forwards exact captured headers (`Referer`, `Origin`, `User-Agent`, `Cookie`) on playlist,
 *   key, and segment requests.
 * - Retries transient `429`/`5xx`/network errors cleanly while respecting configured retry counts.
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
        val isInitMap: Boolean = false,
        val keySpec: HlsKeySpec = HlsKeySpec(),
        val mediaSequence: Long = 0L,
        val discontinuityBlock: Int = 0
    )

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        dest: File,
        onProgress: ProgressCallback
    ) = download(playlistUrl, headers, dest, 0L, Constants.SEGMENT_MAX_RETRIES, onProgress)

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        dest: File,
        expectedTotalBytes: Long,
        onProgress: ProgressCallback
    ) = download(playlistUrl, headers, dest, expectedTotalBytes, Constants.SEGMENT_MAX_RETRIES, onProgress)

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        dest: File,
        expectedTotalBytes: Long,
        maxRetries: Int,
        onProgress: ProgressCallback
    ) = coroutineScope {
        var mediaUrl = playlistUrl
        var text = http.fetchString(playlistUrl, headers)

        if (text.contains("#EXT-X-STREAM-INF")) {
            mediaUrl = firstVariantUrl(text, playlistUrl)
                ?: throw MediaException(AppError.NO_MEDIA_FOUND, "No HLS variant found")
            text = http.fetchString(mediaUrl, headers)
        }

        val sanitized = HlsPlaylistSanitizer.parseAndSanitize(text, mediaUrl)
        if (sanitized.hasUnsupportedDrm) {
            throw MediaException(
                AppError.GENERIC,
                "This stream is encrypted (DRM) and cannot be downloaded."
            )
        }

        val segments = sanitized.segments.map { it.toHlsSegment() }
        if (segments.isEmpty()) throw MediaException(AppError.NO_MEDIA_FOUND, "No HLS segments found")

        // Fetch any distinct RFC 8216 clear AES-128 keys upfront with identical headers
        val aesKeys = fetchAes128Keys(segments, headers)

        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val exactByteRangeTotal = if (segments.all { it.byteRangeLength != null && it.byteRangeLength > 0L } && aesKeys.isEmpty()) {
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
        val completedMediaSegments = AtomicInteger(0)
        val tracker = SpeedTracker(0L)

        fun computeSegmentDrivenTotal(currentWritten: Long): Long {
            if (exactByteRangeTotal > 0L) {
                return maxOf(exactByteRangeTotal, currentWritten)
            }
            val doneSegs = completedMediaSegments.get()
            if (totalMediaSegments <= 0) {
                return if (hintedTotal > 0L) maxOf(hintedTotal, currentWritten) else -1L
            }
            if (doneSegs <= 0) {
                return if (hintedTotal > 0L) maxOf(hintedTotal, currentWritten + 1L) else -1L
            }
            if (doneSegs >= totalMediaSegments) {
                return currentWritten
            }
            // Drive progress bar and percentage strictly from completedSegments / totalSegments
            val impliedTotal = kotlin.math.round(
                (currentWritten.toDouble() * totalMediaSegments.toDouble()) / doneSegs.toDouble()
            ).toLong()
            return maxOf(currentWritten + 1L, impliedTotal)
        }

        val progressJob = launch {
            while (isActive) {
                delay(Constants.PROGRESS_THROTTLE_MS)
                val bytes = written.get()
                val estTotal = computeSegmentDrivenTotal(bytes)
                val speed = tracker.sample(bytes)
                val eta = if (speed > 0 && estTotal > bytes) ((estTotal - bytes) / speed).coerceAtLeast(0) else -1L
                onProgress(DownloadProgress(bytes, estTotal, speed, eta))
            }
        }

        val cdnLimit = Regex("""(?:[?&/,;])limit=(\d+)(?:[&#/,;]|$)""", RegexOption.IGNORE_CASE)
            .find(mediaUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        val maxConcurrent = if (cdnLimit != null) minOf(CONCURRENT_SEGMENTS, cdnLimit) else CONCURRENT_SEGMENTS
        val semaphore = Semaphore(maxConcurrent)
        val effectiveRetries = maxRetries.coerceAtLeast(0)
        val tasks = segments.mapIndexed { index, segment ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    val partFile = File(partsDir, "seg_$index.part")
                    val keyBytes = if (segment.keySpec.method == HlsEncryptionMethod.AES_128 && !segment.isInitMap) {
                        segment.keySpec.uri?.let { aesKeys[it] }
                    } else null
                    downloadSegmentToPart(
                        segment = segment,
                        headers = headers,
                        keyBytes = keyBytes,
                        maxRetries = effectiveRetries,
                        partFile = partFile,
                        written = written
                    )
                    if (!segment.isInitMap) {
                        completedMediaSegments.incrementAndGet()
                    }
                    partFile
                }
            }
        }
        var succeeded = false
        try {
            val tsNormalizer = MpegTsTimestampNormalizer()
            FileOutputStream(dest, true).use { output ->
                for ((index, task) in tasks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val partFile = task.await()
                    val seg = segments[index]
                    if (seg.isInitMap) {
                        partFile.inputStream().use { it.copyTo(output, Constants.DOWNLOAD_BUFFER_SIZE) }
                    } else {
                        tsNormalizer.writeSegment(partFile, seg.discontinuityBlock, output)
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

    private suspend fun fetchAes128Keys(
        segments: List<HlsSegment>,
        headers: Map<String, String>
    ): Map<String, ByteArray> {
        val aesSegments = segments.filter { !it.isInitMap && it.keySpec.method == HlsEncryptionMethod.AES_128 }
        if (aesSegments.isEmpty()) return emptyMap()

        val keys = LinkedHashMap<String, ByteArray>()
        for (seg in aesSegments) {
            val uri = seg.keySpec.uri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: throw MediaException(
                    AppError.GENERIC,
                    "This stream is encrypted and has no retrievable AES-128 key URI."
                )
            if (keys.containsKey(uri)) continue

            val keyBytes = withContext(Dispatchers.IO) {
                runCatching {
                    val req = http.buildRequest(uri, headers)
                        .header("X-Internal-Download", "true")
                        .get()
                        .build()
                    client.executeCancellable(req) { resp ->
                        if (!resp.isSuccessful) return@executeCancellable null
                        resp.body?.bytes()
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                }.getOrNull()
            }

            if (keyBytes == null || keyBytes.size != AES_128_KEY_SIZE) {
                throw MediaException(
                    AppError.GENERIC,
                    "This stream is encrypted and its decryption key could not be retrieved."
                )
            }
            keys[uri] = keyBytes
        }
        return keys
    }

    private suspend fun downloadSegmentToPart(
        segment: HlsSegment,
        headers: Map<String, String>,
        keyBytes: ByteArray?,
        maxRetries: Int,
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

                var retryAfterMs: Long? = null
                client.executeCancellable(reqBuilder.build()) { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        if (response.code == 429) {
                            retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()
                                ?.coerceIn(1L, 10L)?.times(1000L)
                        }
                        throw SegmentHttpException(response.code, http.mapHttpError(response.code), retryAfterMs)
                    }
                    val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty segment")
                    val serverIgnoredRange = rangeLen != null && rangeLen > 0L && response.code == 200

                    partFile.parentFile?.mkdirs()
                    FileOutputStream(partFile).use { output ->
                        body.byteStream().use { rawInput ->
                            if (serverIgnoredRange && rangeStart > 0L) {
                                skipFully(rawInput, rangeStart)
                            }
                            val boundedInput: InputStream = if (rangeLen != null && rangeLen > 0L) {
                                BoundedInputStream(rawInput, rangeLen)
                            } else {
                                rawInput
                            }
                            val effectiveInput: InputStream = if (keyBytes != null) {
                                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                val iv = resolveAesIv(segment.keySpec.ivHex, segment.mediaSequence)
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                                CipherInputStream(boundedInput, cipher)
                            } else {
                                boundedInput
                            }

                            effectiveInput.use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    attemptBytes += read
                                    written.addAndGet(read.toLong())
                                }
                            }
                        }
                        output.flush()
                    }
                }
                return attemptBytes
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (attemptBytes > 0) written.addAndGet(-attemptBytes)
                runCatching { partFile.delete() }
                throw e
            } catch (e: SegmentHttpException) {
                currentCoroutineContext().ensureActive()
                if (attemptBytes > 0) written.addAndGet(-attemptBytes)
                runCatching { partFile.delete() }
                val isRetryable = e.code == 429 || e.code == 408 || e.code in 500..599
                if (!isRetryable || attempt >= maxRetries) {
                    throw e.mediaException
                }
                attempt++
                val backoffMs = e.retryAfterMs ?: minOf(4_000L, 450L * (1L shl (attempt - 1).coerceAtMost(4)))
                delay(backoffMs)
            } catch (e: MediaException) {
                currentCoroutineContext().ensureActive()
                if (attemptBytes > 0) written.addAndGet(-attemptBytes)
                runCatching { partFile.delete() }
                if (e.error == AppError.GEO_BLOCKED || e.error == AppError.NO_MEDIA_FOUND || e.error == AppError.EXPIRED_LINK) {
                    throw e
                }
                if (attempt >= maxRetries) throw e
                attempt++
                delay(minOf(4_000L, 450L * attempt))
            } catch (e: Exception) {
                if (attemptBytes > 0) written.addAndGet(-attemptBytes)
                runCatching { partFile.delete() }
                currentCoroutineContext().ensureActive()
                if (attempt >= maxRetries) throw e
                attempt++
                delay(minOf(4_000L, 450L * attempt))
            }
        }
    }

    private class SegmentHttpException(
        val code: Int,
        val mediaException: MediaException,
        val retryAfterMs: Long?
    ) : Exception(mediaException.message, mediaException)

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    internal fun resolveAesIv(ivHex: String?, mediaSequence: Long): ByteArray {
        val iv = ByteArray(16)
        if (!ivHex.isNullOrBlank()) {
            val clean = ivHex.trim().removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
            for (i in 0 until 16) {
                val byteVal = clean.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0
                iv[i] = byteVal.toByte()
            }
            return iv
        }
        // RFC 8216 §5.2: 128-bit big-endian binary representation of media sequence number
        ByteBuffer.wrap(iv, 8, 8).putLong(mediaSequence)
        return iv
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

    internal fun parseSegments(mediaText: String, baseUrl: String): List<HlsSegment> =
        HlsPlaylistSanitizer.parseAndSanitize(mediaText, baseUrl).segments.map { it.toHlsSegment() }

    private fun HlsParsedSegment.toHlsSegment(): HlsSegment = HlsSegment(
        url = url,
        durationSec = durationSec,
        byteRangeOffset = byteRangeOffset,
        byteRangeLength = byteRangeLength,
        isInitMap = isInitMap,
        keySpec = keySpec,
        mediaSequence = mediaSequence,
        discontinuityBlock = discontinuityBlock
    )

    private companion object {
        const val CONCURRENT_SEGMENTS = 5
        const val AES_128_KEY_SIZE = 16
    }
}
