package com.melmeligy.mediadownloader.core.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Pure formatting helpers. Kept side-effect free so they can be unit tested directly. */
object FormatUtils {

    private val fileSizeUnits = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Formats a byte count into a human readable string, e.g. 1536 -> "1.5 KB". */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, fileSizeUnits.lastIndex)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return if (digitGroups == 0) {
            "${bytes} B"
        } else {
            String.format(Locale.US, "%.1f %s", value, fileSizeUnits[digitGroups])
        }
    }

    /**
     * Formats a transfer rate adaptively:
     * - Returns "--" if bytesPerSecond <= 0
     * - Formats in "%.2f MB/s" when rate is >= 1.0 MB/s
     * - Formats in "%.1f KB/s" when rate is < 1.0 MB/s (never outputs "0.00 MB/s")
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "--"
        val mbPerSec = bytesPerSecond.toDouble() / (1024.0 * 1024.0)
        return if (mbPerSec >= 1.0) {
            String.format(Locale.US, "%.2f MB/s", mbPerSec)
        } else {
            val kbPerSec = bytesPerSecond.toDouble() / 1024.0
            String.format(Locale.US, "%.1f KB/s", kbPerSec)
        }
    }

    /** Formats an ETA in seconds into "mm:ss" / "hh:mm:ss". */
    fun formatEta(seconds: Long): String {
        if (seconds <= 0) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** Formats a 0f..1f progress into an integer percentage string. */
    fun formatPercent(progress: Float): String =
        "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"

    private const val MAX_FILENAME_UTF8_BYTES = 180

    /**
     * Produces a filesystem-safe file name, stripping characters illegal on Android /
     * FAT filesystems and capping length to [MAX_FILENAME_UTF8_BYTES] UTF-8 bytes without
     * splitting multi-byte characters (e.g., Arabic/emoji) or leaving trailing dots.
     * Never returns an empty string.
     */
    fun sanitizeFileName(raw: String, fallback: String = "download"): String {
        val cleaned = raw
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        val safe = if (cleaned.isBlank()) fallback else cleaned
        val capped = truncateUtf8Safe(safe, MAX_FILENAME_UTF8_BYTES).trim('.', ' ')
        return if (capped.isBlank()) fallback else capped
    }

    private fun truncateUtf8Safe(input: String, maxBytes: Int): String {
        if (input.toByteArray(Charsets.UTF_8).size <= maxBytes) return input
        val sb = StringBuilder(input.length)
        var bytesUsed = 0
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = Character.charCount(cp)
            val cpBytes = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (bytesUsed + cpBytes > maxBytes) break
            sb.append(input, i, i + charCount)
            bytesUsed += cpBytes
            i += charCount
        }
        return sb.toString()
    }

    /** True if [value] is within [tolerance] of [target]; small helper for tests/estimates. */
    fun approximately(value: Double, target: Double, tolerance: Double = 0.001): Boolean =
        abs(value - target) <= tolerance

    /**
     * Infers approximate audio bitrate in kbps from a known byte count and duration.
     * Returns null when inputs are missing or outside a realistic audio bitrate window.
     */
    fun inferAudioBitrateKbps(sizeBytes: Long?, durationSeconds: Long?): Int? {
        if (sizeBytes == null || sizeBytes <= 0L || durationSeconds == null || durationSeconds <= 0L) {
            return null
        }
        val kbps = ((sizeBytes * 8L) / (durationSeconds * 1000L)).toInt()
        return kbps.takeIf { it in 24..512 }
    }

    /**
     * Estimates the output file size in bytes when transcoding audio to [targetBitrateKbps]
     * (e.g., 70, 128, 160, 320 kbps).
     *
     * Prefers exact duration-based sizing (`targetBitrateKbps * 1000 * durationSeconds / 8`)
     * and falls back to proportional scaling from [sourceSizeBytes] and [sourceBitrateKbps]
     * (defaulting to 128 kbps when source bitrate is unknown).
     */
    fun estimateTranscodedAudioBytes(
        targetBitrateKbps: Int,
        durationSeconds: Long? = null,
        sourceSizeBytes: Long? = null,
        sourceBitrateKbps: Int? = null
    ): Long? {
        if (targetBitrateKbps <= 0) return sourceSizeBytes?.takeIf { it > 0L }
        if (durationSeconds != null && durationSeconds > 0L) {
            return ((targetBitrateKbps.toLong() * 1000L * durationSeconds) / 8L).coerceAtLeast(16_384L)
        }
        if (sourceSizeBytes != null && sourceSizeBytes > 0L) {
            val baseKbps = sourceBitrateKbps?.takeIf { it in 24..512 } ?: 128
            return ((sourceSizeBytes.toDouble() * targetBitrateKbps.toDouble()) / baseKbps.toDouble())
                .toLong()
                .coerceAtLeast(16_384L)
        }
        return null
    }

    val STANDARD_VIDEO_TIERS: IntArray = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160)

    private val rawPHeightRegex = Regex("""(?<!\d)(\d{3,4})p\b""", RegexOption.IGNORE_CASE)

    /**
     * Normalizes raw video dimensions (including widescreen/cropped cinema frames like `426x192`,
     * `640x272`, `682x384`, `854x384`, `1280x536`, `1920x800`, and portrait frames like `720x1280`)
     * into the nearest standard quality tier (`144, 240, 360, 480, 720, 1080, 1440, 2160`).
     */
    fun normalizeQualityHeight(width: Int?, height: Int?): Int? {
        val w = width?.takeIf { it > 0 }
        val h = height?.takeIf { it > 0 }
        val effective = when {
            w != null && h != null -> {
                if (h > w) {
                    // Portrait video (e.g. 720x1280 -> 720p, 1080x1920 -> 1080p)
                    w
                } else {
                    // Landscape / widescreen cinema: compute effective 16:9 height
                    maxOf(h, kotlin.math.round(w * 9.0 / 16.0).toInt())
                }
            }
            h != null -> h
            w != null -> kotlin.math.round(w * 9.0 / 16.0).toInt()
            else -> return null
        }
        return snapToStandardTier(effective)
    }

    private fun snapToStandardTier(effectiveHeight: Int): Int {
        var best = STANDARD_VIDEO_TIERS[0]
        var bestDiff = abs(effectiveHeight - best)
        for (i in 1 until STANDARD_VIDEO_TIERS.size) {
            val tier = STANDARD_VIDEO_TIERS[i]
            val diff = abs(effectiveHeight - tier)
            if (diff <= bestDiff) {
                best = tier
                bestDiff = diff
            }
        }
        return best
    }

    /**
     * Normalizes any raw video quality label (e.g. `"384p"`, `"Fast (192p)"`, `"536p"`) so
     * odd widescreen heights never leak into the format sheet or download card.
     */
    fun normalizeQualityLabel(rawLabel: String, width: Int? = null, height: Int? = null): String {
        val trimmed = rawLabel.trim()
        val match = rawPHeightRegex.find(trimmed)
        val parsedHeight = height?.takeIf { it > 0 }
            ?: match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        val normalized = normalizeQualityHeight(width, parsedHeight) ?: return trimmed
        if (match == null) return trimmed
        val matchedNum = match.groupValues[1].toIntOrNull()
        if (matchedNum == normalized) return trimmed
        return trimmed.replaceRange(match.range, "${normalized}p")
    }

    /**
     * Infers a standard video quality tier (`144..2160`) from all available stream metadata:
     * 1. Explicit `width` / `height`,
     * 2. URL / label resolution hints (including `_h`, `_n`, `_l`, `hd`, `sd`, `fhd`),
     * 3. Explicit `bitrateKbps` or computed `sizeBytes / durationSeconds` bitrate.
     */
    fun inferStandardQualityFromMetadata(
        url: String,
        label: String = "",
        width: Int? = null,
        height: Int? = null,
        bitrateKbps: Int? = null,
        sizeBytes: Long? = null,
        durationSeconds: Long? = null
    ): Int? {
        normalizeQualityHeight(width, height)?.let { return it }
        UrlUtils.inferResolution(url, label)?.let { inferred ->
            normalizeQualityHeight(width, inferred)?.let { return it }
        }
        val effectiveKbps = bitrateKbps?.takeIf { it > 0 } ?: run {
            if (sizeBytes != null && sizeBytes > 0L && durationSeconds != null && durationSeconds >= 10L) {
                ((sizeBytes * 8L) / (durationSeconds * 1000L)).toInt().takeIf { it > 0 }
            } else {
                null
            }
        }
        if (effectiveKbps != null) {
            return when {
                effectiveKbps >= 5_500 -> 1440
                effectiveKbps >= 2_200 -> 1080
                effectiveKbps >= 1_050 -> 720
                effectiveKbps >= 550 -> 480
                effectiveKbps >= 280 -> 360
                else -> 240
            }
        }
        return null
    }

    /**
     * Estimates audio output size when synthesizing M4A/MP3 from a muxed video stream.
     * Prefers exact duration-based sizing; when duration is absent, scales by realistic
     * height/size-based video bitrate so a 2.0 GB HD movie does not claim 300+ MB of audio.
     */
    fun estimateSynthesizedAudioBytes(
        targetBitrateKbps: Int,
        durationSeconds: Long? = null,
        videoSizeBytes: Long? = null,
        videoHeight: Int? = null
    ): Long? {
        if (targetBitrateKbps <= 0) return null
        if (durationSeconds != null && durationSeconds > 0L) {
            return ((targetBitrateKbps.toLong() * 1000L * durationSeconds) / 8L).coerceAtLeast(16_384L)
        }
        val vSize = videoSizeBytes?.takeIf { it > 500_000L } ?: return null
        if (vSize < 250_000_000L) {
            return if (targetBitrateKbps <= 128) {
                (vSize * 14L) / 100L
            } else {
                (((vSize * 18L) / 100L) * targetBitrateKbps.toLong()) / 160L
            }
        }
        val assumedVideoKbps = when {
            videoHeight != null && videoHeight >= 1440 -> 7_000.0
            videoHeight != null && videoHeight >= 1080 -> 3_800.0
            videoHeight != null && videoHeight >= 720 -> 2_200.0
            videoHeight != null && videoHeight >= 480 -> 1_200.0
            videoHeight != null && videoHeight > 0 -> 700.0
            else -> 3_200.0
        }
        val ratio = (targetBitrateKbps.toDouble() / assumedVideoKbps).coerceIn(0.02, 0.14)
        return (vSize.toDouble() * ratio).toLong().coerceAtLeast(16_384L)
    }
}


