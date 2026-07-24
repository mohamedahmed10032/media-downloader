package com.melmeligy.mediadownloader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.ui.navigation.AppNavHost
import com.melmeligy.mediadownloader.ui.theme.MediaDownloaderTheme
import com.melmeligy.mediadownloader.ui.theme.ThemeViewModel

/** Applies the current theme and hosts the navigation graph. */
@Composable
fun AppRoot(
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit
) {
    val themeViewModel: ThemeViewModel = hiltViewModel()
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()

    MediaDownloaderTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNavHost(sharedUrl = sharedUrl, onSharedUrlConsumed = onSharedUrlConsumed)
        }
    }
}
