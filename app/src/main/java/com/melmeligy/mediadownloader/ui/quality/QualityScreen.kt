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
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.ui.common.ErrorState
import com.melmeligy.mediadownloader.ui.common.LoadingState
import com.melmeligy.mediadownloader.ui.common.asMessage
import com.melmeligy.mediadownloader.ui.share.LoadingSkeletonStyle
import com.melmeligy.mediadownloader.ui.share.MultiImageBottomSheetContent
import com.melmeligy.mediadownloader.ui.share.MultiImageSheetSkeleton
import com.melmeligy.mediadownloader.ui.share.NeutralSheetLoadingSkeleton
import com.melmeligy.mediadownloader.ui.share.loadingSkeletonStyleForUrl
import com.melmeligy.mediadownloader.ui.share.shouldShowMultiImageSheet
import com.melmeligy.mediadownloader.ui.theme.Indigo

private val BrandAccent = Indigo

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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadingStage by viewModel.loadingStage.collectAsStateWithLifecycle()

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
                    IconButton(onClick = {
                        viewModel.cancelResolve()
                        onBack()
                    }) {
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
            loadingMessage = loadingStage,
            defaultVideoQuality = settings.defaultVideoQuality,
            defaultAudioBitrate = settings.defaultAudioBitrate,
            onRetry = { viewModel.resolve(url) },
            onCancel = {
                viewModel.cancelResolve()
                onBack()
            },
            onOpenInBrowser = onOpenInBrowser,
            onDownload = viewModel::download,
            onDownloadBatch = viewModel::downloadBatch
        )
    }
}

/**
 * Smart-routed download sheet content used by:
 * 1. [com.melmeligy.mediadownloader.ui.share.ShareDialogActivity] (floating overlay sheet),
 * 2. [com.melmeligy.mediadownloader.ui.browser.BrowserScreen] (in-browser modal sheet), and
 * 3. [QualityScreen].
 *
 * Routes single-image, multi-image carousels/slideshows, and mixed photo+video carousels to
 * [MultiImageBottomSheetContent], while keeping video/audio links on the existing format selector.
 */
@Composable
fun ChooseFormatStateContent(
    state: Resource<ResolvedMedia>,
    url: String,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    useShimmerLoading: Boolean = false,
    loadingMessage: String = "Getting media formats…",
    defaultVideoQuality: VideoQuality = VideoQuality.P720,
    defaultAudioBitrate: AudioBitrate = AudioBitrate.KBPS160,
    onRetry: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onOpenInBrowser: ((String) -> Unit)? = null,
    onDownload: (MediaStream) -> Unit,
    onDownloadBatch: ((List<GallerySlide>) -> Unit)? = null
) {
    when (state) {
        is Resource.Loading -> {
            if (useShimmerLoading) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = loadingMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (onCancel != null) {
                            androidx.compose.material3.TextButton(
                                onClick = onCancel,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("Cancel", fontSize = 13.sp)
                            }
                        }
                    }
                    when (loadingSkeletonStyleForUrl(url)) {
                        LoadingSkeletonStyle.IMAGE_GRID -> MultiImageSheetSkeleton(contentPadding = contentPadding)
                        LoadingSkeletonStyle.NEUTRAL -> NeutralSheetLoadingSkeleton(contentPadding = contentPadding)
                    }
                }
            } else {
                LoadingState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(contentPadding),
                    message = loadingMessage,
                    onCancel = onCancel
                )
            }
        }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandAccent,
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
        is Resource.Success -> {
            val resolved = state.data
            if (onDownloadBatch != null && shouldShowMultiImageSheet(resolved)) {
                MultiImageBottomSheetContent(
                    resolved = resolved,
                    contentPadding = contentPadding,
                    onDownloadSelected = onDownloadBatch
                )
            } else {
                SnaptubeFormatSheetContent(
                    resolved = resolved,
                    contentPadding = contentPadding,
                    defaultVideoQuality = defaultVideoQuality,
                    defaultAudioBitrate = defaultAudioBitrate,
                    onDownload = onDownload
                )
            }
        }
    }
}

