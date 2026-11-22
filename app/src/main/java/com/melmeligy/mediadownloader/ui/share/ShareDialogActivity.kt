package com.melmeligy.mediadownloader.ui.share

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.MainActivity
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.ui.quality.ChooseFormatStateContent
import com.melmeligy.mediadownloader.ui.quality.QualityViewModel
import com.melmeligy.mediadownloader.ui.theme.MediaDownloaderTheme
import com.melmeligy.mediadownloader.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Lightweight translucent floating overlay activity for `Intent.ACTION_SEND` (`text/plain`).
 *
 * Launches directly over the user's current app (Instagram, Pinterest, TikTok, X, Reddit,
 * Facebook, Threads, Chrome, etc.) without pulling Media Downloader's main task into the
 * foreground. Opens a native Media Downloader Modal Bottom Sheet immediately with a themed
 * shimmer skeleton and smart-routes:
 * - Single-image, multi-image carousels/slideshows, and mixed carousels -> [MultiImageBottomSheetContent]
 * - Videos and audio streams -> existing video/audio quality picker
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

            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()

            MediaDownloaderTheme(themeMode = themeMode) {
                ShareOverlaySheet(
                    url = currentUrl,
                    onQueued = { count ->
                        val message = if (count > 1) {
                            getString(R.string.toast_batch_download_started, count)
                        } else {
                            getString(R.string.toast_download_started)
                        }
                        Toast.makeText(
                            this@ShareDialogActivity,
                            message,
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, android.R.anim.fade_out)
        }
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
    onQueued: (Int) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: QualityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadingStage by viewModel.loadingStage.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.84f).dp

    LaunchedEffect(url) {
        viewModel.resolve(url)
    }

    LaunchedEffect(Unit) {
        viewModel.queuedCount.collect { count -> onQueued(count) }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.cancelResolve()
            onDismiss()
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
        ) {
            ChooseFormatStateContent(
                state = state,
                url = url,
                useShimmerLoading = true,
                loadingMessage = loadingStage,
                defaultVideoQuality = settings.defaultVideoQuality,
                defaultAudioBitrate = settings.defaultAudioBitrate,
                onRetry = { viewModel.resolve(url) },
                onCancel = {
                    viewModel.cancelResolve()
                    onDismiss()
                },
                onOpenInBrowser = onOpenInBrowser,
                onDownload = viewModel::download,
                onDownloadBatch = viewModel::downloadBatch
            )
        }
    }
}
