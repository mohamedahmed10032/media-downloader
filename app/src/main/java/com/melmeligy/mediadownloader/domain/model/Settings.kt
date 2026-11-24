package com.melmeligy.mediadownloader.domain.model

/** Light/dark/system theme selection. Dark is the default (dark-mode-first). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Selectable default video quality. HIGHEST picks the best available stream. */
enum class VideoQuality(val label: String, val maxHeight: Int) {
    P360("360p", 360),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
    HIGHEST("Highest available", Int.MAX_VALUE)
}

/** Selectable default audio bitrate for audio-only extraction & transcoding. */
enum class AudioBitrate(val label: String, val kbps: Int) {
    KBPS128("128 kbps", 128),
    KBPS160("160 kbps", 160),
    KBPS192("192 kbps", 192),
    KBPS320("320 kbps", 320)
}

/** All user-configurable settings, persisted via the encrypted settings store. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaultVideoQuality: VideoQuality = VideoQuality.P720,
    val defaultAudioBitrate: AudioBitrate = AudioBitrate.KBPS160,
    val maxConcurrentDownloads: Int = 3,
    val retryCount: Int = 3,
    val saveSubfolder: String = "MediaDownloader",
    val customSaveTreeUri: String? = null,
    val customSaveOrganizeByType: Boolean = false,
    val adBlockDisabledHosts: Set<String> = emptySet()
) {
    /** True when Mode B (custom SAF folder) is active; false for Mode A (Smart Media Folders). */
    val isCustomSaveLocation: Boolean
        get() = !customSaveTreeUri.isNullOrBlank()

    /** True when sub-resource ad-blocking is enabled for [urlOrHost] (popup/redirect blocking is always ON). */
    fun isAdBlockEnabledFor(urlOrHost: String?): Boolean {
        val host = normalizeAdBlockHost(urlOrHost) ?: return true
        return host !in adBlockDisabledHosts
    }

    /** Human-readable summary shown in the Settings -> Downloads -> Save location row. */
    fun saveLocationSummary(): String {
        val tree = customSaveTreeUri?.takeIf { it.isNotBlank() }
        return if (tree != null) {
            "Custom: ${formatTreeUriForDisplay(tree)}"
        } else {
            "Movies|Music|Pictures / $saveSubfolder"
        }
    }

    companion object {
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 6
        const val MIN_RETRIES = 0
        const val MAX_RETRIES = 5
        const val DEFAULT_SUBFOLDER = "MediaDownloader"

        private val ILLEGAL_SEGMENT_CHARS = Regex("""[:*?"<>|\u0000-\u001F]""")

        /** Normalizes a URL or hostname for per-site ad-blocker toggle storage (`lowercase`, no `www.`). */
        fun normalizeAdBlockHost(urlOrHost: String?): String? {
            if (urlOrHost.isNullOrBlank()) return null
            val trimmed = urlOrHost.trim()
            val rawHost = if (trimmed.contains("://")) {
                runCatching { java.net.URI(trimmed).host }.getOrNull()
                    ?: trimmed.substringAfter("://")
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringBefore(':')
            } else {
                trimmed.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            }
            return rawHost?.trim()?.lowercase()?.removePrefix("www.")?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        }

        /**
         * Sanitizes a user-supplied subfolder path for Android MediaStore `RELATIVE_PATH`.
         * Strips path traversal segments (`.` / `..`), backslashes, redundant slashes, and
         * filesystem-forbidden characters. Returns empty string if no valid segments remain.
         */
        fun sanitizeSubfolderOrEmpty(raw: String): String {
            return raw.replace('\\', '/')
                .split('/')
                .map { segment ->
                    ILLEGAL_SEGMENT_CHARS.replace(segment, "").trim()
                }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
        }

        /** Sanitizes [raw] and falls back to [DEFAULT_SUBFOLDER] when empty/invalid. */
        fun sanitizeSubfolder(raw: String): String =
            sanitizeSubfolderOrEmpty(raw).ifBlank { DEFAULT_SUBFOLDER }

        /** Converts a persisted SAF tree URI into a clean, human-readable storage path. */
        fun formatTreeUriForDisplay(uriString: String): String =
            com.melmeligy.mediadownloader.domain.model.formatTreeUriForDisplay(uriString)
    }
}

/**
 * Pure JVM helper that converts an Android SAF tree URI (e.g.
 * `content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMyClips`) or raw
 * document tree ID (`primary:Download/MyClips`, `ABCD-1234:Media/Tube`) into a clean,
 * human-readable path such as `Internal storage / Download / MyClips` or `SD Card / Media / Tube`.
 * Never returns raw `content://com.android.externalstorage.documents/...` strings.
 */
fun formatTreeUriForDisplay(uriString: String): String {
    val trimmed = uriString.trim()
    if (trimmed.isEmpty()) return "Custom folder"

    val noQuery = trimmed.substringBefore('?').substringBefore('#')

    // Extract the tree segment from SAF URIs like .../tree/<encodedTreeId>[/document/...]
    val rawTreeSegment = when {
        noQuery.contains("/tree/") -> noQuery.substringAfter("/tree/").substringBefore("/document/")
        noQuery.contains("/document/") -> noQuery.substringAfter("/document/")
        else -> noQuery
    }

    val decoded = decodePercentUtf8(rawTreeSegment)
        .replace('\\', '/')
        .trim()

    // Handle external storage / volume-prefixed IDs: "primary:Download/Clips", "primary:", "ABCD-1234:Videos"
    val colonIdx = decoded.indexOf(':')
    if (colonIdx > 0 && !decoded.startsWith("content://", ignoreCase = true)) {
        val volume = decoded.substring(0, colonIdx).trim()
        val relPath = decoded.substring(colonIdx + 1)
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (volume.equals("raw", ignoreCase = true)) {
            val cleanedRaw = relPath.dropWhile {
                it.equals("storage", ignoreCase = true) ||
                    it.equals("emulated", ignoreCase = true) ||
                    it == "0"
            }
            return if (cleanedRaw.isEmpty()) {
                "Internal storage"
            } else {
                "Internal storage / ${cleanedRaw.joinToString(" / ")}"
            }
        }

        val rootLabel = when {
            volume.equals("primary", ignoreCase = true) -> "Internal storage"
            volume.equals("home", ignoreCase = true) -> "Internal storage / Documents"
            else -> "SD Card"
        }

        return if (relPath.isEmpty()) {
            rootLabel
        } else {
            "$rootLabel / ${relPath.joinToString(" / ")}"
        }
    }

    // Handle Downloads provider or generic document providers cleanly
    if (noQuery.contains("downloads.documents", ignoreCase = true)) {
        val segments = decoded.split('/').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("downloads", ignoreCase = true) }
        return if (segments.isEmpty()) "Downloads" else "Downloads / ${segments.joinToString(" / ")}"
    }

    val fallbackSegments = decoded
        .removePrefix("content://")
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.contains(".documents", ignoreCase = true) }

    return if (fallbackSegments.isEmpty()) "Custom folder" else fallbackSegments.joinToString(" / ")
}

private fun decodePercentUtf8(value: String): String {
    // Preserve literal '+' characters when decoding percent-encoded UTF-8 URI segments.
    return runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrDefault(value)
}
