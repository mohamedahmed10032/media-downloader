package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-threaded, resumable HTTP downloader.
 *
 * When the server supports ranged requests and the size is known, the file is split into
 * N segments downloaded concurrently and written at their byte offsets via [RandomAccessFile].
 * Per-segment progress is persisted to a sidecar `.idx` file so an interrupted download
 * resumes exactly where it stopped. Otherwise it falls back to a single, resumable stream.
 *
 * Cancellation is cooperative: cancelling the calling coroutine stops writing and leaves the
 * partial file + sidecar in place for a later resume.
 */
@Singleton
class SegmentedDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val http: ExtractorHttp
) {

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

        val isGoogleVideo = url.contains("googlevideo.com", ignoreCase = true)
        var total = knownTotal
        if (total <= 0 && isGoogleVideo) {
            total = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        }
        var ranged = acceptsRanges || isGoogleVideo
        if (total <= 0 || !ranged) {
            runCatching { http.probe(url, headers) }.getOrNull()?.let { info ->
                if (total <= 0) total = info.contentLength
                ranged = ranged || info.acceptsRanges
            }
        }

        val canSegment = ranged && total >= Constants.MIN_SEGMENT_SIZE_BYTES && segmentCount > 1
        if (!canSegment) {
            singleStream(url, headers, dest, total, ranged, isGoogleVideo, onProgress)
            return@coroutineScope
        }

        val n = segmentCount.coerceIn(2, 8)
        val bounds = computeBounds(total, n)
        val idxFile = File(dest.parentFile, dest.name + ".idx")
        val positions = loadOrInit(idxFile, dest, bounds, total)

        val downloaded = AtomicLong(positions.indices.sumOf { positions[it] - bounds[it].first })

        val tracker = SpeedTracker(downloaded.get())
        val progressJob = launch {
            while (isActive) {
                delay(Constants.PROGRESS_THROTTLE_MS)
                emit(downloaded.get(), total, tracker, onProgress)
                persistPositions(idxFile, positions)
            }
        }

        try {
            val tasks = (0 until n).map { i ->
                async(Dispatchers.IO) { downloadSegment(url, headers, dest, bounds[i], positions, i, downloaded, isGoogleVideo) }
            }
            tasks.awaitAll()
        } finally {
            progressJob.cancel()
            persistPositions(idxFile, positions)
        }

        // All segments complete: clean up sidecar and emit final progress.
        idxFile.delete()
        onProgress(DownloadProgress(total, total, 0, 0))
    }

    private suspend fun downloadSegment(
        url: String,
        headers: Map<String, String>,
        dest: File,
        bound: Pair<Long, Long>,
        positions: LongArray,
        index: Int,
        downloaded: AtomicLong,
        isGoogleVideo: Boolean
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
                    .build()

                var bytesInPass = 0L
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) throw http.mapHttpError(response.code)
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
                if (bytesInPass <= 0L && positions[index] <= endInclusive) {
                    throw MediaException(AppError.GENERIC, "Segment stalled at ${positions[index]}")
                }
                attempt = 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
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
        onProgress: ProgressCallback
    ) = coroutineScope {
        val downloaded = AtomicLong(if (dest.exists()) dest.length() else 0L)
        val tracker = SpeedTracker(downloaded.get())
        val progressJob = launch {
            while (isActive) {
                delay(Constants.PROGRESS_THROTTLE_MS)
                emit(downloaded.get(), total, tracker, onProgress)
            }
        }

        var attempt = 0
        try {
            while (total <= 0 || downloaded.get() < total) {
                currentCoroutineContext().ensureActive()
                val existing = if (dest.exists()) dest.length() else 0L
                val canResume = acceptsRanges && existing > 0 && (total <= 0 || existing < total)
                if (!canResume && dest.exists() && attempt == 0 && downloaded.get() == 0L) {
                    dest.delete()
                }

                val builder = http.buildRequest(url, headers)
                if (isGoogleVideo && total > 0L) {
                    val chunkEnd = minOf(total - 1L, existing + GOOGLE_VIDEO_CHUNK_BYTES - 1L)
                    builder.header("Range", "bytes=$existing-$chunkEnd")
                } else if (canResume) {
                    builder.header("Range", "bytes=$existing-")
                }
                val request = builder.build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful && response.code != 206) throw http.mapHttpError(response.code)
                        val isPartial = response.code == 206
                        val append = (canResume || existing > 0L) && isPartial
                        if (!append && existing > 0) {
                            downloaded.set(0L)
                        } else if (append) {
                            downloaded.set(existing)
                        }
                        val body = response.body ?: throw MediaException(AppError.GENERIC, "Empty body")
                        java.io.FileOutputStream(dest, append).use { output ->
                            val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
                            body.byteStream().use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    downloaded.addAndGet(read.toLong())
                                }
                            }
                            output.flush()
                        }
                    }
                    // If we drained the entire file (or unknown-length stream), break; otherwise loop next chunk
                    if (total <= 0L || downloaded.get() >= total) break
                    attempt = 0
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= Constants.SEGMENT_MAX_RETRIES) throw e
                    delay(500L * attempt)
                }
            }
        } finally {
            progressJob.cancel()
        }
        val finalTotal = if (total > 0) total else downloaded.get()
        onProgress(DownloadProgress(downloaded.get(), finalTotal, 0, 0))
    }

    private suspend fun emit(done: Long, total: Long, tracker: SpeedTracker, onProgress: ProgressCallback) {
        val speed = tracker.sample(done)
        val eta = if (speed > 0 && total > 0) ((total - done) / speed).coerceAtLeast(0) else -1
        onProgress(DownloadProgress(done, total, speed, eta))
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
        if (idxFile.exists() && dest.exists() && dest.length() == total) {
            val saved = runCatching {
                idxFile.readLines().filter { it.isNotBlank() }.map { it.trim().toLong() }
            }.getOrNull()
            if (saved != null && saved.size == bounds.size) {
                return LongArray(bounds.size) { saved[it].coerceIn(bounds[it].first, bounds[it].second + 1) }
            }
        }
        // Fresh start: (re)allocate the destination to the full size.
        RandomAccessFile(dest, "rw").use { it.setLength(total) }
        return fresh
    }

    private fun persistPositions(idxFile: File, positions: LongArray) {
        runCatching { idxFile.writeText(positions.joinToString("\n")) }
    }

    private companion object {
        const val GOOGLE_VIDEO_CHUNK_BYTES = 9_437_184L // 9 MB per ranged request to prevent YouTube CDN throttling
    }
}
