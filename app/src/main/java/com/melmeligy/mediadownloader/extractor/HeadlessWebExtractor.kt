package com.melmeligy.mediadownloader.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.BlobBridge
import com.melmeligy.mediadownloader.intercept.BlobBridgeInterface
import com.melmeligy.mediadownloader.intercept.HeaderExtractor
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offscreen WebView extractor that executes a webpage's JS player and GraphQL/API scripts
 * in the background (without opening a visible browser window) to capture live video and audio
 * CDN streams, cookies, and thumbnails when static HTML scraping does not expose them.
 */
@Singleton
class HeadlessWebExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedEngine: MediaInterceptionEngine,
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val dashExtractor: DashExtractor,
    private val dispatchers: DispatcherProvider
) {

    private val resRegex = Regex("(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(targetUrl: String, timeoutMs: Long = 6_800L): ResolvedMedia? = coroutineScope {
        val localCaptured = CopyOnWriteArrayList<MediaStreamPayload>()
        var capturedTitle: String? = null
        var capturedThumb: String? = null

        var webViewRef: WebView? = null
        try {
            withContext(dispatchers.main) {
                val wv = WebView(context).apply {
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadsImagesAutomatically = false
                        userAgentString = Constants.DEFAULT_USER_AGENT
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                webViewRef = wv

                val tempEngine = MediaInterceptionEngine()
                tempEngine.onPageChanged(targetUrl)
                tempEngine.addSink { payload ->
                    localCaptured.add(payload)
                    sharedEngine.submit(payload)
                }

                wv.addJavascriptInterface(
                    BlobBridgeInterface(context, wv, tempEngine),
                    BlobBridge.NAME
                )

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        runCatching {
                            val reqUrl = request.url.toString()
                            if (request.method.equals("GET", ignoreCase = true) &&
                                !reqUrl.startsWith("data:") && !reqUrl.startsWith("about:")
                            ) {
                                val accept = request.requestHeaders["Accept"]
                                val type = MediaSniffer.classify(reqUrl, accept)
                                if (MediaSniffer.isMedia(type)) {
                                    tempEngine.submit(
                                        MediaStreamPayload(
                                            url = MediaSniffer.cleanMediaUrl(reqUrl),
                                            streamType = type,
                                            headersMap = HeaderExtractor.fromRequest(request, targetUrl),
                                            pageUrl = targetUrl,
                                            mimeType = accept
                                        )
                                    )
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (!view.title.isNullOrBlank()) capturedTitle = view.title
                        runCatching {
                            view.evaluateJavascript(BlobBridge.SCRIPT, null)
                            // Trigger video metadata load & extract og:image / og:title
                            view.evaluateJavascript(
                                "(function(){try{" +
                                    "var v=document.querySelector('video'); if(v){v.muted=true;v.play().catch(function(){});}" +
                                    "var ogImg=document.querySelector('meta[property=\"og:image\"]');" +
                                    "var ogTitle=document.querySelector('meta[property=\"og:title\"]');" +
                                    "return JSON.stringify({t:ogTitle?ogTitle.content:'',i:ogImg?ogImg.content:''});" +
                                    "}catch(e){return '';}})();"
                            ) { jsonStr ->
                                runCatching {
                                    val unquoted = jsonStr?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")
                                    if (!unquoted.isNullOrBlank() && unquoted.startsWith("{")) {
                                        val obj = org.json.JSONObject(unquoted)
                                        obj.optString("t").takeIf { it.isNotBlank() }?.let { capturedTitle = it }
                                        obj.optString("i").takeIf { it.startsWith("http") }?.let { capturedThumb = it }
                                    }
                                }
                            }
                        }
                    }
                }

                wv.loadUrl(targetUrl)
            }

            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(350)
                val hasVideo = localCaptured.any { !MediaSniffer.isAudioTrackUrl(it.url, it.mimeType) }
                val hasAudio = localCaptured.any { MediaSniffer.isAudioTrackUrl(it.url, it.mimeType) }
                if (hasVideo && (hasAudio || System.currentTimeMillis() - start > 3_200L)) {
                    break
                }
            }
        } finally {
            withContext(dispatchers.main) {
                runCatching {
                    webViewRef?.stopLoading()
                    webViewRef?.destroy()
                }
            }
        }

        if (localCaptured.isEmpty()) return@coroutineScope null

        val distinctPayloads = localCaptured
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .distinctBy { MediaSniffer.cleanMediaUrl(it.url).substringBefore('?') }

        val resolved = distinctPayloads.take(8).mapIndexed { idx, payload ->
            async(dispatchers.io) {
                val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
                val headers = payload.headersMap
                when (payload.streamType) {
                    StreamType.HLS -> runCatching { hlsExtractor.extract(cleanUrl, headers)?.streams }.getOrNull().orEmpty()
                    StreamType.DASH -> runCatching { dashExtractor.extract(cleanUrl, headers)?.streams }.getOrNull().orEmpty()
                    StreamType.PROGRESSIVE -> {
                        val info = runCatching { http.probe(cleanUrl, headers) }.getOrNull()
                        val contentType = info?.contentType ?: payload.mimeType.orEmpty()
                        if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                            emptyList()
                        } else {
                            val isAudio = MediaSniffer.isAudioTrackUrl(cleanUrl, contentType)
                            val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
                            val container = MediaSniffer.containerFor(StreamType.PROGRESSIVE, cleanUrl, contentType)
                            val size = info?.contentLength?.takeIf { it > 0 } ?: payload.sizeBytes?.takeIf { it > 0 }
                            val height = resRegex.find(cleanUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            if (size != null && size in 1..15_000L) {
                                emptyList()
                            } else {
                                listOf(
                                    MediaStream(
                                        id = "hl-$idx",
                                        url = info?.finalUrl?.let { MediaSniffer.cleanMediaUrl(it) } ?: cleanUrl,
                                        type = type,
                                        container = container,
                                        label = if (type == MediaType.AUDIO) "Original Audio (${container.uppercase()})"
                                        else if (height != null) "${height}p" else "HD Video",
                                        height = height,
                                        sizeBytes = size,
                                        isHls = false,
                                        headers = headers
                                    )
                                )
                            }
                        }
                    }
                    else -> emptyList()
                }
            }
        }.awaitAll().flatten()

        if (resolved.isEmpty()) return@coroutineScope null

        val rawAudios = resolved.filter { it.type == MediaType.AUDIO }
            .distinctBy { it.url.substringBefore('?') }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val bestAudio = rawAudios.firstOrNull()

        val videos = resolved.filter { it.type == MediaType.VIDEO }
            .distinctBy { it.url.substringBefore('?') }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
            .mapIndexed { i, s ->
                val label = when {
                    s.height != null && s.height >= 1080 -> "${s.height}p FHD"
                    s.height != null && s.height >= 720 -> "${s.height}p HD"
                    s.height != null -> "${s.height}p"
                    i == 0 -> "HD Video"
                    else -> "SD Video"
                }
                if (s.headers["X-Internal-Dash-VideoOnly"] == "true" && bestAudio != null) {
                    val vSize = s.sizeBytes ?: 0L
                    val aSize = bestAudio.sizeBytes ?: 0L
                    val combined = if (vSize > 0 && aSize > 0) vSize + aSize else s.sizeBytes
                    s.copy(
                        id = "hl-v-$i",
                        label = label,
                        sizeBytes = combined,
                        headers = s.headers + mapOf(
                            "X-Internal-Companion-Audio" to bestAudio.url,
                            "X-Internal-Video-Bytes" to vSize.toString(),
                            "X-Internal-Audio-Bytes" to aSize.toString()
                        )
                    )
                } else {
                    s.copy(id = "hl-v-$i", label = label)
                }
            }

        val audios = if (bestAudio != null) {
            listOf(
                bestAudio.copy(
                    id = "hl-audio-fast",
                    container = "m4a",
                    label = "Fast",
                    headers = bestAudio.headers + mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "hl-audio-mp3",
                    container = "mp3",
                    label = "Classic MP3",
                    headers = bestAudio.headers + mapOf(
                        "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true"
                    )
                )
            )
        } else {
            emptyList()
        }

        val finalStreams = videos + audios
        if (finalStreams.isEmpty()) return@coroutineScope null

        ResolvedMedia(
            sourceUrl = targetUrl,
            title = capturedTitle?.takeIf { it.isNotBlank() } ?: UrlUtils.host(targetUrl) ?: "Media",
            thumbnailUrl = capturedThumb,
            streams = finalStreams,
            extractor = "Smart Extractor"
        )
    }
}
