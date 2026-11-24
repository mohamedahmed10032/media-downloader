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

    private val portalResolver = StreamingPortalResolver(
        http = http,
        hlsExtractor = hlsExtractor,
        dashExtractor = dashExtractor,
        progressTracker = progressTracker
    )

    internal var cookieFlusher: () -> Unit = {
        runCatching { CookieManager.getInstance().flush() }
    }
    internal var cookieLookup: (String) -> String? = { target ->
        runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull()
    }

    private val resRegex = Regex("(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(
        targetUrl: String,
        timeoutMs: Long = 7_500L,
        socialEmbedFastMode: Boolean = false
    ): ResolvedMedia? = coroutineScope {
        val isSocialFastMode = socialEmbedFastMode || UrlUtils.isDedicatedSocialPlatformUrl(targetUrl)
        val effectiveTimeoutMs = if (isSocialFastMode) minOf(timeoutMs, SOCIAL_FAST_TIMEOUT_MS) else timeoutMs
        progressTracker.update(ExtractionProgressTracker.STAGE_HEADLESS)
        com.melmeligy.mediadownloader.core.AppLogger.d(
            "Headless",
            "Start extraction host=${UrlUtils.host(targetUrl)} timeoutMs=$effectiveTimeoutMs socialFast=$isSocialFastMode"
        )
        val localCaptured = CopyOnWriteArrayList<MediaStreamPayload>()
        val discoveredEmbedUrls = CopyOnWriteArrayList<String>()
        val prefetched = consumePrefetchedMetadataHint(targetUrl)
        val capturedTitleRef = AtomicReference<String?>(
            prefetched?.first?.takeIf { !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, targetUrl) }
        )
        val capturedThumbRef = AtomicReference<String?>(
            HtmlThumbnailExtractor.resolveValidImageCandidate(prefetched?.second, targetUrl)
        )
        var currentPageUrl = targetUrl
        val lastEmbedIframeUrl = AtomicReference<String?>(null)
        val activePromotedEmbedUrl = AtomicReference<String?>(null)
        val targetHostFamily = extractHostFamily(targetUrl)

        val destroyed = AtomicBoolean(false)
        val pageFinished = AtomicBoolean(false)
        val metadataScrapeTriggered = AtomicBoolean(false)
        val metadataScrapeCompleted = AtomicBoolean(false)
        val firstVideoCapturedAtMs = AtomicLong(-1L)
        val promotedVideoCaptured = AtomicBoolean(false)
        val wakeSignal = Channel<Unit>(Channel.CONFLATED)

        var webViewRef: WebView? = null
        val start = System.currentTimeMillis()
        var promotedEmbedsAttempted = 0

        fun hasValidTitle(): Boolean {
            val t = capturedTitleRef.get()
            return !t.isNullOrBlank() && !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(t, targetUrl)
        }

        fun hasValidThumb(): Boolean = !capturedThumbRef.get().isNullOrBlank()

        fun scrapeMetadataFromWebView(
            view: WebView,
            pageUrlForResolve: String,
            tempEngine: MediaInterceptionEngine,
            isFinalPass: Boolean,
            onDone: (() -> Unit)? = null
        ) {
            if (destroyed.get()) {
                onDone?.invoke()
                return
            }
            if (isFinalPass) {
                metadataScrapeTriggered.set(true)
            }
            val evaluated = runCatching {
                val pageViewTitle = view.title?.trim()
                if (!pageViewTitle.isNullOrBlank() &&
                    !pageViewTitle.startsWith("http") &&
                    !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(pageViewTitle, targetUrl) &&
                    !hasValidTitle()
                ) {
                    capturedTitleRef.set(pageViewTitle)
                }
                view.evaluateJavascript(METADATA_SCRAPE_JS) { jsonStr ->
                    if (!destroyed.get()) {
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
                                }
                            }
                        }
                    }
                    // Never mark metadata scrape complete on an early onPageCommitVisible pass if the
                    // real title/poster haven't rendered yet (e.g. Cloudflare interstitial or lazy DOM).
                    if (isFinalPass || pageFinished.get() || (hasValidTitle() && hasValidThumb())) {
                        metadataScrapeCompleted.set(true)
                    }
                    wakeSignal.trySend(Unit)
                    onDone?.invoke()
                }
            }
            if (evaluated.isFailure) {
                onDone?.invoke()
            }
        }

        suspend fun ensureOuterMetadataScraped(
            view: WebView?,
            tempEngine: MediaInterceptionEngine,
            maxWaitMs: Long = 380L
        ) {
            if (view == null || destroyed.get() || activePromotedEmbedUrl.get() != null) return
            if (hasValidTitle() && hasValidThumb()) return
            val doneCh = Channel<Unit>(Channel.CONFLATED)
            withContext(dispatchers.main) {
                scrapeMetadataFromWebView(
                    view = view,
                    pageUrlForResolve = currentPageUrl,
                    tempEngine = tempEngine,
                    isFinalPass = true,
                    onDone = { doneCh.trySend(Unit) }
                )
            }
            withTimeoutOrNull(maxWaitMs) { doneCh.receiveCatching() }
            doneCh.close()
        }

        suspend fun collectDomEmbedIframes(view: WebView): List<String> {
            if (destroyed.get()) return emptyList()
            val resultCh = Channel<List<String>>(Channel.CONFLATED)
            withContext(dispatchers.main) {
                runCatching {
                    view.evaluateJavascript(EMBED_IFRAME_SCRAPE_JS) { jsonStr ->
                        val parsed = runCatching {
                            val unquoted = (org.json.JSONTokener(jsonStr ?: "").nextValue() as? String)
                                ?: jsonStr?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")
                            if (!unquoted.isNullOrBlank() && unquoted.startsWith("[")) {
                                val arr = org.json.JSONArray(unquoted)
                                List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
                            } else {
                                emptyList()
                            }
                        }.getOrDefault(emptyList())
                        resultCh.trySend(parsed)
                    }
                }.onFailure { resultCh.trySend(emptyList()) }
            }
            val list = withTimeoutOrNull(450L) { resultCh.receiveCatching().getOrNull() }.orEmpty()
            resultCh.close()
            return list
        }

        lateinit var tempEngineRef: MediaInterceptionEngine
        var hybridPortalJob: kotlinx.coroutines.Deferred<StreamingPortalResolver.PortalExtractionResult>? = null

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
                        lastWebViewUserAgent = userAgentString
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
                    if (AdBlocker.isAd(payload.url) || MediaSniffer.isPreviewOrAdUrl(payload.url)) return@addSink
                    val promoted = activePromotedEmbedUrl.get()
                    val adjustedPayload = if (promoted != null) {
                        payload.copy(
                            headersMap = applyPromotedEmbedReferer(
                                headers = payload.headersMap,
                                promotedEmbedUrl = promoted,
                                fallbackPageUrl = currentPageUrl,
                                outerPageUrl = currentPageUrl
                            )
                        )
                    } else {
                        payload
                    }
                    localCaptured.add(adjustedPayload)
                    if (!MediaSniffer.isAudioTrackUrl(adjustedPayload.url, adjustedPayload.mimeType) &&
                        adjustedPayload.streamType != StreamType.BLOB &&
                        adjustedPayload.streamType != StreamType.UNKNOWN
                    ) {
                        firstVideoCapturedAtMs.compareAndSet(-1L, System.currentTimeMillis() - start)
                        if (promoted != null) {
                            promotedVideoCaptured.set(true)
                        }
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
                        if (AdBlocker.shouldBlockPopupOrRedirect(reqUrl, currentPageUrl)) {
                            return true // Block intent://, ad & pop-under redirects
                        }
                        if (MediaSniffer.isPreviewOrAdUrl(reqUrl)) {
                            return true
                        }
                        if (request.isForMainFrame) {
                            val promoted = activePromotedEmbedUrl.get()
                            val activeFamily = if (promoted != null) extractHostFamily(promoted) else targetHostFamily
                            val nextFamily = extractHostFamily(reqUrl)
                            val sameDomain = activeFamily.isNotEmpty() && nextFamily == activeFamily
                            val isKnownPlayerPath = reqUrl.contains("/embed", ignoreCase = true) ||
                                reqUrl.contains("/watch", ignoreCase = true) ||
                                reqUrl.contains("/play", ignoreCase = true) ||
                                reqUrl.contains("/e/", ignoreCase = true) ||
                                (promoted != null && (
                                    reqUrl.contains("/v/", ignoreCase = true) ||
                                        reqUrl.contains("/f/", ignoreCase = true) ||
                                        reqUrl.contains("/d/", ignoreCase = true) ||
                                        reqUrl.contains("/s/", ignoreCase = true) ||
                                        reqUrl.contains("/player", ignoreCase = true)
                                    )) ||
                                AdBlocker.isProtectedEmbedHost(reqUrl)
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
                        val promoted = activePromotedEmbedUrl.get()

                        // Track sub-frame embed player URLs on outer page for promotion & Referer fallback
                        if (promoted == null &&
                            !request.isForMainFrame &&
                            accept?.contains("text/html", ignoreCase = true) == true &&
                            reqUrl.startsWith("http", ignoreCase = true) &&
                            !AdBlocker.isAd(reqUrl)
                        ) {
                            val subFamily = extractHostFamily(reqUrl)
                            if (subFamily.isNotEmpty() && subFamily != targetHostFamily) {
                                lastEmbedIframeUrl.set(reqUrl)
                                if (reqUrl !in discoveredEmbedUrls) {
                                    discoveredEmbedUrls.add(reqUrl)
                                }
                            }
                        }

                        // Inspect for media streams while filtering out all ad/VAST URLs
                        runCatching {
                            if (request.method.equals("GET", ignoreCase = true) &&
                                !reqUrl.startsWith("data:") && !reqUrl.startsWith("about:") &&
                                !AdBlocker.isAd(reqUrl) &&
                                !MediaSniffer.isPreviewOrAdUrl(reqUrl)
                            ) {
                                val type = MediaSniffer.classify(reqUrl, accept)
                                if (MediaSniffer.isMedia(type)) {
                                    val fallbackRefererPage = promoted ?: lastEmbedIframeUrl.get() ?: currentPageUrl
                                    val rawHeaders = HeaderExtractor.fromRequest(request, fallbackRefererPage)
                                    val capturedHeaders = applyPromotedEmbedReferer(
                                        headers = rawHeaders,
                                        promotedEmbedUrl = promoted,
                                        fallbackPageUrl = fallbackRefererPage,
                                        outerPageUrl = currentPageUrl
                                    )
                                    tempEngine.submit(
                                        MediaStreamPayload(
                                            url = MediaSniffer.cleanMediaUrl(reqUrl),
                                            streamType = type,
                                            headersMap = capturedHeaders,
                                            pageUrl = promoted ?: currentPageUrl,
                                            mimeType = accept
                                        )
                                    )
                                }
                            }
                        }

                        // Do NOT block page/ad sub-resources inside HeadlessWebExtractor so
                        // anti-adblock overlays never block player initialization.
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        super.onPageCommitVisible(view, url)
                        if (destroyed.get()) return
                        val promoted = activePromotedEmbedUrl.get()
                        if (!url.isNullOrBlank() && url.startsWith("http")) {
                            if (promoted == null) {
                                currentPageUrl = url
                                tempEngine.onPageChanged(url)
                            } else if (!AdBlocker.isAd(url)) {
                                // Track post-redirect mirror domain (e.g. earnvids.pro -> dintezuvio.com)
                                activePromotedEmbedUrl.set(url)
                                tempEngine.onPageChanged(url)
                            }
                        }
                        runCatching {
                            view.evaluateJavascript(BlobBridge.SCRIPT, null)
                            view.evaluateJavascript(STREAMING_TRIGGER_JS, null)
                        }
                        if (promoted == null && (!hasValidTitle() || !hasValidThumb())) {
                            scrapeMetadataFromWebView(view, url ?: targetUrl, tempEngine, isFinalPass = false)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (destroyed.get()) return
                        pageFinished.set(true)
                        val promoted = activePromotedEmbedUrl.get()
                        if (!url.isNullOrBlank() && url.startsWith("http")) {
                            if (promoted == null) {
                                currentPageUrl = url
                                tempEngine.onPageChanged(url)
                            } else if (!AdBlocker.isAd(url)) {
                                activePromotedEmbedUrl.set(url)
                                tempEngine.onPageChanged(url)
                            }
                        }
                        runCatching {
                            view.evaluateJavascript(BlobBridge.SCRIPT, null)
                            view.evaluateJavascript(STREAMING_TRIGGER_JS, null)
                        }
                        if (promoted == null && (!hasValidTitle() || !hasValidThumb() || !metadataScrapeCompleted.get())) {
                            scrapeMetadataFromWebView(view, url ?: targetUrl, tempEngine, isFinalPass = true)
                        } else {
                            wakeSignal.trySend(Unit)
                        }
                    }
                }

                wv.loadUrl(targetUrl)
            }

            var triedNextServerTab = false
            var lastDomPollAtMs = 0L
            val verifiedStreams = CopyOnWriteArrayList<MediaStream>()
            val verificationFailureReasons = CopyOnWriteArrayList<String>()
            val verifiedPayloadKeys = ConcurrentHashMap.newKeySet<String>()
            val webViewUa = lastWebViewUserAgent

            fun maybeStartHybridPortal(embeds: Collection<String>) {
                if (hybridPortalJob != null || embeds.isEmpty()) return
                val promotableNow = selectPromotableEmbedUrls(
                    outerPageUrl = currentPageUrl,
                    candidates = embeds,
                    maxEmbeds = StreamingPortalResolver.MAX_SERVERS,
                    allowDirectMediaFiles = true
                )
                if (promotableNow.isEmpty()) return
                cookieFlusher()
                val hybridHeaders = LinkedHashMap<String, String>().apply {
                    put("User-Agent", webViewUa)
                    put("Referer", currentPageUrl)
                    cookieLookup(currentPageUrl)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                }
                com.melmeligy.mediadownloader.core.AppLogger.d(
                    "Headless",
                    "Hybrid parallel StreamingPortalResolver launched for ${promotableNow.size} DOM embed(s)"
                )
                hybridPortalJob = async(dispatchers.io) {
                    runCatching {
                        portalResolver.resolveDirectEmbedUrls(currentPageUrl, promotableNow, hybridHeaders)
                    }.getOrElse { StreamingPortalResolver.PortalExtractionResult(emptyList()) }
                }
            }

            suspend fun verifyNewCapturedPayloads(embedContextUrl: String?): Boolean {
                val fresh = localCaptured
                    .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
                    .filterNot { MediaSniffer.isPreviewOrAdUrl(it.url) }
                    .filter { p ->
                        val key = "${p.streamType}|${MediaSniffer.dedupeKey(p.url)}|${p.qualityHint ?: 0}"
                        verifiedPayloadKeys.add(key)
                    }
                if (fresh.isEmpty()) return false
                cookieFlusher()
                val batchResults = withCancellableHttpCalls(timeoutMs = HLS_VERIFY_BATCH_TIMEOUT_MS) {
                    fresh.take(8).mapIndexed { idx, payload ->
                        async(dispatchers.io) {
                            verifyPayloadCandidate(
                                payload = payload,
                                embedUrl = embedContextUrl,
                                webViewUa = webViewUa,
                                idx = verifiedStreams.size + idx
                            )
                        }
                    }.awaitAll()
                }
                var addedPlayableVideo = false
                for ((streams, reason) in batchResults) {
                    if (streams.isNotEmpty()) {
                        verifiedStreams.addAll(streams)
                        if (streams.any { it.type == MediaType.VIDEO }) {
                            addedPlayableVideo = true
                        }
                    } else if (!reason.isNullOrBlank()) {
                        verificationFailureReasons.add(reason)
                    }
                }
                return addedPlayableVideo
            }

            while (true) {
                // If hybrid fast OkHttp portal resolver already found verified streams, exit immediately!
                if (hybridPortalJob?.isCompleted == true) {
                    val res = runCatching { hybridPortalJob?.getCompleted() }.getOrNull()
                    if (res != null && res.streams.isNotEmpty()) {
                        verifiedStreams.addAll(res.streams)
                        if (!res.posterUrl.isNullOrBlank() && capturedThumbRef.get().isNullOrBlank()) {
                            capturedThumbRef.set(res.posterUrl)
                        }
                        break
                    }
                }

                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= effectiveTimeoutMs) break

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

                // Fast DOM-ready polling (every ~300ms once pageFinished or >= 1.2s):
                // the instant server tabs / iframes appear in DOM, launch parallel StreamingPortalResolver!
                // Never run movie-portal polling/server clicking on social platforms.
                if (!isSocialFastMode &&
                    firstVidAt == null &&
                    hybridPortalJob == null &&
                    webViewRef != null &&
                    (pageFinished.get() || elapsed >= 1_200L) &&
                    elapsed - lastDomPollAtMs >= DOM_READY_POLL_INTERVAL_MS
                ) {
                    lastDomPollAtMs = elapsed
                    val domEmbeds = collectDomEmbedIframes(webViewRef!!)
                    if (domEmbeds.isNotEmpty() || discoveredEmbedUrls.isNotEmpty()) {
                        domEmbeds.forEach { if (it !in discoveredEmbedUrls) discoveredEmbedUrls.add(it) }
                        maybeStartHybridPortal(discoveredEmbedUrls)
                    }
                }

                // If page finished and we already have embed URLs in DOM (no Cloudflare stall),
                // don't wait out the full 7.5s outer timeout — move to promoted embeds after 2.2s.
                if (!isSocialFastMode &&
                    firstVidAt == null &&
                    pageFinished.get() &&
                    discoveredEmbedUrls.isNotEmpty() &&
                    elapsed >= OUTER_EMBED_READY_EARLY_BREAK_MS
                ) {
                    break
                }

                // If Server #0 produced nothing after 1.8s, try clicking the next watch server tab safely
                if (!isSocialFastMode && firstVidAt == null && !triedNextServerTab && elapsed >= NEXT_SERVER_TAB_DELAY_MS) {
                    triedNextServerTab = true
                    progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
                    withContext(dispatchers.main) {
                        runCatching { webViewRef?.evaluateJavascript(TRY_NEXT_SERVER_JS, null) }
                    }
                }

                val remaining = effectiveTimeoutMs - (System.currentTimeMillis() - start)
                if (remaining <= 0L) break
                withTimeoutOrNull(minOf(remaining, EARLY_EXIT_TICK_MS)) {
                    wakeSignal.receiveCatching()
                }
            }

            // Capture outer-page title/poster BEFORE leaving the outer page (either for embed promotion
            // or for returning early via hybridPortalJob).
            if (!hasValidThumb() || !hasValidTitle()) {
                ensureOuterMetadataScraped(webViewRef, tempEngineRef)
            }

            // Inline-verify any outer-page candidates BEFORE deciding whether to promote embed servers!
            if (verifiedStreams.isEmpty() && localCaptured.isNotEmpty()) {
                progressTracker.update(ExtractionProgressTracker.STAGE_QUALITIES)
                verifyNewCapturedPayloads(embedContextUrl = lastEmbedIframeUrl.get())
            }

            // Bounded top-level embed <iframe> promotion when no VERIFIED video stream has been found yet
            val hasVerifiedOuterVideo = verifiedStreams.any { it.type == MediaType.VIDEO }
            com.melmeligy.mediadownloader.core.AppLogger.d(
                "Headless",
                "Outer pass finished host=${UrlUtils.host(currentPageUrl)} captured=${localCaptured.size} verified=${verifiedStreams.size} interceptedEmbeds=${discoveredEmbedUrls.size}"
            )
            if (!isSocialFastMode && !hasVerifiedOuterVideo && webViewRef != null) {
                val failedOuterEmbed = lastEmbedIframeUrl.get()?.substringBefore('#')?.trimEnd('/')?.lowercase()
                var domEmbeds = collectDomEmbedIframes(webViewRef!!)
                var allEmbeds = discoveredEmbedUrls + domEmbeds
                val rawPromotable = selectPromotableEmbedUrls(
                    outerPageUrl = currentPageUrl,
                    candidates = allEmbeds,
                    maxEmbeds = MAX_PROMOTED_EMBEDS + 1
                )
                val untestedAlternateExists = rawPromotable.any {
                    failedOuterEmbed == null || it.substringBefore('#').trimEnd('/').lowercase() != failedOuterEmbed
                }
                // If outer pass captured a candidate that failed verification and DOM only listed that same embed
                // (e.g. portals that inject alternate server iframes only on tab click), click the next server tab once.
                if (!untestedAlternateExists && localCaptured.isNotEmpty()) {
                    progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
                    withContext(dispatchers.main) {
                        runCatching { webViewRef?.evaluateJavascript(TRY_NEXT_SERVER_JS, null) }
                    }
                    delay(1_400L)
                    domEmbeds = collectDomEmbedIframes(webViewRef!!)
                    allEmbeds = discoveredEmbedUrls + domEmbeds
                }
                maybeStartHybridPortal(allEmbeds)
                val orderedCandidates = if (failedOuterEmbed != null) {
                    val (failedOnes, freshOnes) = allEmbeds.partition {
                        it.substringBefore('#').trimEnd('/').lowercase() == failedOuterEmbed
                    }
                    freshOnes + failedOnes
                } else {
                    allEmbeds
                }
                val promotable = selectPromotableEmbedUrls(
                    outerPageUrl = currentPageUrl,
                    candidates = orderedCandidates,
                    maxEmbeds = MAX_PROMOTED_EMBEDS
                )
                com.melmeligy.mediadownloader.core.AppLogger.d(
                    "Headless",
                    "Embed promotion candidates=${promotable.size} hosts=${promotable.mapNotNull { UrlUtils.host(it) }}"
                )
                val outerPageForReferer = currentPageUrl
                for ((idx, embedUrl) in promotable.withIndex()) {
                    // Check if parallel hybrid OkHttp resolver already succeeded!
                    if (hybridPortalJob?.isCompleted == true) {
                        val res = runCatching { hybridPortalJob?.getCompleted() }.getOrNull()
                        if (res != null && res.streams.isNotEmpty()) {
                            verifiedStreams.addAll(res.streams)
                            break
                        }
                    }

                    promotedEmbedsAttempted++
                    activePromotedEmbedUrl.set(embedUrl)
                    promotedVideoCaptured.set(false)
                    pageFinished.set(false)
                    progressTracker.update(ExtractionProgressTracker.STAGE_EMBEDS)
                    com.melmeligy.mediadownloader.core.AppLogger.d(
                        "Headless",
                        "Promoting embed ${idx + 1}/${promotable.size} host=${UrlUtils.host(embedUrl)} refererHost=${UrlUtils.host(outerPageForReferer)}"
                    )
                    withContext(dispatchers.main) {
                        runCatching {
                            webViewRef?.stopLoading()
                            tempEngineRef.onPageChanged(embedUrl)
                            webViewRef?.loadUrl(embedUrl, mapOf("Referer" to outerPageForReferer))
                        }
                    }

                    val embedStart = System.currentTimeMillis()
                    var retriggeredPlay = false
                    while (true) {
                        if (hybridPortalJob?.isCompleted == true) {
                            val res = runCatching { hybridPortalJob?.getCompleted() }.getOrNull()
                            if (res != null && res.streams.isNotEmpty()) {
                                verifiedStreams.addAll(res.streams)
                                break
                            }
                        }
                        if (promotedVideoCaptured.get()) {
                            com.melmeligy.mediadownloader.core.AppLogger.d(
                                "Headless",
                                "Promoted embed ${idx + 1} captured stream in ${System.currentTimeMillis() - embedStart}ms; verifying inline"
                            )
                            break
                        }
                        val embedElapsed = System.currentTimeMillis() - embedStart
                        if (!retriggeredPlay && embedElapsed >= 1_200L) {
                            retriggeredPlay = true
                            withContext(dispatchers.main) {
                                runCatching {
                                    webViewRef?.evaluateJavascript(BlobBridge.SCRIPT, null)
                                    webViewRef?.evaluateJavascript(STREAMING_TRIGGER_JS, null)
                                }
                            }
                        }
                        val embedRemaining = PROMOTED_EMBED_TIMEOUT_MS - embedElapsed
                        if (embedRemaining <= 0L) {
                            com.melmeligy.mediadownloader.core.AppLogger.d(
                                "Headless",
                                "Promoted embed ${idx + 1} timed out after ${embedElapsed}ms"
                            )
                            break
                        }
                        withTimeoutOrNull(minOf(embedRemaining, EARLY_EXIT_TICK_MS)) {
                            wakeSignal.receiveCatching()
                        }
                    }

                    if (verifiedStreams.any { it.type == MediaType.VIDEO }) break

                    // Verify this promoted embed's captured candidate(s) inline:
                    // if verification FAILS, do NOT abort — continue to the next embed server!
                    if (promotedVideoCaptured.get()) {
                        val effectiveEmbedCtx = activePromotedEmbedUrl.get() ?: embedUrl
                        val verifiedOk = verifyNewCapturedPayloads(embedContextUrl = effectiveEmbedCtx)
                        if (verifiedOk) {
                            com.melmeligy.mediadownloader.core.AppLogger.d(
                                "Headless",
                                "Promoted embed ${idx + 1} verified playable stream; exiting immediately"
                            )
                            break
                        } else {
                            com.melmeligy.mediadownloader.core.AppLogger.w(
                                "Headless",
                                "Promoted embed ${idx + 1} failed verification (${verificationFailureReasons.lastOrNull()}); continuing to next embed server"
                            )
                        }
                    }
                }
                activePromotedEmbedUrl.set(null)
            }

            // If still no verified streams and hybrid portal job is still running, give it a brief finish window
            if (verifiedStreams.isEmpty() && hybridPortalJob != null) {
                val hybridRes = withTimeoutOrNull(1_500L) {
                    runCatching { hybridPortalJob?.await() }.getOrNull()
                }
                if (hybridRes != null && hybridRes.streams.isNotEmpty()) {
                    verifiedStreams.addAll(hybridRes.streams)
                    if (!hybridRes.posterUrl.isNullOrBlank() && capturedThumbRef.get().isNullOrBlank()) {
                        capturedThumbRef.set(hybridRes.posterUrl)
                    }
                }
            }
            hybridPortalJob?.cancel()

            // Verify any remaining unverified payloads captured before WebView teardown
            if (verifiedStreams.isEmpty() && localCaptured.isNotEmpty()) {
                verifyNewCapturedPayloads(embedContextUrl = lastEmbedIframeUrl.get())
            }

            // Always flush WebView cookies (e.g. Cloudflare cf_clearance) for subsequent fast OkHttp calls
            cookieFlusher()

            val resolved = verifiedStreams.toList()
            if (resolved.isEmpty()) {
                val attemptedCount = localCaptured
                    .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
                    .filterNot { MediaSniffer.isPreviewOrAdUrl(it.url) }
                    .distinctBy { "${it.streamType}|${MediaSniffer.dedupeKey(it.url)}" }
                    .size
                val reasonSuffix = verificationFailureReasons.distinct().take(2).joinToString(" / ")
                val diag = when {
                    attemptedCount > 0 && reasonSuffix.isNotBlank() ->
                        "Captured $attemptedCount stream candidate(s), verification failed ($reasonSuffix)."
                    attemptedCount > 0 ->
                        "Captured $attemptedCount stream candidate(s), but none passed download verification."
                    promotedEmbedsAttempted > 0 ->
                        "Checked $promotedEmbedsAttempted embedded player(s) — no playable video stream responded."
                    else ->
                        "Page loaded without exposing a playable video stream."
                }
                progressTracker.setDiagnosticReason(diag)
                com.melmeligy.mediadownloader.core.AppLogger.w("Headless", "No verified streams for ${UrlUtils.host(targetUrl)}: $diag")
                return@coroutineScope null
            }

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
                    val durSec = s.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
                    val normH = com.melmeligy.mediadownloader.core.util.FormatUtils.inferStandardQualityFromMetadata(
                        url = s.url,
                        label = s.label,
                        width = s.width,
                        height = s.height,
                        bitrateKbps = s.bitrateKbps,
                        sizeBytes = s.sizeBytes,
                        durationSeconds = durSec
                    )
                    val label = when {
                        normH != null && normH >= 1080 -> "${normH}p FHD"
                        normH != null && normH >= 720 -> "${normH}p HD"
                        normH != null -> "${normH}p"
                        i == 0 -> "HD Video"
                        else -> "SD Video"
                    }
                    if (s.headers["X-Internal-Dash-VideoOnly"] == "true" && bestAudio != null) {
                        val vSize = s.sizeBytes ?: 0L
                        val aSize = bestAudio.sizeBytes ?: 0L
                        val combined = if (vSize > 0 && aSize > 0) vSize + aSize else s.sizeBytes
                        s.copy(
                            id = "hl-v-$i",
                            height = normH ?: s.height,
                            label = label,
                            sizeBytes = combined,
                            headers = s.headers + mapOf(
                                "X-Internal-Companion-Audio" to bestAudio.url,
                                "X-Internal-Video-Bytes" to vSize.toString(),
                                "X-Internal-Audio-Bytes" to aSize.toString()
                            )
                        )
                    } else {
                        s.copy(id = "hl-v-$i", height = normH ?: s.height, label = label)
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

            return@coroutineScope ResolvedMedia(
                sourceUrl = targetUrl,
                title = capturedTitle ?: HtmlThumbnailExtractor.humanReadableTitleFromUrl(targetUrl) ?: "Web Video",
                thumbnailUrl = capturedThumb,
                streams = videos + audios,
                extractor = "Smart Extractor"
            )
        } finally {
            destroyed.set(true)
            hybridPortalJob?.cancel()
            wakeSignal.close()
            withContext(kotlinx.coroutines.NonCancellable + dispatchers.main) {
                runCatching {
                    webViewRef?.stopLoading()
                    webViewRef?.removeJavascriptInterface(BlobBridge.NAME)
                    webViewRef?.destroy()
                }
            }
        }
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
            withContext(kotlinx.coroutines.NonCancellable + dispatchers.main) {
                runCatching {
                    webViewRef?.stopLoading()
                    webViewRef?.destroy()
                }
            }
        }

        resultHolder.get()
    }

    internal fun mergeWebViewCookiesAndUa(
        streamUrl: String,
        headers: Map<String, String>,
        embedUrl: String?,
        webViewUa: String = lastWebViewUserAgent
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(headers)
        if (webViewUa.isNotBlank()) {
            out["User-Agent"] = webViewUa
        }
        val cookieMap = LinkedHashMap<String, String>()
        fun absorbCookieString(raw: String?) {
            if (raw.isNullOrBlank()) return
            raw.split(';').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        cookieMap[name] = value
                    }
                }
            }
        }
        absorbCookieString(headers["Cookie"])
        if (!embedUrl.isNullOrBlank()) {
            absorbCookieString(cookieLookup(embedUrl))
        }
        absorbCookieString(cookieLookup(streamUrl))
        if (cookieMap.isNotEmpty()) {
            out["Cookie"] = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        return out
    }

    internal suspend fun verifyPayloadCandidate(
        payload: MediaStreamPayload,
        embedUrl: String?,
        webViewUa: String = lastWebViewUserAgent,
        idx: Int = 0
    ): Pair<List<MediaStream>, String?> {
        val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
        val enrichedHeaders = mergeWebViewCookiesAndUa(
            streamUrl = cleanUrl,
            headers = payload.headersMap,
            embedUrl = embedUrl,
            webViewUa = webViewUa
        )
        return when (payload.streamType) {
            StreamType.HLS -> {
                // HlsExtractor.extractVerifiedDetailed already handles relaxed header variants
                // internally across master, variant, and segment verification; do not nest an outer
                // header loop that multiplies probes and starves the verification timeout budget.
                var lastReason: String? = null
                repeat(2) { tryNum ->
                    val outcome = try {
                        hlsExtractor.extractVerifiedDetailed(cleanUrl, enrichedHeaders, payload.qualityHint)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (me: com.melmeligy.mediadownloader.core.MediaException) {
                        HlsExtractor.HlsVerificationResult(
                            media = null,
                            failureReason = when (me.error) {
                                com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED -> "HTTP 403"
                                com.melmeligy.mediadownloader.core.AppError.EXPIRED_LINK -> "HTTP 404/410"
                                com.melmeligy.mediadownloader.core.AppError.SERVER_ERROR -> "HTTP 5xx"
                                else -> me.message?.take(32) ?: me.error.name
                            }
                        )
                    } catch (e: Exception) {
                        HlsExtractor.HlsVerificationResult(
                            media = null,
                            failureReason = if (e is java.net.SocketTimeoutException) "timeout" else (e.message?.take(32) ?: "network error")
                        )
                    }
                    val verified = outcome.media?.streams.orEmpty()
                    if (verified.isNotEmpty()) {
                        return verified to null
                    }
                    lastReason = outcome.failureReason ?: lastReason
                    val isTransient = lastReason?.let { r ->
                        r.contains("timeout", ignoreCase = true) ||
                            r.contains("5xx", ignoreCase = true) ||
                            r.contains("HTTP 5", ignoreCase = true) ||
                            r.contains("network error", ignoreCase = true)
                    } == true
                    if (!isTransient || tryNum > 0) {
                        return emptyList<MediaStream>() to (lastReason ?: "HLS verification failed")
                    }
                    delay(150L)
                }
                emptyList<MediaStream>() to (lastReason ?: "HLS verification failed")
            }
            StreamType.DASH -> {
                val streams = runCatching { dashExtractor.extract(cleanUrl, enrichedHeaders)?.streams }.getOrNull().orEmpty()
                if (streams.isNotEmpty()) streams to null else emptyList<MediaStream>() to "DASH verification failed"
            }
            StreamType.PROGRESSIVE -> {
                val (info, workingHeaders, failReason) = probeHeadlessProgressiveWithFallback(cleanUrl, enrichedHeaders)
                if (info == null || workingHeaders == null) {
                    return emptyList<MediaStream>() to (failReason ?: "probe failed")
                }
                val contentType = (info.contentType ?: payload.mimeType.orEmpty()).lowercase()
                if (ExtractorHttp.isRejectedMediaContentType(contentType)) {
                    return emptyList<MediaStream>() to "HTML/non-media response"
                }
                val isAudio = MediaSniffer.isAudioTrackUrl(cleanUrl, contentType)
                val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
                val container = MediaSniffer.containerFor(StreamType.PROGRESSIVE, cleanUrl, contentType)
                val size = info.contentLength.takeIf { it > 0 } ?: payload.sizeBytes?.takeIf { it > 0 }
                val height = payload.qualityHint?.takeIf { it > 0 } ?: UrlUtils.inferResolution(cleanUrl)
                if (size != null && size in 1..40_000L) {
                    return emptyList<MediaStream>() to "tiny non-video file"
                }
                val finalHeaders = LinkedHashMap<String, String>(
                    workingHeaders.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
                ).apply {
                    if (info.acceptsRanges) put("X-Internal-Accepts-Ranges", "true")
                }
                val stream = MediaStream(
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
                listOf(stream) to null
            }
            else -> emptyList<MediaStream>() to "unsupported stream type"
        }
    }

    private fun probeHeadlessProgressiveWithFallback(
        url: String,
        primaryHeaders: Map<String, String>
    ): Triple<HeadInfo?, Map<String, String>?, String?> {
        val candidates = listOf(primaryHeaders) +
            ExtractorHttp.buildRelaxedHeaderVariants(primaryHeaders, includeBareNoReferer = true)
        var lastReason: String? = null
        for ((idx, hdrs) in candidates.withIndex()) {
            val maxTries = if (idx == 0) 2 else 1
            for (attempt in 0 until maxTries) {
                val info = try {
                    http.probe(url, hdrs, POST_CAPTURE_PROBE_TIMEOUT_MS)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (me: com.melmeligy.mediadownloader.core.MediaException) {
                    lastReason = when (me.error) {
                        com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED -> "HTTP 403"
                        com.melmeligy.mediadownloader.core.AppError.EXPIRED_LINK -> "HTTP 404/410"
                        com.melmeligy.mediadownloader.core.AppError.SERVER_ERROR -> "HTTP 5xx"
                        else -> me.message?.take(32) ?: "timeout"
                    }
                    if (me.error == com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED ||
                        me.error == com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED
                    ) {
                        break // move directly to next relaxed header variant
                    }
                    if (attempt + 1 < maxTries) continue
                    break
                } catch (e: Exception) {
                    lastReason = if (e is java.net.SocketTimeoutException) "timeout" else "network error"
                    if (attempt + 1 < maxTries) continue
                    break
                }
                if (info.code in 200..299 && !ExtractorHttp.isRejectedMediaContentType(info.contentType)) {
                    return Triple(info, hdrs, null)
                }
                lastReason = if (ExtractorHttp.isRejectedMediaContentType(info.contentType)) {
                    "HTML response"
                } else {
                    "HTTP ${info.code}"
                }
                if (info.code != 401 && info.code != 403) {
                    return Triple(null, null, lastReason)
                }
                break // 401/403 -> try next relaxed header variant
            }
        }
        return Triple(null, null, lastReason ?: "HTTP 403 / timeout")
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
        @Volatile
        internal var lastWebViewUserAgent: String = Constants.DEFAULT_USER_AGENT

        private const val METADATA_HINT_TTL_MS = 30_000L
        private const val MAX_METADATA_HINTS = 32
        private const val EARLY_EXIT_TICK_MS = 120L
        private const val DOM_READY_POLL_INTERVAL_MS = 300L
        private const val OUTER_EMBED_READY_EARLY_BREAK_MS = 2_200L
        private const val NEXT_SERVER_TAB_DELAY_MS = 1_800L
        private const val POST_CAPTURE_PROBE_TIMEOUT_MS = 5_000L
        private const val HLS_VERIFY_BATCH_TIMEOUT_MS = 10_500L
        private const val SELF_CONTAINED_SETTLE_MS = 220L
        private const val SPLIT_DASH_AUDIO_WAIT_MS = 750L
        private const val METADATA_SCRAPE_MAX_WAIT_MS = 750L
        internal const val MAX_PROMOTED_EMBEDS = 4
        internal const val PROMOTED_EMBED_TIMEOUT_MS = 4_500L
        internal const val SOCIAL_FAST_TIMEOUT_MS = 3_800L

        private data class MetadataHint(
            val title: String?,
            val thumbnailUrl: String?,
            val savedAtMs: Long
        )

        private val metadataHints = ConcurrentHashMap<String, MetadataHint>()

        /**
         * Pure, unit-testable verified embed promotion runner matching the runtime failover policy:
         * verifies each embed's captured candidate(s) inline and NEVER aborts on a single unverified
         * candidate — continues through remaining embed servers until a verified playable stream is
         * found or [maxEmbeds] is reached.
         */
        internal suspend fun runVerifiedEmbedPromotion(
            outerPageUrl: String,
            candidateEmbeds: Collection<String>,
            maxEmbeds: Int = MAX_PROMOTED_EMBEDS,
            loadEmbed: suspend (embedUrl: String, refererUrl: String) -> List<MediaStreamPayload>,
            verifyCandidate: suspend (payload: MediaStreamPayload, embedUrl: String) -> Pair<List<MediaStream>, String?>
        ): Pair<List<MediaStream>, List<String>> {
            val promotable = selectPromotableEmbedUrls(outerPageUrl, candidateEmbeds, maxEmbeds)
            val verified = mutableListOf<MediaStream>()
            val failureReasons = mutableListOf<String>()
            for (embedUrl in promotable) {
                val rawPayloads = loadEmbed(embedUrl, outerPageUrl)
                val valid = rawPayloads
                    .filterNot { AdBlocker.isAd(it.url) || MediaSniffer.isPreviewOrAdUrl(it.url) }
                    .map { payload ->
                        payload.copy(
                            headersMap = applyPromotedEmbedReferer(
                                headers = payload.headersMap,
                                promotedEmbedUrl = embedUrl,
                                fallbackPageUrl = embedUrl,
                                outerPageUrl = outerPageUrl
                            ),
                            pageUrl = embedUrl
                        )
                    }
                for (payload in valid) {
                    val (streams, reason) = verifyCandidate(payload, embedUrl)
                    if (streams.isNotEmpty()) {
                        verified.addAll(streams)
                    } else if (!reason.isNullOrBlank()) {
                        failureReasons.add(reason)
                    }
                }
                if (verified.any { it.type == MediaType.VIDEO }) {
                    break
                }
            }
            return verified to failureReasons
        }

        private val NON_PLAYER_IFRAME_MARKERS = listOf(
            "recaptcha",
            "hcaptcha",
            "challenges.cloudflare.com",
            "googletagmanager",
            "google-analytics",
            "googlesyndication",
            "doubleclick.net",
            "facebook.com/plugins",
            "twitter.com/widgets",
            "disqus.com"
        )

        private val DIRECT_MEDIA_FILE_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "m4v", "m3u8", "mpd", "mp3", "m4a", "zip", "rar"
        )

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
         * Sub-resource blocking is intentionally disabled inside [HeadlessWebExtractor] so
         * anti-adblock scripts never trigger blocking modals that prevent embedded HTML5
         * players from initializing. Ad/VAST URLs are still filtered out of captured streams.
         */
        internal fun shouldBlockAuxiliaryResource(
            url: String,
            acceptHeader: String?,
            isMainFrame: Boolean
        ): Boolean = false

        /**
         * Selects up to [maxEmbeds] non-ad, cross-origin embed `<iframe>` URLs discovered on
         * [outerPageUrl] for bounded top-level promotion so `BlobBridge` and play triggers run
         * natively in the player's own origin.
         */
        internal fun selectPromotableEmbedUrls(
            outerPageUrl: String,
            candidates: Collection<String>,
            maxEmbeds: Int = MAX_PROMOTED_EMBEDS,
            allowDirectMediaFiles: Boolean = false
        ): List<String> {
            if (maxEmbeds <= 0 || candidates.isEmpty()) return emptyList()
            val outerFamily = extractHostFamilyStatic(outerPageUrl)
            val seen = LinkedHashSet<String>()
            val out = mutableListOf<String>()
            for (raw in candidates) {
                if (out.size >= maxEmbeds) break
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("about:", true) || trimmed.startsWith("javascript:", true)) {
                    continue
                }
                val resolved = when {
                    trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
                    trimmed.startsWith("//") -> "https:$trimmed"
                    else -> UrlUtils.resolveUrl(outerPageUrl, trimmed)
                }
                if (!resolved.startsWith("http://", true) && !resolved.startsWith("https://", true)) continue
                if (AdBlocker.isAd(resolved) || MediaSniffer.isPreviewOrAdUrl(resolved)) continue
                val lower = resolved.lowercase()
                if (NON_PLAYER_IFRAME_MARKERS.any { lower.contains(it) }) continue
                if (!allowDirectMediaFiles) {
                    val ext = UrlUtils.fileExtension(resolved)?.lowercase()
                    if (ext != null && ext in DIRECT_MEDIA_FILE_EXTENSIONS) continue
                }
                val embedFamily = extractHostFamilyStatic(resolved)
                if (embedFamily.isEmpty() || (outerFamily.isNotEmpty() && embedFamily == outerFamily)) continue
                val dedupeKey = resolved.substringBefore('#').trimEnd('/').lowercase()
                if (seen.add(dedupeKey)) {
                    out.add(resolved)
                }
            }
            return out
        }

        /**
         * Ensures streams captured from a promoted embed `<iframe>` carry a valid embed `Referer`
         * and `Origin` rather than the outer portal page, while NEVER clobbering a real
         * post-redirect cross-origin player `Referer`/`Origin` already sent by Chromium (e.g. when
         * `earnvids.pro/e/x` redirected to mirror `dintezuvio.com/v/x`).
         */
        internal fun applyPromotedEmbedReferer(
            headers: Map<String, String>,
            promotedEmbedUrl: String?,
            fallbackPageUrl: String,
            outerPageUrl: String? = null
        ): Map<String, String> {
            val out = LinkedHashMap<String, String>(headers)
            val refKey = out.keys.firstOrNull { it.equals("Referer", ignoreCase = true) }
            val origKey = out.keys.firstOrNull { it.equals("Origin", ignoreCase = true) }
            val existingRef = refKey?.let { out[it] }?.trim().orEmpty()

            if (!promotedEmbedUrl.isNullOrBlank()) {
                val outerFamily = outerPageUrl?.takeIf { it.isNotBlank() }?.let(::extractHostFamilyStatic).orEmpty()
                val existingRefFamily = if (existingRef.isNotBlank()) extractHostFamilyStatic(existingRef) else ""
                val leakedOuterPortal = outerFamily.isNotEmpty() && existingRefFamily == outerFamily
                val shouldOverwrite = existingRef.isBlank() || leakedOuterPortal
                if (shouldOverwrite) {
                    if (refKey != null) out.remove(refKey)
                    if (origKey != null) out.remove(origKey)
                    out["Referer"] = promotedEmbedUrl
                    originOfStatic(promotedEmbedUrl)?.let { out["Origin"] = it }
                } else {
                    // Preserve Chromium's real post-redirect embed Referer (and Origin if already present)
                    if (origKey == null) {
                        originOfStatic(existingRef)?.let { out["Origin"] = it }
                    }
                }
            } else if (existingRef.isBlank() && fallbackPageUrl.isNotBlank()) {
                out["Referer"] = fallbackPageUrl
                if (origKey == null) {
                    originOfStatic(fallbackPageUrl)?.let { out["Origin"] = it }
                }
            }
            return out
        }

        /**
         * Pure, unit-testable bounded embed promotion runner matching the runtime promotion
         * policy: promotes up to [maxEmbeds] non-ad cross-origin embeds with `Referer: outerPageUrl`,
         * exits immediately on the first playable video stream, and rewrites each captured stream's
         * download `Referer` to the promoted embed URL.
         */
        internal suspend fun runBoundedEmbedPromotion(
            outerPageUrl: String,
            candidateEmbeds: Collection<String>,
            maxEmbeds: Int = MAX_PROMOTED_EMBEDS,
            loadEmbed: suspend (embedUrl: String, refererUrl: String) -> List<MediaStreamPayload>
        ): List<MediaStreamPayload> {
            val promotable = selectPromotableEmbedUrls(outerPageUrl, candidateEmbeds, maxEmbeds)
            val collected = mutableListOf<MediaStreamPayload>()
            for (embedUrl in promotable) {
                val rawPayloads = loadEmbed(embedUrl, outerPageUrl)
                val valid = rawPayloads
                    .filterNot { AdBlocker.isAd(it.url) || MediaSniffer.isPreviewOrAdUrl(it.url) }
                    .map { payload ->
                        payload.copy(
                            headersMap = applyPromotedEmbedReferer(
                                headers = payload.headersMap,
                                promotedEmbedUrl = embedUrl,
                                fallbackPageUrl = embedUrl,
                                outerPageUrl = outerPageUrl
                            ),
                            pageUrl = embedUrl
                        )
                    }
                if (valid.isNotEmpty()) {
                    collected.addAll(valid)
                    val hasPlayableVideo = valid.any {
                        it.streamType != StreamType.BLOB &&
                            it.streamType != StreamType.UNKNOWN &&
                            !MediaSniffer.isAudioTrackUrl(it.url, it.mimeType)
                    }
                    if (hasPlayableVideo) {
                        break // Exit immediately as soon as the first playable stream is captured
                    }
                }
            }
            return collected
        }

        private fun extractHostFamilyStatic(url: String): String {
            val host = runCatching { java.net.URI(UrlUtils.encodeNonAsciiUrl(url)).host?.lowercase().orEmpty() }
                .getOrDefault("")
                .removePrefix("www.")
            if (host.isEmpty()) return ""
            val parts = host.split('.')
            return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
        }

        private fun originOfStatic(url: String): String? = runCatching {
            val uri = java.net.URI(UrlUtils.encodeNonAsciiUrl(url))
            val scheme = uri.scheme
            val host = uri.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) null
            else if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
        }.getOrNull()

        val EMBED_IFRAME_SCRAPE_JS =
            "(function(){try{" +
                "var out=[];var seen={};" +
                "function addUrl(raw){" +
                "if(!raw)return;raw=raw.trim();" +
                "if(!raw||raw.indexOf('about:')===0||raw.indexOf('javascript:')===0)return;" +
                "if(!/^https?:\\/\\//i.test(raw)&&raw.indexOf('//')!==0&&/^[A-Za-z0-9+/=_-]{16,}$/.test(raw)){" +
                "try{var dec=atob(raw.replace(/-/g,'+').replace(/_/g,'/')).trim();if(/^https?:\\/\\//i.test(dec)||dec.indexOf('//')===0)raw=dec;}catch(e){}" +
                "}" +
                "try{var abs=new URL(raw,location.href).href;if(/^https?:\\/\\//i.test(abs)&&!seen[abs]){seen[abs]=true;out.push(abs);}}catch(e){}" +
                "}" +
                "var frames=document.querySelectorAll('iframe[src],iframe[data-src],iframe[data-lazy-src]');" +
                "for(var i=0;i<frames.length;i++){" +
                "addUrl(frames[i].getAttribute('src')||frames[i].getAttribute('data-src')||frames[i].getAttribute('data-lazy-src')||'');" +
                "}" +
                "var tabs=document.querySelectorAll('[data-url],[data-embed],[data-iframe],[data-watch],[data-link],[data-player],[data-src]');" +
                "for(var j=0;j<tabs.length;j++){" +
                "var tag=(tabs[j].tagName||'').toLowerCase();" +
                "if(tag==='img'||tag==='script'||tag==='link'||tag==='source'||tag==='video'||tag==='audio')continue;" +
                "addUrl(tabs[j].getAttribute('data-url')||tabs[j].getAttribute('data-embed')||tabs[j].getAttribute('data-iframe')||tabs[j].getAttribute('data-watch')||tabs[j].getAttribute('data-link')||tabs[j].getAttribute('data-player')||tabs[j].getAttribute('data-src')||'');" +
                "}" +
                "var dlLinks=document.querySelectorAll('.List--Download--Wecima--Single a[href]');" +
                "for(var k=0;k<dlLinks.length;k++){" +
                "addUrl(dlLinks[k].getAttribute('href')||'');" +
                "}" +
                "return JSON.stringify(out);" +
                "}catch(e){return '[]';}})();"

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
                "function firstSrcset(s){if(!s)return '';var p=s.split(',')[0]||'';return p.trim().split(/\\s+/)[0]||'';}" +
                "var sels=['meta[property=\"og:image:secure_url\"]','meta[property=\"og:image\"]','meta[property=\"og:image:url\"]','meta[name=\"og:image\"]','meta[name=\"twitter:image\"]','meta[property=\"twitter:image\"]','meta[name=\"twitter:image:src\"]','meta[itemprop=\"thumbnailUrl\"]','meta[itemprop=\"image\"]','meta[name=\"thumbnail\"]'];" +
                "var img='';" +
                "for(var s=0;s<sels.length&&!img;s++){var m=document.querySelector(sels[s]);if(m&&m.content)img=abs(m.content);}" +
                "if(!img){var l=document.querySelector('link[rel=\"image_src\"]');if(l&&l.href)img=abs(l.href);}" +
                "if(!img){var v=document.querySelector('video[poster],video[data-poster]');if(v)img=abs(v.getAttribute('poster')||v.getAttribute('data-poster'));}" +
                "if(!img){var p=document.querySelector('[data-poster],[data-poster-url],[data-thumb],[data-thumbnail],[data-bg],[data-bg-src],[data-lazy-bg],[data-image-src],[data-background]');if(p)img=abs(p.getAttribute('data-poster')||p.getAttribute('data-poster-url')||p.getAttribute('data-thumb')||p.getAttribute('data-thumbnail')||p.getAttribute('data-bg')||p.getAttribute('data-bg-src')||p.getAttribute('data-lazy-bg')||p.getAttribute('data-image-src')||p.getAttribute('data-background'));}" +
                "if(!img){try{var lds=document.querySelectorAll('script[type=\"application/ld+json\"]');for(var i=0;i<lds.length&&!img;i++){var d=JSON.parse(lds[i].textContent||'{}');var arr=Array.isArray(d)?d:(d['@graph']?[d].concat(d['@graph']):[d]);for(var j=0;j<arr.length&&!img;j++){var it=arr[j]||{};var c=it.thumbnailUrl||it.image||it.primaryImageOfPage||it.thumbnail;if(Array.isArray(c))c=c[0];if(c&&typeof c==='object')c=c.contentUrl||c.url||c['@id'];if(typeof c==='string'&&c)img=abs(c);}}}catch(e){}}" +
                "if(!img){var cn=document.querySelectorAll('.Poster--Single-begin,.BG--Poster,.BG--Mycima-Single,.Separate--Poster,.BG--GridItem,.Thumb--GridItem,.PosterBlock,.wecima,.mycima,.Poster,.poster,.Single--Poster,.jw-preview,.vjs-poster,.plyr__poster,[data-lazy-style],[style*=\"--img\"],[style*=\"--image\"],[style*=\"background-image\"]');for(var k=0;k<cn.length&&!img;k++){var st=(cn[k].getAttribute('data-lazy-style')||'')+' '+(cn[k].getAttribute('style')||'');var cu=cssUrl(st);if(!cu&&window.getComputedStyle){try{var cs=window.getComputedStyle(cn[k]);cu=cssUrl((cs.backgroundImage||'')+' '+(cs.getPropertyValue('--image')||'')+' '+(cs.getPropertyValue('--img')||''));}catch(e){}}if(cu&&!/logo|favicon|avatar|icon|sprite|placeholder|blank/i.test(cu))img=abs(cu);}}" +
                "if(!img){var sts=document.querySelectorAll('style');for(var x=0;x<sts.length&&!img;x++){var sm=(sts[x].textContent||'').match(/(?:Poster--Single-begin|BG--Poster|BG--Mycima-Single|Single--Poster|Separate--Poster|Poster|--image|--img)[^}]{0,400}?url\\(\\s*(?:'([^']+)'|\"([^\"]+)\"|([^)'\"\\s]+))\\s*\\)/i);if(sm){img=abs(sm[1]||sm[2]||sm[3]||'');}}}" +
                "if(!img){var ims=document.querySelectorAll('.Poster--Single-begin img,.BG--Poster img,.Poster img,.poster img,.Single--Poster img,.Separate--Poster img,.PosterBlock img,.thumb img,.thumbnail img,img.wp-post-image,img[class*=\"poster\" i],img[class*=\"thumb\" i],img[class*=\"cover\" i]');for(var n=0;n<ims.length&&!img;n++){var ci=ims[n].getAttribute('data-lazy-src')||ims[n].getAttribute('data-src')||ims[n].getAttribute('data-original')||firstSrcset(ims[n].getAttribute('data-srcset')||ims[n].getAttribute('srcset')||'')||ims[n].currentSrc||ims[n].src;if(ci&&!/logo|favicon|avatar|icon|sprite|placeholder|blank/i.test(ci))img=abs(ci);}}" +
                "var ogTitle=document.querySelector('meta[property=\"og:title\"],meta[name=\"twitter:title\"]');" +
                "var h1=document.querySelector('h1');" +
                "var bestTitle=(ogTitle&&ogTitle.content)||(h1&&h1.innerText)||document.title||'';" +
                "return JSON.stringify({t:bestTitle,i:img});" +
                "}catch(e){return '';}})();"

        val STREAMING_TRIGGER_JS = """
            (function(){
              try {
                window.open = function(){ return null; };
                var hasMedia = document.querySelector('video, iframe[src*="embed"], iframe[src*="player"], [data-server], [data-url]');
                if (!hasMedia && !window.__dlFollowedWatch) {
                  var curPath = (location.pathname || '').replace(/\/+$/, '');
                  var swappedWatch = curPath.replace(/^\/(episode|film|series|movie)\//i, '/watch/');
                  var links = document.querySelectorAll('a[href]');
                  for (var i = 0; i < links.length; i++) {
                    var a = links[i];
                    if (a.hostname !== location.hostname) continue;
                    var p = (a.pathname || '').replace(/\/+$/, '');
                    var isChildWatch = p && curPath && p.indexOf(curPath + '/') === 0 && /\/(watch|play|stream)$/i.test(p);
                    var isSwappedWatch = swappedWatch !== curPath && p && p.toLowerCase() === swappedWatch.toLowerCase();
                    if (isChildWatch || isSwappedWatch) {
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
                var playBtns = document.querySelectorAll('.jw-display-icon-container, .plyr__control--overlaid, .vjs-big-play-button, button[aria-label*="Play" i], .play-button, .play-btn, #vplayer .jw-preview, [class*="play-btn" i]');
                if (playBtns.length > 0 && playBtns[0].tagName !== 'A') {
                  try { playBtns[0].click(); } catch(e){}
                }
              } catch(e) {}
            })();
        """.trimIndent()

        val TRY_NEXT_SERVER_JS = """
            (function(){
              try {
                var tabs = document.querySelectorAll('.server--item, [data-server], [data-post][data-nume], .WatchServersList li, .WatchServersList btn, .WatchServersList button, [data-url], [data-embed]');
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