@Composable
private fun SnaptubeFormatSheetContent(
    resolved: ResolvedMedia,
    contentPadding: PaddingValues,
    defaultVideoQuality: VideoQuality,
    defaultAudioBitrate: AudioBitrate,
    onDownload: (MediaStream) -> Unit
) {
    val prepared = remember(resolved, defaultVideoQuality, defaultAudioBitrate) {
        prepareSnaptubeStreams(
            resolved = resolved,
            preferredVideoQuality = defaultVideoQuality,
            preferredAudioBitrate = defaultAudioBitrate
        )
    }
    val imageStreams = prepared.images
    val musicStreams = prepared.music
    val videoStreams = prepared.video
    val quickImages = prepared.quickImages
    val quickMusic = prepared.quickMusic
    val quickVideo = prepared.quickVideo
    val allStreams = remember(imageStreams, musicStreams, videoStreams) { imageStreams + musicStreams + videoStreams }

    var userManuallySelected by remember(resolved) { mutableStateOf(false) }
    var selectedStream by remember(resolved) {
        mutableStateOf(
            selectDefaultStream(
                prepared = prepared,
                preferredVideoQuality = defaultVideoQuality,
                preferredAudioBitrate = defaultAudioBitrate
            )
        )
    }

    // Reactively re-apply user's default quality/bitrate if preferences finish loading or change,
    // without overriding an explicit manual tap by the user.
    LaunchedEffect(prepared, defaultVideoQuality, defaultAudioBitrate) {
        val currentId = selectedStream?.id
        if (!userManuallySelected || currentId == null) {
            selectedStream = selectDefaultStream(
                prepared = prepared,
                preferredVideoQuality = defaultVideoQuality,
                preferredAudioBitrate = defaultAudioBitrate
            )
        } else {
            // Rebind to updated MediaStream instance if ID still exists (e.g. bitrate/size updated)
            val refreshed = allStreams.firstOrNull { it.id == currentId }
            selectedStream = refreshed ?: selectDefaultStream(
                prepared = prepared,
                preferredVideoQuality = defaultVideoQuality,
                preferredAudioBitrate = defaultAudioBitrate
            )
        }
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
                    onSelect = {
                        userManuallySelected = true
                        selectedStream = it
                    },
                    onMoreFormatsClick = { showMoreFormats = true }
                )
            } else {
                ExpandedMoreFormatsView(
                    imageStreams = imageStreams,
                    musicStreams = musicStreams,
                    videoStreams = videoStreams,
                    selectedId = selectedStream?.id,
                    onSelect = {
                        userManuallySelected = true
                        selectedStream = it
                    },
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
                        containerColor = BrandAccent,
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
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val hasImages = resolved.streams.any { it.type == MediaType.IMAGE }
            val hasVideos = resolved.streams.any { it.type == MediaType.VIDEO }
            val icon = if (hasImages && !hasVideos) Icons.Rounded.Image else Icons.Rounded.Movie
            var thumbLoaded by remember(resolved.thumbnailUrl) { mutableStateOf(false) }
            if (!thumbLoaded) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            val thumbUrl = resolved.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (thumbUrl != null) {
                val streamHeaders = remember(resolved.streams) {
                    resolved.streams.firstOrNull { it.headers.isNotEmpty() }?.headers.orEmpty()
                }
                val request = remember(thumbUrl, resolved.sourceUrl, streamHeaders) {
                    val candidate = com.melmeligy.mediadownloader.download.DownloadThumbnailCache
                        .resolveExistingLocalFile(thumbUrl)
                        ?.let { com.melmeligy.mediadownloader.ui.common.ThumbnailCandidate.LocalFile(it) }
                        ?: com.melmeligy.mediadownloader.ui.common.ThumbnailCandidate.RemoteUrl(thumbUrl)
                    com.melmeligy.mediadownloader.ui.common.buildThumbnailImageRequest(
                        context = context,
                        candidate = candidate,
                        sourceUrl = resolved.sourceUrl,
                        headers = streamHeaders,
                        sizePx = 192
                    )
                }
                AsyncImage(
                    model = request,
                    contentDescription = resolved.title,
                    contentScale = ContentScale.Crop,
                    onSuccess = { thumbLoaded = true },
                    onError = { thumbLoaded = false },
                    modifier = Modifier.fillMaxSize()
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
            tint = if (isSelected) BrandAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
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

internal fun formatStreamSize(stream: MediaStream): String = when {
    stream.sizeBytes != null && stream.sizeBytes > 0 -> FormatUtils.formatBytes(stream.sizeBytes)
    stream.bitrateKbps != null && stream.bitrateKbps > 0 -> "${stream.bitrateKbps} kbps"
    stream.isHls -> "Stream"
    stream.type == MediaType.IMAGE -> stream.container.uppercase()
    else -> "MP4"
}

@Composable
private fun formatShortSize(stream: MediaStream): String = formatStreamSize(stream)

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

internal data class PreparedSnaptubeStreams(
    val images: List<MediaStream>,
    val music: List<MediaStream>,
    val video: List<MediaStream>,
    val quickImages: List<MediaStream>,
    val quickMusic: List<MediaStream>,
    val quickVideo: List<MediaStream>
)

internal fun prepareSnaptubeStreams(
    resolved: ResolvedMedia,
    preferredVideoQuality: VideoQuality = VideoQuality.P720,
    preferredAudioBitrate: AudioBitrate = AudioBitrate.KBPS160
): PreparedSnaptubeStreams {
    val rawImages = resolved.streams
        .filter { it.type == MediaType.IMAGE }
        .distinctBy { it.url }
        .sortedByDescending { it.sizeBytes ?: 0L }
        .map { img ->
            val label = if (img.label.startsWith("Photo") || img.label.startsWith("Image")) img.label else "Photo (${img.container.uppercase()})"
            img.copy(label = label)
        }
    val quickImages = rawImages.take(2)

    // Normalize & sort video streams in Snaptube order (144p -> 240p -> 360p -> 480p -> 720p -> 1080p -> 2K -> 4K)
    // and deduplicate streams that resolve to the exact same URL or identical byte size.
    val seenVideoUrls = HashSet<String>()
    val seenVideoSizes = HashSet<Long>()
    val rawVideos = resolved.streams
        .filter { it.type == MediaType.VIDEO }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<MediaStream> { it.height ?: Int.MAX_VALUE }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
        )
        .filter { v ->
            val cleanUrlKey = com.melmeligy.mediadownloader.intercept.MediaSniffer.dedupeKey(v.url)
            if (!seenVideoUrls.add(cleanUrlKey)) return@filter false
            val sz = v.sizeBytes
            if (!v.isHls && sz != null && sz > 0L) {
                if (!seenVideoSizes.add(sz)) return@filter false
            }
            true
        }
        .map { v ->
            val h = v.height
            val normalizedLabel = when {
                h != null && h >= 2160 -> "High quality (4K)"
                h != null && h >= 1440 -> "High quality (2K)"
                h != null && h >= 1080 -> "1080p FHD"
                h != null && h >= 720 -> "High quality (${h}p)"
                h != null && h == 480 -> "480p"
                h != null && h <= 360 -> "Fast (${h}p)"
                h != null -> "${h}p"
                v.label.startsWith("High quality (") || (v.label.startsWith("Fast (") && v.label.endsWith("p)")) -> v.label
                v.label == "Auto (HLS)" -> "Auto"
                v.label.isNotBlank() && !v.label.startsWith("Variant") -> v.label
                else -> "Video"
            }
            v.copy(label = normalizedLabel)
        }

    var rawMusic = resolved.streams
        .filter { it.type == MediaType.AUDIO }
        .distinctBy { it.id }
        .map { a ->
            if (a.headers["X-Internal-Default"] == "true") {
                a.copy(headers = a.headers - "X-Internal-Default")
            } else a
        }

    val targetMp3Kbps = preferredAudioBitrate.kbps

    // If a non-YouTube extractor only returned video streams, synthesize Fast (M4A) and Classic MP3 options
    // strictly from valid audio-bearing sources (never null/zero-size broken progressive streams or mute adaptive video).
    if (rawMusic.isEmpty() && rawVideos.isNotEmpty()) {
        val companionCarrier = rawVideos.firstOrNull {
            it.headers["X-Internal-Companion-Audio"]?.startsWith("http") == true
        }
        val validMuxedProgressive = rawVideos
            .filter {
                !it.isHls &&
                    it.headers["X-Internal-Companion-Audio"] == null &&
                    it.headers["X-Internal-Dash-VideoOnly"] != "true" &&
                    (it.sizeBytes ?: 0L) > 0L
            }
            .sortedWith(
                compareBy<MediaStream> { it.height ?: Int.MAX_VALUE }
                    .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
            )
            .firstOrNull()

        val validMuxedHls = rawVideos
            .filter {
                it.isHls &&
                    it.headers["X-Internal-Companion-Audio"] == null &&
                    it.headers["X-Internal-Dash-VideoOnly"] != "true"
            }
            .sortedWith(
                compareBy<MediaStream> { it.height ?: Int.MAX_VALUE }
                    .thenBy { it.sizeBytes?.takeIf { s -> s > 0L } ?: Long.MAX_VALUE }
            )
            .firstOrNull()

        val sourceVideo = validMuxedProgressive ?: validMuxedHls

        if (sourceVideo != null) {
            val vSize = sourceVideo.sizeBytes ?: 0L
            val durationSeconds = sourceVideo.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
                ?: rawVideos.firstNotNullOfOrNull { it.headers["X-Internal-Duration-Seconds"]?.toLongOrNull() }

            val estM4aSize = if (durationSeconds != null && durationSeconds > 0L) {
                (128_000L * durationSeconds) / 8L
            } else if (vSize > 500_000L) {
                (vSize * 14L) / 100L
            } else null

            val estMp3Size = if (durationSeconds != null && durationSeconds > 0L) {
                (targetMp3Kbps.toLong() * 1000L * durationSeconds) / 8L
            } else if (vSize > 500_000L) {
                (((vSize * 18L) / 100L) * targetMp3Kbps.toLong()) / 160L
            } else null

            val baseHeaders = sourceVideo.headers.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) } + buildMap {
                put("X-Internal-Extract-Audio", "true")
                if (vSize > 0L) put("X-Internal-Video-Bytes", vSize.toString())
                if (durationSeconds != null && durationSeconds > 0L) {
                    put("X-Internal-Duration-Seconds", durationSeconds.toString())
                }
            }
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
                    bitrateKbps = targetMp3Kbps,
                    sizeBytes = estMp3Size,
                    isHls = sourceVideo.isHls,
                    headers = baseHeaders + mapOf(
                        "X-Internal-Subtitle" to "MP3 (${targetMp3Kbps}K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true"
                    )
                )
            )
        } else if (companionCarrier != null) {
            // Adaptive video-only stream with companion audio: route synthesized audio directly to companion URL
            val companionUrl = companionCarrier.headers["X-Internal-Companion-Audio"]!!
            val audioIsHls = companionCarrier.headers["X-Internal-Companion-Audio-Hls"] == "true" ||
                companionUrl.contains(".m3u8", ignoreCase = true)
            val durationSeconds = companionCarrier.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
                ?: rawVideos.firstNotNullOfOrNull { it.headers["X-Internal-Duration-Seconds"]?.toLongOrNull() }
            val audioBytes = companionCarrier.headers["X-Internal-Audio-Bytes"]?.toLongOrNull()?.takeIf { it > 0L }
            val estM4aSize = audioBytes ?: FormatUtils.estimateTranscodedAudioBytes(128, durationSeconds)
            val estMp3Size = FormatUtils.estimateTranscodedAudioBytes(
                targetBitrateKbps = targetMp3Kbps,
                durationSeconds = durationSeconds,
                sourceSizeBytes = audioBytes,
                sourceBitrateKbps = 128
            )

            val cleanBase = companionCarrier.headers.filterKeys {
                !it.equals("X-Internal-Default", ignoreCase = true) &&
                    !it.equals("X-Internal-Companion-Audio", ignoreCase = true) &&
                    !it.equals("X-Internal-Companion-Audio-Hls", ignoreCase = true) &&
                    !it.equals("X-Internal-Dash-VideoOnly", ignoreCase = true) &&
                    !it.equals("X-Internal-Video-Bytes", ignoreCase = true) &&
                    !it.equals("X-Internal-Extract-Audio", ignoreCase = true)
            } + buildMap {
                if (audioBytes != null) put("X-Internal-Video-Bytes", audioBytes.toString())
            }
            rawMusic = listOf(
                MediaStream(
                    id = "${companionCarrier.id}-companion-m4a",
                    url = companionUrl,
                    type = MediaType.AUDIO,
                    container = "m4a",
                    label = "Fast",
                    bitrateKbps = 128,
                    sizeBytes = estM4aSize,
                    isHls = audioIsHls,
                    headers = cleanBase + mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                MediaStream(
                    id = "${companionCarrier.id}-companion-mp3",
                    url = companionUrl,
                    type = MediaType.AUDIO,
                    container = "mp3",
                    label = "Classic MP3",
                    bitrateKbps = targetMp3Kbps,
                    sizeBytes = estMp3Size,
                    isHls = audioIsHls,
                    headers = cleanBase + mapOf(
                        "X-Internal-Subtitle" to "MP3 (${targetMp3Kbps}K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true"
                    )
                )
            )
        }
    } else {
        // When a non-YouTube extractor emitted a single transcoded Classic MP3 option alongside Fast M4A
        // (not a native original MP3 file), align its target encoding bitrate and estimated size with Settings.
        rawMusic = adaptSingleTranscodedMp3Option(rawMusic, preferredAudioBitrate)
    }

    // Consistency guard: if an extractor emitted both M4A (128K) and transcoded MP3 (160K/192K/320K/70K)
    // backed by the exact same URL and raw source byte size, compute the target-bitrate MP3 size so
    // distinct bitrate options never show contradictory identical sizes.
    rawMusic = normalizeAudioOptionSizes(rawMusic)

    val quickMusic = buildQuickMusic(rawMusic, preferredAudioBitrate)
    val quickVideo = buildQuickVideo(rawVideos, preferredVideoQuality)

    return PreparedSnaptubeStreams(
        images = rawImages,
        music = rawMusic,
        video = rawVideos,
        quickImages = quickImages,
        quickMusic = quickMusic,
        quickVideo = quickVideo
    )
}

