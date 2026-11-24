package com.melmeligy.mediadownloader.extractor

import android.util.Base64
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.math.BigInteger
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * High-priority universal extractor for major social platforms (videos, single photos,
 * multi-image carousels/albums/slideshows, and mixed photo+video carousels):
 * 1. Instagram (Reels, Posts, Carousels, TV) via FastDL + Polaris GraphQL + doc_id + Embed JSON.
 * 2. Facebook (Videos, Watch, Reels, fb.watch, Single & Multi-Photo Albums).
 * 3. TikTok (Videos, Photo Mode Slideshows, Audio) via tikwm API + Hydration JSON racing.
 * 4. X / Twitter (Single/Multi-Image, Mixed, Videos) via parallel vxtwitter + fxtwitter + syndication.
 * 5. Pinterest (Single Pins, Carousel Pins, Idea/Story Pins, Pin Videos, pin.it).
 * 6. Threads (Single/Multi-Image, Mixed Carousels, Videos).
 * 7. Reddit (Image Galleries, Single Images, Crossposts, Reddit Videos, redd.it).
 */
@Singleton
class SocialMediaExtractor @Inject constructor(
    private val http: ExtractorHttp,
    private val headlessWebExtractor: HeadlessWebExtractor,
    private val progressTracker: ExtractionProgressTracker
) : MediaExtractor {

    constructor(
        http: ExtractorHttp,
        headlessWebExtractor: HeadlessWebExtractor
    ) : this(http, headlessWebExtractor, ExtractionProgressTracker())

    override val name = "Social Media"
    override val priority = 95

    private data class CachedRedirect(
        val resolvedUrl: String,
        val cachedAtMs: Long
    )

    private val redirectCache = ConcurrentHashMap<String, CachedRedirect>()

    @Volatile
    private var tikwmBlockedUntilMs: Long = 0L

    internal var clockMs: () -> Long = { System.currentTimeMillis() }
    internal var cookieProvider: (String) -> String? = { target ->
        runCatching { android.webkit.CookieManager.getInstance().getCookie(target) }.getOrNull()
    }
    internal var instagramEmbedFallbackRunner: suspend (String) -> ResolvedMedia? = { embedUrl ->
        headlessWebExtractor.extract(
            targetUrl = embedUrl,
            timeoutMs = HeadlessWebExtractor.SOCIAL_FAST_TIMEOUT_MS,
            socialEmbedFastMode = true
        )
    }

    private val igCanonicalShortcodeRegex =
        Regex("(?<!/share)/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val igShareTokenRegex =
        Regex("/share/(?:reel|p)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val twitterStatusRegex =
        Regex("(?:twitter\\.com|x\\.com)/([^/]+)/status/(\\d+)", RegexOption.IGNORE_CASE)
    private val pinterestPinIdRegex =
        Regex("pinterest\\.[a-z.]+/pin/(?:[^/?#]*--)?(\\d+)", RegexOption.IGNORE_CASE)

    override fun canHandle(url: String): Boolean {
        val host = UrlUtils.host(url)?.lowercase() ?: return false
        return host.contains("instagram.com") ||
            host.contains("instagr.am") ||
            host.contains("tiktok.com") ||
            host.contains("twitter.com") ||
            host.contains("x.com") ||
            host == "t.co" ||
            host.contains("facebook.com") ||
            host.contains("fb.watch") ||
            host.contains("fb.com") ||
            host.contains("fb.me") ||
            host.contains("pinterest.") ||
            host.contains("pin.it") ||
            host.contains("threads.net") ||
            host.contains("threads.com") ||
            host.contains("reddit.com") ||
            host.contains("redd.it")
    }

    override suspend fun extract(url: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        if (!canHandle(url) && !UrlUtils.isShortOrRedirectUrl(url)) return@withContext null
        progressTracker.update(ExtractionProgressTracker.STAGE_FETCHING)
        val rawHost = UrlUtils.host(url)?.lowercase().orEmpty()
        // TikTok short links (vt.tiktok.com / vm.tiktok.com) are accepted directly by tikwm, so do NOT
        // block sequentially on redirect resolution; race tikwm against redirect + HTML hydration in parallel.
        val isTikTokRaw = rawHost.contains("tiktok.com")
        val workingUrl = if (!isTikTokRaw && UrlUtils.isShortOrRedirectUrl(url)) {
            resolveRedirectUrl(url)
        } else {
            url
        }
        if (!canHandle(workingUrl) && !canHandle(url)) return@withContext null
        val host = UrlUtils.host(workingUrl)?.lowercase().orEmpty()
        progressTracker.update(ExtractionProgressTracker.STAGE_QUALITIES)
        val timeoutMs = if (host.contains("pinterest.") || host.contains("pin.it")) 14_000L else 10_000L
        val fastResult = try {
            withTimeoutOrNull(timeoutMs) {
                when {
                    host.contains("instagram.com") || host.contains("instagr.am") -> extractInstagram(workingUrl)
                    host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.com") || host.contains("fb.me") -> extractFacebook(workingUrl)
                    host.contains("twitter.com") || host.contains("x.com") -> extractTwitter(workingUrl)
                    host.contains("tiktok.com") -> extractTikTok(workingUrl)
                    host.contains("pinterest.") || host.contains("pin.it") -> extractPinterest(workingUrl)
                    host.contains("threads.net") || host.contains("threads.com") -> extractThreads(workingUrl)
                    host.contains("reddit.com") || host.contains("redd.it") -> extractReddit(workingUrl)
                    else -> null
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }

        if (fastResult != null && (fastResult.streams.isNotEmpty() || fastResult.gallerySlides.isNotEmpty())) {
            return@withContext fastResult.copy(sourceUrl = url)
        }

        // For Instagram (which handles its own targeted embed-only fallback), Pinterest, Threads, Reddit
        // do NOT fall back to full-page Headless WebView.
        if (host.contains("instagram.com") || host.contains("instagr.am") ||
            host.contains("pinterest.") || host.contains("pin.it") ||
            host.contains("threads.net") || host.contains("threads.com") ||
            host.contains("reddit.com") || host.contains("redd.it")
        ) {
            return@withContext null
        }

        // Targeted social-fast-mode Headless WebView fallback (never clicks watch servers or promotes embeds)
        try {
            headlessWebExtractor.extract(
                targetUrl = workingUrl,
                timeoutMs = HeadlessWebExtractor.SOCIAL_FAST_TIMEOUT_MS,
                socialEmbedFastMode = true
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun resolveRedirectUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val cacheKey = rawUrl.trim()
        val now = clockMs()
        redirectCache[cacheKey]?.let { entry ->
            if (now - entry.cachedAtMs < REDIRECT_CACHE_TTL_MS) {
                return@withContext entry.resolvedUrl
            } else {
                redirectCache.remove(cacheKey, entry)
            }
        }

        // Fast single-hop Location-header check (followRedirects = false, zero body download)
        val singleHop = withTimeoutOrNull(REDIRECT_PROBE_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = REDIRECT_PROBE_TIMEOUT_MS) {
                runCatching { http.resolveRedirectLocation(rawUrl) }.getOrNull()
            }
        }
        val resolved = if (!singleHop.isNullOrBlank() && singleHop.startsWith("http") && singleHop != rawUrl) {
            UrlUtils.cleanSocialUrl(singleHop)
        } else {
            val probeFinal = withTimeoutOrNull(REDIRECT_PROBE_TIMEOUT_MS) {
                withCancellableHttpCalls(timeoutMs = REDIRECT_PROBE_TIMEOUT_MS) {
                    runCatching { http.probe(rawUrl).finalUrl }.getOrNull()
                }
            }
            if (!probeFinal.isNullOrBlank() && probeFinal.startsWith("http") && probeFinal != rawUrl) {
                UrlUtils.cleanSocialUrl(probeFinal)
            } else {
                val getFinal = withTimeoutOrNull(REDIRECT_GET_TIMEOUT_MS) {
                    withCancellableHttpCalls(timeoutMs = REDIRECT_GET_TIMEOUT_MS) {
                        runCatching { http.resolveFinalUrl(rawUrl) }.getOrNull()
                    }
                }
                if (!getFinal.isNullOrBlank() && getFinal.startsWith("http") && getFinal != rawUrl) {
                    UrlUtils.cleanSocialUrl(getFinal)
                } else {
                    UrlUtils.cleanSocialUrl(probeFinal?.takeIf { it.startsWith("http") } ?: rawUrl)
                }
            }
        }

        if (resolved != rawUrl) {
            if (redirectCache.size >= MAX_REDIRECT_CACHE_ENTRIES) {
                val cutoff = now - REDIRECT_CACHE_TTL_MS
                redirectCache.entries.removeIf { it.value.cachedAtMs < cutoff }
                if (redirectCache.size >= MAX_REDIRECT_CACHE_ENTRIES) {
                    redirectCache.clear()
                }
            }
            redirectCache[cacheKey] = CachedRedirect(resolved, now)
        }
        resolved
    }

    private fun buildGalleryResolvedMedia(
        rawUrl: String,
        extractorName: String,
        parsed: SocialMediaGalleryParsers.ParsedPost
    ): ResolvedMedia {
        val orderedSlides = parsed.slides.mapIndexed { idx, slide ->
            slide.copy(index = idx)
        }
        val slideStreams = orderedSlides.mapIndexed { idx, slide ->
            MediaStream(
                id = slide.id,
                url = slide.mediaUrl,
                type = slide.type,
                container = slide.container,
                label = if (slide.type == MediaType.VIDEO) "Slide ${idx + 1} (Video)" else "Photo ${idx + 1} (${slide.container.uppercase()})",
                width = slide.width,
                height = slide.height,
                sizeBytes = slide.sizeBytes,
                isHls = false,
                headers = slide.headers
            )
        }
        return ResolvedMedia(
            sourceUrl = rawUrl,
            title = parsed.title?.takeIf { it.isNotBlank() } ?: "$extractorName Post",
            thumbnailUrl = parsed.thumbnailUrl ?: orderedSlides.firstOrNull()?.thumbnailUrl,
            streams = slideStreams + parsed.audioStreams,
            extractor = extractorName,
            gallerySlides = orderedSlides,
            postId = parsed.postId ?: UrlUtils.extractPostId(rawUrl)
        )
    }

    // =========================================================================
    // 1. INSTAGRAM (Snaptube Polaris GraphQL + doc_id + Embed DASH/JSON)
    // =========================================================================

    private data class InstagramRaceOutcome(
        val resolved: ResolvedMedia? = null,
        val loginRequired: Boolean = false
    )

    internal fun extractInstagramShortcode(url: String): String? {
        igCanonicalShortcodeRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        // Instagram /share/reel/... sometimes 302-redirects to /accounts/login/?next=%2Freel%2F<CODE>%2F
        val nextParam = Regex("[?&]next=([^&#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
        if (!nextParam.isNullOrBlank()) {
            val decodedNext = runCatching { java.net.URLDecoder.decode(nextParam, "UTF-8") }.getOrDefault(nextParam)
            igCanonicalShortcodeRegex.find(decodedNext)?.groupValues?.getOrNull(1)?.let { return it }
        }
        // Last resort for legacy /share/reel/<shortcode> links that did not redirect
        return igShareTokenRegex.find(url)?.groupValues?.getOrNull(1)
    }

    internal fun looksLikeInstagramLoginOrPrivate(payload: String): Boolean {
        if (payload.isBlank()) return false
        val lower = payload.lowercase()
        val hasMedia = lower.contains("\"video_url\"") ||
            lower.contains("\"video_versions\"") ||
            lower.contains("<video") ||
            lower.contains("embeddedmediaimage")
        if (hasMedia) return false
        return lower.contains("\"login_required\"") ||
            lower.contains("\"require_login\":true") ||
            lower.contains("\"is_private\":true") ||
            lower.contains("this account is private") ||
            lower.contains("sorry, this page isn't available") ||
            lower.contains("sorry, this page isn&#039;t available") ||
            (lower.contains("/accounts/login/") && !lower.contains("gql_data"))
    }

    private fun extractCsrfTokenFromCookie(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("csrftoken=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun extractInstagram(rawUrl: String): ResolvedMedia? = coroutineScope {
        val resolvedUrl = if (rawUrl.contains("/share/", ignoreCase = true) || rawUrl.contains("instagr.am", ignoreCase = true)) {
            resolveRedirectUrl(rawUrl)
        } else {
            rawUrl
        }

        val shortcode = extractInstagramShortcode(resolvedUrl)
        if (shortcode.isNullOrBlank()) {
            if (resolvedUrl.contains("/accounts/login", ignoreCase = true)) {
                progressTracker.setDiagnosticReason(INSTAGRAM_LOGIN_REQUIRED_MESSAGE)
            }
            return@coroutineScope null
        }

        val mediaId = shortcodeToMediaId(shortcode)
        val liveCookie = runCatching { cookieProvider("https://www.instagram.com/") }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val csrfToken = extractCsrfTokenFromCookie(liveCookie)

        val defaultHeaders = LinkedHashMap<String, String>().apply {
            put("Referer", "https://www.instagram.com/")
            put("Accept-Language", "en-US,en;q=0.9")
            put("User-Agent", IG_DESKTOP_UA)
            if (!liveCookie.isNullOrBlank()) put("Cookie", liveCookie)
        }

        val isPostPath = resolvedUrl.contains("/p/", ignoreCase = true) || rawUrl.contains("/share/p/", ignoreCase = true)
        val canonicalUrl = if (isPostPath) {
            "https://www.instagram.com/p/$shortcode/"
        } else {
            "https://www.instagram.com/reel/$shortcode/"
        }
        val primaryEmbedUrl = if (isPostPath) {
            "https://www.instagram.com/p/$shortcode/embed/captioned/"
        } else {
            "https://www.instagram.com/reel/$shortcode/embed/captioned/"
        }
        val alternateEmbedUrl = if (isPostPath) {
            "https://www.instagram.com/reel/$shortcode/embed/captioned/"
        } else {
            "https://www.instagram.com/p/$shortcode/embed/captioned/"
        }

        fun parsePayloadIntoResolved(
            payloadText: String,
            parsedPost: SocialMediaGalleryParsers.ParsedPost?,
            fallbackTitle: String? = null,
            fallbackThumb: String? = null,
            extraVideos: List<String> = emptyList()
        ): ResolvedMedia? {
            if (parsedPost != null && parsedPost.slides.isNotEmpty()) {
                return buildGalleryResolvedMedia(rawUrl, "Instagram", parsedPost)
            }
            val rawStreams = LinkedHashMap<String, MediaStream>()
            var counter = 0
            var capturedTitle = parsedPost?.title ?: fallbackTitle
            var capturedThumb = parsedPost?.thumbnailUrl ?: fallbackThumb

            fun addCandidate(
                candidateUrl: String,
                type: MediaType,
                container: String,
                label: String,
                width: Int? = null,
                height: Int? = null,
                bitrateKbps: Int? = null,
                isDashVideoOnly: Boolean = false
            ) {
                val cleaned = MediaSniffer.cleanMediaUrl(unescape(candidateUrl))
                if (!cleaned.startsWith("http")) return
                val key = cleaned.substringBefore('?')
                if (rawStreams.containsKey(key)) return
                val extraHeaders = if (isDashVideoOnly) {
                    defaultHeaders + ("X-Internal-Dash-VideoOnly" to "true")
                } else {
                    defaultHeaders
                }
                rawStreams[key] = MediaStream(
                    id = "ig-${counter++}",
                    url = cleaned,
                    type = type,
                    container = container,
                    label = label,
                    width = width,
                    height = height,
                    bitrateKbps = bitrateKbps,
                    isHls = false,
                    headers = extraHeaders
                )
            }

            parsedPost?.videoStreams?.forEach { v ->
                addCandidate(v.url, MediaType.VIDEO, v.container, v.label, v.width, v.height, v.bitrateKbps)
            }

            if (payloadText.isNotBlank()) {
                val unescaped = unescape(payloadText)

                // DASH manifest XML
                val dashRegex = Regex("<MPD[\\s\\S]*?</MPD>", RegexOption.IGNORE_CASE)
                dashRegex.findAll(unescaped).forEach { match ->
                    parseDashXml(match.value).forEach { s ->
                        addCandidate(
                            s.url,
                            s.type,
                            s.container,
                            s.label,
                            s.width,
                            s.height,
                            s.bitrateKbps,
                            isDashVideoOnly = (s.type == MediaType.VIDEO)
                        )
                    }
                }

                // video_versions JSON array
                val versionsRegex = Regex("\"video_versions\"\\s*:\\s*(\\[[^\\]]+\\])")
                versionsRegex.findAll(unescaped).forEach { match ->
                    runCatching {
                        val arr = JSONArray(match.groupValues[1])
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val vUrl = obj.optString("url")
                            val w = obj.optInt("width", 0).takeIf { it > 0 }
                            val h = obj.optInt("height", 0).takeIf { it > 0 }
                            val label = when {
                                h != null && h >= 720 -> "High quality (${h}p)"
                                h != null -> "Fast (${h}p)"
                                i == 0 -> "High quality (720p)"
                                else -> "Fast (480p)"
                            }
                            addCandidate(vUrl, MediaType.VIDEO, "mp4", label, w, h)
                        }
                    }
                }

                // direct video_url fields
                val videoUrlRegex = Regex("\"video_url\"\\s*:\\s*\"([^\"]+)\"")
                videoUrlRegex.findAll(unescaped).forEach { match ->
                    addCandidate(match.groupValues[1], MediaType.VIDEO, "mp4", "High quality (720p)")
                }

                // display_url / thumbnail_src
                if (capturedThumb == null) {
                    val thumbRegex = Regex("\"(?:display_url|thumbnail_src)\"\\s*:\\s*\"([^\"]+)\"")
                    thumbRegex.find(unescaped)?.groupValues?.getOrNull(1)?.let {
                        if (it.startsWith("http")) capturedThumb = it
                    }
                }
            }

            extraVideos.forEach { vUrl ->
                addCandidate(vUrl, MediaType.VIDEO, "mp4", "High quality (720p)")
            }

            if (rawStreams.isEmpty()) return null
            return buildInstagramResolvedMedia(
                rawUrl = rawUrl,
                shortcode = shortcode,
                probed = rawStreams.values.take(10).toList(),
                capturedTitle = capturedTitle,
                capturedThumb = capturedThumb
            )
        }

        // Race all 4 lightweight HTTP paths in parallel (First-Valid-Wins; cancel slower siblings immediately)
        val raceChannel = Channel<InstagramRaceOutcome>(capacity = 4)
        val raceJobs = buildList {
            // Path 1: Public login-free /embed/captioned/ page (reel & post variants)
            add(launch(Dispatchers.IO) {
                val outcome = try {
                    withTimeoutOrNull(SOCIAL_API_TIMEOUT_MS) {
                        withCancellableHttpCalls(timeoutMs = SOCIAL_API_TIMEOUT_MS) {
                            var html = runCatching { http.fetchString(primaryEmbedUrl, defaultHeaders) }.getOrNull().orEmpty()
                            var usedUrl = primaryEmbedUrl
                            if (html.isBlank()) {
                                html = runCatching { http.fetchString(alternateEmbedUrl, defaultHeaders) }.getOrNull().orEmpty()
                                usedUrl = alternateEmbedUrl
                            }
                            if (html.isBlank()) {
                                InstagramRaceOutcome()
                            } else {
                                val parsedEmbed = SocialMediaGalleryParsers.parseInstagramEmbedHtml(html, shortcode, defaultHeaders)
                                val doc = Jsoup.parse(html, usedUrl)
                                val videoTags = doc.select("video[src], video source[src]").mapNotNull { el ->
                                    el.absUrl("src").ifBlank { el.attr("src") }.takeIf { it.startsWith("http") }
                                }
                                val title = doc.select(".CaptionUsername").text().takeIf { it.isNotBlank() }
                                    ?.let { "Instagram Reel (@$it)" }
                                    ?: doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
                                val thumb = doc.select("img.EmbeddedMediaImage").attr("src")
                                    .ifBlank { doc.select("meta[property=og:image]").attr("content") }
                                    .let { unescape(it) }
                                    .takeIf { it.startsWith("http") }
                                val resolved = parsePayloadIntoResolved(
                                    payloadText = html,
                                    parsedPost = parsedEmbed,
                                    fallbackTitle = title,
                                    fallbackThumb = thumb,
                                    extraVideos = videoTags
                                )
                                InstagramRaceOutcome(
                                    resolved = resolved,
                                    loginRequired = resolved == null && looksLikeInstagramLoginOrPrivate(html)
                                )
                            }
                        }
                    } ?: InstagramRaceOutcome()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    InstagramRaceOutcome()
                }
                raceChannel.trySend(outcome)
            })

            // Path 2: Polaris GraphQL POST (with X-IG-App-ID, optional live CookieManager session & CSRF token)
            if (mediaId != null) {
                add(launch(Dispatchers.IO) {
                    val outcome = try {
                        withTimeoutOrNull(SOCIAL_API_TIMEOUT_MS) {
                            withCancellableHttpCalls(timeoutMs = SOCIAL_API_TIMEOUT_MS) {
                                val polarisHeaders = LinkedHashMap<String, String>().apply {
                                    put("X-IG-App-ID", IG_WEB_APP_ID)
                                    put("X-ASBD-ID", "198387")
                                    put("Origin", "https://www.instagram.com")
                                    put("Referer", canonicalUrl)
                                    put("User-Agent", IG_MOBILE_UA)
                                    if (!liveCookie.isNullOrBlank()) put("Cookie", liveCookie)
                                    if (!csrfToken.isNullOrBlank()) put("X-CSRFToken", csrfToken)
                                }
                                val polarisBody = "av=0&__d=www&__user=0&dpr=1&lsd=AVqb_dummy&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery&variables=" +
                                    URLEncoder.encode("{\"media_id\":\"$mediaId\"}", "UTF-8") +
                                    "&doc_id=27130156389949648"
                                val polarisJson = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                                    http.postForm("https://www.instagram.com/api/graphql", polarisBody, polarisHeaders)
                                }
                                val parsed = SocialMediaGalleryParsers.parseInstagramGraphQl(polarisJson, shortcode, defaultHeaders)
                                val resolved = parsePayloadIntoResolved(polarisJson, parsed)
                                InstagramRaceOutcome(
                                    resolved = resolved,
                                    loginRequired = resolved == null && looksLikeInstagramLoginOrPrivate(polarisJson)
                                )
                            }
                        } ?: InstagramRaceOutcome()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        InstagramRaceOutcome()
                    }
                    raceChannel.trySend(outcome)
                })
            }

            // Path 3: Instagram GraphQL doc_id GET query (with X-IG-App-ID & optional live CookieManager session)
            add(launch(Dispatchers.IO) {
                val outcome = try {
                    withTimeoutOrNull(SOCIAL_API_TIMEOUT_MS) {
                        withCancellableHttpCalls(timeoutMs = SOCIAL_API_TIMEOUT_MS) {
                            val docHeaders = LinkedHashMap<String, String>().apply {
                                put("X-IG-App-ID", IG_WEB_APP_ID)
                                put("Referer", canonicalUrl)
                                put("User-Agent", IG_DESKTOP_UA)
                                if (!liveCookie.isNullOrBlank()) put("Cookie", liveCookie)
                                if (!csrfToken.isNullOrBlank()) put("X-CSRFToken", csrfToken)
                            }
                            val docUrl = "https://www.instagram.com/graphql/query/?doc_id=8845758582119845&variables=" +
                                URLEncoder.encode("{\"shortcode\":\"$shortcode\"}", "UTF-8")
                            val docJson = http.fetchString(docUrl, docHeaders)
                            val parsed = SocialMediaGalleryParsers.parseInstagramGraphQl(docJson, shortcode, defaultHeaders)
                            val resolved = parsePayloadIntoResolved(docJson, parsed)
                            InstagramRaceOutcome(
                                resolved = resolved,
                                loginRequired = resolved == null && looksLikeInstagramLoginOrPrivate(docJson)
                            )
                        }
                    } ?: InstagramRaceOutcome()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    InstagramRaceOutcome()
                }
                raceChannel.trySend(outcome)
            })

            // Path 4: Lightweight FastDL web resolver (raced in parallel, never blocks other paths)
            add(launch(Dispatchers.IO) {
                val outcome = try {
                    withTimeoutOrNull(SOCIAL_API_TIMEOUT_MS) {
                        withCancellableHttpCalls(timeoutMs = SOCIAL_API_TIMEOUT_MS) {
                            val fastDlHeaders = mapOf(
                                "User-Agent" to IG_DESKTOP_UA,
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "Origin" to "https://fastdl.to",
                                "Referer" to "https://fastdl.to/en",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "*/*"
                            )
                            val postBody = "q=" + URLEncoder.encode(canonicalUrl, "UTF-8") + "&t=media&lang=en&v=v2"
                            val respJson = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                                http.postForm("https://fastdl.to/api/ajaxSearch", postBody, fastDlHeaders)
                            }
                            if (respJson.isBlank()) {
                                InstagramRaceOutcome()
                            } else {
                                val jsonRoot = JSONObject(respJson)
                                val dataHtml = if (jsonRoot.optString("status") == "ok") jsonRoot.optString("data") else ""
                                val parsedFastDl = if (dataHtml.isNotBlank()) {
                                    SocialMediaGalleryParsers.parseInstagramFastDlHtml(
                                        dataHtml = dataHtml,
                                        shortcode = shortcode,
                                        headers = defaultHeaders
                                    )
                                } else null
                                val resolved = if (parsedFastDl != null) {
                                    parsePayloadIntoResolved("", parsedFastDl)
                                } else null
                                InstagramRaceOutcome(resolved = resolved)
                            }
                        }
                    } ?: InstagramRaceOutcome()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    InstagramRaceOutcome()
                }
                raceChannel.trySend(outcome)
            })
        }

        var winner: ResolvedMedia? = null
        var sawLoginWall = false
        try {
            for (i in raceJobs.indices) {
                val outcome = raceChannel.receiveCatching().getOrNull() ?: break
                if (outcome.loginRequired) sawLoginWall = true
                if (outcome.resolved != null) {
                    winner = outcome.resolved
                    break
                }
            }
        } finally {
            raceJobs.forEach { it.cancel() }
            raceChannel.close()
        }

        if (winner != null) {
            return@coroutineScope winner
        }

        // Fail fast with a clear diagnostic when Instagram explicitly requires login / private access
        if (sawLoginWall) {
            progressTracker.setDiagnosticReason(INSTAGRAM_LOGIN_REQUIRED_MESSAGE)
            return@coroutineScope null
        }

        // Targeted, short-capped (~3.8s) WebView fallback strictly on the login-free /embed/captioned/ URL
        // (socialEmbedFastMode = true: zero watch-server clicking, zero iframe promotion)
        val embedFallback = try {
            instagramEmbedFallbackRunner(primaryEmbedUrl)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
        if (embedFallback != null && (embedFallback.streams.isNotEmpty() || embedFallback.gallerySlides.isNotEmpty())) {
            return@coroutineScope embedFallback.copy(
                sourceUrl = rawUrl,
                title = embedFallback.title.takeIf { it.isNotBlank() && !it.equals("Instagram", ignoreCase = true) }
                    ?: "Instagram Reel ($shortcode)",
                extractor = "Instagram"
            )
        }

        if (progressTracker.peekDiagnosticReason().isNullOrBlank()) {
            progressTracker.setDiagnosticReason(INSTAGRAM_LOGIN_REQUIRED_MESSAGE)
        }
        null
    }

    private fun buildInstagramResolvedMedia(
        rawUrl: String,
        shortcode: String,
        probed: List<MediaStream>,
        capturedTitle: String?,
        capturedThumb: String?
    ): ResolvedMedia {
        // Return formats immediately without blocking on HEAD/Range size probes;
        // StreamSizeRefiner populates missing sizes asynchronously in the background.
        val rawAudios = probed.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val bestAudio = rawAudios.firstOrNull()

        val videos = probed.filter { it.type == MediaType.VIDEO }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
            .map { v ->
                if (v.headers["X-Internal-Dash-VideoOnly"] == "true" && bestAudio != null) {
                    val vSize = v.sizeBytes ?: 0L
                    val aSize = bestAudio.sizeBytes ?: 0L
                    val combined = if (vSize > 0 && aSize > 0) vSize + aSize else v.sizeBytes
                    v.copy(
                        sizeBytes = combined,
                        headers = v.headers + mapOf(
                            "X-Internal-Companion-Audio" to bestAudio.url,
                            "X-Internal-Video-Bytes" to vSize.toString(),
                            "X-Internal-Audio-Bytes" to aSize.toString()
                        )
                    )
                } else {
                    v
                }
            }

        val audios = if (bestAudio != null) {
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
                    id = "ig-audio-fast",
                    container = "m4a",
                    label = "Fast",
                    bitrateKbps = srcKbps,
                    headers = bestAudio.headers + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "M4A (${srcKbps}K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "ig-audio-mp3",
                    container = "mp3",
                    label = "Classic MP3",
                    bitrateKbps = 160,
                    sizeBytes = mp3Size,
                    headers = bestAudio.headers + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true"
                    )
                )
            )
        } else if (videos.isNotEmpty()) {
            val v = videos.first()
            val vBytes = v.sizeBytes ?: 0L
            val estM4a = if (vBytes > 0L) (vBytes * 16L / 100L).coerceAtLeast(150_000L) else null
            val estMp3 = if (vBytes > 0L) (vBytes * 20L / 100L).coerceAtLeast(180_000L) else null
            listOf(
                v.copy(
                    id = "ig-audio-fast",
                    type = MediaType.AUDIO,
                    container = "m4a",
                    label = "Fast",
                    sizeBytes = estM4a,
                    headers = v.headers + mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true",
                        "X-Internal-Extract-Audio" to "true",
                        "X-Internal-Video-Bytes" to vBytes.toString()
                    )
                ),
                v.copy(
                    id = "ig-audio-mp3",
                    type = MediaType.AUDIO,
                    container = "mp3",
                    label = "Classic MP3",
                    sizeBytes = estMp3,
                    headers = v.headers + mapOf(
                        "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true",
                        "X-Internal-Extract-Audio" to "true",
                        "X-Internal-Video-Bytes" to vBytes.toString()
                    )
                )
            )
        } else {
            emptyList()
        }

        val images = probed.filter { it.type == MediaType.IMAGE }

        return ResolvedMedia(
            sourceUrl = rawUrl,
            title = capturedTitle ?: if (videos.isNotEmpty()) "Instagram Reel ($shortcode)" else "Instagram Photo ($shortcode)",
            thumbnailUrl = capturedThumb ?: images.firstOrNull()?.url,
            streams = images + videos + audios,
            extractor = "Instagram"
        )
    }

    private fun shortcodeToMediaId(code: String): String? {
        if (code.isBlank()) return null
        return runCatching {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            var bigInt = BigInteger.ZERO
            val base = BigInteger.valueOf(64)
            for (ch in code) {
                val index = alphabet.indexOf(ch)
                if (index >= 0) {
                    bigInt = bigInt.multiply(base).add(BigInteger.valueOf(index.toLong()))
                }
            }
            bigInt.toString()
        }.getOrNull()
    }

    // =========================================================================
    // 2. FACEBOOK (Snaptube FacebookCodec + DASH Manifest + Native SD/HD)
    // =========================================================================

    private suspend fun extractFacebook(rawUrl: String): ResolvedMedia? = coroutineScope {
        val resolvedUrl = if (rawUrl.contains("fb.watch", ignoreCase = true) || rawUrl.contains("/share/", ignoreCase = true)) {
            resolveRedirectUrl(rawUrl)
        } else {
            rawUrl
        }

        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Mode" to "navigate"
        )

        val html = runCatching { http.fetchString(resolvedUrl, desktopHeaders) }.getOrNull().orEmpty()
        if (html.isBlank()) return@coroutineScope null

        val rawStreams = LinkedHashMap<String, MediaStream>()
        var counter = 0

        fun addCandidate(
            candidateUrl: String,
            type: MediaType,
            container: String,
            label: String,
            width: Int? = null,
            height: Int? = null,
            bitrateKbps: Int? = null,
            isDashVideoOnly: Boolean = false
        ) {
            val cleaned = MediaSniffer.cleanMediaUrl(unescape(candidateUrl))
            if (!cleaned.startsWith("http")) return
            val key = cleaned.substringBefore('?')
            if (rawStreams.containsKey(key)) return
            val extraHeaders = if (isDashVideoOnly) {
                desktopHeaders + ("X-Internal-Dash-VideoOnly" to "true")
            } else {
                desktopHeaders
            }
            rawStreams[key] = MediaStream(
                id = "fb-${counter++}",
                url = cleaned,
                type = type,
                container = container,
                label = label,
                width = width,
                height = height,
                bitrateKbps = bitrateKbps,
                isHls = false,
                headers = extraHeaders
            )
        }

        val unescapedHtml = unescape(html)

        // 1. DASH Manifest (Provides full 1080p, 720p, 480p, 360p + M4A Audio tracks)
        val dashRegex = Regex("\"dash_manifest(?:_xml_string)?\"\\s*:\\s*\"([^\"]+)\"")
        val dashMatch = dashRegex.find(html)?.groupValues?.getOrNull(1)
        val dashXml = if (!dashMatch.isNullOrBlank()) unescape(dashMatch)
        else Regex("<MPD[\\s\\S]*?</MPD>", RegexOption.IGNORE_CASE).find(unescapedHtml)?.value.orEmpty()

        if (dashXml.isNotBlank()) {
            parseDashXml(dashXml).forEach { s ->
                addCandidate(
                    s.url,
                    s.type,
                    s.container,
                    s.label,
                    s.width,
                    s.height,
                    s.bitrateKbps,
                    isDashVideoOnly = (s.type == MediaType.VIDEO)
                )
            }
        }

        // 2. Direct progressive URLs (HD and SD)
        val hdRegex = Regex("\"(?:browser_native_hd_url|playable_url_quality_hd|hd_src)\"\\s*:\\s*\"([^\"]+)\"")
        hdRegex.findAll(unescapedHtml).forEach { match ->
            addCandidate(match.groupValues[1], MediaType.VIDEO, "mp4", "High quality (720p HD)", height = 720)
        }

        val sdRegex = Regex("\"(?:browser_native_sd_url|playable_url|sd_src)\"\\s*:\\s*\"([^\"]+)\"")
        sdRegex.findAll(unescapedHtml).forEach { match ->
            addCandidate(match.groupValues[1], MediaType.VIDEO, "mp4", "Fast (360p SD)", height = 360)
        }

        if (rawStreams.isEmpty()) {
            val parsedPhotos = SocialMediaGalleryParsers.parseFacebookPhotoHtml(
                html = html,
                sourceUrl = resolvedUrl,
                headers = desktopHeaders
            )
            if (parsedPhotos != null && parsedPhotos.slides.isNotEmpty()) {
                return@coroutineScope buildGalleryResolvedMedia(rawUrl, "Facebook", parsedPhotos)
            }
            return@coroutineScope null
        }

        // Return formats immediately without blocking on HEAD/Range size probes;
        // StreamSizeRefiner populates missing sizes asynchronously in the background.
        val probed = rawStreams.values.take(10).toList()

        val rawAudios = probed.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val bestAudio = rawAudios.firstOrNull()

        val videos = probed.filter { it.type == MediaType.VIDEO }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
            .map { v ->
                if (v.headers["X-Internal-Dash-VideoOnly"] == "true" && bestAudio != null) {
                    val vSize = v.sizeBytes ?: 0L
                    val aSize = bestAudio.sizeBytes ?: 0L
                    val combined = if (vSize > 0 && aSize > 0) vSize + aSize else v.sizeBytes
                    v.copy(
                        sizeBytes = combined,
                        headers = v.headers + mapOf(
                            "X-Internal-Companion-Audio" to bestAudio.url,
                            "X-Internal-Video-Bytes" to vSize.toString(),
                            "X-Internal-Audio-Bytes" to aSize.toString()
                        )
                    )
                } else {
                    v
                }
            }

        val audios = if (bestAudio != null) {
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
                    id = "fb-audio-fast",
                    container = "m4a",
                    label = "Fast",
                    bitrateKbps = srcKbps,
                    headers = bestAudio.headers + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "M4A (${srcKbps}K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "fb-audio-mp3",
                    container = "mp3",
                    label = "Classic MP3",
                    bitrateKbps = 160,
                    sizeBytes = mp3Size,
                    headers = bestAudio.headers + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true"
                    )
                )
            )
        } else {
            emptyList()
        }

        val doc = Jsoup.parse(html, resolvedUrl)
        val title = doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
            ?: "Facebook Video"
        val thumb = doc.select("meta[property=og:image]").attr("content").takeIf { it.startsWith("http") }

        ResolvedMedia(
            sourceUrl = rawUrl,
            title = title,
            thumbnailUrl = thumb,
            streams = videos + audios,
            extractor = "Facebook"
        )
    }

    // =========================================================================
    // 3. TIKTOK (Parallel Race: tikwm API vs Universal Hydration JSON)
    // =========================================================================

    private suspend fun extractTikTok(rawUrl: String): ResolvedMedia? = coroutineScope {
        val cleanedRaw = UrlUtils.cleanSocialUrl(rawUrl)
        val isShortLink = UrlUtils.isShortOrRedirectUrl(rawUrl)

        val desktopHeaders = mapOf(
            "User-Agent" to TIKTOK_DESKTOP_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        fun buildBaseCdnHeaders(targetUrl: String): Map<String, String> = LinkedHashMap<String, String>().apply {
            put("User-Agent", TIKTOK_DESKTOP_UA)
            put("Referer", "https://www.tiktok.com/")
            put("Origin", "https://www.tiktok.com")
            http.cookieHeaderFor(targetUrl)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }

        // If tikwm recently returned Cloudflare 403/429, go straight to HTML Hydration with 0ms API wait
        if (clockMs() < tikwmBlockedUntilMs) {
            val resolvedUrl = if (isShortLink) resolveRedirectUrl(rawUrl) else cleanedRaw
            return@coroutineScope extractTikTokViaHtmlHydration(
                rawUrl = rawUrl,
                canonicalUrl = resolvedUrl,
                desktopHeaders = desktopHeaders,
                tikTokBaseCdnHeaders = buildBaseCdnHeaders(resolvedUrl)
            )
        }

        // Parallel race (first-valid-wins):
        // - apiJob hits tikwm immediately with rawUrl (tikwm natively accepts vt.tiktok.com/vm.tiktok.com).
        // - htmlJob resolves short redirect (single-hop Location header) if needed and fetches HTML hydration concurrently.
        // First valid winner returns immediately and cancels the losing branch + underlying TCP socket.
        val raceChannel = Channel<ResolvedMedia?>(capacity = 2)
        val apiJob = launch(Dispatchers.IO) {
            val res = try {
                extractTikTokViaTikwmApi(rawUrl, cleanedRaw)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            raceChannel.trySend(res)
        }
        val htmlJob = launch(Dispatchers.IO) {
            val res = try {
                val resolvedUrl = if (isShortLink) resolveRedirectUrl(rawUrl) else cleanedRaw
                extractTikTokViaHtmlHydration(
                    rawUrl = rawUrl,
                    canonicalUrl = resolvedUrl,
                    desktopHeaders = desktopHeaders,
                    tikTokBaseCdnHeaders = buildBaseCdnHeaders(resolvedUrl)
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            raceChannel.trySend(res)
        }

        var winner: ResolvedMedia? = null
        try {
            for (i in 0 until 2) {
                val candidate = raceChannel.receiveCatching().getOrNull() ?: continue
                if (candidate.streams.isNotEmpty() || candidate.gallerySlides.isNotEmpty()) {
                    winner = candidate
                    break
                }
            }
        } finally {
            apiJob.cancel()
            htmlJob.cancel()
            raceChannel.close()
        }

        winner
    }

    private suspend fun extractTikTokViaTikwmApi(
        rawUrl: String,
        canonicalUrl: String
    ): ResolvedMedia? = coroutineScope {
        val encoded = URLEncoder.encode(canonicalUrl, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
        val jsonText = withTimeoutOrNull(TIKWM_API_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = TIKWM_API_TIMEOUT_MS) {
                try {
                    http.fetchString(apiUrl)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (me: MediaException) {
                    if (me.error == AppError.ACCESS_DENIED ||
                        me.error == AppError.GEO_BLOCKED ||
                        me.error == AppError.SERVER_ERROR ||
                        me.message?.contains("429") == true
                    ) {
                        tikwmBlockedUntilMs = clockMs() + TIKWM_COOLDOWN_MS
                    }
                    null
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (jsonText.isNullOrBlank()) return@coroutineScope null
        if (jsonText.trimStart().startsWith("<")) {
            tikwmBlockedUntilMs = clockMs() + TIKWM_COOLDOWN_MS
            return@coroutineScope null
        }

        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@coroutineScope null
        val data = root.optJSONObject("data") ?: return@coroutineScope null

        val hasImages = (data.optJSONArray("images")?.length() ?: 0) > 0
        if (hasImages) {
            // Zero blocking probes: emit photo slides + audio immediately; StreamSizeRefiner refines sizes asynchronously.
            val parsedPhotos = SocialMediaGalleryParsers.parseTikTokPhotoFromApiData(
                data = data,
                postId = UrlUtils.extractPostId(canonicalUrl),
                musicProbe = null,
                headers = mapOf("Referer" to "https://www.tiktok.com/")
            )
            if (parsedPhotos != null && parsedPhotos.slides.isNotEmpty()) {
                return@coroutineScope buildGalleryResolvedMedia(rawUrl, "TikTok", parsedPhotos)
            }
        }

        // Zero blocking probes: emit video & audio formats immediately so the sheet renders without delay.
        val streams = SocialMediaGalleryParsers.buildTikTokVideoAndAudioStreams(
            data = data,
            hdProbe = null,
            playProbe = null,
            musicProbe = null
        )
        if (streams.isEmpty()) return@coroutineScope null

        val title = data.optString("title").takeIf { it.isNotBlank() } ?: "TikTok Video"
        val cover = data.optString("cover").takeIf { it.startsWith("http") }
        ResolvedMedia(
            sourceUrl = rawUrl,
            title = title,
            thumbnailUrl = cover,
            streams = streams,
            extractor = "TikTok"
        )
    }

    private suspend fun extractTikTokViaHtmlHydration(
        rawUrl: String,
        canonicalUrl: String,
        desktopHeaders: Map<String, String>,
        tikTokBaseCdnHeaders: Map<String, String>
    ): ResolvedMedia? = coroutineScope {
        val fetchedHtml = withTimeoutOrNull(TIKTOK_HTML_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = TIKTOK_HTML_TIMEOUT_MS) {
                runCatching { http.fetchHtmlWithCookies(canonicalUrl, desktopHeaders) }.getOrNull()
            }
        }
        val html = fetchedHtml?.html.orEmpty()
        if (html.isBlank()) return@coroutineScope null

        val cdnHeaders = LinkedHashMap<String, String>(tikTokBaseCdnHeaders).apply {
            val cookie = fetchedHtml?.cookieHeader?.takeIf { it.isNotBlank() }
                ?: http.cookieHeaderFor(fetchedHtml?.finalUrl ?: canonicalUrl)
            if (!cookie.isNullOrBlank()) {
                put("Cookie", cookie)
            }
        }

        // Photo Mode Slideshow from Hydration JSON (zero blocking audio probe; StreamSizeRefiner fills size asynchronously)
        val parsedPhotoHtml = SocialMediaGalleryParsers.parseTikTokPhotoFromHydrationHtml(
            html = html,
            postId = UrlUtils.extractPostId(canonicalUrl),
            headers = cdnHeaders
        )
        if (parsedPhotoHtml != null && parsedPhotoHtml.slides.isNotEmpty()) {
            return@coroutineScope buildGalleryResolvedMedia(
                rawUrl = rawUrl,
                extractorName = "TikTok",
                parsed = parsedPhotoHtml
            )
        }

        // Full structured __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON video + multi-quality bitrateInfo + music.
        // Zero blocking HEAD/Range probes before returning ResolvedMedia; StreamSizeRefiner refines missing sizes asynchronously.
        val parsedVideoPost = SocialMediaGalleryParsers.parseTikTokVideoFromHydrationHtml(html, cdnHeaders)
        if (parsedVideoPost != null) {
            val allStreams = parsedVideoPost.videoStreams + parsedVideoPost.audioStreams
            if (allStreams.isNotEmpty()) {
                return@coroutineScope ResolvedMedia(
                    sourceUrl = rawUrl,
                    title = parsedVideoPost.title ?: "TikTok Video",
                    thumbnailUrl = parsedVideoPost.thumbnailUrl,
                    streams = allStreams,
                    extractor = "TikTok",
                    postId = parsedVideoPost.postId ?: UrlUtils.extractPostId(canonicalUrl)
                )
            }
        }

        // Legacy surface fallback when __UNIVERSAL_DATA_FOR_REHYDRATION__ wrapper is absent (zero blocking probes)
        val playAddrRegex = Regex("\"playAddr\"\\s*:\\s*\"([^\"]+)\"")
        val playUrl = playAddrRegex.find(html)?.groupValues?.getOrNull(1)?.let { unescape(it) }
        val musicRegex = Regex("\"playUrl\"\\s*:\\s*\"([^\"]+)\"")
        val musicUrl = musicRegex.find(html)?.groupValues?.getOrNull(1)?.let { unescape(it) }
        val descRegex = Regex("\"desc\"\\s*:\\s*\"([^\"]{1,200})\"")
        val descTitle = descRegex.find(html)?.groupValues?.getOrNull(1)?.let { unescape(it) }?.trim()
            ?.takeIf { it.isNotBlank() }
        val titleRegex = Regex("<title>([^<]+)</title>")
        val htmlTitle = titleRegex.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.takeUnless { it.equals("TikTok", ignoreCase = true) || it.startsWith("TikTok - Make Your Day", ignoreCase = true) }
        val title = descTitle ?: htmlTitle ?: "TikTok Video"

        val streams = mutableListOf<MediaStream>()
        if (playUrl != null && playUrl.startsWith("http")) {
            streams += MediaStream(
                id = "tt-page-v",
                url = playUrl,
                type = MediaType.VIDEO,
                container = "mp4",
                label = "High quality (720p HD)",
                height = 720,
                sizeBytes = null,
                isHls = false,
                headers = cdnHeaders
            )
        }
        if (musicUrl != null && musicUrl.startsWith("http")) {
            streams += SocialMediaGalleryParsers.buildTikTokAudioStreamsFromUrl(
                musicUrl = musicUrl,
                musicProbe = null,
                baseHeaders = cdnHeaders,
                idPrefix = "tt-page-a"
            )
        }

        if (streams.isNotEmpty()) {
            return@coroutineScope ResolvedMedia(
                sourceUrl = rawUrl,
                title = title,
                thumbnailUrl = null,
                streams = streams,
                extractor = "TikTok"
            )
        }

        null
    }

    // =========================================================================
    // 4. X / TWITTER (Parallel Race: vxtwitter + fxtwitter + syndication)
    // =========================================================================

    private suspend fun extractTwitter(rawUrl: String): ResolvedMedia? = coroutineScope {
        val m = twitterStatusRegex.find(rawUrl) ?: return@coroutineScope null
        val user = m.groupValues[1]
        val statusId = m.groupValues[2]

        val apis = listOf(
            "https://api.vxtwitter.com/$user/status/$statusId",
            "https://api.fxtwitter.com/$user/status/$statusId",
            "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=a"
        )

        val ch = Channel<SocialMediaGalleryParsers.ParsedPost?>(capacity = apis.size)
        val jobs = apis.map { apiUrl ->
            launch(Dispatchers.IO) {
                val parsed = try {
                    withTimeoutOrNull(SOCIAL_API_TIMEOUT_MS) {
                        withCancellableHttpCalls(timeoutMs = SOCIAL_API_TIMEOUT_MS) {
                            val jsonText = runCatching { http.fetchString(apiUrl) }.getOrNull()
                            if (jsonText != null) SocialMediaGalleryParsers.parseTwitterApiJson(jsonText, user, statusId) else null
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
                ch.trySend(parsed)
            }
        }

        var winningParsed: SocialMediaGalleryParsers.ParsedPost? = null
        try {
            for (i in apis.indices) {
                val candidate = ch.receiveCatching().getOrNull() ?: continue
                if (candidate.slides.isNotEmpty() || candidate.videoStreams.isNotEmpty()) {
                    winningParsed = candidate
                    break
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
            ch.close()
        }

        val parsed = winningParsed ?: return@coroutineScope null
        if (parsed.slides.isNotEmpty()) {
            return@coroutineScope buildGalleryResolvedMedia(rawUrl, "X / Twitter", parsed)
        }

        if (parsed.videoStreams.isNotEmpty()) {
            // Return immediately without blocking on size probes; StreamSizeRefiner refines sizes asynchronously.
            return@coroutineScope ResolvedMedia(
                sourceUrl = rawUrl,
                title = parsed.title ?: "X Video (@$user)",
                thumbnailUrl = parsed.thumbnailUrl,
                streams = parsed.videoStreams,
                extractor = "X / Twitter",
                postId = statusId
            )
        }

        null
    }

    // =========================================================================
    // DASH MANIFEST XML PARSER
    // =========================================================================

    private fun parseDashXml(xml: String): List<MediaStream> {
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return emptyList()

        val list = mutableListOf<MediaStream>()
        val reps = doc.getElementsByTagName("Representation")
        for (i in 0 until reps.length) {
            val rep = reps.item(i) as? Element ?: continue
            val parent = rep.parentNode as? Element
            val mime = rep.getAttribute("mimeType").ifBlank { parent?.getAttribute("mimeType").orEmpty() }.lowercase()
            val codecs = rep.getAttribute("codecs").lowercase()
            val fbQuality = rep.getAttribute("FBQualityLabel").ifBlank { rep.getAttribute("qualityLabel") }
            val baseUrl = firstChildText(rep, "BaseURL") ?: continue

            val isAudio = mime.startsWith("audio") || codecs.contains("mp4a") || MediaSniffer.isAudioTrackUrl(baseUrl, mime)
            val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
            val container = if (isAudio) "m4a" else "mp4"
            val width = rep.getAttribute("width").toIntOrNull()?.takeIf { it > 0 }
            val height = rep.getAttribute("height").toIntOrNull()?.takeIf { it > 0 }
                ?: Regex("(\\d{3,4})p?", RegexOption.IGNORE_CASE).find(fbQuality)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val bandwidth = rep.getAttribute("bandwidth").toIntOrNull()?.takeIf { it > 0 }
            val bitrateKbps = bandwidth?.div(1000)

            val label = when {
                type == MediaType.AUDIO && bitrateKbps != null -> "Audio (${bitrateKbps} kbps)"
                type == MediaType.AUDIO -> "Original Audio (M4A)"
                fbQuality.isNotBlank() -> fbQuality
                height != null && height >= 1080 -> "${height}p FHD"
                height != null && height >= 720 -> "${height}p HD"
                height != null -> "${height}p"
                else -> "Video"
            }

            list += MediaStream(
                id = "dash-$i",
                url = MediaSniffer.cleanMediaUrl(unescape(baseUrl)),
                type = type,
                container = container,
                label = label,
                width = width,
                height = height,
                bitrateKbps = bitrateKbps,
                isHls = false
            )
        }
        return list
    }

    // =========================================================================
    // 5. PINTEREST (pin.it, Single Pins, Carousel Pins, Idea/Story Pins, Pin Videos)
    // =========================================================================

    private suspend fun extractPinterest(rawUrl: String): ResolvedMedia? = coroutineScope {
        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "https://www.pinterest.com/"
        )

        val resolvedUrl = if (rawUrl.contains("pin.it", ignoreCase = true) ||
            pinterestPinIdRegex.find(rawUrl) == null
        ) {
            resolveRedirectUrl(rawUrl)
        } else {
            UrlUtils.cleanSocialUrl(rawUrl)
        }

        val pinId = pinterestPinIdRegex.find(resolvedUrl)?.groupValues?.getOrNull(1)
            ?: pinterestPinIdRegex.find(rawUrl)?.groupValues?.getOrNull(1)
        val canonicalUrl = if (!pinId.isNullOrBlank()) {
            "https://www.pinterest.com/pin/$pinId/"
        } else {
            resolvedUrl
        }

        var bestParsed: SocialMediaGalleryParsers.ParsedPost? = null

        // Tier 1: Fetch canonical Pin HTML and scan ALL Relay SSR / __PWS_DATA__ / JSON blocks
        val html = runCatching { http.fetchString(canonicalUrl, desktopHeaders) }.getOrNull().orEmpty()
        if (html.isNotBlank()) {
            bestParsed = SocialMediaGalleryParsers.preferRicherPinterestPost(
                bestParsed,
                SocialMediaGalleryParsers.parsePinterestHtml(html, pinId, desktopHeaders)
            )
        }

        fun needsMultiSlideUpgrade(): Boolean {
            val cur = bestParsed ?: return true
            return cur.slides.size <= 1 && cur.videoStreams.isEmpty()
        }

        // Tier 2 & 3 in parallel: PinResource/get + v3/pins fallback when HTML yielded <= 1 image.
        // Cancels remaining API calls as soon as a multi-slide or video result is discovered.
        if (needsMultiSlideUpgrade() && !pinId.isNullOrBlank()) {
            val fieldSets = listOf("detailed", "unauth_react_main_pin", "auth_web_main_pin")
            val apiHeaders = desktopHeaders + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-Pinterest-AppState" to "active",
                "X-Pinterest-PWS-Handler" to "www/pin/[id].js",
                "Referer" to canonicalUrl,
                "Accept" to "application/json, text/javascript, */*, q=0.01"
            )
            val v3Headers = desktopHeaders + mapOf(
                "Accept" to "application/json",
                "Referer" to canonicalUrl
            )
            val targets: List<Pair<String, Map<String, String>>> = fieldSets.map { fieldSet ->
                val dataJson = "{\"options\":{\"id\":\"$pinId\",\"field_set_key\":\"$fieldSet\"}}"
                val dataParam = URLEncoder.encode(dataJson, "UTF-8")
                "https://www.pinterest.com/resource/PinResource/get/?source_url=/pin/$pinId/&data=$dataParam" to apiHeaders
            } + ("https://api.pinterest.com/v3/pins/$pinId/?fields=pin.id,pin.title,pin.images,pin.carousel_data,pin.story_pin_data,pin.videos" to v3Headers)

            val pinCh = Channel<SocialMediaGalleryParsers.ParsedPost?>(capacity = targets.size)
            val pinJobs = targets.map { (targetUrl, reqHeaders) ->
                launch(Dispatchers.IO) {
                    val parsed = try {
                        withTimeoutOrNull(SOCIAL_API_TIMEOUT_MS) {
                            withCancellableHttpCalls(timeoutMs = SOCIAL_API_TIMEOUT_MS) {
                                val apiJson = runCatching { http.fetchString(targetUrl, reqHeaders) }.getOrNull()
                                if (!apiJson.isNullOrBlank()) {
                                    SocialMediaGalleryParsers.parsePinterestHtml(apiJson, pinId, desktopHeaders)
                                } else null
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null
                    }
                    pinCh.trySend(parsed)
                }
            }
            try {
                for (i in targets.indices) {
                    val apiParsed = pinCh.receiveCatching().getOrNull()
                    if (apiParsed != null) {
                        bestParsed = SocialMediaGalleryParsers.preferRicherPinterestPost(bestParsed, apiParsed)
                        if (!needsMultiSlideUpgrade()) break
                    }
                }
            } finally {
                pinJobs.forEach { it.cancel() }
                pinCh.close()
            }
        }

        // Tier 4: Strictly scoped Headless WebView fallback when HTTP still yielded <= 1 image
        // and the page either hints at a carousel/story pin or lacked hydrated Relay/PWS JSON.
        if (needsMultiSlideUpgrade() &&
            (bestParsed == null ||
                SocialMediaGalleryParsers.pinterestHtmlHintsMultiSlide(html) ||
                !SocialMediaGalleryParsers.hasStructuredPinterestJson(html))
        ) {
            runCatching {
                val headlessJson = headlessWebExtractor.extractPinterestPinPayload(canonicalUrl)
                if (!headlessJson.isNullOrBlank()) {
                    val headlessParsed = SocialMediaGalleryParsers.parsePinterestHeadlessPayload(
                        payloadJson = headlessJson,
                        pinId = pinId,
                        headers = desktopHeaders
                    )
                    bestParsed = SocialMediaGalleryParsers.preferRicherPinterestPost(bestParsed, headlessParsed)
                }
            }
        }

        val resolved = bestParsed ?: return@coroutineScope null

        // Multi-image Carousel / Idea Pin / Single Image Pin -> Multi-Image Bottom Sheet
        if (resolved.slides.isNotEmpty()) {
            val verifiedSlides = resolved.slides.mapIndexed { idx, slide ->
                async {
                    if (slide.type == MediaType.IMAGE &&
                        slide.mediaUrl.contains("/originals/", ignoreCase = true) &&
                        idx < 24
                    ) {
                        verifyPinterestOriginalSlide(slide, desktopHeaders)
                    } else {
                        slide
                    }
                }
            }.awaitAll()

            return@coroutineScope buildGalleryResolvedMedia(
                rawUrl = rawUrl,
                extractorName = "Pinterest",
                parsed = resolved.copy(slides = verifiedSlides)
            )
        }

        // Single Video Pin -> existing video + synthesized audio flow (zero regression)
        if (resolved.videoStreams.isNotEmpty()) {
            val probedVideos = resolved.videoStreams.map { v ->
                async {
                    val info = runCatching { http.probe(v.url, desktopHeaders) }.getOrNull()
                    if (info != null && info.contentLength > 0) v.copy(sizeBytes = info.contentLength) else v
                }
            }.awaitAll()
            val v = probedVideos.first()
            val vBytes = v.sizeBytes ?: 0L
            val estM4a = if (vBytes > 0L) (vBytes * 16L / 100L).coerceAtLeast(150_000L) else null
            val estMp3 = if (vBytes > 0L) (vBytes * 20L / 100L).coerceAtLeast(180_000L) else null
            val audios = listOf(
                v.copy(
                    id = "pin-audio-fast",
                    type = MediaType.AUDIO,
                    container = "m4a",
                    label = "Fast",
                    sizeBytes = estM4a,
                    headers = v.headers + mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true",
                        "X-Internal-Extract-Audio" to "true",
                        "X-Internal-Video-Bytes" to vBytes.toString()
                    )
                ),
                v.copy(
                    id = "pin-audio-mp3",
                    type = MediaType.AUDIO,
                    container = "mp3",
                    label = "Classic MP3",
                    sizeBytes = estMp3,
                    headers = v.headers + mapOf(
                        "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true",
                        "X-Internal-Extract-Audio" to "true",
                        "X-Internal-Video-Bytes" to vBytes.toString()
                    )
                )
            )
            return@coroutineScope ResolvedMedia(
                sourceUrl = rawUrl,
                title = resolved.title ?: "Pinterest Pin",
                thumbnailUrl = resolved.thumbnailUrl,
                streams = probedVideos + audios,
                extractor = "Pinterest",
                postId = pinId
            )
        }

        null
    }

    private fun verifyPinterestOriginalSlide(
        slide: GallerySlide,
        headers: Map<String, String>
    ): GallerySlide {
        val info = runCatching { http.probe(slide.mediaUrl, headers) }.getOrNull()
        if (info != null && info.code in 200..299) {
            return slide.copy(sizeBytes = info.contentLength.takeIf { it > 0 })
        }
        if (info == null || info.code != 404) {
            return slide
        }
        // Try alternate original extensions (.png, .webp) when an upgraded/signature-derived .jpg 404s
        val baseWithoutExt = slide.mediaUrl.substringBeforeLast('.')
        val altExts = listOf("png", "webp").filterNot { slide.mediaUrl.endsWith(".$it", ignoreCase = true) }
        for (ext in altExts) {
            val candidateUrl = "$baseWithoutExt.$ext"
            val altInfo = runCatching { http.probe(candidateUrl, headers) }.getOrNull()
            if (altInfo != null && altInfo.code in 200..299) {
                return slide.copy(
                    mediaUrl = candidateUrl,
                    container = ext,
                    sizeBytes = altInfo.contentLength.takeIf { it > 0 }
                )
            }
        }
        // Fall back cleanly to 736x or the slide's original thumbnail URL
        val fallback736 = SocialMediaGalleryParsers.pinterest736UrlFromOriginal(slide.mediaUrl)
        if (fallback736 != slide.mediaUrl) {
            val info736 = runCatching { http.probe(fallback736, headers) }.getOrNull()
            if (info736 != null && info736.code in 200..299) {
                return slide.copy(
                    mediaUrl = fallback736,
                    thumbnailUrl = fallback736,
                    sizeBytes = info736.contentLength.takeIf { it > 0 }
                )
            }
        }
        return slide.copy(mediaUrl = slide.thumbnailUrl)
    }

    // =========================================================================
    // 6. THREADS (Single Photos, Multi-Image / Mixed Carousels & Videos)
    // =========================================================================

    private suspend fun extractThreads(rawUrl: String): ResolvedMedia? = coroutineScope {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Mode" to "navigate",
            "Referer" to "https://www.threads.net/"
        )

        val html = runCatching { http.fetchString(rawUrl, headers) }.getOrNull().orEmpty()
        if (html.isBlank()) return@coroutineScope null

        val parsed = SocialMediaGalleryParsers.parseThreadsHtml(html, rawUrl, headers)
            ?: return@coroutineScope null

        if (parsed.slides.isNotEmpty()) {
            return@coroutineScope buildGalleryResolvedMedia(rawUrl, "Threads", parsed)
        }

        if (parsed.videoStreams.isNotEmpty()) {
            return@coroutineScope ResolvedMedia(
                sourceUrl = rawUrl,
                title = parsed.title ?: "Threads Video",
                thumbnailUrl = parsed.thumbnailUrl,
                streams = parsed.videoStreams,
                extractor = "Threads",
                postId = parsed.postId
            )
        }

        null
    }

    // =========================================================================
    // 7. REDDIT (Galleries, Single Images, Crossposts & Reddit Videos)
    // =========================================================================

    private suspend fun extractReddit(rawUrl: String): ResolvedMedia? = coroutineScope {
        val redditHeaders = mapOf(
            "User-Agent" to "android:com.melmeligy.mediadownloader:v2.3 (by /u/mediadownloader)",
            "Accept" to "application/json, text/html;q=0.9"
        )

        val host = UrlUtils.host(rawUrl)?.lowercase().orEmpty()
        if (host == "i.redd.it") {
            val ext = SocialMediaGalleryParsers.inferImageExt(rawUrl)
            val slide = GallerySlide(
                id = "rd-slide-0",
                index = 0,
                mediaUrl = rawUrl,
                thumbnailUrl = rawUrl,
                type = MediaType.IMAGE,
                container = ext,
                headers = redditHeaders,
                postId = UrlUtils.fileNameFromUrl(rawUrl).substringBeforeLast('.')
            )
            return@coroutineScope buildGalleryResolvedMedia(
                rawUrl = rawUrl,
                extractorName = "Reddit",
                parsed = SocialMediaGalleryParsers.ParsedPost(
                    title = "Reddit Image",
                    thumbnailUrl = rawUrl,
                    postId = slide.postId,
                    slides = listOf(slide)
                )
            )
        }

        val baseNoQuery = rawUrl.substringBefore('?').trimEnd('/')
        val jsonUrl = if (baseNoQuery.endsWith(".json", ignoreCase = true)) {
            "$baseNoQuery?raw_json=1"
        } else {
            "$baseNoQuery.json?raw_json=1"
        }

        val jsonText = runCatching { http.fetchString(jsonUrl, redditHeaders) }.getOrNull()
        val parsed = jsonText?.let { SocialMediaGalleryParsers.parseRedditJson(it, redditHeaders) }

        if (parsed != null) {
            if (parsed.slides.isNotEmpty()) {
                return@coroutineScope buildGalleryResolvedMedia(rawUrl, "Reddit", parsed)
            }
            if (parsed.videoStreams.isNotEmpty()) {
                return@coroutineScope ResolvedMedia(
                    sourceUrl = rawUrl,
                    title = parsed.title ?: "Reddit Video",
                    thumbnailUrl = parsed.thumbnailUrl,
                    streams = parsed.videoStreams,
                    extractor = "Reddit",
                    postId = parsed.postId
                )
            }
        }

        // HTML OpenGraph fallback if Reddit JSON endpoint is rate-limited
        val htmlHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        )
        val html = runCatching { http.fetchString(rawUrl, htmlHeaders) }.getOrNull().orEmpty()
        if (html.isNotBlank()) {
            val doc = Jsoup.parse(html, rawUrl)
            val ogImg = doc.select("meta[property=og:image]").attr("content").takeIf { it.startsWith("http") }
            if (ogImg != null && (ogImg.contains("i.redd.it") || ogImg.contains("preview.redd.it"))) {
                val cleanImg = SocialMediaGalleryParsers.unescape(ogImg)
                val postId = UrlUtils.extractPostId(rawUrl)
                val slide = GallerySlide(
                    id = "rd-slide-0",
                    index = 0,
                    mediaUrl = cleanImg,
                    thumbnailUrl = cleanImg,
                    type = MediaType.IMAGE,
                    container = SocialMediaGalleryParsers.inferImageExt(cleanImg),
                    headers = htmlHeaders,
                    postId = postId
                )
                return@coroutineScope buildGalleryResolvedMedia(
                    rawUrl = rawUrl,
                    extractorName = "Reddit",
                    parsed = SocialMediaGalleryParsers.ParsedPost(
                        title = doc.select("meta[property=og:title]").attr("content").ifBlank { "Reddit Post" },
                        thumbnailUrl = cleanImg,
                        postId = postId,
                        slides = listOf(slide)
                    )
                )
            }
        }

        null
    }

    private fun probeSocialStream(candidate: MediaStream, headers: Map<String, String>): MediaStream? {
        val mergedHeaders = if (headers.isEmpty()) candidate.headers else candidate.headers + headers
        val info = try {
            http.probe(candidate.url, mergedHeaders)
        } catch (e: com.melmeligy.mediadownloader.core.MediaException) {
            if (e.error == com.melmeligy.mediadownloader.core.AppError.NO_MEDIA_FOUND ||
                e.error == com.melmeligy.mediadownloader.core.AppError.EXPIRED_LINK ||
                e.error == com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED
            ) {
                return null
            }
            return null
        } catch (_: Exception) {
            return null
        }
        if (info.code !in 200..299) return null
        val ct = info.contentType?.lowercase().orEmpty()
        if (ct.startsWith("text/html") ||
            ct.startsWith("application/xhtml") ||
            ct.startsWith("application/json") ||
            (candidate.type != MediaType.IMAGE && ct.startsWith("image/"))
        ) {
            return null
        }
        val extra = LinkedHashMap<String, String>(candidate.headers).apply {
            if (info.acceptsRanges) put("X-Internal-Accepts-Ranges", "true")
        }
        return candidate.copy(
            url = info.finalUrl.ifBlank { candidate.url },
            sizeBytes = info.contentLength.takeIf { it > 0L } ?: candidate.sizeBytes,
            headers = extra
        )
    }

    private fun firstChildText(parent: Element, tag: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName.equals(tag, ignoreCase = true)) {
                return node.textContent?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun unescape(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003C", "<", ignoreCase = true)
        .replace("\\u003E", ">", ignoreCase = true)
        .replace("\\\"", "\"")
        .replace("&amp;", "&")

    companion object {
        private const val TIKTOK_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val REDIRECT_CACHE_TTL_MS = 120_000L
        private const val MAX_REDIRECT_CACHE_ENTRIES = 128
        private const val REDIRECT_PROBE_TIMEOUT_MS = 2_200L
        private const val REDIRECT_GET_TIMEOUT_MS = 2_800L
        private const val TIKWM_API_TIMEOUT_MS = 2_000L
        private const val TIKWM_COOLDOWN_MS = 60_000L
        private const val TIKTOK_HTML_TIMEOUT_MS = 5_500L
        private const val SOCIAL_API_TIMEOUT_MS = 2_800L
        private const val FAST_PROBE_TIMEOUT_MS = 1_800L
        private const val FAST_AUDIO_PROBE_TIMEOUT_MS = 1_200L
        internal const val IG_WEB_APP_ID = "936619743392459"
        private const val IG_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        private const val IG_MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        internal const val INSTAGRAM_LOGIN_REQUIRED_MESSAGE =
            "Instagram login required or private post — open in browser to sign in"
    }
}
