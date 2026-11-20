package com.melmeligy.mediadownloader.ui.share

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.ui.theme.Indigo

/**
 * Smart routing predicate:
 * - True for single-image posts, multi-image carousels/albums/slideshows, and mixed photo+video carousels.
 * - False for standard video/audio links so they route to the existing video quality sheet without regressions.
 */
internal fun shouldShowMultiImageSheet(resolved: ResolvedMedia): Boolean {
    if (resolved.gallerySlides.isNotEmpty()) return true
    return resolved.hasImage && !resolved.hasVideo
}

/**
 * Returns the ordered list of selectable [GallerySlide]s for [resolved].
 * Prefers explicitly extracted [ResolvedMedia.gallerySlides], falling back to deduplicated image streams.
 */
internal fun extractGallerySlides(resolved: ResolvedMedia): List<GallerySlide> {
    if (resolved.gallerySlides.isNotEmpty()) {
        return resolved.gallerySlides.sortedBy { it.index }
    }
    return resolved.streams
        .filter { it.type == MediaType.IMAGE }
        .distinctBy { it.url }
        .mapIndexed { idx, stream ->
            GallerySlide(
                id = stream.id.ifBlank { "img-$idx" },
                index = idx,
                mediaUrl = stream.url,
                thumbnailUrl = stream.url,
                type = MediaType.IMAGE,
                container = stream.container.ifBlank { "jpg" },
                width = stream.width,
                height = stream.height,
                sizeBytes = stream.sizeBytes,
                headers = stream.headers,
                postId = resolved.postId
            )
        }
}

internal fun masterSelectionLabel(selectedCount: Int, totalCount: Int): String = when {
    totalCount <= 0 || selectedCount <= 0 -> "Select all"
    selectedCount >= totalCount -> "All selected"
    else -> "$selectedCount of $totalCount selected"
}

internal fun downloadCtaLabel(selectedCount: Int): String =
    if (selectedCount > 0) "Download ($selectedCount)" else "Download"

/**
 * Native Media Downloader Multi-Image / Carousel Bottom Sheet body:
 * - Bold "Download" header aligned to start
 * - Master circular selection toggle ("All selected" / "X of Y selected" / "Select all")
 * - Vertically scrollable 3-column selectable grid with tight uniform spacing, top-left circular
 *   selection badges, subtle video badges on mixed-carousel slides, and a right-edge scroll thumb
 * - Sticky full-width direct Download CTA button styled in Media Downloader's brand Indigo palette
 *   with zero ads
 */
