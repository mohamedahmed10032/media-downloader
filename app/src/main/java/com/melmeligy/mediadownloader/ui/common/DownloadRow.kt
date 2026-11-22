package com.melmeligy.mediadownloader.ui.common

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.melmeligy.mediadownloader.core.util.FormatUtils
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
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatDownloadPrimaryStatus(item),
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
                        text = secondary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                if (item.isActive && item.status != DownloadStatus.QUEUED) {
                    Spacer(Modifier.height(6.dp))
                    if (item.status == DownloadStatus.FINALIZING || item.totalBytes <= 0) {
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
    Box(
        modifier = Modifier.size(52.dp).clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (!item.thumbnailUrl.isNullOrBlank()) {
            val request = androidx.compose.runtime.remember(item.thumbnailUrl) {
                coil.request.ImageRequest.Builder(context)
                    .data(item.thumbnailUrl)
                    .size(156, 156)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(shape)
            )
        } else {
            Icon(
                imageVector = iconFor(item.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
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
    val quality = item.qualityLabel.ifBlank { item.container.uppercase() }
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
        DownloadStatus.FAILED -> "Failed · tap retry"
        DownloadStatus.CANCELED -> "Canceled"
    }
}

internal fun formatDownloadSecondaryStatus(item: DownloadItem): String? {
    return when (item.status) {
        DownloadStatus.FINALIZING -> formatFinalizingStageLabel(item)
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
