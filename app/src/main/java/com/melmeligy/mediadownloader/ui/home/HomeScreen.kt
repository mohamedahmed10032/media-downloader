package com.melmeligy.mediadownloader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.ui.common.DownloadRow
import com.melmeligy.mediadownloader.ui.common.RequestRuntimePermissions

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenBrowser: (String?) -> Unit,
    onDetect: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    val recent by viewModel.recent.collectAsStateWithLifecycle()

    // Ask for the runtime permissions the app needs (notifications, legacy storage).
    RequestRuntimePermissions()

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        if (viewModel.isUrl(text)) onDetect(text) else onOpenBrowser(text)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Paste a link, or open the browser to find media you're allowed to download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.home_paste_hint)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { input = it.trim() }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.home_paste_from_clipboard))
                    }
                }
            )
        }

        item {
            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Text(
                    text = stringResource(R.string.home_detect),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            OutlinedButton(
                onClick = { onOpenBrowser(null) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null)
                Text(
                    text = stringResource(R.string.home_open_browser),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        if (recent.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(recent, key = { it.id }) { item ->
                DownloadRow(item = item)
            }
        }
    }
}
