package com.melmeligy.mediadownloader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.JsonUtils
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType

@Composable
fun DownloadRow(
    item: DownloadItem,
    modifier: Modifier = Modifier,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Thumbnail(item)
            Spacer(Modifier.width(12.dp))
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = isolateBidiForDisplay(item.title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = isolateBidiForDisplay(formatDownloadPrimaryStatus(item)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                val secondary = formatDownloadSecondaryStatus(item)
                if (secondary != null) {
                    Text(
                        text = isolateBidiForDisplay(secondary),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                if (item.isActive && item.status != DownloadStatus.QUEUED) {
                    Spacer(Modifier.height(6.dp))
                    if (item.totalBytes <= 0L) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { item.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            ActionButtons(item, onPause, onResume, onCancel, onRetry)
        }
    }
}

@Composable
private fun Thumbnail(item: DownloadItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    val candidates = remember(item.id, item.thumbnailUrl, item.contentUri, item.type) {
        resolveThumbnailCandidates(item)
    }
    val headers = remember(item.headersJson) {
        JsonUtils.jsonToMap(item.headersJson)
    }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var loaded by remember(candidates, candidateIndex) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        if (!loaded) {
            Icon(
                imageVector = iconFor(item.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        val activeCandidate = candidates.getOrNull(candidateIndex)
        if (activeCandidate != null) {
            val request = remember(activeCandidate, item.sourceUrl, headers) {
                buildThumbnailImageRequest(
                    context = context,
                    candidate = activeCandidate,
                    sourceUrl = item.sourceUrl,
                    headers = headers,
                    sizePx = 156
                )
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = {
                    loaded = false
                    if (candidateIndex + 1 < candidates.size) {
                        candidateIndex++
                    }
                },
                modifier = Modifier.size(52.dp).clip(shape)
            )
        }
    }
}

@Composable
private fun ActionButtons(
    item: DownloadItem,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?
) {
    if (item.status == DownloadStatus.FINALIZING) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                if (onPause != null) IconButton(onClick = onPause, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause")
                }
            }
            DownloadStatus.PAUSED -> {
                if (onResume != null) IconButton(onClick = onResume, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume")
                }
            }
            DownloadStatus.FAILED -> {
                if (onRetry != null) IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
                }
            }
            else -> {}
        }
        if (onCancel != null && item.status != DownloadStatus.COMPLETED) {
            IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel")
            }
        }
    }
}

private fun iconFor(type: MediaType): ImageVector = when (type) {
    MediaType.VIDEO -> Icons.Rounded.Movie
    MediaType.AUDIO -> Icons.Rounded.MusicNote
    MediaType.IMAGE -> Icons.Rounded.Image
}

internal fun formatDownloadPrimaryStatus(item: DownloadItem): String {
    val rawQuality = item.qualityLabel.ifBlank { item.container.uppercase() }
    val quality = if (item.type == MediaType.VIDEO) {
        FormatUtils.normalizeQualityLabel(rawQuality)
    } else {
        rawQuality
    }
    val safeDone = item.downloadedBytes.coerceAtLeast(0L)
    val safeTotal = if (item.totalBytes > 0L) maxOf(item.totalBytes, safeDone) else 0L
    return when (item.status) {
        DownloadStatus.QUEUED -> "$quality · Queued"
        DownloadStatus.RUNNING -> {
            val percent = if (safeTotal > 0L) " · ${FormatUtils.formatPercent(item.progress)}" else ""
            val size = if (item.isHls) {
                FormatUtils.formatBytes(safeDone)
            } else if (safeTotal > 0L) {
                "${FormatUtils.formatBytes(safeDone)} / ${FormatUtils.formatBytes(safeTotal)}"
            } else {
                FormatUtils.formatBytes(safeDone)
            }
            "$quality · $size$percent"
        }
        DownloadStatus.FINALIZING -> {
            val sizeBytes = maxOf(safeDone, safeTotal)
            if (sizeBytes > 0L) "$quality · ${FormatUtils.formatBytes(sizeBytes)}" else "$quality · Finalizing"
        }
        DownloadStatus.PAUSED -> "$quality · Paused (${FormatUtils.formatPercent(item.progress)})"
        DownloadStatus.COMPLETED -> "$quality · ${FormatUtils.formatBytes(item.totalBytes.coerceAtLeast(safeDone))}"
        DownloadStatus.FAILED -> formatFailedStatus(item.errorType)
        DownloadStatus.CANCELED -> "Canceled"
    }
}

internal fun formatFailedStatus(rawErrorType: String?): String {
    if (rawErrorType.isNullOrBlank()) return "Failed · tap retry"
    val (code, detail) = com.melmeligy.mediadownloader.core.AppError.decodeFromStorage(rawErrorType)
    val shortDetail = detail?.trim()?.take(48)?.takeIf { it.isNotEmpty() }
    val reason = when (code) {
        com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED ->
            if (shortDetail != null) "Access denied ($shortDetail)" else "Access denied"
        com.melmeligy.mediadownloader.core.AppError.EXPIRED_LINK -> "Link expired"
        com.melmeligy.mediadownloader.core.AppError.SERVER_ERROR ->
            if (shortDetail != null) "Server error ($shortDetail)" else "Server error"
        com.melmeligy.mediadownloader.core.AppError.NO_INTERNET -> "No internet"
        com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED -> "Unavailable in region"
        com.melmeligy.mediadownloader.core.AppError.INSUFFICIENT_STORAGE -> "Not enough storage"
        com.melmeligy.mediadownloader.core.AppError.NO_MEDIA_FOUND -> "Media not found"
        com.melmeligy.mediadownloader.core.AppError.UNSUPPORTED_SITE -> "Unsupported site"
        com.melmeligy.mediadownloader.core.AppError.INVALID_LINK -> "Invalid link"
        com.melmeligy.mediadownloader.core.AppError.GENERIC ->
            if (shortDetail != null) "Failed ($shortDetail)" else "Failed"
    }
    return "$reason · tap retry"
}

internal fun isolateBidiForDisplay(text: String): String {
    if (text.isEmpty()) return text
    val hasRtl = text.any { ch ->
        val dir = Character.getDirectionality(ch)
        dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }
    val hasLtrOrDigit = text.any { ch ->
        val dir = Character.getDirectionality(ch)
        dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT ||
            dir == Character.DIRECTIONALITY_EUROPEAN_NUMBER ||
            dir == Character.DIRECTIONALITY_ARABIC_NUMBER
    }
    if (!hasRtl && !hasLtrOrDigit) return text
    // Isolate embedded LTR runs (metrics, extensions, speeds, filenames) cleanly in RTL contexts,
    // and wrap mixed text in First-Strong Isolate (\u2068...\u2069).
    val ltrRunRegex = Regex("""[A-Za-z0-9][A-Za-z0-9._:/%+\-\s]*(?<=[A-Za-z0-9%])""")
    val isolatedRuns = if (hasRtl) {
        ltrRunRegex.replace(text) { m -> "\u2066${m.value}\u2069" }
    } else {
        text
    }
    return "\u2068$isolatedRuns\u2069"
}

internal fun formatDownloadSecondaryStatus(item: DownloadItem): String? {
    return when (item.status) {
        DownloadStatus.FINALIZING -> {
            val stage = formatFinalizingStageLabel(item)
            val safeDone = item.downloadedBytes.coerceAtLeast(0L)
            val safeTotal = item.totalBytes.coerceAtLeast(0L)
            if (safeTotal > 0L && safeDone < safeTotal) {
                "$stage (${FormatUtils.formatPercent(item.progress)})"
            } else {
                stage
            }
        }
        DownloadStatus.RUNNING -> {
            val safeDone = item.downloadedBytes.coerceAtLeast(0L)
            val safeTotal = if (item.totalBytes > 0L) maxOf(item.totalBytes, safeDone) else 0L
            val speedBytes = item.speedBytesPerSec.coerceAtLeast(0L)
            if (speedBytes <= 0L) {
                when {
                    safeTotal > 0L && safeDone >= safeTotal -> formatFinalizingStageLabel(item)
                    safeDone == 0L -> "Starting…"
                    else -> "Downloading…"
                }
            } else {
                val speed = FormatUtils.formatSpeed(speedBytes)
                val eta = if (item.etaSeconds > 0 && safeTotal > safeDone) {
                    " · ${FormatUtils.formatEta(item.etaSeconds)} left"
                } else ""
                "$speed$eta"
            }
        }
        else -> null
    }
}

private fun formatFinalizingStageLabel(item: DownloadItem): String {
    val headers = item.headersJson.orEmpty()
    val hasCompanionAudio = headers.contains("X-Internal-Companion-Audio")
    val isExtractAudio = headers.contains("X-Internal-Extract-Audio") || item.type == MediaType.AUDIO
    return when {
        isExtractAudio && item.container.equals("mp3", ignoreCase = true) -> "Converting to MP3…"
        isExtractAudio -> "Finalizing audio…"
        hasCompanionAudio -> "Merging video & audio…"
        item.isHls -> "Merging video…"
        else -> "Saving file…"
    }
}