@Composable
fun MultiImageBottomSheetContent(
    resolved: ResolvedMedia,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onDownloadSelected: (List<GallerySlide>) -> Unit
) {
    val slides = remember(resolved) { extractGallerySlides(resolved) }
    var selectedIds by remember(slides) {
        mutableStateOf(slides.map { it.id }.toSet())
    }

    val totalCount = slides.size
    val selectedCount = selectedIds.size
    val allSelected = totalCount > 0 && selectedCount == totalCount
    val gridState = rememberLazyGridState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        // 1. Header
        Text(
            text = stringResource(R.string.sheet_download_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)
        )

        // 2. Master Selection Toggle Row ("All selected" / "5 of 11 selected" / "Select all")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Checkbox) {
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        slides.map { it.id }.toSet()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MasterSelectionCircle(
                selectedCount = selectedCount,
                totalCount = totalCount
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = masterSelectionLabel(selectedCount, totalCount),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = if (selectedCount > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }

        // 3. Vertically Scrollable 3-Column Selectable Media Grid with Vertical Scrollbar
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalGridScrollbar(
                    state = gridState,
                    thumbColor = scrollbarColor
                ),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(slides, key = { it.id }) { slide ->
                val isSelected = slide.id in selectedIds
                GallerySlideTile(
                    slide = slide,
                    isSelected = isSelected,
                    onToggle = {
                        selectedIds = if (isSelected) {
                            selectedIds - slide.id
                        } else {
                            selectedIds + slide.id
                        }
                    }
                )
            }
        }

        // 4. Sticky Bottom Direct Download CTA Button (No Ads)
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Button(
                onClick = {
                    val orderedSelected = slides.filter { it.id in selectedIds }
                    if (orderedSelected.isNotEmpty()) {
                        onDownloadSelected(orderedSelected)
                    }
                },
                enabled = selectedCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo,
                    contentColor = Color.White,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = downloadCtaLabel(selectedCount),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MasterSelectionCircle(
    selectedCount: Int,
    totalCount: Int
) {
    val isAllSelected = totalCount > 0 && selectedCount >= totalCount
    val isPartial = selectedCount in 1 until totalCount
    val isActive = isAllSelected || isPartial

    val bgColor by animateColorAsState(
        targetValue = if (isActive) Indigo else Color.Transparent,
        animationSpec = tween(150),
        label = "MasterToggleBg"
    )

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(bgColor)
            .then(
                if (!isActive) {
                    Modifier.border(
                        width = 1.75.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            isAllSelected -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "All selected",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
            isPartial -> Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = "Partially selected",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun GallerySlideTile(
    slide: GallerySlide,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val tileShape = RoundedCornerShape(10.dp)

    val imageRequest = remember(slide.thumbnailUrl, slide.headers) {
        ImageRequest.Builder(context)
            .data(slide.thumbnailUrl)
            .crossfade(true)
            .apply {
                slide.headers.forEach { (k, v) ->
                    if (!k.startsWith("X-Internal-", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                        addHeader(k, v)
                    }
                }
            }
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(tileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = Indigo,
                        shape = tileShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = if (isSelected) {
                    "Slide ${slide.index + 1}, selected"
                } else {
                    "Slide ${slide.index + 1}, not selected"
                }
            }
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = "Media item ${slide.index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Subtle scrim when unselected so selection state stands out immediately
        if (!isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )
        }

        // Top-left circular selection badge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(7.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isSelected) Indigo else Color.Black.copy(alpha = 0.34f))
                .border(
                    width = 1.5.dp,
                    color = if (isSelected) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.85f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Subtle bottom-right badge for video slides inside mixed photo+video carousels
        if (slide.type == MediaType.VIDEO) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Video slide",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "Video",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 12.sp
                )
            }
        }
    }
}

/**
 * Smooth shimmer/skeleton 3-column grid shown immediately inside the Bottom Sheet
 * while media links are being extracted, styled strictly with Media Downloader's theme colors.
 */
@Composable
fun MultiImageSheetSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val transition = rememberInfiniteTransition(label = "SheetShimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerOffset"
    )

    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(base, base.copy(alpha = 0.55f), highlight, base),
        start = Offset(shimmerX - 240f, 0f),
        end = Offset(shimmerX, 240f)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        Text(
            text = stringResource(R.string.sheet_download_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
        )

        // Master toggle placeholder row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(116.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(shimmerBrush)
            )
        }

        // 3-column shimmer skeleton grid (2 rows = 6 tiles for fast compact preview)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(0.78f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
        }

        // Bottom CTA placeholder bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(shimmerBrush)
            )
        }
    }
}

/**
 * Draws a clean, minimalist right-edge vertical scroll indicator on a [LazyVerticalGrid]
 * whenever its content exceeds the visible viewport height.
 */
private fun Modifier.verticalGridScrollbar(
    state: LazyGridState,
    thumbColor: Color
): Modifier = drawWithContent {
    drawContent()
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems <= 0 || visibleItems.isEmpty() || visibleItems.size >= totalItems) return@drawWithContent

    val firstIndex = state.firstVisibleItemIndex.toFloat()
    val visibleCount = visibleItems.size.toFloat()
    val proportion = (visibleCount / totalItems.toFloat()).coerceIn(0.12f, 0.85f)
    val thumbHeight = size.height * proportion
    val maxScrollableItems = (totalItems - visibleCount).coerceAtLeast(1f)
    val progress = (firstIndex / maxScrollableItems).coerceIn(0f, 1f)
    val thumbTop = progress * (size.height - thumbHeight)
    val thumbWidth = 3.dp.toPx()
    val rightInset = 4.dp.toPx()

    drawRoundRect(
        color = thumbColor,
        topLeft = Offset(x = size.width - thumbWidth - rightInset, y = thumbTop),
        size = Size(width = thumbWidth, height = thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f)
    )
}
