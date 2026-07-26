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
                    text = statusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (item.isActive && item.status != DownloadStatus.QUEUED) {
                    Spacer(Modifier.height(6.dp))
                    if (item.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { item.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            ActionButtons(item, onPause, onResume, onCancel, onRetry)
        }
    }
}

@Composable
private fun Thumbnail(item: DownloadItem) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.size(52.dp).clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (!item.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.thumbnailUrl,
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
    Row {
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                if (onPause != null) IconButton(onClick = onPause) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause")
                }
            }
            DownloadStatus.PAUSED -> {
                if (onResume != null) IconButton(onClick = onResume) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume")
                }
            }
            DownloadStatus.FAILED -> {
                if (onRetry != null) IconButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
                }
            }
            else -> {}
        }
        if (onCancel != null && item.status != DownloadStatus.COMPLETED) {
            IconButton(onClick = onCancel) {
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

private fun statusLine(item: DownloadItem): String {
    val quality = item.qualityLabel.ifBlank { item.container.uppercase() }
    return when (item.status) {
        DownloadStatus.QUEUED -> "$quality · Queued"
        DownloadStatus.RUNNING -> {
            val size = if (item.totalBytes > 0)
                "${FormatUtils.formatBytes(item.downloadedBytes)} / ${FormatUtils.formatBytes(item.totalBytes)}"
            else FormatUtils.formatBytes(item.downloadedBytes)
            val speed = FormatUtils.formatSpeed(item.speedBytesPerSec)
            val eta = if (item.etaSeconds > 0) " · ${FormatUtils.formatEta(item.etaSeconds)} left" else ""
            "$size · $speed$eta"
        }
        DownloadStatus.PAUSED -> "$quality · Paused (${FormatUtils.formatPercent(item.progress)})"
        DownloadStatus.COMPLETED -> "$quality · ${FormatUtils.formatBytes(item.totalBytes)}"
        DownloadStatus.FAILED -> "Failed · tap retry"
        DownloadStatus.CANCELED -> "Canceled"
    }
}
