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

    /** Formats a transfer rate, e.g. 1_048_576 -> "1.0 MB/s". */
    fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "--" else "${formatBytes(bytesPerSecond)}/s"

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
}
