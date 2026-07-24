package com.melmeligy.mediadownloader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    if (downloads.isEmpty()) {
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
        items(downloads, key = { it.id }) { item ->
            DownloadRow(
                item = item,
                onPause = { viewModel.pause(item.id) },
                onResume = { viewModel.resume(item.id) },
                onCancel = { viewModel.cancel(item.id) },
                onRetry = { viewModel.retry(item.id) }
            )
        }
    }
}