/**
 * Selects the default stream for the format sheet:
 * - Always defaults to VIDEO on video pages (even when explicit/synthesized audio exists),
 *   respecting [preferredVideoQuality] (exact match -> closest lower -> nearest available).
 * - Selects IMAGE on pure image pages.
 * - Selects AUDIO on pure audio pages, respecting [preferredAudioBitrate] (exact match -> closest lower -> nearest available).
 */
internal fun selectDefaultStream(
    prepared: PreparedSnaptubeStreams,
    preferredVideoQuality: VideoQuality = VideoQuality.P720,
    preferredAudioBitrate: AudioBitrate = AudioBitrate.KBPS160
): MediaStream? {
    if (prepared.video.isNotEmpty()) {
        return selectPreferredVideoStream(prepared.video, preferredVideoQuality)
            ?: prepared.quickVideo.lastOrNull()
            ?: prepared.video.lastOrNull()
    }

    val imageDefault = prepared.quickImages.firstOrNull() ?: prepared.images.firstOrNull()
    if (imageDefault != null) return imageDefault

    if (prepared.music.isNotEmpty()) {
        return selectPreferredAudioStream(prepared.music, preferredAudioBitrate)
            ?: prepared.quickMusic.lastOrNull()
            ?: prepared.music.lastOrNull()
    }

    return null
}

