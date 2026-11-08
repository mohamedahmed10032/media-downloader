package com.melmeligy.mediadownloader.ui.quality

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val SnaptubePurple = Color(0xFF7C3AED)
private val SnaptubePurpleHover = Color(0xFF6D28D9)
private val SnaptubeDarkText = Color(0xFF141414)

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

    LaunchedEffect(url) {
        if (url.isNotBlank()) viewModel.resolve(url)
    }

    LaunchedEffect(Unit) {
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
        ChooseFormatStateContent(
            state = state,
            url = url,
            contentPadding = padding,
            onRetry = { viewModel.resolve(url) },
            onOpenInBrowser = onOpenInBrowser,
            onDownload = viewModel::download
        )
    }
}

/**
 * Snaptube-authentic 2-stage format selector (`Download video as` -> `More formats / All >`)
 * used by:
 * 1. [com.melmeligy.mediadownloader.ui.share.ShareDialogActivity] (floating overlay sheet),
 * 2. [com.melmeligy.mediadownloader.ui.browser.BrowserScreen] (in-browser modal sheet), and
 * 3. [QualityScreen].
 */
@Composable
fun ChooseFormatStateContent(
    state: Resource<ResolvedMedia>,
    url: String,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onRetry: () -> Unit,
    onOpenInBrowser: ((String) -> Unit)? = null,
    onDownload: (MediaStream) -> Unit
) {
    when (state) {
        is Resource.Loading -> LoadingState(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(contentPadding),
            message = "Getting video & music formats…"
        )
        is Resource.Error -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ErrorState(
                message = state.error.asMessage(),
                icon = Icons.Rounded.SearchOff,
                modifier = Modifier.fillMaxWidth(),
                onRetry = onRetry
            )
            if (onOpenInBrowser != null && url.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onOpenInBrowser(url) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SnaptubePurple,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        text = "Open in Browser to Auto-Catch Stream",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
        is Resource.Success -> SnaptubeFormatSheetContent(
            resolved = state.data,
            contentPadding = contentPadding,
            onDownload = onDownload
        )
    }
}

@Composable
private fun SnaptubeFormatSheetContent(
    resolved: ResolvedMedia,
    contentPadding: PaddingValues,
    onDownload: (MediaStream) -> Unit
) {
    val prepared = remember(resolved) { prepareSnaptubeStreams(resolved) }
    val imageStreams = prepared.images
    val musicStreams = prepared.music
    val videoStreams = prepared.video
    val quickImages = prepared.quickImages
    val quickMusic = prepared.quickMusic
    val quickVideo = prepared.quickVideo
    val allStreams = remember(imageStreams, musicStreams, videoStreams) { imageStreams + musicStreams + videoStreams }

    var selectedStream by remember(allStreams) {
        val defaultPick = allStreams.firstOrNull { it.headers["X-Internal-Default"] == "true" }
            ?: quickVideo.lastOrNull()
            ?: quickImages.firstOrNull()
            ?: quickMusic.lastOrNull()
            ?: allStreams.firstOrNull()
        mutableStateOf(defaultPick)
    }

    var showMoreFormats by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showMoreFormats) {
        showMoreFormats = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        AnimatedContent(
            targetState = showMoreFormats,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "FormatSheetStage",
            modifier = Modifier.weight(1f, fill = false)
        ) { expanded ->
            if (!expanded) {
                CompactDownloadVideoAsView(
                    resolved = resolved,
                    quickImages = quickImages,
                    quickMusic = quickMusic,
                    quickVideo = quickVideo,
                    selectedId = selectedStream?.id,
                    onSelect = { selectedStream = it },
                    onMoreFormatsClick = { showMoreFormats = true }
                )
            } else {
                ExpandedMoreFormatsView(
                    imageStreams = imageStreams,
                    musicStreams = musicStreams,
                    videoStreams = videoStreams,
                    selectedId = selectedStream?.id,
                    onSelect = { selectedStream = it },
                    onBackClick = { showMoreFormats = false }
                )
            }
        }

        // Bottom full-width Yellow "Download" CTA button (Snaptube style)
        val current = selectedStream
        if (current != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Button(
                    onClick = { onDownload(current) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SnaptubePurple,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = stringResource(R.string.quality_download),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactDownloadVideoAsView(
    resolved: ResolvedMedia,
    quickImages: List<MediaStream>,
    quickMusic: List<MediaStream>,
    quickVideo: List<MediaStream>,
    selectedId: String?,
    onSelect: (MediaStream) -> Unit,
    onMoreFormatsClick: () -> Unit
) {
    val titleText = when {
        quickVideo.isNotEmpty() -> "Download video as"
        quickImages.isNotEmpty() -> "Download photo as"
        quickMusic.isNotEmpty() -> "Download music as"
        else -> "Download media as"
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                CompactVideoHeader(resolved = resolved)
            }
        }

        if (quickImages.isNotEmpty()) {
            item {
                SnaptubeSectionHeader(title = "Photo")
            }
            items(quickImages, key = { it.id }) { stream ->
                SnaptubeFormatRow(
                    stream = stream,
                    icon = Icons.Rounded.Image,
                    isSelected = stream.id == selectedId,
                    showSubtitle = true,
                    onClick = { onSelect(stream) }
                )
            }
        }

        if (quickImages.isNotEmpty() && (quickMusic.isNotEmpty() || quickVideo.isNotEmpty())) {
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
            }
        }

        if (quickMusic.isNotEmpty()) {
            item {
                SnaptubeSectionHeader(title = "Music")
            }
            items(quickMusic, key = { it.id }) { stream ->
                SnaptubeFormatRow(
                    stream = stream,
                    icon = Icons.Rounded.MusicNote,
                    isSelected = stream.id == selectedId,
                    showSubtitle = true,
                    onClick = { onSelect(stream) }
                )
            }
        }

        if (quickMusic.isNotEmpty() && quickVideo.isNotEmpty()) {
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
            }
        }

        if (quickVideo.isNotEmpty()) {
            item {
                SnaptubeSectionHeader(title = "Video")
            }
            items(quickVideo, key = { it.id }) { stream ->
                SnaptubeFormatRow(
                    stream = stream,
                    icon = Icons.Rounded.SmartDisplay,
                    isSelected = stream.id == selectedId,
                    showSubtitle = false,
                    onClick = { onSelect(stream) }
                )
            }
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMoreFormatsClick() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "More formats",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "All",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "All formats",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedMoreFormatsView(
    imageStreams: List<MediaStream> = emptyList(),
    musicStreams: List<MediaStream>,
    videoStreams: List<MediaStream>,
    selectedId: String?,
    onSelect: (MediaStream) -> Unit,
    onBackClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "More formats",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (imageStreams.isNotEmpty()) {
                item {
                    SnaptubeSectionHeader(title = "Photo")
                }
                items(imageStreams, key = { it.id }) { stream ->
                    SnaptubeFormatRow(
                        stream = stream,
                        icon = Icons.Rounded.Image,
                        isSelected = stream.id == selectedId,
                        showSubtitle = true,
                        onClick = { onSelect(stream) }
                    )
                }
            }

            if (imageStreams.isNotEmpty() && (musicStreams.isNotEmpty() || videoStreams.isNotEmpty())) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )
                }
            }

            if (musicStreams.isNotEmpty()) {
                item {
                    SnaptubeSectionHeader(title = "Music")
                }
                items(musicStreams, key = { it.id }) { stream ->
                    SnaptubeFormatRow(
                        stream = stream,
                        icon = Icons.Rounded.MusicNote,
                        isSelected = stream.id == selectedId,
                        showSubtitle = true,
                        onClick = { onSelect(stream) }
                    )
                }
            }

            if (musicStreams.isNotEmpty() && videoStreams.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )
                }
            }

            if (videoStreams.isNotEmpty()) {
                item {
                    SnaptubeSectionHeader(title = "Video")
                }
                items(videoStreams, key = { it.id }) { stream ->
                    SnaptubeFormatRow(
                        stream = stream,
                        icon = Icons.Rounded.SmartDisplay,
                        isSelected = stream.id == selectedId,
                        showSubtitle = true,
                        onClick = { onSelect(stream) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactVideoHeader(resolved: ResolvedMedia) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 44.dp)
                .clip(RoundedCornerShape(6.dp))
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
                val hasImages = resolved.streams.any { it.type == MediaType.IMAGE }
                val hasVideos = resolved.streams.any { it.type == MediaType.VIDEO }
                val icon = if (hasImages && !hasVideos) Icons.Rounded.Image else Icons.Rounded.Movie
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = resolved.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = resolved.extractor,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SnaptubeSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SnaptubeFormatRow(
    stream: MediaStream,
    icon: ImageVector,
    isSelected: Boolean,
    showSubtitle: Boolean,
    onClick: () -> Unit
) {
    val subtitle = stream.headers["X-Internal-Subtitle"]
        ?: defaultSubtitleFor(stream)
    val badge = stream.headers["X-Internal-Badge"]
    val sizeText = formatShortSize(stream)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stream.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (!badge.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    SnaptubeTagBadge(text = badge)
                }
            }
            if (showSubtitle && !subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = sizeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) SnaptubePurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun SnaptubeTagBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun formatShortSize(stream: MediaStream): String = when {
    stream.sizeBytes != null && stream.sizeBytes > 0 -> FormatUtils.formatBytes(stream.sizeBytes)
    stream.isHls && stream.bitrateKbps != null -> "~${stream.bitrateKbps}K"
    stream.isHls -> "HLS"
    stream.type == MediaType.IMAGE -> stream.container.uppercase()
    else -> "MP4"
}

private fun defaultSubtitleFor(stream: MediaStream): String? {
    if (stream.type == MediaType.IMAGE) {
        val w = stream.width
        val h = stream.height
        return if (w != null && h != null) {
            "${w}x${h} • High resolution picture"
        } else {
            "High resolution picture"
        }
    }
    if (stream.type == MediaType.AUDIO) {
        return if (stream.container.equals("m4a", ignoreCase = true)) {
            "M4A (128K), best for mobile play"
        } else {
            "Supports Bluetooth speakers, phones, car stereos, smartwatches, etc"
        }
    }
    val h = stream.height ?: return null
    return when {
        h <= 160 -> "Poor video quality"
        h <= 270 -> "Low quality for quick play"
        h <= 400 -> "Normal quality for quick play"
        h <= 560 -> "Clear view and quick play"
        h <= 800 -> "High details for full screen play"
        else -> "High details for big screen play"
    }
}

private data class PreparedSnaptubeStreams(
    val images: List<MediaStream>,
    val music: List<MediaStream>,
    val video: List<MediaStream>,
    val quickImages: List<MediaStream>,
    val quickMusic: List<MediaStream>,
    val quickVideo: List<MediaStream>
)

private fun prepareSnaptubeStreams(resolved: ResolvedMedia): PreparedSnaptubeStreams {
    val rawImages = resolved.streams
        .filter { it.type == MediaType.IMAGE }
        .distinctBy { it.url }
        .map { img ->
            val label = if (img.label.startsWith("Photo") || img.label.startsWith("Image")) img.label else "Photo (${img.container.uppercase()})"
            img.copy(label = label)
        }
    val quickImages = rawImages.take(2)

    // Normalize & sort video streams in Snaptube order (144p -> 240p -> 360p -> 480p -> 720p -> 1080p -> 2K -> 4K)
    val rawVideos = resolved.streams
        .filter { it.type == MediaType.VIDEO }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<MediaStream> { it.height ?: Int.MAX_VALUE }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
        )
        .map { v ->
            val h = v.height
            val normalizedLabel = when {
                v.label.startsWith("Fast") || v.label.startsWith("High quality") -> v.label
                h != null && h >= 2160 -> "High quality (4K)"
                h != null && h >= 1440 -> "High quality (2K)"
                h != null && h >= 720 -> "High quality (${h}p)"
                h != null -> "Fast (${h}p)"
                else -> v.label
            }
            v.copy(label = normalizedLabel)
        }

    var rawMusic = resolved.streams
        .filter { it.type == MediaType.AUDIO }
        .distinctBy { it.id }

    // If a non-YouTube extractor only returned video streams, synthesize Fast (M4A) and Classic MP3 options
    // using on-device hardware audio track extraction so Music section is always available like Snaptube!
    if (rawMusic.isEmpty() && rawVideos.isNotEmpty()) {
        val sourceVideo = rawVideos.firstOrNull { !it.isHls } ?: rawVideos.first()
        val vSize = sourceVideo.sizeBytes ?: 0L
        val estM4aSize = if (vSize > 0L) (vSize * 16L / 100L).coerceAtLeast(150_000L) else null
        val estMp3Size = if (vSize > 0L) (vSize * 20L / 100L).coerceAtLeast(180_000L) else null
        val baseHeaders = sourceVideo.headers + mapOf(
            "X-Internal-Extract-Audio" to "true",
            "X-Internal-Video-Bytes" to vSize.toString()
        )
        rawMusic = listOf(
            MediaStream(
                id = "${sourceVideo.id}-synth-m4a",
                url = sourceVideo.url,
                type = MediaType.AUDIO,
                container = "m4a",
                label = "Fast",
                bitrateKbps = 128,
                sizeBytes = estM4aSize,
                isHls = sourceVideo.isHls,
                headers = baseHeaders + mapOf(
                    "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                    "X-Internal-Quick" to "true"
                )
            ),
            MediaStream(
                id = "${sourceVideo.id}-synth-mp3",
                url = sourceVideo.url,
                type = MediaType.AUDIO,
                container = "mp3",
                label = "Classic MP3",
                bitrateKbps = 160,
                sizeBytes = estMp3Size,
                isHls = sourceVideo.isHls,
                headers = baseHeaders + mapOf(
                    "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                    "X-Internal-Quick" to "true",
                    "X-Internal-Default" to "true"
                )
            )
        )
    }

    val quickMusic = rawMusic.filter { it.headers["X-Internal-Quick"] == "true" }
        .ifEmpty { rawMusic.take(2) }

    val quickVideo = rawVideos.filter { it.headers["X-Internal-Quick"] == "true" }
        .ifEmpty {
            val picked = mutableListOf<MediaStream>()
            rawVideos.firstOrNull { it.height == 360 }?.let { picked += it }
            rawVideos.firstOrNull { it.height == 720 }?.let { picked += it }
            if (picked.size < 2) {
                for (v in rawVideos) {
                    if (v !in picked) picked += v
                    if (picked.size >= 2) break
                }
            }
            picked
        }

    return PreparedSnaptubeStreams(
        images = rawImages,
        music = rawMusic,
        video = rawVideos,
        quickImages = quickImages,
        quickMusic = quickMusic,
        quickVideo = quickVideo
    )
}
