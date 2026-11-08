package com.melmeligy.mediadownloader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.ui.common.DownloadRow
import com.melmeligy.mediadownloader.ui.common.RequestRuntimePermissions

private data class QuickSite(val name: String, val url: String)

private val QUICK_SITES = listOf(
    QuickSite("YouTube", "https://m.youtube.com"),
    QuickSite("Instagram", "https://www.instagram.com"),
    QuickSite("TikTok", "https://www.tiktok.com"),
    QuickSite("Facebook", "https://m.facebook.com"),
    QuickSite("Vimeo", "https://vimeo.com"),
    QuickSite("Dailymotion", "https://www.dailymotion.com"),
    QuickSite("SoundCloud", "https://m.soundcloud.com"),
    QuickSite("Internet Archive", "https://archive.org/details/movies")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenBrowser: (String?) -> Unit,
    onDetect: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    val recent by viewModel.recent.collectAsStateWithLifecycle()

    // Ask for the runtime permissions the app needs (notifications, legacy storage).
    RequestRuntimePermissions()

    fun inspectClipboard() {
        val raw = clipboard.getText()?.text?.trim()
        if (!raw.isNullOrBlank()) {
            val clean = UrlUtils.extractFirstUrl(raw) ?: if (viewModel.isUrl(raw)) UrlUtils.normalize(raw) else null
            if (clean != null) {
                clipboardUrl = clean
                if (input.isBlank()) {
                    input = clean
                }
            }
        }
    }

    // Inspect clipboard initially
    LaunchedEffect(Unit) {
        inspectClipboard()
    }

    // Automatically inspect clipboard on resume every time user switches back to the app
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                inspectClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasClipboardUrl = !clipboardUrl.isNullOrBlank()

    fun submit() {
        var text = input.trim()
        if (text.isEmpty() && hasClipboardUrl) {
            text = clipboardUrl.orEmpty().trim()
            input = text
        }
        if (text.isEmpty()) {
            val clipText = clipboard.getText()?.text?.trim().orEmpty()
            if (clipText.isNotBlank()) {
                val clean = UrlUtils.extractFirstUrl(clipText) ?: UrlUtils.normalize(clipText) ?: clipText
                input = clean
                text = clean
            }
        }
        if (text.isEmpty()) return
        val clean = UrlUtils.extractFirstUrl(text) ?: UrlUtils.normalize(text)
        if (clean != null) {
            onDetect(clean)
        } else if (viewModel.isUrl(text)) {
            onDetect(text)
        } else {
            onOpenBrowser(text)
        }
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

        // Instant Clipboard Link Detected Card (Snaptube-style 1-tap bar)
        val detectedClip = clipboardUrl
        if (detectedClip != null && detectedClip != input.trim()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Link detected in clipboard",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = detectedClip,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val clean = UrlUtils.extractFirstUrl(detectedClip) ?: UrlUtils.normalize(detectedClip) ?: detectedClip
                                input = clean
                                onDetect(clean)
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download")
                        }
                        IconButton(onClick = { clipboardUrl = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
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
                        clipboard.getText()?.text?.let { raw ->
                            val clean = UrlUtils.extractFirstUrl(raw.trim()) ?: UrlUtils.normalize(raw.trim()) ?: raw.trim()
                            input = clean
                        }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.home_paste_from_clipboard))
                    }
                }
            )
        }

        item {
            Button(
                onClick = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7C3AED),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (input.isBlank() && hasClipboardUrl) "Paste & Download" else stringResource(R.string.home_detect),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
            }
            OutlinedButton(
                onClick = { onOpenBrowser(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_open_browser),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        item {
            Text(
                text = "Quick Platforms",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                QUICK_SITES.forEach { site ->
                    AssistChip(
                        onClick = { onOpenBrowser(site.url) },
                        label = { Text(site.name) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Public,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
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

