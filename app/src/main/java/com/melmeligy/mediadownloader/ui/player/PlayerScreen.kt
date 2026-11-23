package com.melmeligy.mediadownloader.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.ui.common.ErrorState
import com.melmeligy.mediadownloader.ui.common.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    downloadId: Long,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val item by viewModel.item.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item?.title ?: stringResource(R.string.player_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is PlayerUiState.Loading -> {
                LoadingState(
                    modifier = Modifier.padding(padding),
                    message = stringResource(R.string.player_opening)
                )
            }
            is PlayerUiState.Missing -> {
                ErrorState(
                    message = stringResource(R.string.player_error_missing),
                    icon = Icons.Rounded.ErrorOutline,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
            is PlayerUiState.Ready -> {
                val uriString = state.uriString
                val genericPlaybackError = stringResource(R.string.player_error_playback)
                var playbackError by remember(uriString) { mutableStateOf<String?>(null) }

                val exoPlayer = remember(uriString) {
                    val audioAttrs = AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build()
                    ExoPlayer.Builder(context)
                        .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
                        .build()
                        .apply {
                            setMediaItem(MediaItem.fromUri(uriString.toUri()))
                            prepare()
                            playWhenReady = true
                        }
                }

                DisposableEffect(exoPlayer, genericPlaybackError) {
                    val listener = object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            playbackError = error.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: genericPlaybackError
                        }
                    }
                    exoPlayer.addListener(listener)
                    onDispose { exoPlayer.removeListener(listener) }
                }

                DisposableEffect(lifecycleOwner, exoPlayer) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) {
                            exoPlayer.pause()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                DisposableEffect(uriString) {
                    onDispose { exoPlayer.release() }
                }

                if (playbackError != null) {
                    ErrorState(
                        message = playbackError!!,
                        icon = Icons.Rounded.ErrorOutline,
                        modifier = Modifier.fillMaxSize().padding(padding),
                        onRetry = {
                            playbackError = null
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    setShowNextButton(false)
                                    setShowPreviousButton(false)
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            update = { view ->
                                if (view.player !== exoPlayer) {
                                    view.player = exoPlayer
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
