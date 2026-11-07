package com.melmeligy.mediadownloader.ui.quality

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.ui.common.ErrorState
import com.melmeligy.mediadownloader.ui.common.LoadingState
import com.melmeligy.mediadownloader.ui.common.asMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityScreen(
    url: String,
    onQueued: () -> Unit,
    onBack: () -> Unit,
    onOpenInBrowser: ((String) -> Unit)? = null,
    viewModel: QualityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.queued.collect { onQueued() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quality_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val s = state) {
            is Resource.Loading -> LoadingState(
                modifier = Modifier.padding(padding),
                message = "Resolving available formats & sizes…"
            )
            is Resource.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ErrorState(
                    message = s.error.asMessage(),
                    icon = Icons.Rounded.SearchOff,
                    modifier = Modifier.weight(1f, fill = false),
                    onRetry = viewModel::resolve
                )
                if (onOpenInBrowser != null && url.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onOpenInBrowser(url) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Open in Browser to Auto-Catch Stream")
                    }
                }
            }
            is Resource.Success -> QualityContent(
                resolved = s.data,
                contentPadding = padding,
                onDownload = viewModel::download
            )
        }
    }
}

@Composable
private fun QualityContent(
    resolved: ResolvedMedia,
    contentPadding: PaddingValues,
    onDownload: (MediaStream) -> Unit
) {
    val video = resolved.streams.filter { it.type == MediaType.VIDEO }
    val audio = resolved.streams.filter { it.type == MediaType.AUDIO }
    val image = resolved.streams.filter { it.type == MediaType.IMAGE }

    var selectedStream by remember(resolved) {
        mutableStateOf(resolved.streams.firstOrNull())
    }

    val videoTitle = stringResource(R.string.quality_video)
    val audioTitle = stringResource(R.string.quality_audio)
    val imageTitle = stringResource(R.string.quality_image)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                MediaPreviewCard(resolved = resolved)
            }
            section(
                title = videoTitle,
                icon = Icons.Rounded.Videocam,
                streams = video,
                selectedId = selectedStream?.id,
                onSelect = { selectedStream = it },
                onQuickDownload = onDownload
            )
            section(
                title = audioTitle,
                icon = Icons.Rounded.Audiotrack,
                streams = audio,
                selectedId = selectedStream?.id,
                onSelect = { selectedStream = it },
                onQuickDownload = onDownload
            )
            section(
                title = imageTitle,
                icon = Icons.Rounded.Image,
                streams = image,
                selectedId = selectedStream?.id,
                onSelect = { selectedStream = it },
                onQuickDownload = onDownload
            )
        }

        // Sticky bottom download bar (Snaptube-style instant confirmation bar)
        val current = selectedStream
        if (current != null) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "${current.label} (${current.container.uppercase()})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatStreamSize(current),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { onDownload(current) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.quality_download),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPreviewCard(resolved: ResolvedMedia) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!resolved.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = resolved.thumbnailUrl,
                        contentDescription = resolved.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resolved.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${resolved.extractor} • ${resolved.streams.size} formats available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    icon: ImageVector,
    streams: List<MediaStream>,
    selectedId: String?,
    onSelect: (MediaStream) -> Unit,
    onQuickDownload: (MediaStream) -> Unit
) {
    if (streams.isEmpty()) return
    item {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    items(streams, key = { it.id }) { stream ->
        StreamRow(
            stream = stream,
            isSelected = stream.id == selectedId,
            onSelect = { onSelect(stream) },
            onDownloadClick = { onQuickDownload(stream) }
        )
    }
}

@Composable
private fun StreamRow(
    stream: MediaStream,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val sizeText = formatStreamSize(stream)
    val isHd = (stream.height ?: 0) >= 720 || stream.label.contains("HD", ignoreCase = true)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stream.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FormatBadge(text = stream.container.uppercase(), highlight = false)
                    if (isHd) {
                        Spacer(modifier = Modifier.width(6.dp))
                        FormatBadge(text = if ((stream.height ?: 0) >= 1080) "FHD" else "HD", highlight = true)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sizeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDownloadClick) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.quality_download),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun FormatBadge(text: String, highlight: Boolean) {
    val bg = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

@Composable
private fun formatStreamSize(stream: MediaStream): String = when {
    stream.isHls && stream.bitrateKbps != null -> "HLS stream • ~${stream.bitrateKbps} kbps"
    stream.isHls -> "HLS adaptive stream"
    stream.sizeBytes != null && stream.sizeBytes > 0 -> "~${FormatUtils.formatBytes(stream.sizeBytes)} • Fast multi-thread"
    else -> stringResource(R.string.quality_unknown_size)
}

