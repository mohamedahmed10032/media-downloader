package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import com.melmeligy.mediadownloader.extractor.HeadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-threaded, resumable HTTP downloader.
 *
 * Enforces CDN `limit=N` concurrency caps, attaches `X-Internal-Download: true` on all
 * single-stream and segmented requests, resets corrupt/oversized `.idx` sidecars cleanly,
 * fails loudly on truncated segment streams instead of publishing sparse zero-filled files,
 * and surfaces permanent HTTP 403/404/410 errors immediately without wasteful retries.
 */
@Singleton
class SegmentedDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val http: ExtractorHttp
) {

    suspend fun probe(url: String, headers: Map<String, String>): HeadInfo? =
        withContext(Dispatchers.IO) {
            runCatching { http.probe(url, headers) }.getOrNull()
        }

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        dest: File,
        knownTotal: Long,
        acceptsRanges: Boolean,
        segmentCount: Int,
        onProgress: ProgressCallback
    ) = coroutineScope {
        dest.parentFile?.mkdirs()

        val allowImageContentType = headers["X-Internal-Allow-Image"]?.equals("true", ignoreCase = true) == true
        var effectiveHeaders = headers
        val isGoogleVideo = url.contains("googlevideo.com", ignoreCase = true)
        var total = knownTotal
        if (total <= 0 && isGoogleVideo) {
            total = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        }
        var ranged = acceptsRanges || isGoogleVideo
        if (total <= 0 || !ranged) {
            val probed = probeWithHeaderFallback(url, effectiveHeaders, allowImageContentType)
            if (probed != null) {
                val (info, workingHeaders) = probed
                effectiveHeaders = workingHeaders
                if (info.code !in 200..299 && info.code != 405 && info.code != 416) {
                    throw http.mapHttpError(info.code)
                }
                if (ExtractorHttp.isRejectedMediaContentType(info.contentType, allowImage = allowImageContentType)) {
                    throw MediaException(AppError.EXPIRED_LINK, "Media URL returned non-media content (${info.contentType})")
                }
                if (total <= 0 && info.contentLength > 0) total = info.contentLength
                ranged = ranged || info.acceptsRanges
            }
        }

        val cdnLimit = extractCdnConnectionLimit(url)
        val effectiveSegments = when {
            isTokenizedSingleStreamUrl(url) -> 1
            cdnLimit != null -> minOf(segmentCount, cdnLimit)
            else -> segmentCount
        }

        val canSegment = ranged && total >= Constants.MIN_SEGMENT_SIZE_BYTES && effectiveSegments > 1
        if (!canSegment) {
            singleStream(url, effectiveHeaders, dest, total, ranged, isGoogleVideo, allowImageContentType, onProgress)
            return@coroutineScope
        }

        val idxFile = File(dest.parentFile, dest.name + ".idx")
        if (total > 0L && dest.exists() && dest.length() == total && !idxFile.exists()) {
            onProgress(DownloadProgress(total, total, 0, 0))
            return@coroutineScope
        }

        val n = effectiveSegments.coerceIn(2, 8)
        val bounds = computeBounds(total, n)
        val positions = loadOrInit(idxFile, dest, bounds, total)

        val downloaded = AtomicLong(positions.indices.sumOf { positions[it] - bounds[it].first })

        val tracker = SpeedTracker(downloaded.get())
        val progressJob = launch(Dispatchers.Default) {
            while (isActive) {
                delay(Constants.PROGRESS_THROTTLE_MS)
                emit(downloaded.get().coerceAtMost(total), total, tracker, onProgress)
                persistPositions(idxFile, positions)
            }
        }

        var fallbackToSingleStream = false
        try {
            coroutineScope {
                val tasks = (0 until n).map { i ->
                    async(Dispatchers.IO) {
                        downloadSegment(url, effectiveHeaders, dest, bounds[i], positions, i, downloaded, total, isGoogleVideo, allowImageContentType)
                    }
                }
                tasks.awaitAll()
            }
        } catch (_: RangeNotSupportedException) {
            fallbackToSingleStream = true
        } catch (_: ParallelConnectionRejectedException) {
            fallbackToSingleStream = true
        } finally {
            progressJob.cancel()
            if (!fallbackToSingleStream) {
                persistPositions(idxFile, positions)
            }
        }

        if (fallbackToSingleStream) {
            runCatching { idxFile.delete() }
            runCatching { dest.delete() }
            singleStream(url, effectiveHeaders, dest, total, acceptsRanges = false, isGoogleVideo = false, allowImageContentType = allowImageContentType, onProgress = onProgress)
            return@coroutineScope
        }

        // Verify all segments completed cleanly with zero truncated holes
        val allComplete = positions.indices.all { i -> positions[i] == bounds[i].second + 1L }
        if (!allComplete || !dest.exists() || dest.length() != total) {
            throw MediaException(AppError.GENERIC, "Incomplete segmented download")
        }

        idxFile.delete()
        onProgress(DownloadProgress(total, total, 0, 0))
    }

    private suspend fun probeWithHeaderFallback(
        url: String,
        primaryHeaders: Map<String, String>,
        allowImageContentType: Boolean = false
    ): Pair<HeadInfo, Map<String, String>>? = withContext(Dispatchers.IO) {
        val candidates = listOf(primaryHeaders) +
            ExtractorHttp.buildRelaxedHeaderVariants(primaryHeaders, includeBareNoReferer = true)
        var lastPermanent: MediaException? = null
        for (hdrs in candidates) {
            val info = try {
                http.probe(url, hdrs)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: MediaException) {
                if (e.error == AppError.ACCESS_DENIED || e.error == AppError.GEO_BLOCKED) {
                    lastPermanent = e
                    continue
                }
                if (isPermanentError(e.error)) throw e
                return@withContext null
            } catch (_: Exception) {
                return@withContext null
            }
            if (info.code == 401 || info.code == 403 || info.code == 416 || info.code == 429 || info.code == 503) {
                lastPermanent = http.mapHttpError(info.code)
                continue
            }
            if (ExtractorHttp.isRejectedMediaContentType(info.contentType, allowImage = allowImageContentType)) {
                lastPermanent = MediaException(AppError.EXPIRED_LINK, "Media URL returned non-media content (${info.contentType})")
                continue
            }
            return@withContext info to hdrs
        }
        if (lastPermanent != null) throw lastPermanent
        null
    }

    private class RangeNotSupportedException : Exception("Server returned HTTP 200 instead of 206 for Range request")
    private class ParallelConnectionRejectedException(code: Int) : Exception("Parallel Range connection rejected with HTTP $code")

    private suspend fun downloadSegment(
        url: String,
        headers: Map<String, String>,
        dest: File,
        bound: Pair<Long, Long>,
        positions: LongArray,
        index: Int,
        downloaded: AtomicLong,
        total: Long,
        isGoogleVideo: Boolean,
        allowImageContentType: Boolean = false
    ) {
        val (_, endInclusive) = bound
        var attempt = 0
        while (positions[index] <= endInclusive) {
            currentCoroutineContext().ensureActive()
            try {
                val startByte = positions[index]
                val chunkEnd = if (isGoogleVideo) {
                    minOf(endInclusive, startByte + GOOGLE_VIDEO_CHUNK_BYTES - 1L)
                } else {
                    endInclusive
                }
                val request = http.buildRequest(url, headers)
                    .header("Range", "bytes=$startByte-$chunkEnd")
                    .header("X-Internal-Download", "true")
                    .build()

                var bytesInPass = 0L
                val expectedPassBytes = chunkEnd - startByte + 1L
                client.executeCancellable(request) { response ->
                    if (response.code == 200 && (startByte > 0L || chunkEnd < total - 1L)) {
                        throw RangeNotSupportedException()
                    }
                    // Many movie CDNs enforce 1 connection per IP/token and return 403/429/503 on parallel ranges -> degrade cleanly to singleStream
                    if (response.code == 403 || response.code == 429 || response.code == 503) {
                        throw ParallelConnectionRejectedException(response.code)
                    }
                    if (response.code != 206 && !response.isSuccessful) {
                        throw http.mapHttpError(response.code)
                    }
                    if (ExtractorHttp.isRejectedMediaContentType(response.header("Content-Type"), allowImage = allowImageContentType)) {
                        throw MediaException(AppError.EXPIRED_LINK, "Segment returned HTML instead of media")
                    }
                    val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty segment body")
                    RandomAccessFile(dest, "rw").use { raf ->
                        raf.seek(positions[index])
                        val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
                        body.byteStream().use { input ->
                            while (positions[index] <= chunkEnd) {
                                currentCoroutineContext().ensureActive()
                                val maxToRead = minOf(buffer.size.toLong(), chunkEnd - positions[index] + 1L).toInt()
                                val read = input.read(buffer, 0, maxToRead)
                                if (read == -1) break
                                raf.write(buffer, 0, read)
                                positions[index] += read
                                bytesInPass += read
                                downloaded.addAndGet(read.toLong())
                            }
                        }
                    }
                }
                if (bytesInPass < expectedPassBytes) {
                    throw MediaException(
                        AppError.GENERIC,
                        "Truncated segment stream ($bytesInPass/$expectedPassBytes bytes)"
                    )
                }
                attempt = 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RangeNotSupportedException) {
                throw e
            } catch (e: ParallelConnectionRejectedException) {
                throw e
            } catch (e: MediaException) {
                currentCoroutineContext().ensureActive()
                if (isPermanentError(e.error)) throw e
                attempt++
                if (attempt >= Constants.SEGMENT_MAX_RETRIES) throw e
                delay(400L * attempt)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt >= Constants.SEGMENT_MAX_RETRIES) throw e
                delay(400L * attempt)
            }
        }
    }

    private suspend fun singleStream(
        url: String,
        headers: Map<String, String>,
        dest: File,
        total: Long,
        acceptsRanges: Boolean,
        isGoogleVideo: Boolean,
        allowImageContentType: Boolean = false,
        onProgress: ProgressCallback
    ) = coroutineScope {
        val downloaded = AtomicLong(if (dest.exists()) dest.length() else 0L)
        val tracker = SpeedTracker(downloaded.get())
        val progressJob = launch(Dispatchers.Default) {
            while (isActive) {
                delay(Constants.PROGRESS_THROTTLE_MS)
                emit(downloaded.get(), total, tracker, onProgress)
            }
        }

        var activeHeaders = headers
        val relaxedHeaderQueue = ArrayDeque(
            ExtractorHttp.buildRelaxedHeaderVariants(headers, includeBareNoReferer = true)
        )
        var attempt = 0
        try {
            while (total <= 0 || downloaded.get() < total) {
                currentCoroutineContext().ensureActive()
                val existing = if (dest.exists()) dest.length() else 0L
                val canResume = acceptsRanges && existing > 0 && (total <= 0 || existing < total)
                if (!canResume && dest.exists() && attempt == 0 && downloaded.get() == 0L) {
                    dest.delete()
                }

                val builder = http.buildRequest(url, activeHeaders)
                    .header("X-Internal-Download", "true")
                if (isGoogleVideo && total > 0L) {
                    val chunkEnd = minOf(total - 1L, existing + GOOGLE_VIDEO_CHUNK_BYTES - 1L)
                    builder.header("Range", "bytes=$existing-$chunkEnd")
                } else if (canResume) {
                    builder.header("Range", "bytes=$existing-")
                }
                val request = builder.build()

                try {
                    var bytesInPass = 0L
                    var responseContentLength = -1L
                    client.executeCancellable(request) { response ->
                        if (!response.isSuccessful && response.code != 206) throw http.mapHttpError(response.code)
                        if (ExtractorHttp.isRejectedMediaContentType(response.header("Content-Type"), allowImage = allowImageContentType)) {
                            throw MediaException(AppError.EXPIRED_LINK, "Stream returned HTML instead of media")
                        }
                        val isPartial = response.code == 206
                        val append = (canResume || existing > 0L) && isPartial
                        if (!append && existing > 0) {
                            downloaded.set(0L)
                        } else if (append) {
                            downloaded.set(existing)
                        }
                        responseContentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty body")
                        java.io.FileOutputStream(dest, append).use { output ->
                            val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
                            body.byteStream().use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    bytesInPass += read
                                    downloaded.addAndGet(read.toLong())
                                }
                            }
                            output.flush()
                        }
                    }
                    if (responseContentLength > 0L && bytesInPass < responseContentLength) {
                        throw MediaException(AppError.GENERIC, "Truncated single-stream transfer")
                    }
                    if (total <= 0L || downloaded.get() >= total) break
                    if (bytesInPass <= 0L) {
                        throw MediaException(AppError.GENERIC, "Single-stream transfer stalled")
                    }
                    attempt = 0
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: MediaException) {
                    currentCoroutineContext().ensureActive()
                    val canRelaxHeaders = relaxedHeaderQueue.isNotEmpty() && (
                        e.error == AppError.ACCESS_DENIED ||
                            e.error == AppError.GEO_BLOCKED ||
                            e.error == AppError.SERVER_ERROR ||
                            (e.error == AppError.EXPIRED_LINK && downloaded.get() == 0L) ||
                            (e.message?.contains("416") == true && downloaded.get() == 0L)
                        )
                    if (canRelaxHeaders) {
                        activeHeaders = relaxedHeaderQueue.removeFirst()
                        continue
                    }
                    if (isPermanentError(e.error)) throw e
                    attempt++
                    if (attempt >= Constants.SEGMENT_MAX_RETRIES) throw e
                    delay(500L * attempt)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    attempt++
                    if (attempt >= Constants.SEGMENT_MAX_RETRIES) throw e
                    delay(500L * attempt)
                }
            }
        } finally {
            progressJob.cancel()
        }
        val actualFinal = dest.length()
        val finalTotal = if (total > 0L) maxOf(total, actualFinal) else actualFinal
        onProgress(DownloadProgress(actualFinal, finalTotal, 0, 0))
    }

    private suspend fun emit(done: Long, total: Long, tracker: SpeedTracker, onProgress: ProgressCallback) {
        val speed = tracker.sample(done)
        val effectiveTotal = if (total > 0L) maxOf(total, done) else total
        val eta = if (speed > 0 && effectiveTotal > 0) ((effectiveTotal - done) / speed).coerceAtLeast(0) else -1
        onProgress(DownloadProgress(done, effectiveTotal, speed, eta))
    }

    private fun computeBounds(total: Long, n: Int): List<Pair<Long, Long>> {
        val size = total / n
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) total - 1 else (i + 1) * size - 1
            start to end
        }
    }

    private fun loadOrInit(idxFile: File, dest: File, bounds: List<Pair<Long, Long>>, total: Long): LongArray {
        val fresh = LongArray(bounds.size) { bounds[it].first }
        if (idxFile.exists() && idxFile.length() in 1..MAX_IDX_BYTES && dest.exists() && dest.length() == total) {
            val saved = runCatching {
                idxFile.readLines().filter { it.isNotBlank() }.map { it.trim().toLong() }
            }.getOrNull()
            val valid = saved != null &&
                saved.size == bounds.size &&
                saved.indices.all { i -> saved[i] in bounds[i].first..(bounds[i].second + 1L) }
            if (valid && saved != null) {
                return LongArray(bounds.size) { saved[it] }
            }
        }
        // Corrupt, oversized, or fresh start: wipe sidecar and (re)allocate clean destination
        runCatching { idxFile.delete() }
        runCatching { dest.delete() }
        RandomAccessFile(dest, "rw").use { it.setLength(total) }
        return fresh
    }

    private fun persistPositions(idxFile: File, positions: LongArray) {
        runCatching { idxFile.writeText(positions.joinToString("\n")) }
    }

    private fun isPermanentError(error: AppError): Boolean =
        error == AppError.ACCESS_DENIED ||
            error == AppError.GEO_BLOCKED ||
            error == AppError.NO_MEDIA_FOUND ||
            error == AppError.EXPIRED_LINK

    internal fun extractCdnConnectionLimit(url: String): Int? {
        val m = CDN_LIMIT_REGEX.find(url) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }

    companion object {
        private const val GOOGLE_VIDEO_CHUNK_BYTES = 9_437_184L // 9 MB per ranged request to prevent YouTube CDN throttling
        private const val MAX_IDX_BYTES = 16_384L
        private val CDN_LIMIT_REGEX = Regex("""(?:[?&/,;])limit=(\d+)(?:[&#/,;]|$)""", RegexOption.IGNORE_CASE)

        /**
         * Tokenized movie-mirror URLs (`/get_video`, `/d/`, `token=`) commonly reject parallel
         * Range connections with HTTP 403/503; default them to single-stream while keeping
         * multi-segment downloads enabled for YouTube and social CDNs.
         */
        internal fun isTokenizedSingleStreamUrl(url: String): Boolean {
            val lower = url.lowercase()
            if (lower.contains("googlevideo.com") ||
                lower.contains("tiktokcdn") ||
                lower.contains("fbcdn.net") ||
                lower.contains("cdninstagram.com") ||
                lower.contains("twimg.com") ||
                lower.contains("redd.it")
            ) {
                return false
            }
            return lower.contains("/get_video") ||
                lower.contains("/d/") ||
                lower.contains("token=")
        }
    }
}
