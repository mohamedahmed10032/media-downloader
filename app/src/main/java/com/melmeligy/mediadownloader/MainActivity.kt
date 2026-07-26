package com.melmeligy.mediadownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import com.melmeligy.mediadownloader.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val initialSharedUrl = extractSharedUrl(intent)

        setContent {
            var sharedUrl by remember { mutableStateOf(initialSharedUrl) }

            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { newIntent -> sharedUrl = extractSharedUrl(newIntent) }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }

            AppRoot(
                sharedUrl = sharedUrl,
                onSharedUrlConsumed = { sharedUrl = null }
            )
        }
    }

    /** Pulls a shared link out of an ACTION_SEND intent ("Share -> Media Downloader"). */
    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}
