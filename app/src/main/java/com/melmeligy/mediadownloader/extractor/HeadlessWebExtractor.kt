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
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offscreen WebView extractor that executes a webpage's JS player and GraphQL/API scripts
 * in the background (without opening a visible browser window) to capture live video and audio
 * CDN streams, cookies, and thumbnails when static HTML scraping does not expose them.
 *
 * Blocks non-essential heavy resources (ads, trackers, fonts, non-media images) and exits
 * early as soon as a valid media stream plus title/thumbnail metadata are captured.
 */
@Singleton
class HeadlessWebExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedEngine: MediaInterceptionEngine,
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val dashExtractor: DashExtractor,
    private val dispatchers: DispatcherProvider,
    private val progressTracker: ExtractionProgressTracker
) {

    private val resRegex = Regex("(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(targetUrl: String, timeoutMs: Long = 7_500L): ResolvedMedia? = coroutineScope {
        progressTracker.update(ExtractionProgressTracker.STAGE_HEADLESS)
        val localCaptured = CopyOnWriteArrayList<MediaStreamPayload>()
        val prefetched = consumePrefetchedMetadataHint(targetUrl)
        val capturedTitleRef = AtomicReference<String?>(
            prefetched?.first?.takeIf { !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, targetUrl) }
        )
        val capturedThumbRef = AtomicReference<String?>(
            HtmlThumbnailExtractor.resolveValidImageCandidate(prefetched?.second, targetUrl)
        )
        var currentPageUrl = targetUrl
        val lastEmbedIframeUrl = AtomicReference<String?>(null)
        val targetHostFamily = extractHostFamily(targetUrl)

        val destroyed = AtomicBoolean(false)
        val pageFinished = AtomicBoolean(false)
        val metadataScrapeTriggered = AtomicBoolean(false)
        val metadataScrapeCompleted = AtomicBoolean(false)
        val firstVideoCapturedAtMs = AtomicLong(-1L)
        val wakeSignal = Channel<Unit>(Channel.CONFLATED)

        var webViewRef: WebView? = null
        val start = System.currentTimeMillis()

        fun hasValidTitle(): Boolean {
            val t = capturedTitleRef.get()
            return !t.isNullOrBlank() && !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(t, targetUrl)
        }

        fun hasValidThumb(): Boolean = !capturedThumbRef.get().isNullOrBlank()

        fun scrapeMetadataFromWebView(
            view: WebView,
            pageUrlForResolve: String,
            tempEngine: MediaInterceptionEngine,
            isFinalPass: Boolean
        ) {
            if (destroyed.get()) return
            if (isFinalPass) {
                metadataScrapeTriggered.set(true)
            }
            runCatching {
                val pageViewTitle = view.title?.trim()
                if (!pageViewTitle.isNullOrBlank() &&
                    !pageViewTitle.startsWith("http") &&
                    !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(pageViewTitle, targetUrl) &&
                    !hasValidTitle()
                ) {
                    capturedTitleRef.set(pageViewTitle)
                }
                view.evaluateJavascript(METADATA_SCRAPE_JS) { jsonStr ->
                    if (destroyed.get()) return@evaluateJavascript
                    runCatching {
                        val unquoted = (org.json.JSONTokener(jsonStr ?: "").nextValue() as? String)
                            ?: jsonStr?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")
                        if (!unquoted.isNullOrBlank() && unquoted.startsWith("{")) {
                            val obj = org.json.JSONObject(unquoted)
                            obj.optString("t").trim().takeIf {
                                it.isNotBlank() && !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, targetUrl)
                            }?.let { capturedTitleRef.set(it) }
                            HtmlThumbnailExtractor.resolveValidImageCandidate(
                                obj.optString("i").trim(),
                                pageUrlForResolve
                            )?.let { capturedThumbRef.set(it) }
                            val t = capturedTitleRef.get()
                            val th = capturedThumbRef.get()
                            if (!t.isNullOrBlank() || !th.isNullOrBlank()) {
                                tempEngine.updatePageMetadata(t, th, targetUrl)
                                sharedEngine.updatePageMetadata(t, th, targetUrl)
                            }
                        }
                    }
                    // Never mark metadata scrape complete on an early onPageCommitVisible pass if the
                    // real title/poster haven't rendered yet (e.g. Cloudflare interstitial or lazy DOM).
                    if (isFinalPass || pageFinished.get() || (hasValidTitle() && hasValidThumb())) {
                        metadataScrapeCompleted.set(true)
                    }
                    wakeSignal.trySend(Unit)
                }
            }
        }

        lateinit var tempEngineRef: MediaInterceptionEngine

        try {
            withContext(dispatchers.main) {
                val wv = WebView(context).apply {
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                        allowContentAccess = false
                        userAgentString = Constants.DEFAULT_USER_AGENT
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                webViewRef = wv

                val tempEngine = MediaInterceptionEngine()
                tempEngineRef = tempEngine
                tempEngine.onPageChanged(targetUrl)
                if (hasValidTitle() || hasValidThumb()) {
                    tempEngine.updatePageMetadata(capturedTitleRef.get(), capturedThumbRef.get(), targetUrl)
                }
                tempEngine.addSink { payload ->
                    if (destroyed.get()) return@addSink
                    localCaptured.add(payload)
                    sharedEngine.submit(payload)
                    if (!MediaSniffer.isAudioTrackUrl(payload.url, payload.mimeType)) {
                        firstVideoCapturedAtMs.compareAndSet(-1L, System.currentTimeMillis() - start)
                    }
                    wakeSignal.trySend(Unit)
                }

                wv.addJavascriptInterface(
                    BlobBridgeInterface(context, wv, tempEngine),
                    BlobBridge.NAME
                )

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val reqUrl = request.url?.toString().orEmpty()
                        if (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://")) {
                            return true // Block intent://, market://, tel:, javascript: hijacks
                        }
                        if (AdBlocker.isAd(reqUrl) || MediaSniffer.isPreviewOrAdUrl(reqUrl)) {
                            return true // Block ad & pop-under redirects
                        }
                        if (request.isForMainFrame) {
                            val nextFamily = extractHostFamily(reqUrl)
                            val sameDomain = targetHostFamily.isNotEmpty() && nextFamily == targetHostFamily
                            val isKnownPlayerPath = reqUrl.contains("/embed", ignoreCase = true) ||
                                reqUrl.contains("/watch", ignoreCase = true) ||
                                reqUrl.contains("/play", ignoreCase = true)
                            if (!sameDomain && !isKnownPlayerPath) {
                                return true // Block off-domain top-frame ad hijack
                            }
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        val accept = request.requestHeaders["Accept"]

                        // Track sub-frame embed player URLs so media requests with stripped Referer still inherit the real embed origin
                        if (!request.isForMainFrame &&
                            accept?.contains("text/html", ignoreCase = true) == true &&
                            reqUrl.startsWith("http", ignoreCase = true) &&
                            !AdBlocker.isAd(reqUrl)
                        ) {
                            val subFamily = extractHostFamily(reqUrl)
                            if (subFamily.isNotEmpty() && subFamily != targetHostFamily) {
                                lastEmbedIframeUrl.set(reqUrl)
                            }
                        }

                        // Always inspect for media streams FIRST so real CDN streams are never blocked
                        var detectedMedia = false
                        runCatching {
                            if (request.method.equals("GET", ignoreCase = true) &&
                                !reqUrl.startsWith("data:") && !reqUrl.startsWith("about:") &&
                                !AdBlocker.isAd(reqUrl) &&
                                !MediaSniffer.isPreviewOrAdUrl(reqUrl)
                            ) {
                                val type = MediaSniffer.classify(reqUrl, accept)
                                if (MediaSniffer.isMedia(type)) {
                                    detectedMedia = true
                                    val fallbackRefererPage = lastEmbedIframeUrl.get() ?: currentPageUrl
                                    val capturedHeaders = HeaderExtractor.fromRequest(request, fallbackRefererPage)
                                    tempEngine.submit(
                                        MediaStreamPayload(
                                            url = MediaSniffer.cleanMediaUrl(reqUrl),
                                            streamType = type,
                                            headersMap = capturedHeaders,
                                            pageUrl = currentPageUrl,
                                            mimeType = accept
                                        )
                                    )
                                }
                            }
                        }

                        if (!detectedMedia && shouldBlockAuxiliaryResource(reqUrl, accept, request.isForMainFrame)) {
                            return AdBlocker.createEmptyResponse()
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        super.onPageCommitVisible(view, url)
                        if (destroyed.get()) return
                        if (!url.isNullOrBlank() && url.startsWith("http")) {
                            currentPageUrl = url
                            tempEngine.onPageChanged(url)
                        }
                        runCatching {
                            view.evaluateJavascript(BlobBridge.SCRIPT, null)
                            view.evaluateJavascript(STREAMING_TRIGGER_JS, null)
                        }
                        if (!hasValidTitle() || !hasValidThumb()) {
                            scrapeMetadataFromWebView(view, url ?: targetUrl, tempEngine, isFinalPass = false)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (destroyed.get()) return
                        pageFinished.set(true)
                        if (!url.isNullOrBlank() && url.startsWith("http")) {
                            currentPageUrl = url
                            tempEngine.onPageChanged(url)
                        }
                        runCatching {
                            view.evaluateJavascript(BlobBridge.SCRIPT, null)
                            view.evaluateJavascript(STREAMING_TRIGGER_JS, null)
                        }
                        if (!hasValidTitle() || !hasValidThumb() || !metadataScrapeCompleted.get()) {
                            scrapeMetadataFromWebView(view, url ?: targetUrl, tempEngine, isFinalPass = true)
                        } else {
                            wakeSignal.trySend(Unit)
                        }
                    }
                }

                wv.loadUrl(targetUrl)
            }

            var triedNextServerTab = false
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= timeoutMs) break

                val firstVidAt = firstVideoCapturedAtMs.get().takeIf { it >= 0L }
                val decision = evaluateEarlyExit(
                    captured = localCaptured,
                    elapsedMs = elapsed,
                    firstVideoCapturedAtMs = firstVidAt,
                    hasGoodTitle = hasValidTitle(),
                    hasThumbnail = hasValidThumb(),
                    metadataScrapeCompleted = metadataScrapeCompleted.get(),
                    pageFinished = pageFinished.get()
                )
                when (decision) {
                    EarlyExitDecision.EXIT_NOW -> break
                    EarlyExitDecision.TRIGGER_METADATA_SCRAPE -> {
                        if (!metadataScrapeTriggered.getAndSet(true)) {
                            withContext(dispatchers.main) {
                                webViewRef?.let { scrapeMetadataFromWebView(it, currentPageUrl, tempEngineRef, isFinalPass = true) }
                            }
                        }
                    }
                    EarlyExitDecision.CONTINUE_WAITING -> Unit
                }

                // If Server #0 produced nothing after 1.8s, try clicking the next watch server tab safely
                if (firstVidAt == null && !triedNextServerTab && elapsed >= NEXT_SERVER_TAB_DELAY_MS) {
                    triedNextServerTab = true
                    progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
                    withContext(dispatchers.main) {
                        runCatching { webViewRef?.evaluateJavascript(TRY_NEXT_SERVER_JS, null) }
                    }
                }

                val remaining = timeoutMs - (System.currentTimeMillis() - start)
                if (remaining <= 0L) break
                withTimeoutOrNull(minOf(remaining, EARLY_EXIT_TICK_MS)) {
                    wakeSignal.receiveCatching()
                }
            }
        } finally {
            destroyed.set(true)
            wakeSignal.close()
            withContext(dispatchers.main) {
                runCatching {
                    webViewRef?.stopLoading()
                    webViewRef?.removeJavascriptInterface(BlobBridge.NAME)
                    webViewRef?.destroy()
                }
            }
        }

        if (localCaptured.isEmpty()) return@coroutineScope null
        progressTracker.update(ExtractionProgressTracker.STAGE_QUALITIES)

        var capturedTitle = capturedTitleRef.get()
        var capturedThumb = capturedThumbRef.get()
        if (capturedTitle.isNullOrBlank() || HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(capturedTitle, targetUrl)) {
            capturedTitle = localCaptured.firstNotNullOfOrNull {
                it.pageTitle?.takeIf { t -> !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(t, targetUrl) }
            } ?: HtmlThumbnailExtractor.humanReadableTitleFromUrl(targetUrl) ?: capturedTitle
        }
        if (capturedThumb.isNullOrBlank()) {
            capturedThumb = localCaptured.firstNotNullOfOrNull {
                HtmlThumbnailExtractor.resolveValidImageCandidate(it.thumbnailUrl, targetUrl)
            }
        }
        if (!capturedTitle.isNullOrBlank() || !capturedThumb.isNullOrBlank()) {
            sharedEngine.updatePageMetadata(capturedTitle, capturedThumb, targetUrl)
        }

        val distinctPayloads = localCaptured
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .filterNot { MediaSniffer.isPreviewOrAdUrl(it.url) }
            .distinctBy { "${it.streamType}|${MediaSniffer.dedupeKey(it.url)}|${it.qualityHint ?: 0}" }

        val resolved = withCancellableHttpCalls(timeoutMs = POST_CAPTURE_PROBE_TIMEOUT_MS) {
            distinctPayloads.take(12).mapIndexed { idx, payload ->
                async(dispatchers.io) {
                    val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
                    val headers = payload.headersMap
                    when (payload.streamType) {
                        StreamType.HLS -> runCatching {
                            hlsExtractor.extractVerified(cleanUrl, headers, payload.qualityHint)?.streams
                        }.getOrNull().orEmpty()
                        StreamType.DASH -> runCatching { dashExtractor.extract(cleanUrl, headers)?.streams }.getOrNull().orEmpty()
                        StreamType.PROGRESSIVE -> {
                            val probed = probeHeadlessProgressiveWithFallback(cleanUrl, headers)
                            if (probed == null) {
                                emptyList()
                            } else {
                                val (info, workingHeaders) = probed
                                val contentType = (info.contentType ?: payload.mimeType.orEmpty()).lowercase()
                                if (ExtractorHttp.isRejectedMediaContentType(contentType)) {
                                    emptyList()
                                } else {
                                    val isAudio = MediaSniffer.isAudioTrackUrl(cleanUrl, contentType)
                                    val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
                                    val container = MediaSniffer.containerFor(StreamType.PROGRESSIVE, cleanUrl, contentType)
                                    val size = info.contentLength.takeIf { it > 0 } ?: payload.sizeBytes?.takeIf { it > 0 }
                                    val height = payload.qualityHint?.takeIf { it > 0 } ?: UrlUtils.inferResolution(cleanUrl)
                                    if (size != null && size in 1..40_000L) {
                                        emptyList()
                                    } else {
                                        val finalHeaders = LinkedHashMap<String, String>(
                                            workingHeaders.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
                                        ).apply {
                                            if (info.acceptsRanges) put("X-Internal-Accepts-Ranges", "true")
                                        }
                                        listOf(
                                            MediaStream(
                                                id = "hl-$idx",
                                                url = info.finalUrl.takeIf { it.startsWith("http") }?.let { MediaSniffer.cleanMediaUrl(it) } ?: cleanUrl,
                                                type = type,
                                                container = container,
                                                label = if (type == MediaType.AUDIO) "Original Audio (${container.uppercase()})"
                                                else if (height != null) "${height}p" else "HD Video",
                                                height = height,
                                                sizeBytes = size,
                                                isHls = false,
                                                headers = finalHeaders
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        else -> emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        if (resolved.isEmpty()) return@coroutineScope null

        val rawAudios = resolved.filter { it.type == MediaType.AUDIO }
            .distinctBy { it.url.substringBefore('?') }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val bestAudio = rawAudios.firstOrNull()

        val filteredVideos = StreamCandidateRanker.filterAndRankVideos(
            resolved.filter { it.type == MediaType.VIDEO }
                .distinctBy { it.url.substringBefore('?') }
        )
        val videos = filteredVideos
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
            val cleanAudioHeaders = bestAudio.headers.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
            val srcBytes = bestAudio.sizeBytes?.takeIf { it > 0L }
            val srcKbps = bestAudio.bitrateKbps?.takeIf { it in 24..512 } ?: 128
            val mp3Size = com.melmeligy.mediadownloader.core.util.FormatUtils.estimateTranscodedAudioBytes(
                targetBitrateKbps = 160,
                sourceSizeBytes = srcBytes,
                sourceBitrateKbps = srcKbps
            )
            val srcBytesHeader = if (srcBytes != null) mapOf("X-Internal-Video-Bytes" to srcBytes.toString()) else emptyMap()
            listOf(
                bestAudio.copy(
                    id = "hl-audio-fast",
                    container = "m4a",
                    label = "Fast",
                    bitrateKbps = srcKbps,
                    headers = cleanAudioHeaders + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "M4A (${srcKbps}K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "hl-audio-mp3",
                    container = "mp3",
                    label = "Classic MP3",
                    bitrateKbps = 160,
                    sizeBytes = mp3Size,
                    headers = cleanAudioHeaders + srcBytesHeader + mapOf(
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

        val finalTitle = capturedTitle?.takeIf {
            !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, targetUrl)
        } ?: HtmlThumbnailExtractor.humanReadableTitleFromUrl(targetUrl)
            ?: capturedTitle?.takeIf { it.isNotBlank() }
            ?: UrlUtils.host(targetUrl)?.replaceFirstChar { it.uppercase() }
            ?: "Media"

        ResolvedMedia(
            sourceUrl = targetUrl,
            title = finalTitle,
            thumbnailUrl = capturedThumb,
            streams = finalStreams,
            extractor = "Smart Extractor"
        )
    }

    /**
     * Loads a canonical Pinterest Pin URL in a lightweight offscreen WebView to collect hydrated
     * Relay/PWS JSON scripts and strictly scoped main-Pin carousel/story slides (explicitly
     * excluding "More to explore" / related pins feeds).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractPinterestPinPayload(
        targetUrl: String,
        timeoutMs: Long = 5_000L
    ): String? = coroutineScope {
        val resultHolder = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var pageFinished = false
        var webViewRef: WebView? = null

        val scrapeJs = """
            (function(){
              try {
                var scripts = [];
                var nodes = document.querySelectorAll('script[data-relay-response="true"], script#__PWS_DATA__, script#__PWS_INITIAL_PROPS__, script[type="application/json"]');
                for (var i = 0; i < nodes.length && scripts.length < 30; i++) {
                  var t = (nodes[i].textContent || '').trim();
                  if (t.length > 2 && t[0] === '{' && (t.indexOf('carousel') >= 0 || t.indexOf('storyPin') >= 0 || t.indexOf('story_pin') >= 0 || t.indexOf('v3GetPinQuery') >= 0 || t.indexOf('imageSpec') >= 0 || t.indexOf('pinimg.com') >= 0)) {
                    scripts.push(t);
                  }
                }
                if (window.__PWS_DATA__) { try { scripts.push(JSON.stringify(window.__PWS_DATA__)); } catch(e){} }
                if (window.__PWS_INITIAL_PROPS__) { try { scripts.push(JSON.stringify(window.__PWS_INITIAL_PROPS__)); } catch(e){} }

                var domSlides = [];
                var seen = {};
                var mainSelectors = '[data-test-id="story-pin-closeup-page"] img, [data-test-id="carousel-slide"] img, [data-test-id="closeup-carousel"] img, [data-test-id="pin-closeup-image"] img, [data-test-id="closeup-brick"] img';
                var imgs = document.querySelectorAll(mainSelectors);
                for (var j = 0; j < imgs.length; j++) {
                  var el = imgs[j];
                  if (el.closest('[data-test-id*="related"], [data-test-id*="more-ideas"], [data-test-id*="recommend"], [data-test-id*="moreToExplore"]')) continue;
                  var src = el.currentSrc || el.src || '';
                  if (src.indexOf('i.pinimg.com/') >= 0 && src.indexOf('/75x75') < 0 && src.indexOf('/30x30') < 0 && !seen[src]) {
                    seen[src] = true;
                    domSlides.push(src);
                  }
                }
                return JSON.stringify({ title: document.title || '', scripts: scripts, domSlides: domSlides });
              } catch(e) { return ''; }
            })();
        """.trimIndent()

        try {
            withContext(dispatchers.main) {
                val wv = WebView(context).apply {
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                webViewRef = wv

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        val accept = request.requestHeaders["Accept"]
                        if (shouldBlockAuxiliaryResource(reqUrl, accept, request.isForMainFrame)) {
                            return AdBlocker.createEmptyResponse()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        pageFinished = true
                    }
                }
                wv.loadUrl(targetUrl)
            }

            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(200)
                if (!pageFinished && System.currentTimeMillis() - start < 1_500L) continue
                withContext(dispatchers.main) {
                    webViewRef?.evaluateJavascript(scrapeJs) { raw ->
                        if (!raw.isNullOrBlank() && raw != "null" && raw != "\"\"") {
                            val decoded = runCatching {
                                org.json.JSONArray("[$raw]").getString(0)
                            }.getOrNull()
                            if (!decoded.isNullOrBlank() && decoded.startsWith("{")) {
                                resultHolder.set(decoded)
                            }
                        }
                    }
                }
                val current = resultHolder.get()
                if (current != null && (current.contains("carouselSlots") || current.contains("carousel_slots") || current.contains("storyPinData") || current.contains("story_pin_data"))) {
                    break
                }
                if (pageFinished && current != null && System.currentTimeMillis() - start > 1_200L) {
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

        resultHolder.get()
    }

    private fun probeHeadlessProgressiveWithFallback(
        url: String,
        primaryHeaders: Map<String, String>
    ): Pair<HeadInfo, Map<String, String>>? {
        val candidates = listOf(primaryHeaders) +
            ExtractorHttp.buildRelaxedHeaderVariants(primaryHeaders, includeBareNoReferer = true)
        for (hdrs in candidates) {
            val info = try {
                http.probe(url, hdrs, POST_CAPTURE_PROBE_TIMEOUT_MS)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (me: com.melmeligy.mediadownloader.core.MediaException) {
                if (me.error == com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED) continue
                return null
            } catch (_: Exception) {
                return null
            }
            if (info.code in 200..299 && !ExtractorHttp.isRejectedMediaContentType(info.contentType)) {
                return info to hdrs
            }
            if (info.code != 401 && info.code != 403) {
                return null
            }
        }
        return null
    }

    private fun extractHostFamily(url: String): String {
        val host = runCatching { java.net.URI(UrlUtils.encodeNonAsciiUrl(url)).host?.lowercase().orEmpty() }
            .getOrDefault("")
            .removePrefix("www.")
        if (host.isEmpty()) return ""
        val parts = host.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    }

    internal enum class EarlyExitDecision {
        EXIT_NOW,
        TRIGGER_METADATA_SCRAPE,
        CONTINUE_WAITING
    }

    companion object {
        private const val METADATA_HINT_TTL_MS = 30_000L
        private const val MAX_METADATA_HINTS = 32
        private const val EARLY_EXIT_TICK_MS = 120L
        private const val NEXT_SERVER_TAB_DELAY_MS = 1_800L
        private const val POST_CAPTURE_PROBE_TIMEOUT_MS = 3_000L
        private const val SELF_CONTAINED_SETTLE_MS = 220L
        private const val SPLIT_DASH_AUDIO_WAIT_MS = 750L
        private const val METADATA_SCRAPE_MAX_WAIT_MS = 750L

        private data class MetadataHint(
            val title: String?,
            val thumbnailUrl: String?,
            val savedAtMs: Long
        )

        private val metadataHints = ConcurrentHashMap<String, MetadataHint>()

        private val BLOCKED_TRACKER_MARKERS = listOf(
            "google-analytics.com",
            "googletagmanager.com",
            "analytics.google.com",
            "clarity.ms",
            "hotjar.com",
            "connect.facebook.net",
            "facebook.com/tr",
            "fbevents.js",
            "mc.yandex.ru",
            "histats.com",
            "statcounter.com",
            "disqus.com",
            "tawk.to",
            "crisp.chat",
            "onesignal.com",
            "sentry.io",
            "newrelic.com",
            "datadoghq.com",
            "amplitude.com",
            "mixpanel.com",
            "segment.io",
            "matomo.",
            "quantserve.com",
            "scorecardresearch.com",
            "/analytics.js",
            "/gtag/js",
            "/telemetry",
            "/beacon",
            "/pixel?"
        )

        private val FONT_HOST_MARKERS = listOf(
            "fonts.googleapis.com",
            "fonts.gstatic.com",
            "use.typekit.net",
            "kit.fontawesome.com"
        )

        private val BLOCKED_FONT_EXTENSIONS = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val BLOCKED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")

        /** Stores prefetched page title/thumbnail from static HTML before headless fallback so early exit does not wait on DOM scrape. */
        internal fun hintPageMetadata(url: String, title: String?, thumbnailUrl: String?) {
            if (title.isNullOrBlank() && thumbnailUrl.isNullOrBlank()) return
            val key = UrlUtils.normalize(url) ?: url.trim()
            if (key.isEmpty()) return
            val now = System.currentTimeMillis()
            if (metadataHints.size >= MAX_METADATA_HINTS) {
                metadataHints.entries.removeIf { now - it.value.savedAtMs > METADATA_HINT_TTL_MS }
            }
            metadataHints[key] = MetadataHint(title, thumbnailUrl, now)
        }

        internal fun consumePrefetchedMetadataHint(url: String): Pair<String?, String?>? {
            val key = UrlUtils.normalize(url) ?: url.trim()
            val hint = metadataHints.remove(key) ?: return null
            return if (System.currentTimeMillis() - hint.savedAtMs <= METADATA_HINT_TTL_MS) {
                hint.title to hint.thumbnailUrl
            } else null
        }

        /**
         * Returns true if a WebView sub-resource request (ads, trackers, fonts, heavy non-media
         * images) should be short-circuited with an empty response to accelerate page loading.
         * Never blocks main-frame document navigations (except known ads) or real video/audio streams.
         */
        internal fun shouldBlockAuxiliaryResource(
            url: String,
            acceptHeader: String?,
            isMainFrame: Boolean
        ): Boolean {
            if (url.isBlank() || url.startsWith("data:") || url.startsWith("about:") || url.startsWith("blob:")) {
                return false
            }
            if (AdBlocker.isAd(url)) return true
            if (isMainFrame) return false
            if (MediaSniffer.isPreviewOrAdUrl(url)) return true

            // Never block anything MediaSniffer recognizes as a real video/audio/playlist stream
            val mediaType = MediaSniffer.classify(url, acceptHeader)
            if (MediaSniffer.isMedia(mediaType)) return false

            val lower = url.lowercase()
            if (BLOCKED_TRACKER_MARKERS.any { lower.contains(it) }) return true
            if (FONT_HOST_MARKERS.any { lower.contains(it) }) return true

            val ext = UrlUtils.fileExtension(url)?.lowercase()
            if (ext != null && (ext in BLOCKED_FONT_EXTENSIONS || ext in BLOCKED_IMAGE_EXTENSIONS)) {
                return true
            }

            val acceptLower = acceptHeader?.lowercase().orEmpty()
            if (acceptLower.startsWith("image/") && !acceptLower.contains("text/html") && !acceptLower.contains("application/")) {
                return true
            }

            return false
        }

        /**
         * Pure early-exit decision state machine for [HeadlessWebExtractor].
         * Returns [EarlyExitDecision.EXIT_NOW] as soon as a valid stream + title/thumbnail are available
         * (instead of waiting for full `onPageFinished` or a fixed 3.2s sleep).
         */
        internal fun evaluateEarlyExit(
            captured: List<MediaStreamPayload>,
            elapsedMs: Long,
            firstVideoCapturedAtMs: Long?,
            hasGoodTitle: Boolean,
            hasThumbnail: Boolean,
            metadataScrapeCompleted: Boolean,
            pageFinished: Boolean
        ): EarlyExitDecision {
            val validMedia = captured.filter {
                it.streamType != StreamType.BLOB &&
                    it.streamType != StreamType.UNKNOWN &&
                    !MediaSniffer.isPreviewOrAdUrl(it.url)
            }
            val hasVideo = validMedia.any { !MediaSniffer.isAudioTrackUrl(it.url, it.mimeType) }
            if (!hasVideo) return EarlyExitDecision.CONTINUE_WAITING

            val sinceFirstVideoMs = (elapsedMs - (firstVideoCapturedAtMs ?: elapsedMs)).coerceAtLeast(0L)
            val hasAudio = validMedia.any { MediaSniffer.isAudioTrackUrl(it.url, it.mimeType) }
            val hasAuthoritative = validMedia.any { it.isAuthoritativePlayer }
            val hasHlsOrDash = validMedia.any { it.streamType == StreamType.HLS || it.streamType == StreamType.DASH }

            val looksLikeSplitDashVideoOnly = !hasHlsOrDash && !hasAudio && validMedia.any { p ->
                val u = p.url.lowercase()
                u.contains("videoplayback") || u.contains("bytestart=") || u.contains("mime=video")
            }
            if (looksLikeSplitDashVideoOnly && sinceFirstVideoMs < SPLIT_DASH_AUDIO_WAIT_MS) {
                return EarlyExitDecision.CONTINUE_WAITING
            }

            val metadataReady = (hasGoodTitle && hasThumbnail) || metadataScrapeCompleted
            if (!metadataReady) {
                return if (sinceFirstVideoMs >= METADATA_SCRAPE_MAX_WAIT_MS) {
                    EarlyExitDecision.EXIT_NOW
                } else {
                    EarlyExitDecision.TRIGGER_METADATA_SCRAPE
                }
            }

            // Metadata is ready and a valid video stream is captured: exit immediately for HLS/DASH/authoritative/finished,
            // or after a brief settle window for progressive captures to batch sibling qualities.
            if (hasAuthoritative || hasHlsOrDash || hasAudio || pageFinished || sinceFirstVideoMs >= SELF_CONTAINED_SETTLE_MS) {
                return EarlyExitDecision.EXIT_NOW
            }
            return EarlyExitDecision.CONTINUE_WAITING
        }

        val METADATA_SCRAPE_JS =
            "(function(){try{" +
                "if(window.__dlScanNow) window.__dlScanNow();" +
                "function abs(u){if(!u)return '';try{var r=new URL(u,location.href).href;return /^https?:\\/\\//i.test(r)?r:'';}catch(e){return '';}}" +
                "function cssUrl(s){if(!s)return '';var m=s.match(/url\\(\\s*(?:'([^']+)'|\"([^\"]+)\"|([^)'\"\\s]+))\\s*\\)/i);return m?(m[1]||m[2]||m[3]||''):'';}" +
                "var sels=['meta[property=\"og:image:secure_url\"]','meta[property=\"og:image\"]','meta[property=\"og:image:url\"]','meta[name=\"og:image\"]','meta[name=\"twitter:image\"]','meta[property=\"twitter:image\"]','meta[name=\"twitter:image:src\"]','meta[itemprop=\"thumbnailUrl\"]','meta[itemprop=\"image\"]','meta[name=\"thumbnail\"]'];" +
                "var img='';" +
                "for(var s=0;s<sels.length&&!img;s++){var m=document.querySelector(sels[s]);if(m&&m.content)img=abs(m.content);}" +
                "if(!img){var l=document.querySelector('link[rel=\"image_src\"]');if(l&&l.href)img=abs(l.href);}" +
                "if(!img){var v=document.querySelector('video[poster],video[data-poster]');if(v)img=abs(v.getAttribute('poster')||v.getAttribute('data-poster'));}" +
                "if(!img){var p=document.querySelector('[data-poster],[data-poster-url],[data-thumb],[data-thumbnail],[data-bg],[data-bg-src],[data-lazy-bg],[data-image-src],[data-background]');if(p)img=abs(p.getAttribute('data-poster')||p.getAttribute('data-poster-url')||p.getAttribute('data-thumb')||p.getAttribute('data-thumbnail')||p.getAttribute('data-bg')||p.getAttribute('data-bg-src')||p.getAttribute('data-lazy-bg')||p.getAttribute('data-image-src')||p.getAttribute('data-background'));}" +
                "if(!img){try{var lds=document.querySelectorAll('script[type=\"application/ld+json\"]');for(var i=0;i<lds.length&&!img;i++){var d=JSON.parse(lds[i].textContent||'{}');var arr=Array.isArray(d)?d:(d['@graph']?[d].concat(d['@graph']):[d]);for(var j=0;j<arr.length&&!img;j++){var it=arr[j]||{};var c=it.thumbnailUrl||it.image||it.primaryImageOfPage||it.thumbnail;if(Array.isArray(c))c=c[0];if(c&&typeof c==='object')c=c.contentUrl||c.url||c['@id'];if(typeof c==='string'&&c)img=abs(c);}}}catch(e){}}" +
                "if(!img){var cn=document.querySelectorAll('.BG--Mycima-Single,.Separate--Poster,.BG--GridItem,.Thumb--GridItem,.PosterBlock,.wecima,.mycima,.Poster,.poster,.Single--Poster,.jw-preview,.vjs-poster,.plyr__poster,[data-lazy-style],[style*=\"--image\"],[style*=\"background-image\"]');for(var k=0;k<cn.length&&!img;k++){var st=(cn[k].getAttribute('data-lazy-style')||'')+' '+(cn[k].getAttribute('style')||'');var cu=cssUrl(st);if(!cu&&window.getComputedStyle){try{var cs=window.getComputedStyle(cn[k]);cu=cssUrl((cs.backgroundImage||'')+' '+(cs.getPropertyValue('--image')||''));}catch(e){}}if(cu&&!/logo|favicon|avatar|icon|sprite|placeholder|blank/i.test(cu))img=abs(cu);}}" +
                "if(!img){var sts=document.querySelectorAll('style');for(var x=0;x<sts.length&&!img;x++){var sm=(sts[x].textContent||'').match(/(?:BG--Mycima-Single|Single--Poster|Separate--Poster|Poster|--image)[^}]{0,400}?url\\(\\s*(?:'([^']+)'|\"([^\"]+)\"|([^)'\"\\s]+))\\s*\\)/i);if(sm){img=abs(sm[1]||sm[2]||sm[3]||'');}}}" +
                "if(!img){var ims=document.querySelectorAll('.Poster img,.poster img,.Single--Poster img,.Separate--Poster img,.PosterBlock img,.thumb img,.thumbnail img,img.wp-post-image,img[class*=\"poster\" i],img[class*=\"thumb\" i],img[class*=\"cover\" i]');for(var n=0;n<ims.length&&!img;n++){var ci=ims[n].getAttribute('data-lazy-src')||ims[n].getAttribute('data-src')||ims[n].getAttribute('data-original')||ims[n].currentSrc||ims[n].src;if(ci&&!/logo|favicon|avatar|icon|sprite|placeholder|blank/i.test(ci))img=abs(ci);}}" +
                "var ogTitle=document.querySelector('meta[property=\"og:title\"],meta[name=\"twitter:title\"]');" +
                "var h1=document.querySelector('h1');" +
                "var bestTitle=(ogTitle&&ogTitle.content)||(h1&&h1.innerText)||document.title||'';" +
                "return JSON.stringify({t:bestTitle,i:img});" +
                "}catch(e){return '';}})();"

        val STREAMING_TRIGGER_JS = """
            (function(){
              try {
                window.open = function(){ return null; };
                var hasMedia = document.querySelector('video, iframe[src*="embed"], iframe[src*="player"], [data-server]');
                if (!hasMedia && !window.__dlFollowedWatch) {
                  var curPath = (location.pathname || '').replace(/\/+$/, '');
                  var links = document.querySelectorAll('a[href]');
                  for (var i = 0; i < links.length; i++) {
                    var a = links[i];
                    if (a.hostname !== location.hostname) continue;
                    var p = (a.pathname || '').replace(/\/+$/, '');
                    if (p && curPath && p.indexOf(curPath + '/') === 0 && /\/(watch|play|stream)$/i.test(p)) {
                      window.__dlFollowedWatch = true;
                      location.href = a.href;
                      return;
                    }
                  }
                }
                var videos = document.querySelectorAll('video');
                for (var v = 0; v < videos.length; v++) {
                  try { videos[v].muted = true; videos[v].play().catch(function(){}); } catch(e){}
                }
                var playBtns = document.querySelectorAll('.jw-display-icon-container, .plyr__control--overlaid, .vjs-big-play-button, button[aria-label*="Play" i]');
                if (playBtns.length > 0 && playBtns[0].tagName !== 'A') {
                  try { playBtns[0].click(); } catch(e){}
                }
              } catch(e) {}
            })();
        """.trimIndent()

        val TRY_NEXT_SERVER_JS = """
            (function(){
              try {
                var tabs = document.querySelectorAll('.server--item, [data-server], [data-post][data-nume]');
                for (var i = 0; i < tabs.length; i++) {
                  if (!tabs[i].classList.contains('active') && !tabs[i].getAttribute('data-dl-tried')) {
                    tabs[i].setAttribute('data-dl-tried', '1');
                    tabs[i].click();
                    break;
                  }
                }
              } catch(e) {}
            })();
        """.trimIndent()
    }
}
