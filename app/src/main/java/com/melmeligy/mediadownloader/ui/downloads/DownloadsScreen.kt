package com.melmeligy.mediadownloader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.ui.common.DownloadRow
import com.melmeligy.mediadownloader.ui.common.EmptyState

@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val completedDownloads by viewModel.completedDownloads.collectAsStateWithLifecycle()

    if (activeDownloads.isEmpty() && completedDownloads.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.DownloadForOffline,
            message = stringResource(R.string.downloads_empty),
            modifier = Modifier.padding(contentPadding)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (activeDownloads.isNotEmpty()) {
            item(key = "header_active") {
                Text(
                    text = stringResource(R.string.downloads_active_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
            items(activeDownloads, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    onPause = { viewModel.pause(item.id) },
                    onResume = { viewModel.resume(item.id) },
                    onCancel = { viewModel.cancel(item.id) },
                    onRetry = { viewModel.retry(item.id) }
                )
            }
        }
        if (completedDownloads.isNotEmpty()) {
            item(key = "header_completed") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (activeDownloads.isNotEmpty()) 8.dp else 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.downloads_completed_header),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { viewModel.clearCompleted() }) {
                        Text(text = stringResource(R.string.downloads_clear_completed))
                    }
                }
            }
            items(completedDownloads, key = { it.id }) { item ->
                DownloadRow(item = item)
            }
        }
    }
}
