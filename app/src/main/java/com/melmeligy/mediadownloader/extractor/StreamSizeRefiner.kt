package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-blocking background size refiner for progressive streams whose size probe timed out
 * or was deferred so the format sheet could appear immediately without stalling.
 *
 * Probes unique missing-size stream URLs concurrently with tight cancellable timeouts and
 * preserves every stream ID, order, label, and header so active UI selections stay stable.
 */
@Singleton
class StreamSizeRefiner @Inject constructor(
    private val http: ExtractorHttp,
    private val dispatchers: DispatcherProvider
) {

    fun needsRefinement(media: ResolvedMedia): Boolean =
        media.streams.any { isEligible(it) }

    suspend fun refineMissingSizes(
        media: ResolvedMedia,
        perStreamTimeoutMs: Long = DEFAULT_PER_STREAM_TIMEOUT_MS,
        totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS
    ): ResolvedMedia? = withContext(dispatchers.io) {
        val eligible = media.streams.filter { isEligible(it) }
        if (eligible.isEmpty()) return@withContext null

        val uniqueTargets = eligible
            .distinctBy { it.url }
            .take(MAX_BACKGROUND_PROBES)

        val probeByUrl = withTimeoutOrNull(totalBudgetMs) {
            withCancellableHttpCalls(timeoutMs = perStreamTimeoutMs) {
                uniqueTargets.map { stream ->
                    async(dispatchers.io) {
                        val info = try {
                            http.probe(stream.url, stream.headers, perStreamTimeoutMs)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                        val validInfo = info?.takeIf { isValidMediaProbe(it) }
                        stream.url to validInfo
                    }
                }.awaitAll().toMap()
            }
        } ?: return@withContext null

        var changed = false
        val updatedStreams = media.streams.map { stream ->
            if (!isEligible(stream)) return@map stream
            val info = probeByUrl[stream.url] ?: return@map stream
            val contentLength = info.contentLength
            if (contentLength <= 0L) return@map stream

            val updatedHeaders = LinkedHashMap<String, String>(stream.headers).apply {
                if (info.acceptsRanges) put("X-Internal-Accepts-Ranges", "true")
            }

            val refinedSize = if (
                stream.type == MediaType.AUDIO &&
                stream.container.equals("mp3", ignoreCase = true) &&
                !isNativeMp3(info)
            ) {
                updatedHeaders["X-Internal-Video-Bytes"] = contentLength.toString()
                val durationSec = stream.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
                val srcKbps = FormatUtils.inferAudioBitrateKbps(contentLength, durationSec) ?: 128
                val targetKbps = stream.bitrateKbps?.takeIf { it in 64..320 } ?: 160
                FormatUtils.estimateTranscodedAudioBytes(
                    targetBitrateKbps = targetKbps,
                    durationSeconds = durationSec,
                    sourceSizeBytes = contentLength,
                    sourceBitrateKbps = srcKbps
                ) ?: contentLength
            } else {
                if (stream.type == MediaType.AUDIO) {
                    updatedHeaders["X-Internal-Video-Bytes"] = contentLength.toString()
                }
                contentLength
            }

            if (refinedSize > 0L && refinedSize != stream.sizeBytes) {
                changed = true
                stream.copy(sizeBytes = refinedSize, headers = updatedHeaders)
            } else {
                stream
            }
        }

        if (changed) media.copy(streams = updatedStreams) else null
    }

    private fun isEligible(stream: MediaStream): Boolean =
        !stream.isHls &&
            stream.type != MediaType.IMAGE &&
            (stream.sizeBytes == null || stream.sizeBytes <= 0L) &&
            stream.url.startsWith("http")

    private fun isValidMediaProbe(info: HeadInfo): Boolean {
        if (info.code !in 200..299 || info.contentLength <= 0L) return false
        val ct = info.contentType?.lowercase().orEmpty()
        return !ct.startsWith("text/html") &&
            !ct.startsWith("application/xhtml") &&
            !ct.startsWith("application/json") &&
            !ct.startsWith("image/")
    }

    private fun isNativeMp3(info: HeadInfo): Boolean {
        val ct = info.contentType?.lowercase().orEmpty()
        return ct.contains("mpeg") || ct.contains("mp3") ||
            info.finalUrl.substringBefore('?').endsWith(".mp3", ignoreCase = true)
    }

    companion object {
        const val MAX_BACKGROUND_PROBES = 6
        const val DEFAULT_PER_STREAM_TIMEOUT_MS = 2_500L
        const val DEFAULT_TOTAL_BUDGET_MS = 3_500L
    }
}
