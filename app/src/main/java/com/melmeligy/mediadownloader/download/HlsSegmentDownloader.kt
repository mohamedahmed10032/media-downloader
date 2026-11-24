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
class HlsSegmentDownloader private constructor(
    private val client: OkHttpClient,
    private val http: ExtractorHttp,
    private val priorityCoordinator: com.melmeligy.mediadownloader.core.ExtractionPriorityCoordinator?,
    @Suppress("UNUSED_PARAMETER") marker: Boolean
) {

    @Inject
    constructor(
        client: OkHttpClient,
        http: ExtractorHttp,
        priorityCoordinator: com.melmeligy.mediadownloader.core.ExtractionPriorityCoordinator
    ) : this(client, http, priorityCoordinator, true)

    constructor(
        client: OkHttpClient,
        http: ExtractorHttp
    ) : this(client, http, null, false)

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
        var effectiveHeaders = headers
        var mediaUrl = playlistUrl
        val firstFetch = fetchPlaylistWithHeaderFallback(playlistUrl, effectiveHeaders)
        var text = firstFetch.first
        effectiveHeaders = firstFetch.second

        if (text.contains("#EXT-X-STREAM-INF")) {
            mediaUrl = firstVariantUrl(text, playlistUrl)
                ?: throw MediaException(AppError.NO_MEDIA_FOUND, "No HLS variant found")
            val variantFetch = fetchPlaylistWithHeaderFallback(mediaUrl, effectiveHeaders)
            text = variantFetch.first
            effectiveHeaders = variantFetch.second
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
        val aesKeys = fetchAes128Keys(segments, effectiveHeaders)

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
            "hls_parts_${dest.nameWithoutExtension}"
        ).apply { mkdirs() }

        val metaFile = File(partsDir, ".meta")
        val playlistMeta = "${segments.size}:$totalDurationMs"
        val existingMeta = runCatching { metaFile.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
        if (existingMeta != null && existingMeta != playlistMeta) {
            runCatching { partsDir.deleteRecursively() }
            partsDir.mkdirs()
        } else {
            // Remove any interrupted .tmp segment files while keeping completed seg_N.part files intact
            partsDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { runCatching { it.delete() } }
        }
        runCatching { metaFile.writeText(playlistMeta) }

        var initialWritten = 0L
        var initialCompletedSegs = 0
        val alreadyCompleted = BooleanArray(segments.size)
        for ((index, segment) in segments.withIndex()) {
            val partFile = File(partsDir, "seg_$index.part")
            val len = if (partFile.exists()) partFile.length() else 0L
            if (len > 0L) {
                alreadyCompleted[index] = true
                initialWritten += len
                if (!segment.isInitMap) {
                    initialCompletedSegs++
                }
            }
        }

        val written = AtomicLong(initialWritten)
        val completedMediaSegments = AtomicInteger(initialCompletedSegs)
        val tracker = SpeedTracker(initialWritten)

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

        if (initialWritten > 0L) {
            val estTotal = computeSegmentDrivenTotal(initialWritten)
            onProgress(DownloadProgress(initialWritten, estTotal, 0L, -1L))
        }

        val progressJob = launch(Dispatchers.Default) {
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
                val partFile = File(partsDir, "seg_$index.part")
                if (alreadyCompleted[index] && partFile.exists() && partFile.length() > 0L) {
                    return@async partFile
                }
                semaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    if (partFile.exists() && partFile.length() > 0L) {
                        return@withPermit partFile
                    }
                    val keyBytes = if (segment.keySpec.method == HlsEncryptionMethod.AES_128 && !segment.isInitMap) {
                        segment.keySpec.uri?.let { aesKeys[it] }
                    } else null
                    downloadSegmentToPart(
                        segment = segment,
                        headers = effectiveHeaders,
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
            if (dest.exists()) dest.delete()
            java.io.BufferedOutputStream(FileOutputStream(dest, true), Constants.DOWNLOAD_BUFFER_SIZE).use { output ->
                for ((index, task) in tasks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val partFile = task.await()
                    val seg = segments[index]
                    if (seg.isInitMap) {
                        partFile.inputStream().use { it.copyTo(output, Constants.DOWNLOAD_BUFFER_SIZE) }
                    } else {
                        tsNormalizer.writeSegment(partFile, seg.discontinuityBlock, output)
                    }
                }
                output.flush()
            }
            succeeded = true
        } finally {
            progressJob.cancel()
            tasks.forEach { it.cancel() }
            if (succeeded) {
                runCatching { partsDir.deleteRecursively() }
            } else {
                // Keep completed seg_N.part files in partsDir for Resume; only remove incomplete assembled dest
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

    private suspend fun fetchPlaylistWithHeaderFallback(
        url: String,
        primaryHeaders: Map<String, String>
    ): Pair<String, Map<String, String>> {
        try {
            return http.fetchString(url, primaryHeaders) to primaryHeaders
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: MediaException) {
            if (e.error != AppError.ACCESS_DENIED && e.error != AppError.GEO_BLOCKED) throw e
            for (alt in ExtractorHttp.buildRelaxedHeaderVariants(primaryHeaders, includeBareNoReferer = true)) {
                val text = try {
                    http.fetchString(url, alt)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
                if (com.melmeligy.mediadownloader.intercept.MediaSniffer.isValidHlsPlaylistBody(text)) {
                    return text!! to alt
                }
            }
            throw e
        }
    }

    private suspend fun downloadSegmentToPart(
        segment: HlsSegment,
        headers: Map<String, String>,
        keyBytes: ByteArray?,
        maxRetries: Int,
        partFile: File,
        written: AtomicLong
    ): Long {
        var activeHeaders = headers
        val relaxedHeaderQueue = ArrayDeque(
            ExtractorHttp.buildRelaxedHeaderVariants(headers, includeBareNoReferer = true)
        )
        var attempt = 0
        val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            priorityCoordinator?.yieldDownloadChunkIfExtractionActive()
            var attemptBytes = 0L
            try {
                val reqBuilder = http.buildRequest(segment.url, activeHeaders)
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
                    val ct = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    if (ct.startsWith("text/html") || ct.startsWith("application/xhtml") || ct.startsWith("application/json")) {
                        throw MediaException(AppError.EXPIRED_LINK, "HLS segment returned HTML/JSON instead of media")
                    }
                    val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty segment")
                    val serverIgnoredRange = rangeLen != null && rangeLen > 0L && response.code == 200

                    val tempPartFile = File(partFile.parentFile, "${partFile.name}.tmp")
                    tempPartFile.parentFile?.mkdirs()
                    FileOutputStream(tempPartFile).use { output ->
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

                            var checkedSignature = false
                            val isDisguisedCt = ct.startsWith("image/") || ct.startsWith("text/plain")
                            val totalContentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                            effectiveInput.use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    priorityCoordinator?.yieldDownloadChunkIfExtractionActive()
                                    val targetRead = priorityCoordinator?.recommendedReadSize(buffer.size) ?: buffer.size
                                    val read = input.read(buffer, 0, targetRead)
                                    if (read == -1) break
                                    if (!checkedSignature) {
                                        checkedSignature = true
                                        var bufferedLen = read
                                        if (isDisguisedCt && bufferedLen < MAX_PREFIX_SCAN_BYTES) {
                                            while (bufferedLen < MAX_PREFIX_SCAN_BYTES) {
                                                currentCoroutineContext().ensureActive()
                                                val extra = input.read(
                                                    buffer,
                                                    bufferedLen,
                                                    minOf(buffer.size, MAX_PREFIX_SCAN_BYTES) - bufferedLen
                                                )
                                                if (extra == -1) break
                                                bufferedLen += extra
                                            }
                                        }
                                        if (!isValidSegmentPrefix(
                                                bytes = buffer,
                                                length = bufferedLen,
                                                contentType = ct,
                                                isAesEncrypted = keyBytes != null,
                                                totalContentLength = totalContentLength
                                            )
                                        ) {
                                            throw MediaException(AppError.EXPIRED_LINK, "HLS segment returned HTML/JSON error page")
                                        }
                                        output.write(buffer, 0, bufferedLen)
                                        attemptBytes += bufferedLen
                                        written.addAndGet(bufferedLen.toLong())
                                        continue
                                    }
                                    output.write(buffer, 0, read)
                                    attemptBytes += read
                                    written.addAndGet(read.toLong())
                                }
                            }
                        }
                        output.flush()
                    }
                    if (!tempPartFile.renameTo(partFile)) {
                        tempPartFile.copyTo(partFile, overwrite = true)
                        tempPartFile.delete()
                    }
                }
                return attemptBytes
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (attemptBytes > 0) written.addAndGet(-attemptBytes)
                runCatching { File(partFile.parentFile, "${partFile.name}.tmp").delete() }
                throw e
            } catch (e: SegmentHttpException) {
                currentCoroutineContext().ensureActive()
                if (attemptBytes > 0) written.addAndGet(-attemptBytes)
                runCatching { File(partFile.parentFile, "${partFile.name}.tmp").delete() }
                if ((e.code == 401 || e.code == 403) && relaxedHeaderQueue.isNotEmpty()) {
                    activeHeaders = relaxedHeaderQueue.removeFirst()
                    continue
                }
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
                runCatching { File(partFile.parentFile, "${partFile.name}.tmp").delete() }
                if (e.error == AppError.ACCESS_DENIED ||
                    e.error == AppError.GEO_BLOCKED ||
                    e.error == AppError.NO_MEDIA_FOUND ||
                    e.error == AppError.EXPIRED_LINK
                ) {
                    throw e
                }
                if (attempt >= maxRetries) throw e
                attempt++
                delay(minOf(4_000L, 450L * attempt))
            } catch (e: Exception) {
                if (attemptBytes > 0) written.addAndGet(-attemptBytes)
                runCatching { File(partFile.parentFile, "${partFile.name}.tmp").delete() }
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

    companion object {
        private const val CONCURRENT_SEGMENTS = 8
        private const val AES_128_KEY_SIZE = 16
        internal const val MAX_PREFIX_SCAN_BYTES = 4096
        private const val MIN_UNSYNCED_DISGUISED_IMAGE_BYTES = 8_192L

        /**
         * Validates the leading bytes of an HLS segment so disguised `image/` or `text/plain`
         * MPEG-TS (`0x47`), fMP4 (`ftyp`/`moof`/`styp`), and decrypted AES-128 segments are
         * accepted (including fake PNG/JPEG/GIF wrapper headers up to 4096 bytes) while HTML
         * (`<!doctype`, `<html`), JSON (`{`, `[`), plain-text error pages, and tiny tracking
         * images are rejected.
         */
        internal fun isValidSegmentPrefix(
            bytes: ByteArray,
            length: Int,
            contentType: String,
            isAesEncrypted: Boolean,
            totalContentLength: Long = -1L
        ): Boolean {
            if (length <= 0) return false
            val sampleLen = minOf(length, 64)
            // 1. Check ASCII prefix for HTML / JSON error pages (skipping leading whitespace / BOM)
            var idx = 0
            if (sampleLen >= 3 &&
                (bytes[0].toInt() and 0xFF) == 0xEF &&
                (bytes[1].toInt() and 0xFF) == 0xBB &&
                (bytes[2].toInt() and 0xFF) == 0xBF
            ) {
                idx = 3
            }
            while (idx < sampleLen && bytes[idx].toInt().let { it == ' '.code || it == '\t'.code || it == '\r'.code || it == '\n'.code }) {
                idx++
            }
            if (idx < sampleLen) {
                val headText = String(bytes, idx, sampleLen - idx, Charsets.ISO_8859_1).lowercase()
                if (headText.startsWith("<!doctype") ||
                    headText.startsWith("<html") ||
                    headText.startsWith("<head") ||
                    headText.startsWith("<body") ||
                    headText.startsWith("<script") ||
                    headText.startsWith("<?xml")
                ) {
                    return false
                }
                if ((headText.startsWith("{") || headText.startsWith("[")) &&
                    (headText.contains("\"error\"") || headText.contains("\"message\"") || headText.contains("\"status\"") || headText.contains("\"code\""))
                ) {
                    return false
                }
            }

            // 2. If content-type is disguised (image/* or text/plain), verify binary media signature
            val ct = contentType.lowercase()
            if (ct.startsWith("image/") || ct.startsWith("text/plain")) {
                val isGifMagic = length >= 6 &&
                    bytes[0] == 'G'.code.toByte() &&
                    bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() &&
                    bytes[3] == '8'.code.toByte() &&
                    (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
                    bytes[5] == 'a'.code.toByte()
                val scanLimit = minOf(length, MAX_PREFIX_SCAN_BYTES)
                // MPEG-TS sync byte (0x47 at offset 0 or after a disguised PNG/GIF/JPEG/WebP wrapper header up to 4096B).
                // For non-zero offsets (e.g. offset 3 'G' in PNG magic), require a second 0x47 at +188 to avoid false positives.
                val tsSyncAtZero = !isGifMagic &&
                    (bytes[0].toInt() and 0xFF) == 0x47 &&
                    (length < 189 || (bytes[188].toInt() and 0xFF) == 0x47)
                val tsSyncFound = tsSyncAtZero ||
                    (1 until scanLimit).any { off ->
                        off + 188 < length &&
                            (bytes[off].toInt() and 0xFF) == 0x47 &&
                            (bytes[off + 188].toInt() and 0xFF) == 0x47
                    }
                if (tsSyncFound) return true

                // fMP4 box signature at offset 4 (or embedded after a disguised image wrapper header)
                val boxLimit = minOf(length - 4, 512)
                if (boxLimit >= 4) {
                    var off = 4
                    while (off <= boxLimit) {
                        val b0 = bytes[off].toInt() and 0xFF
                        if (b0 == 'f'.code || b0 == 'm'.code || b0 == 's'.code) {
                            val box = String(bytes, off, 4, Charsets.ISO_8859_1)
                            if (box == "ftyp" || box == "moof" || box == "styp" || box == "sidx" || box == "mdat") {
                                return true
                            }
                        }
                        off++
                    }
                }

                // Decrypted AES-128 segments or ID3-prefixed AAC/TS segments
                if (isAesEncrypted) return true
                if (length >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
                    return true
                }
                // Reject plain ASCII text error pages served as text/plain
                if (ct.startsWith("text/plain")) return false
                // For disguised image/* segments without TS/fMP4 sync in the first 4096B, reject tiny tracking/error pixels
                if (totalContentLength in 1L until MIN_UNSYNCED_DISGUISED_IMAGE_BYTES) return false
                if (totalContentLength < 0L && length < 512) return false
            }

            return true
        }
    }
}
