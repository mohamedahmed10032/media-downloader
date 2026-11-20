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

    /**
     * Produces a filesystem-safe file name, stripping characters illegal on Android /
     * FAT filesystems and trimming length. Never returns an empty string.
     */
    fun sanitizeFileName(raw: String, fallback: String = "download"): String {
        val cleaned = raw
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        val safe = if (cleaned.isBlank()) fallback else cleaned
        return if (safe.length > 120) safe.substring(0, 120).trim() else safe
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
}
