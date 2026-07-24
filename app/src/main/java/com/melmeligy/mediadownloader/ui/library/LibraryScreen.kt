package com.melmeligy.mediadownloader.ui.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.ui.common.EmptyState

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    onPlay: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var renameTarget by remember { mutableStateOf<DownloadItem?>(null) }

    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()

    val titles = listOf(
        stringResource(R.string.library_videos),
        stringResource(R.string.library_audio),
        stringResource(R.string.library_images)
    )
    val items = when (tab) {
        0 -> videos
        1 -> audio
        else -> images
    }

    fun open(item: DownloadItem) {
        if (item.type == MediaType.IMAGE) {
            viewModel.openIntent(item)?.let { startActivitySafe(context, it) }
        } else {
            onPlay(item.id)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.PhotoLibrary,
                message = stringResource(R.string.library_empty)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    LibraryItemRow(
                        item = item,
                        onOpen = { open(item) },
                        onShare = { viewModel.shareIntent(item)?.let { startActivitySafe(context, it) } },
                        onRename = { renameTarget = item },
                        onDelete = { viewModel.delete(item) }
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            item = target,
            onConfirm = { newName ->
                viewModel.rename(target, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
}

@Composable
private fun LibraryItemRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Thumb(item, onOpen)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.qualityLabel.ifBlank { item.container.uppercase() }} · ${FormatUtils.formatBytes(item.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val openLabel = if (item.type == MediaType.IMAGE) R.string.action_open_with else R.string.action_play
                    val openIcon = if (item.type == MediaType.IMAGE) Icons.Rounded.OpenInNew else Icons.Rounded.PlayArrow
                    DropdownMenuItem(
                        text = { Text(stringResource(openLabel)) },
                        leadingIcon = { Icon(openIcon, null) },
                        onClick = { menuOpen = false; onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { menuOpen = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumb(item: DownloadItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.size(56.dp).clip(shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (item.type) {
                MediaType.VIDEO -> Icons.Rounded.Movie
                MediaType.AUDIO -> Icons.Rounded.MusicNote
                MediaType.IMAGE -> Icons.Rounded.Image
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        if (item.type != MediaType.AUDIO && item.contentUri != null) {
            AsyncImage(
                model = item.contentUri.toUri(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(shape)
            )
        }
    }
}

/** Launches [intent], ignoring the case where no app can handle it. */
private fun startActivitySafe(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}

@Composable
private fun RenameDialog(
    item: DownloadItem,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(item.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.action_rename)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.perm_not_now)) }
        }
    )
}
