package com.melmeligy.mediadownloader.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.MainActivity
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.ThemeMode
import com.melmeligy.mediadownloader.ui.quality.ChooseFormatStateContent
import com.melmeligy.mediadownloader.ui.quality.QualityViewModel
import com.melmeligy.mediadownloader.ui.theme.MediaDownloaderTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Snaptube-style transparent floating bottom sheet activity (`ActionSendDispatchActivity` equivalent).
 *
 * Launches directly over the user's current app (Instagram, TikTok, YouTube, X, Chrome, etc.)
 * when the user shares a link via `Intent.ACTION_SEND`, resolves Video and Audio qualities with
 * real sizes, and starts the background download on 1-tap while closing itself immediately.
 */
@AndroidEntryPoint
class ShareDialogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialUrl = extractUrl(intent)
        if (initialUrl.isNullOrBlank()) {
            Toast.makeText(this, "No valid link found in shared text", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            var currentUrl by remember { mutableStateOf(initialUrl) }

            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { newIntent ->
                    extractUrl(newIntent)?.let { currentUrl = it }
                }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }

            MediaDownloaderTheme(themeMode = ThemeMode.SYSTEM) {
                ShareOverlaySheet(
                    url = currentUrl,
                    onQueued = {
                        Toast.makeText(
                            this@ShareDialogActivity,
                            "Download started in background",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    },
                    onOpenInBrowser = { targetUrl ->
                        val browserIntent = Intent(this@ShareDialogActivity, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = android.net.Uri.parse(targetUrl)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(browserIntent)
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            ?: intent.dataString?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return UrlUtils.extractFirstUrl(raw) ?: UrlUtils.normalize(raw)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareOverlaySheet(
    url: String,
    onQueued: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: QualityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp

    LaunchedEffect(url) {
        viewModel.resolve(url)
    }

    LaunchedEffect(Unit) {
        viewModel.queued.collect { onQueued() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(bottom = 8.dp)
        ) {
            ChooseFormatStateContent(
                state = state,
                url = url,
                onRetry = { viewModel.resolve(url) },
                onOpenInBrowser = onOpenInBrowser,
                onDownload = viewModel::download
            )
        }
    }
}
