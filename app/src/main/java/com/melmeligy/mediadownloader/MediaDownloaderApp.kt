package com.melmeligy.mediadownloader

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.melmeligy.mediadownloader.intercept.AdBlocker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

/**
 * Application entry point. Enables Hilt, provides the WorkManager configuration so the
 * download worker can be constructed by Hilt, and configures Coil with the app's shared
 * OkHttpClient (carrying WebView cookies) and VideoFrameDecoder for media thumbnails.
 */
@HiltAndroidApp
class MediaDownloaderApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: OkHttpClient

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            AdBlocker.init(this@MediaDownloaderApp)
        }
        // Pre-warm system WebView User-Agent (and Chromium provider metadata) off the main thread
        // so the very first Stage-1 HTTP fetch already matches installed Chromium and Tier-2 avoids cold startup lag.
        appScope.launch {
            runCatching {
                val rawUa = android.webkit.WebSettings.getDefaultUserAgent(this@MediaDownloaderApp)
                val sanitized = com.melmeligy.mediadownloader.extractor.HeadlessWebExtractor.sanitizeWebViewUserAgent(rawUa)
                if (sanitized.isNotBlank()) {
                    com.melmeligy.mediadownloader.extractor.HeadlessWebExtractor.lastWebViewUserAgent = sanitized
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { okHttpClient }
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
}
