package com.melmeligy.mediadownloader.intercept

/**
 * Pure classification rules for intercepted network traffic.
 *
 * Recognises HLS manifests (`.m3u8`, `application/x-mpegURL`,
 * `application/vnd.apple.mpegurl`), DASH manifests (`.mpd`, `application/dash+xml`),
 * plain progressive media files, and blob object URLs.
 *
 * Individual media segments, ad/VAST streams, hover thumbnails, and site promo/bumper clips
 * are deliberately rejected so only real main-content streams reach the format selector.
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
        ".ts", ".m4s", "/seg-", "seg_", "init.mp4", "init.m4s", "/init-", "_init.", "-init.",
        "init-v", "init-a", ".mp4/init", "-frag", "chunk-", "chunk_", "/dash/segment"
    )

    /** Analytics / tracking hosts that occasionally serve media-looking URLs. */
    private val NOISE_HINTS = listOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net", "/beacon", "/pixel"
    )

    /** Hover-preview, trailer, VAST preroll, image-proxy, honeypot, and site intro/bumper URL tokens. */
    private val PREVIEW_OR_BUMPER_HINTS = listOf(
        "mediabook",
        "videopreview",
        "video_preview",
        "hover_preview",
        "_preview.",
        "_preview_",
        "-preview.",
        "-preview-",
        "/preview/",
        "/previews/",
        "thumb_preview",
        "hover_video",
        "thumb-v",
        ".t.mp4",
        ".t.av1.mp4",
        "pix-fl.phncdn.com",
        "pix-egi.phncdn.com",
        ".mp4/plain/",
        "_fb.mp4",
        "180p_",
        "event_reporting",
        "_trailer.",
        "_trailer_",
        "-trailer.",
        "-trailer-",
        "/trailer/",
        "/trailers/",
        "_teaser.",
        "_teaser_",
        "-teaser.",
        "-teaser-",
        "/preroll",
        "_preroll",
        "-preroll",
        "pre-roll",
        "/midroll",
        "/postroll",
        "/bumper",
        "_bumper",
        "-bumper",
        "site_bumper",
        "site_intro",
        "intro_logo",
        "outro_logo",
        "promo_video",
        "/promo/",
        "mozaique",
        "timeline_screens",
        "videothumbs",
        "/thumbs/",
        "/thumb/",
        "storyboard"
    )

    private val moviesDownloadWallRegex = Regex("/movies/[^/]+/download(?:/|$)", RegexOption.IGNORE_CASE)
    private val rawHexOnlyRegex = Regex("^[0-9a-fA-F]{10,}$")
    private val hexPathAfterVideoRegex = Regex("/(?:videos|embed|watch)/(?:[^/?#]+/)*[0-9a-fA-F]{10,}/?$", RegexOption.IGNORE_CASE)
    private val hexLastSeg32Regex = Regex("/[0-9a-fA-F]{32,}/?$")
    private val kvsRotatingHashRegex = Regex("(/get_file/\\d+/)[0-9a-fA-F]{20,}/", RegexOption.IGNORE_CASE)

    private val rangeParamRegex =
        Regex("([?&])(bytestart|byteend|range|rn|rbuf)=[^&#]*", RegexOption.IGNORE_CASE)

    private val qualityParamRegex =
        Regex("[?&](itag|quality|res|resolution)=([^&#]+)", RegexOption.IGNORE_CASE)

    /**
     * True when [rawUrl] is an undecrypted hex cipher blob (raw hex string or hex-only terminal
     * path segment without a media file extension).
     */
    fun isHexCipherUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        if (rawHexOnlyRegex.matches(trimmed)) return true
        val noFragQuery = trimmed.substringBefore('#').substringBefore('?')
        val lastSeg = noFragQuery.trimEnd('/').substringAfterLast('/')
        if (lastSeg.contains('.')) return false
        if (hexLastSeg32Regex.containsMatchIn(noFragQuery)) return true
        if (hexPathAfterVideoRegex.containsMatchIn(noFragQuery)) return true
        return false
    }

    /**
     * Strips byte-range slicing query parameters (`bytestart`, `byteend`, `range`) used by
     * Instagram, Facebook, and YouTube/TikTok MSE players while preserving auth tokens and `#fragment`.
     */
    fun cleanMediaUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val hashIdx = trimmed.indexOf('#')
        val withoutFrag = if (hashIdx >= 0) trimmed.substring(0, hashIdx) else trimmed
        val frag = if (hashIdx >= 0) trimmed.substring(hashIdx) else ""
        if (!withoutFrag.contains('?')) return trimmed
        var cleaned = rangeParamRegex.replace(withoutFrag) { match ->
            if (match.groupValues[1] == "?") "?" else ""
        }
        cleaned = cleaned.replace("?&", "?").trimEnd('?', '&')
        return cleaned + frag
    }

    /** True if [rawUrl] belongs to an ad server, VAST preroll, hover preview, login wall, or site bumper. */
    fun isPreviewOrAdUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank()) return false
        if (AdBlocker.isAd(rawUrl)) return true
        val lower = rawUrl.lowercase()
        if (NOISE_HINTS.any { lower.contains(it) }) return true
        if (moviesDownloadWallRegex.containsMatchIn(lower)) return true
        return PREVIEW_OR_BUMPER_HINTS.any { lower.contains(it) }
    }

    /** Canonical deduplication key that preserves `itag` / `quality` query discriminators and normalizes KVS session hashes. */
    fun dedupeKey(rawUrl: String): String {
        val cleaned = cleanMediaUrl(rawUrl)
        val baseRaw = cleaned.substringBefore('#').substringBefore('?').trimEnd('/')
        val base = kvsRotatingHashRegex.replace(baseRaw, "$1_kvs_/")
        val qMatch = qualityParamRegex.find(cleaned)
        return if (qMatch != null) {
            "${base}?${qMatch.groupValues[1].lowercase()}=${qMatch.groupValues[2]}"
        } else {
            base
        }
    }

    private val mimeTypeQueryRegex = Regex("[?&]mime_type=([^&#]+)", RegexOption.IGNORE_CASE)

    /** Extracts a normalized MIME type from query hints such as `mime_type=video_mp4` or `mime_type=audio_mp4`. */
    internal fun queryMimeType(rawUrl: String): String? {
        val rawVal = mimeTypeQueryRegex.find(rawUrl)?.groupValues?.getOrNull(1)?.lowercase()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val decoded = rawVal.replace("%2f", "/")
        return when {
            decoded.contains('/') -> decoded
            decoded.startsWith("video_") -> "video/" + decoded.removePrefix("video_")
            decoded.startsWith("audio_") -> "audio/" + decoded.removePrefix("audio_")
            decoded.startsWith("image_") -> "image/" + decoded.removePrefix("image_")
            else -> decoded
        }
    }

    private fun isTikTokVideoCdnUrl(lowerUrl: String): Boolean {
        val hasTikTokCdnHost = lowerUrl.contains("tiktokcdn") ||
            (lowerUrl.contains("webapp-prime") && lowerUrl.contains("tiktok.com"))
        if (!hasTikTokCdnHost) return false
        if (!lowerUrl.contains("/video/tos/")) return false
        return !lowerUrl.contains("tplv-") && !lowerUrl.contains("-p-")
    }

    /** Heuristic to detect whether a CDN MP4 URL is actually an audio-only DASH track. */
    fun isAudioTrackUrl(rawUrl: String, mimeType: String? = null): Boolean {
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.startsWith("audio/")) return true
        if (queryMimeType(rawUrl)?.startsWith("audio/") == true) return true
        val lower = rawUrl.lowercase()
        if (lower.contains(".m4a") || lower.contains(".mp3") || lower.contains(".aac") || lower.contains(".ogg") || lower.contains(".opus")) {
            return true
        }
        // Instagram / Facebook DASH audio tracks embed audio indicators in efg or path
        if (lower.contains("audio") || lower.contains("_a.mp4") || lower.contains("dash_ln_heaac") || lower.contains("heaac") || lower.contains("ln_aac")) {
            return true
        }
        return false
    }

    fun classify(rawUrl: String, mimeType: String? = null): StreamType {
        if (rawUrl.isBlank()) return StreamType.UNKNOWN
        val cleaned = cleanMediaUrl(rawUrl)
        val url = cleaned.lowercase()
        if (!url.startsWith("blob:")) {
            if (isHexCipherUrl(cleaned)) return StreamType.UNKNOWN
            if (isPreviewOrAdUrl(cleaned)) return StreamType.UNKNOWN
        }

        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        val queryMime = queryMimeType(cleaned)
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val cleanPath = path.substringAfter("://").substringAfter('/', "")
        val fileName = cleanPath.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")

        // Never treat HTML, JSON, JS, CSS, or static images as downloadable stream manifests/files
        if ((mime != null && (
                mime.startsWith("text/html") ||
                    mime.startsWith("application/json") ||
                    mime.contains("javascript") ||
                    mime.startsWith("image/") ||
                    mime.startsWith("text/css")
                )) ||
            queryMime?.startsWith("image/") == true
        ) {
            if (ext != "m3u8" && ext != "mpd") return StreamType.UNKNOWN
        }

        return when {
            mime in HLS_MIME || ext == "m3u8" || url.contains(".m3u8") -> StreamType.HLS
            mime in DASH_MIME || ext == "mpd" || url.contains(".mpd") -> StreamType.DASH
            url.startsWith("blob:") -> StreamType.BLOB
            mime == "video/mp2t" || ext == "ts" -> StreamType.UNKNOWN
            isSegment(url) -> StreamType.UNKNOWN
            ext in PROGRESSIVE_EXT -> StreamType.PROGRESSIVE
            mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")) ->
                StreamType.PROGRESSIVE
            queryMime != null && (queryMime.startsWith("video/") || queryMime.startsWith("audio/")) ->
                StreamType.PROGRESSIVE
            isTikTokVideoCdnUrl(url) -> StreamType.PROGRESSIVE
            else -> StreamType.UNKNOWN
        }
    }

    fun isSegment(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#').trimEnd('/')
        val cleanPath = path.substringAfter("://").substringAfter('/', "")
        val fileName = cleanPath.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")
        if (ext == "ts" || ext == "m4s") return true
        return SEGMENT_HINTS.any { lower.contains(it) }
    }

    fun isMedia(type: StreamType): Boolean = type != StreamType.UNKNOWN

    /** True when the playlist body advertises multiple quality variants. */
    fun isMasterPlaylist(body: String): Boolean =
        body.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    /** Container extension to persist with a task for a given transport. */
    fun containerFor(type: StreamType, url: String, mimeType: String?): String = when (type) {
        StreamType.HLS -> "ts"
        StreamType.DASH -> "mp4"
        else -> {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
            val cleanPath = path.substringAfter("://").substringAfter('/', "")
            val fileName = cleanPath.substringAfterLast('/')
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val effectiveMime = mimeType?.lowercase()?.takeIf { it.isNotBlank() } ?: queryMimeType(url)
            when {
                effectiveMime?.contains("mpeg") == true || effectiveMime?.contains("mp3") == true -> "mp3"
                isAudioTrackUrl(url, mimeType) && (ext == "mp4" || ext.isBlank()) -> "m4a"
                ext in PROGRESSIVE_EXT -> ext
                effectiveMime?.contains("webm") == true -> "webm"
                effectiveMime?.startsWith("audio/") == true -> "m4a"
                else -> "mp4"
            }
        }
    }
}
