package com.melmeligy.mediadownloader.ui.quality

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                message = "Finding media…"
            )
            is Resource.Error -> ErrorState(
                message = s.error.asMessage(),
                icon = Icons.Rounded.SearchOff,
                modifier = Modifier.padding(padding),
                onRetry = viewModel::resolve
            )
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

    // Resolve strings here (stringResource is @Composable and can't be called inside
    // the non-composable LazyListScope content lambda).
    val videoTitle = stringResource(R.string.quality_video)
    val audioTitle = stringResource(R.string.quality_audio)
    val imageTitle = stringResource(R.string.quality_image)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = resolved.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Source: ${resolved.extractor}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
        }
        section(videoTitle, video, onDownload)
        section(audioTitle, audio, onDownload)
        section(imageTitle, image, onDownload)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    streams: List<MediaStream>,
    onDownload: (MediaStream) -> Unit
) {
    if (streams.isEmpty()) return
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
        )
    }
    items(streams, key = { it.id }) { stream ->
        StreamRow(stream = stream, onClick = { onDownload(stream) })
    }
}

@Composable
private fun StreamRow(stream: MediaStream, onClick: () -> Unit) {
    val sizeText = when {
        stream.isHls -> "HLS stream"
        stream.sizeBytes != null && stream.sizeBytes > 0 -> "~${FormatUtils.formatBytes(stream.sizeBytes)}"
        else -> stringResource(R.string.quality_unknown_size)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${stream.label} · ${stream.container.uppercase()}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = sizeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.quality_download))
            }
        }
    }
}
