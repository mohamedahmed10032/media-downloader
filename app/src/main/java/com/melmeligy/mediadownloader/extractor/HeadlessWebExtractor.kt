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
    private val dispatchers: DispatcherProvider,
    private val progressTracker: ExtractionProgressTracker
) {

    private val resRegex = Regex("(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(targetUrl: String, timeoutMs: Long = 9_500L): ResolvedMedia? = coroutineScope {
        progressTracker.update(ExtractionProgressTracker.STAGE_HEADLESS)
        val localCaptured = CopyOnWriteArrayList<MediaStreamPayload>()
        var capturedTitle: String? = null
        var capturedThumb: String? = null
        var currentPageUrl = targetUrl
        val targetHostFamily = extractHostFamily(targetUrl)

        var webViewRef: WebView? = null
        try {
            withContext(dispatchers.main) {
                val wv = WebView(context).apply {
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadsImagesAutomatically = false
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
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val reqUrl = request.url?.toString().orEmpty()
                        if (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://")) {
                            return true // Block intent://, market://, tel:, javascript: hijacks
                        }
                        if (com.melmeligy.mediadownloader.intercept.AdBlocker.isAd(reqUrl) ||
                            MediaSniffer.isPreviewOrAdUrl(reqUrl)
                        ) {
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
                        if (com.melmeligy.mediadownloader.intercept.AdBlocker.isAd(reqUrl)) {
                            return com.melmeligy.mediadownloader.intercept.AdBlocker.createEmptyResponse()
                        }
                        runCatching {
                            if (request.method.equals("GET", ignoreCase = true) &&
                                !reqUrl.startsWith("data:") && !reqUrl.startsWith("about:") &&
                                !MediaSniffer.isPreviewOrAdUrl(reqUrl)
                            ) {
                                val accept = request.requestHeaders["Accept"]
                                val type = MediaSniffer.classify(reqUrl, accept)
                                if (MediaSniffer.isMedia(type)) {
                                    val capturedHeaders = HeaderExtractor.fromRequest(request, currentPageUrl)
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
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (!url.isNullOrBlank() && url.startsWith("http")) {
                            currentPageUrl = url
                            tempEngine.onPageChanged(url)
                        }
                        if (!view.title.isNullOrBlank()) capturedTitle = view.title
                        runCatching {
                            view.evaluateJavascript(BlobBridge.SCRIPT, null)
                            view.evaluateJavascript(STREAMING_TRIGGER_JS, null)
                            view.evaluateJavascript(
                                "(function(){try{" +
                                    "if(window.__dlScanNow) window.__dlScanNow();" +
                                    "function abs(u){if(!u)return '';try{var r=new URL(u,location.href).href;return /^https?:\\/\\//i.test(r)?r:'';}catch(e){return '';}}" +
                                    "var sels=['meta[property=\"og:image:secure_url\"]','meta[property=\"og:image\"]','meta[property=\"og:image:url\"]','meta[name=\"twitter:image\"]','meta[property=\"twitter:image\"]','meta[name=\"twitter:image:src\"]'];" +
                                    "var img='';" +
                                    "for(var s=0;s<sels.length&&!img;s++){var m=document.querySelector(sels[s]);if(m&&m.content)img=abs(m.content);}" +
                                    "if(!img){var v=document.querySelector('video[poster],video[data-poster]');if(v)img=abs(v.getAttribute('poster')||v.getAttribute('data-poster'));}" +
                                    "if(!img){var p=document.querySelector('[data-poster],[data-thumb],[data-thumbnail]');if(p)img=abs(p.getAttribute('data-poster')||p.getAttribute('data-thumb')||p.getAttribute('data-thumbnail'));}" +
                                    "if(!img){var l=document.querySelector('link[rel=\"image_src\"]');if(l&&l.href)img=abs(l.href);}" +
                                    "var ogTitle=document.querySelector('meta[property=\"og:title\"]');" +
                                    "return JSON.stringify({t:ogTitle?ogTitle.content:'',i:img});" +
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
            var triedNextServerTab = false
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(300)
                val elapsed = System.currentTimeMillis() - start
                val hasAuthoritative = localCaptured.any { it.isAuthoritativePlayer }
                val hasVideo = localCaptured.any { !MediaSniffer.isAudioTrackUrl(it.url, it.mimeType) }
                val hasAudio = localCaptured.any { MediaSniffer.isAudioTrackUrl(it.url, it.mimeType) }
                if (hasAuthoritative && elapsed > 1_000L) {
                    break
                }
                if (hasVideo && (hasAudio || elapsed > 3_200L)) {
                    break
                }
                // If Server #0 produced nothing after 3.5s, try clicking the next watch server tab safely
                if (!hasVideo && !triedNextServerTab && elapsed > 3_500L) {
                    triedNextServerTab = true
                    progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
                    withContext(dispatchers.main) {
                        runCatching { webViewRef?.evaluateJavascript(TRY_NEXT_SERVER_JS, null) }
                    }
                }
            }
        } finally {
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

        if (capturedTitle.isNullOrBlank()) {
            capturedTitle = localCaptured.firstNotNullOfOrNull { it.pageTitle?.takeIf { t -> t.isNotBlank() } }
        }
        if (capturedThumb.isNullOrBlank()) {
            capturedThumb = localCaptured.firstNotNullOfOrNull { it.thumbnailUrl?.takeIf { t -> t.startsWith("http") } }
        }

        val distinctPayloads = localCaptured
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .filterNot { MediaSniffer.isPreviewOrAdUrl(it.url) }
            .distinctBy { "${it.streamType}|${MediaSniffer.dedupeKey(it.url)}|${it.qualityHint ?: 0}" }

        val resolved = distinctPayloads.take(12).mapIndexed { idx, payload ->
            async(dispatchers.io) {
                val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
                val headers = payload.headersMap
                when (payload.streamType) {
                    StreamType.HLS -> runCatching {
                        if (payload.qualityHint != null) {
                            hlsExtractor.extract(cleanUrl, headers, payload.qualityHint)?.streams
                        } else {
                            hlsExtractor.extract(cleanUrl, headers)?.streams
                        }
                    }.getOrNull().orEmpty()
                    StreamType.DASH -> runCatching { dashExtractor.extract(cleanUrl, headers)?.streams }.getOrNull().orEmpty()
                    StreamType.PROGRESSIVE -> {
                        val info = runCatching { http.probe(cleanUrl, headers) }.getOrNull()
                        if (info != null && info.code !in 200..299) {
                            emptyList()
                        } else {
                            val contentType = (info?.contentType ?: payload.mimeType.orEmpty()).lowercase()
                            if (contentType.startsWith("text/html") ||
                                contentType.startsWith("application/json") ||
                                contentType.startsWith("image/")
                            ) {
                                emptyList()
                            } else {
                                val isAudio = MediaSniffer.isAudioTrackUrl(cleanUrl, contentType)
                                val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
                                val container = MediaSniffer.containerFor(StreamType.PROGRESSIVE, cleanUrl, contentType)
                                val size = info?.contentLength?.takeIf { it > 0 } ?: payload.sizeBytes?.takeIf { it > 0 }
                                val height = payload.qualityHint?.takeIf { it > 0 } ?: UrlUtils.inferResolution(cleanUrl)
                                if (size != null && size in 1..40_000L) {
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
                                            headers = headers.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
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

        ResolvedMedia(
            sourceUrl = targetUrl,
            title = capturedTitle?.takeIf { it.isNotBlank() } ?: UrlUtils.host(targetUrl) ?: "Media",
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
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                webViewRef = wv

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        pageFinished = true
                    }
                }
                wv.loadUrl(targetUrl)
            }

            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(350)
                if (!pageFinished && System.currentTimeMillis() - start < 2_200L) continue
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
                if (pageFinished && current != null && System.currentTimeMillis() - start > 1_800L) {
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

    private fun extractHostFamily(url: String): String {
        val host = runCatching { java.net.URI(UrlUtils.encodeNonAsciiUrl(url)).host?.lowercase().orEmpty() }
            .getOrDefault("")
            .removePrefix("www.")
        if (host.isEmpty()) return ""
        val parts = host.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    }

    private companion object {
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