private val videoLabelHeightRegex = Regex("""\b(\d{3,4})p\b""", RegexOption.IGNORE_CASE)
private val audioSubtitleBitrateRegex = Regex("""\((\d{2,3})K\)""", RegexOption.IGNORE_CASE)

internal fun effectiveVideoHeight(stream: MediaStream): Int? {
    stream.height?.takeIf { it > 0 }?.let { return it }
    val label = stream.label
    videoLabelHeightRegex.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return when {
        label.contains("4K", ignoreCase = true) -> 2160
        label.contains("2K", ignoreCase = true) -> 1440
        else -> null
    }
}

internal fun effectiveAudioBitrateKbps(stream: MediaStream): Int? {
    stream.bitrateKbps?.takeIf { it > 0 }?.let { return it }
    audioSubtitleBitrateRegex.find(stream.label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return audioSubtitleBitrateRegex.find(stream.headers["X-Internal-Subtitle"].orEmpty())
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
}

/**
 * Deterministically selects the best video stream for [preferred]:
 * - [VideoQuality.HIGHEST]: picks the highest available resolution.
 * - Fixed resolution (e.g. 480p, 720p, 1080p):
 *   1. Exact match if available.
 *   2. Closest lower resolution (< target) if available.
 *   3. Nearest available resolution (smallest > target) when all streams exceed the target.
 */
internal fun selectPreferredVideoStream(
    videos: List<MediaStream>,
    preferred: VideoQuality
): MediaStream? {
    if (videos.isEmpty()) return null
    val withHeight = videos.mapNotNull { stream -> effectiveVideoHeight(stream)?.let { stream to it } }
    if (withHeight.isEmpty()) {
        return videos.firstOrNull { it.headers["X-Internal-Default"] == "true" } ?: videos.lastOrNull()
    }

    if (preferred == VideoQuality.HIGHEST) {
        return withHeight.maxWithOrNull(
            compareBy<Pair<MediaStream, Int>> { it.second }
                .thenBy { it.first.sizeBytes ?: -1L }
        )?.first
    }

    val target = preferred.maxHeight

    // 1. Exact resolution match
    withHeight
        .filter { it.second == target }
        .maxByOrNull { it.first.sizeBytes ?: -1L }
        ?.let { return it.first }

    // 2. Closest lower resolution
    withHeight
        .filter { it.second < target }
        .maxWithOrNull(
            compareBy<Pair<MediaStream, Int>> { it.second }
                .thenBy { it.first.sizeBytes ?: -1L }
        )
        ?.let { return it.first }

    // 3. Nearest available when all available resolutions are higher than preferred
    return withHeight
        .minWithOrNull(
            compareBy<Pair<MediaStream, Int>> { it.second }
                .thenByDescending { it.first.sizeBytes ?: -1L }
        )
        ?.first
}

/**
 * Deterministically selects the best audio stream for [preferred]:
 * 1. Exact bitrate match (preferring MP3 over M4A on an exact tie).
 * 2. Closest lower bitrate (< target).
 * 3. Nearest available bitrate (smallest > target) when all streams exceed the target.
 */
internal fun selectPreferredAudioStream(
    audios: List<MediaStream>,
    preferred: AudioBitrate
): MediaStream? {
    if (audios.isEmpty()) return null
    val withBitrate = audios.mapNotNull { stream -> effectiveAudioBitrateKbps(stream)?.let { stream to it } }
    if (withBitrate.isEmpty()) return audios.lastOrNull()

    val target = preferred.kbps
    val mp3TieBreak: (MediaStream) -> Int = { if (it.container.equals("mp3", ignoreCase = true)) 1 else 0 }

    // 1. Exact bitrate match
    withBitrate
        .filter { it.second == target }
        .maxByOrNull { mp3TieBreak(it.first) }
        ?.let { return it.first }

    // 2. Closest lower bitrate
    withBitrate
        .filter { it.second < target }
        .maxWithOrNull(
            compareBy<Pair<MediaStream, Int>> { it.second }
                .thenBy { mp3TieBreak(it.first) }
        )
        ?.let { return it.first }

    // 3. Nearest available when all streams are higher
    return withBitrate
        .minWithOrNull(
            compareBy<Pair<MediaStream, Int>> { it.second }
                .thenByDescending { mp3TieBreak(it.first) }
        )
        ?.first
}

private fun adaptSingleTranscodedMp3Option(
    music: List<MediaStream>,
    preferredAudioBitrate: AudioBitrate
): List<MediaStream> {
    val mp3Streams = music.filter { it.container.equals("mp3", ignoreCase = true) }
    if (mp3Streams.size != 1) return music
    val singleMp3 = mp3Streams.first()
    val subtitle = singleMp3.headers["X-Internal-Subtitle"].orEmpty()
    if (subtitle.startsWith("Original MP3", ignoreCase = true)) return music

    val hasSiblingSameUrl = music.any { it.id != singleMp3.id && it.url == singleMp3.url }
    val isExtractedFromVideo = singleMp3.headers["X-Internal-Extract-Audio"] == "true"
    if (!hasSiblingSameUrl && !isExtractedFromVideo) return music

    val targetKbps = preferredAudioBitrate.kbps
    val currentKbps = effectiveAudioBitrateKbps(singleMp3) ?: 160
    if (currentKbps == targetKbps) return music

    val m4aAnchor = music.firstOrNull { it.container.equals("m4a", ignoreCase = true) && it.url == singleMp3.url }
    val durationSec = singleMp3.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
    val sourceSize = m4aAnchor?.sizeBytes?.takeIf { it > 0L }
        ?: singleMp3.headers["X-Internal-Video-Bytes"]?.toLongOrNull()?.takeIf { it > 0L && !isExtractedFromVideo }
        ?: singleMp3.sizeBytes?.takeIf { it > 0L }
    val sourceKbps = when {
        m4aAnchor != null -> effectiveAudioBitrateKbps(m4aAnchor) ?: 128
        else -> currentKbps
    }
    val adjustedSize = FormatUtils.estimateTranscodedAudioBytes(
        targetBitrateKbps = targetKbps,
        durationSeconds = durationSec,
        sourceSizeBytes = sourceSize,
        sourceBitrateKbps = sourceKbps
    ) ?: singleMp3.sizeBytes

    val updatedMp3 = singleMp3.copy(
        bitrateKbps = targetKbps,
        sizeBytes = adjustedSize,
        headers = singleMp3.headers + (
            "X-Internal-Subtitle" to "MP3 (${targetKbps}K), support Bluetooth speaker, mobile phone, car, watch etc."
            )
    )
    return music.map { if (it.id == singleMp3.id) updatedMp3 else it }
}

private fun buildQuickMusic(
    rawMusic: List<MediaStream>,
    preferredAudioBitrate: AudioBitrate
): List<MediaStream> {
    if (rawMusic.size <= 2) return rawMusic
    val mp3s = rawMusic.filter { it.container.equals("mp3", ignoreCase = true) }
    val fastM4a = rawMusic.firstOrNull { it.container.equals("m4a", ignoreCase = true) }
    val preferredMp3 = selectPreferredAudioStream(mp3s, preferredAudioBitrate)
    if (fastM4a != null && preferredMp3 != null) {
        return listOf(fastM4a, preferredMp3)
    }
    val baseQuick = rawMusic.filter { it.headers["X-Internal-Quick"] == "true" }
        .ifEmpty { rawMusic.take(2) }
    val preferred = selectPreferredAudioStream(rawMusic, preferredAudioBitrate)
    if (preferred != null && baseQuick.none { it.id == preferred.id }) {
        return (baseQuick.take(1) + preferred).distinctBy { it.id }
    }
    return baseQuick
}

private fun buildQuickVideo(
    rawVideos: List<MediaStream>,
    preferredVideoQuality: VideoQuality
): List<MediaStream> {
    if (rawVideos.isEmpty()) return emptyList()
    val baseQuick = rawVideos.filter { it.headers["X-Internal-Quick"] == "true" }
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

    val preferred = selectPreferredVideoStream(rawVideos, preferredVideoQuality) ?: return baseQuick
    if (baseQuick.any { it.id == preferred.id }) return baseQuick

    // Ensure the user's preferred quality is immediately visible in the compact format sheet
    // paired with a contrasting quick option (preferring 360p fast or the first quick item).
    val companion = baseQuick.firstOrNull { it.id != preferred.id && (effectiveVideoHeight(it) ?: 0) == 360 }
        ?: baseQuick.firstOrNull { it.id != preferred.id }
        ?: rawVideos.firstOrNull { it.id != preferred.id }

    return listOfNotNull(companion, preferred)
        .distinctBy { it.id }
        .sortedWith(
            compareBy<MediaStream> { effectiveVideoHeight(it) ?: Int.MAX_VALUE }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
        )
}

internal fun normalizeAudioOptionSizes(music: List<MediaStream>): List<MediaStream> {
    if (music.size <= 1) return music
    val byUrl = music.groupBy { it.url }
    return music.map { stream ->
        val siblings = byUrl[stream.url].orEmpty()
        if (siblings.size <= 1) return@map stream

        val curSize = stream.sizeBytes ?: return@map stream
        if (curSize <= 0L) return@map stream

        val targetKbps = stream.bitrateKbps?.takeIf { it > 0 }
            ?: audioSubtitleBitrateRegex.find(stream.label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: audioSubtitleBitrateRegex.find(stream.headers["X-Internal-Subtitle"].orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (stream.container.equals("mp3", ignoreCase = true)) 160 else 128

        val m4aAnchor = siblings.firstOrNull { it.container.equals("m4a", ignoreCase = true) && it.sizeBytes == curSize }
        val sameSizeDifferentBitrate = siblings.any { other ->
            if (other.id == stream.id || other.sizeBytes != curSize) return@any false
            val otherKbps = other.bitrateKbps?.takeIf { it > 0 }
                ?: audioSubtitleBitrateRegex.find(other.label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: audioSubtitleBitrateRegex.find(other.headers["X-Internal-Subtitle"].orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (other.container.equals("mp3", ignoreCase = true)) 160 else 128
            otherKbps != targetKbps
        }

        if (sameSizeDifferentBitrate && stream.container.equals("mp3", ignoreCase = true)) {
            val anchorKbps = m4aAnchor?.bitrateKbps?.takeIf { it > 0 } ?: 128
            val durationSec = stream.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
            val adjusted = FormatUtils.estimateTranscodedAudioBytes(
                targetBitrateKbps = targetKbps,
                durationSeconds = durationSec,
                sourceSizeBytes = curSize,
                sourceBitrateKbps = anchorKbps
            )
            if (adjusted != null && adjusted != curSize) {
                val updatedHeaders = if (stream.headers["X-Internal-Video-Bytes"] == null) {
                    stream.headers + ("X-Internal-Video-Bytes" to curSize.toString())
                } else {
                    stream.headers
                }
                return@map stream.copy(
                    bitrateKbps = targetKbps,
                    sizeBytes = adjusted,
                    headers = updatedHeaders
                )
            }
        }
        stream
    }
}
