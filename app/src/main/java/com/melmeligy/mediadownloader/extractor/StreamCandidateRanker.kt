package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer

/**
 * Ranks extracted stream candidates and rejects ad/preroll/short intro bumper clips
 * whenever a longer primary feature stream exists.
 *
 * Shared by Tier 1 ([HtmlMediaExtractor], [StreamingPortalResolver]) and Tier 2
 * ([HeadlessWebExtractor]) so pre-roll ads and 5–45s site bumpers never beat or pollute
 * the main movie/episode stream.
 */
object StreamCandidateRanker {

    private const val PRIMARY_DURATION_THRESHOLD_SEC = 90L
    private const val PRIMARY_SIZE_THRESHOLD_BYTES = 15L * 1024L * 1024L // 15 MB
    private const val SHORT_BUMPER_MAX_SEC = 45L
    private const val TINY_BUMPER_MAX_BYTES = 3L * 1024L * 1024L // 3 MB
    private const val MIN_VALID_MEDIA_BYTES = 40_000L

    fun durationSeconds(stream: MediaStream): Long? =
        stream.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()?.takeIf { it > 0L }

    /**
     * Filters out ad URLs, tiny probe blobs, and short 5–45s pre-roll/intro bumpers
     * when a longer primary video stream is present, then sorts candidates best-first.
     */
    fun filterAndRankVideos(videos: List<MediaStream>): List<MediaStream> {
        if (videos.isEmpty()) return emptyList()

        val clean = videos.filter { s ->
            s.type == MediaType.VIDEO &&
                !AdBlocker.isAd(s.url) &&
                !MediaSniffer.isPreviewOrAdUrl(s.url) &&
                (s.sizeBytes == null || s.sizeBytes == 0L || s.sizeBytes > MIN_VALID_MEDIA_BYTES)
        }
        if (clean.isEmpty()) return emptyList()

        val maxDuration = clean.mapNotNull { durationSeconds(it) }.maxOrNull() ?: 0L
        val maxSize = clean.mapNotNull { it.sizeBytes?.takeIf { sz -> sz > 0L } }.maxOrNull() ?: 0L
        val hasPrimaryLongFeature = maxDuration >= PRIMARY_DURATION_THRESHOLD_SEC ||
            maxSize >= PRIMARY_SIZE_THRESHOLD_BYTES

        val withoutBumpers = if (hasPrimaryLongFeature) {
            val kept = clean.filterNot { candidate ->
                val dur = durationSeconds(candidate)
                val sz = candidate.sizeBytes?.takeIf { it > 0L }
                val isShortByDuration = dur != null && dur in 1L..SHORT_BUMPER_MAX_SEC
                val isTinyBySize = dur == null && sz != null && sz in 1L..TINY_BUMPER_MAX_BYTES
                isShortByDuration || isTinyBySize
            }
            if (kept.isNotEmpty()) kept else clean
        } else {
            clean
        }

        return withoutBumpers.sortedWith(
            compareByDescending<MediaStream> { it.height ?: 0 }
                .thenByDescending { durationSeconds(it) ?: 0L }
                .thenByDescending { !it.isHls && (it.sizeBytes ?: 0L) >= 500_000L }
                .thenByDescending { it.sizeBytes ?: 0L }
                .thenByDescending { it.bitrateKbps ?: 0 }
        )
    }

    /**
     * Picks the strongest candidate among streams that share the same normalized resolution
     * (e.g. two 720p streams from different movie servers).
     */
    fun pickBestForResolution(group: List<MediaStream>): MediaStream? {
        if (group.size <= 1) return group.firstOrNull()
        return group.maxWithOrNull(
            compareBy<MediaStream> { (durationSeconds(it) ?: 0L) >= PRIMARY_DURATION_THRESHOLD_SEC }
                .thenBy { !it.isHls && (it.sizeBytes ?: 0L) >= 500_000L }
                .thenBy { it.headers["X-Internal-Authoritative"] == "true" }
                .thenBy { !it.isHls && group.none { g -> (g.sizeBytes ?: 0L) > 0L } }
                .thenBy { it.sizeBytes ?: 0L }
                .thenBy { it.bitrateKbps ?: 0 }
        )
    }
}
