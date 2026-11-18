package com.melmeligy.mediadownloader.intercept

/**
 * Transport classification of an intercepted media source.
 *
 * [HLS] and [DASH] are segmented manifests, [PROGRESSIVE] is a single downloadable file,
 * and [BLOB] is an in-page object URL that was resolved to a local cache file by the
 * JavaScript bridge.
 */
enum class StreamType {
    HLS,
    DASH,
    PROGRESSIVE,
    BLOB,
    UNKNOWN
}

/**
 * The single payload produced by every producer of the interception layer
 * (WebView interceptor, OkHttp interceptor, Blob bridge).
 *
 * [headersMap] carries the live request headers (Cookie / User-Agent / Referer /
 * Authorization / Origin) required to re-fetch [url] outside of the WebView.
 */
data class MediaStreamPayload(
    val url: String,
    val streamType: StreamType,
    val headersMap: Map<String, String> = emptyMap(),
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val thumbnailUrl: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val qualityHint: Int? = null,
    val durationSeconds: Long? = null,
    val isAuthoritativePlayer: Boolean = false,
    /** Absolute path of the resolved cache file. Only set for [StreamType.BLOB]. */
    val localFile: String? = null,
    val detectedAt: Long = System.currentTimeMillis()
) {

    /** Stable de-duplication key: transport + canonical deduplication key. */
    val fingerprint: String
        get() = streamType.name + "|" + MediaSniffer.dedupeKey(url)

    /** Short label for the detected-media list in the browser. */
    val label: String
        get() = when (streamType) {
            StreamType.HLS -> "HLS stream"
            StreamType.DASH -> "DASH stream"
            StreamType.PROGRESSIVE -> "Direct file"
            StreamType.BLOB -> "Blob media"
            StreamType.UNKNOWN -> "Media"
        }

    /** Best-effort human title, used when the payload becomes a download task. */
    val suggestedTitle: String
        get() = pageTitle?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "media" }
}

/** Backward-compatible alias for unit tests referencing the earlier payload name. */
typealias CapturedPayload = MediaStreamPayload

/**
 * The download-provider contract. Anything that wants intercepted media implements this
 * and registers itself with [MediaInterceptionEngine.addSink].
 */
fun interface MediaTaskSink {
    fun onMediaDetected(payload: MediaStreamPayload)
}
