package com.melmeligy.mediadownloader.intercept

import android.net.Uri

/**
 * Pure classification rules for intercepted network traffic.
 *
 * Recognises HLS manifests (`.m3u8`, `application/x-mpegURL`,
 * `application/vnd.apple.mpegurl`), DASH manifests (`.mpd`, `application/dash+xml`),
 * plain progressive media files, and blob object URLs.
 *
 * Individual media segments are deliberately rejected so a single playing stream does
 * not flood the queue with hundreds of tasks.
 */
object MediaSniffer {

    private val HLS_MIME = setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        "vnd.apple.mpegurl"
    )

    private val DASH_MIME = setOf(
        "application/dash+xml",
        "video/vnd.mpeg.dash.mpd"
    )

    private val PROGRESSIVE_EXT = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv",
        "m4a", "mp3", "aac", "ogg", "opus", "wav", "flac"
    )

    /** Segment / init markers. Matching URLs are never surfaced as downloadable items. */
    private val SEGMENT_HINTS = listOf(
        ".m4s", "/seg-", "seg_", "init.mp4", "-frag", "chunk-", "chunk_", "/dash/segment"
    )

    /** Analytics / tracking hosts that occasionally serve media-looking URLs. */
    private val NOISE_HINTS = listOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net", "/beacon", "/pixel"
    )

    fun classify(rawUrl: String, mimeType: String? = null): StreamType {
        if (rawUrl.isBlank()) return StreamType.UNKNOWN
        val url = rawUrl.lowercase()
        if (NOISE_HINTS.any { url.contains(it) }) return StreamType.UNKNOWN

        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(url)
        val ext = path.substringAfterLast('.', "")

        return when {
            mime in HLS_MIME || ext == "m3u8" || url.contains(".m3u8") -> StreamType.HLS
            mime in DASH_MIME || ext == "mpd" || url.contains(".mpd") -> StreamType.DASH
            url.startsWith("blob:") -> StreamType.BLOB
            isSegment(url) -> StreamType.UNKNOWN
            ext in PROGRESSIVE_EXT -> StreamType.PROGRESSIVE
            mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")) ->
                StreamType.PROGRESSIVE
            else -> StreamType.UNKNOWN
        }
    }

    fun isSegment(url: String): Boolean = SEGMENT_HINTS.any { url.lowercase().contains(it) }

    fun isMedia(type: StreamType): Boolean = type != StreamType.UNKNOWN

    /** True when the playlist body advertises multiple quality variants. */
    fun isMasterPlaylist(body: String): Boolean =
        body.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    /** Container extension to persist with a task for a given transport. */
    fun containerFor(type: StreamType, url: String, mimeType: String?): String = when (type) {
        StreamType.HLS -> "ts"
        StreamType.DASH -> "mp4"
        else -> {
            val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(url)
            val ext = path.substringAfterLast('.', "").lowercase()
            when {
                ext in PROGRESSIVE_EXT -> ext
                mimeType?.contains("webm") == true -> "webm"
                mimeType?.startsWith("audio/") == true -> "m4a"
                else -> "mp4"
            }
        }
    }
}
