package com.melmeligy.mediadownloader.extractor

import android.webkit.CookieManager
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.AdBlocker
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
import org.jsoup.nodes.Element
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprehensive web-page media extractor supporting:
 * - Specialized multi-quality player configs (Pornhub `flashvars` + `get_media`, XVideos/XNXX
 *   `html5player`, xHamster `window.initials` with `_TPL_.h264.mp4.m3u8` + MP4 qualities,
 *   Arabsex & KVS `kt_player` with `function/0/` + `license_code` decryption, JWPlayer,
 *   FluidPlayer, Plyr, Video.js).
 * - Clean HTML5 `<video>`/`<audio>`/`<source>`, Schema.org `VideoObject`, OpenGraph video tags,
 *   and 1-hop player `<iframe>` embeds.
 * - Strict filtering of VAST preroll ads, hover previews, related-video thumbs, and site bumpers.
 */
@Singleton
class HtmlMediaExtractor @Inject constructor(
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val dashExtractor: DashExtractor,
    private val headlessWebExtractor: HeadlessWebExtractor,
    private val progressTracker: ExtractionProgressTracker
) : MediaExtractor {

    constructor(
        http: ExtractorHttp,
        hlsExtractor: HlsExtractor,
        dashExtractor: DashExtractor,
        headlessWebExtractor: HeadlessWebExtractor
    ) : this(http, hlsExtractor, dashExtractor, headlessWebExtractor, ExtractionProgressTracker())

    private val portalResolver = StreamingPortalResolver(
        http = http,
        hlsExtractor = hlsExtractor,
        dashExtractor = dashExtractor,
        progressTracker = progressTracker
    )

    override val name = "Web page"
    override val priority = 50

    private val m3u8Regex = Regex("https?://[^\"'\\s<>\\\\]+?\\.m3u8[^\"'\\s<>\\\\]*")
    private val mpdRegex = Regex("https?://[^\"'\\s<>\\\\]+?\\.mpd[^\"'\\s<>\\\\]*")
    private val directRegex =
        Regex("https?://[^\"'\\s<>\\\\]+?\\.(mp4|m4a|mp3|webm|aac|ogg|mov)([^\"'\\s<>\\\\]*)")

    private val relatedNoiseRegex = Regex(
        """(?:video_related\s*=\s*\[[\s\S]*?\];|"relatedVideos"\s*:\s*\[[\s\S]*?\]|"related_videos"\s*:\s*\[[\s\S]*?\]|vastOptions\s*:\s*\{[\s\S]*?\})""",
        RegexOption.IGNORE_CASE
    )

    private val xvTitleRegex = Regex("""html5player\.setVideoTitle\s*\(\s*(['"])(.*?)\1\s*\)""")
    private val xvThumb169Regex = Regex("""html5player\.setThumbUrl169\s*\(\s*(['"])(.*?)\1\s*\)""")
    private val xvThumbRegex = Regex("""html5player\.setThumbUrl\s*\(\s*(['"])(.*?)\1\s*\)""")
    private val xvHlsRegex = Regex("""html5player\.setVideoHLS\s*\(\s*(['"])(.*?)\1\s*\)""")
    private val xvHighRegex = Regex("""html5player\.setVideoUrlHigh\s*\(\s*(['"])(.*?)\1\s*\)""")
    private val xvLowRegex = Regex("""html5player\.setVideoUrlLow\s*\(\s*(['"])(.*?)\1\s*\)""")

    private val kvsLicenseRegex = Regex("""license_code\s*:\s*['"]([^'"]+)['"]""")
    private val kvsUrlFieldRegex = Regex(
        """(video_url|video_alt_url\d*)\s*:\s*['"]((?:function/0/)?https?://[^'"]+)['"]"""
    )
    private val kvsTextFieldRegex = Regex(
        """(video_url_text|video_alt_url\d*_text)\s*:\s*['"]([^'"]+)['"]"""
    )
    private val kvsRedirectFieldRegex = Regex(
        """(video_url|video_alt_url\d*)_redirect\s*:\s*['"]?(1|true)['"]?""",
        RegexOption.IGNORE_CASE
    )

    private val playerSourceObjRegex = Regex(
        """\{[^{}]{0,420}?(?:file|src|"file"|"src")\s*:\s*['"](https?://[^'"]+)['"][^{}]{0,420}?\}""",
        RegexOption.IGNORE_CASE
    )
    private val playerLabelAttrRegex = Regex(
        """(?:label|res|size|title|quality|"label"|"res"|"size"|"quality")\s*:\s*['"]?([0-9]{3,4}p?|4k|2k|hd|sd)['"]?""",
        RegexOption.IGNORE_CASE
    )

    override fun canHandle(url: String): Boolean =
        url.startsWith("http") && !UrlUtils.isDedicatedSocialPlatformUrl(url)

    override suspend fun extract(url: String): ResolvedMedia? =
        extractWithHeaders(url, emptyMap(), allowHeadlessFallback = true)

    internal var cookieProvider: (String) -> String? = { target ->
        runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull()
    }
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    suspend fun extractWithHeaders(
        url: String,
        customHeaders: Map<String, String> = emptyMap(),
        allowHeadlessFallback: Boolean = true
    ): ResolvedMedia? = withContext(Dispatchers.IO) {
        progressTracker.update(ExtractionProgressTracker.STAGE_FETCHING)
        val host = UrlUtils.host(url)?.lowercase().orEmpty()
        com.melmeligy.mediadownloader.core.AppLogger.d("HtmlExtractor", "Stage 1 fetch start host=$host")
        val pageHeaders = buildPageHeaders(url, customHeaders)
        val hasCfClearance = pageHeaders["Cookie"].orEmpty().contains("cf_clearance=", ignoreCase = true)
        if (hasCfClearance && host.isNotEmpty()) {
            clearCloudflareChallengeHint(host)
        }

        // Short-TTL Cloudflare fast-skip: if this host just returned HTTP 403 (cf-mitigated) and
        // CookieManager has no cf_clearance yet, jump straight to HeadlessWebExtractor instead of
        // wasting time on another blocked plain-HTTP fetch.
        if (allowHeadlessFallback && host.isNotEmpty() && !hasCfClearance && isCloudflareChallengeHintActive(host)) {
            com.melmeligy.mediadownloader.core.AppLogger.d(
                "HtmlExtractor",
                "Skipping blocked static fetch for host=$host (active CF challenge hint, no cf_clearance)"
            )
            progressTracker.update(ExtractionProgressTracker.STAGE_HEADLESS)
            val headlessResult = try {
                headlessWebExtractor.extract(url)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (cookieProvider(url).orEmpty().contains("cf_clearance=", ignoreCase = true)) {
                clearCloudflareChallengeHint(host)
            }
            if (headlessResult == null && progressTracker.peekDiagnosticReason().isNullOrBlank()) {
                progressTracker.setDiagnosticReason(
                    "Site blocked direct access (Cloudflare / HTTP 403) — open in browser to load the player."
                )
            }
            return@withContext headlessResult
        }

        var staticFetchFailureNote: String? = null
        val html = try {
            withTimeoutOrNull(INITIAL_PAGE_TIMEOUT_MS) {
                withCancellableHttpCalls(timeoutMs = INITIAL_PAGE_TIMEOUT_MS) {
                    http.fetchString(url, pageHeaders)
                }
            }.orEmpty()
        } catch (ce: CancellationException) {
            throw ce
        } catch (me: com.melmeligy.mediadownloader.core.MediaException) {
            if (me.error == com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED && host.isNotEmpty() && !hasCfClearance) {
                markCloudflareChallengeHint(host)
            }
            staticFetchFailureNote = when (me.error) {
                com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED ->
                    "Site blocked direct access (Cloudflare / HTTP 403) — open in browser to load the player."
                com.melmeligy.mediadownloader.core.AppError.SERVER_ERROR ->
                    "Site returned a temporary server error — try again or open in browser."
                else -> me.message?.takeIf { it.isNotBlank() && it != me.error.name }
            }
            ""
        } catch (_: Exception) {
            ""
        }
        if (html.isBlank()) {
            com.melmeligy.mediadownloader.core.AppLogger.d(
                "HtmlExtractor",
                "Stage 1 static fetch empty host=$host headlessFallback=$allowHeadlessFallback note=$staticFetchFailureNote"
            )
            if (!allowHeadlessFallback) {
                staticFetchFailureNote?.let { progressTracker.setDiagnosticReason(it) }
                return@withContext null
            }
            progressTracker.update(ExtractionProgressTracker.STAGE_HEADLESS)
            val headlessResult = try {
                headlessWebExtractor.extract(url)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (host.isNotEmpty() && cookieProvider(url).orEmpty().contains("cf_clearance=", ignoreCase = true)) {
                clearCloudflareChallengeHint(host)
            }
            if (headlessResult == null && staticFetchFailureNote != null) {
                val existingDiag = progressTracker.peekDiagnosticReason()
                if (existingDiag.isNullOrBlank() || existingDiag.startsWith("Page loaded without")) {
                    progressTracker.setDiagnosticReason(staticFetchFailureNote)
                }
            }
            return@withContext headlessResult
        }

        val parsed = parseHtml(
            pageUrl = url,
            html = html,
            baseHeaders = pageHeaders,
            allowEmbedFollow = true
        )
        val hasVideoOrAudio = parsed?.streams?.any {
            it.type == MediaType.VIDEO || it.type == MediaType.AUDIO
        } == true
        com.melmeligy.mediadownloader.core.AppLogger.d(
            "HtmlExtractor",
            "Stage 1 static parse host=${UrlUtils.host(url)} streams=${parsed?.streams?.size ?: 0} hasVideoOrAudio=$hasVideoOrAudio"
        )
        if (parsed != null && hasVideoOrAudio) {
            return@withContext parsed
        }
        if (allowHeadlessFallback && (parsed == null || looksLikeStreamingOrEmbedPage(html))) {
            progressTracker.update(ExtractionProgressTracker.STAGE_HEADLESS)
            com.melmeligy.mediadownloader.core.AppLogger.d("HtmlExtractor", "Stage 2 headless fallback start host=${UrlUtils.host(url)}")
            val fallbackDoc = runCatching { Jsoup.parse(html, url) }.getOrNull()
            val fallbackTitle = parsed?.title?.takeIf {
                !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, url)
            } ?: fallbackDoc?.let { HtmlThumbnailExtractor.extractTitle(it, url) }
            val fallbackThumb = parsed?.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: fallbackDoc?.let { HtmlThumbnailExtractor.extractThumbnail(it, url, html) }
            HeadlessWebExtractor.hintPageMetadata(url, fallbackTitle, fallbackThumb)
            val headless = try {
                headlessWebExtractor.extract(url)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (headless != null && headless.streams.isNotEmpty()) {
                val mergedTitle = if (
                    HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(headless.title, url) &&
                    !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(fallbackTitle, url)
                ) {
                    fallbackTitle!!
                } else {
                    headless.title
                }
                val mergedThumb = headless.thumbnailUrl?.takeIf { it.isNotBlank() } ?: fallbackThumb
                com.melmeligy.mediadownloader.core.AppLogger.d(
                    "HtmlExtractor",
                    "Stage 2 headless succeeded host=${UrlUtils.host(url)} streams=${headless.streams.size}"
                )
                return@withContext headless.copy(
                    title = mergedTitle,
                    thumbnailUrl = mergedThumb
                )
            }
            com.melmeligy.mediadownloader.core.AppLogger.w(
                "HtmlExtractor",
                "Stage 2 headless returned no playable streams host=${UrlUtils.host(url)} reason=${progressTracker.peekDiagnosticReason()}"
            )
        }
        parsed?.takeIf { it.streams.isNotEmpty() }
    }

    internal suspend fun extractPageMetadataOnly(
        url: String,
        customHeaders: Map<String, String> = emptyMap()
    ): Pair<String?, String?> = coroutineScope {
        val pageHeaders = buildPageHeaders(url, customHeaders)
        val html = try {
            http.fetchString(url, pageHeaders)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            ""
        }
        if (html.isBlank()) {
            return@coroutineScope Pair(HtmlThumbnailExtractor.humanReadableTitleFromUrl(url), null)
        }
        val doc = runCatching { Jsoup.parse(html, url) }.getOrNull()
            ?: return@coroutineScope Pair(HtmlThumbnailExtractor.humanReadableTitleFromUrl(url), null)
        val title = HtmlThumbnailExtractor.extractTitle(doc, url)
        val thumb = HtmlThumbnailExtractor.extractThumbnail(doc, url, html)
        Pair(title, thumb)
    }

    private fun looksLikeStreamingOrEmbedPage(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("<iframe") ||
            lower.contains("<video") ||
            lower.contains("data-server") ||
            lower.contains("data-embed") ||
            lower.contains("data-post") ||
            lower.contains("/watch") ||
            lower.contains("jwplayer") ||
            lower.contains("playerjs") ||
            lower.contains("cloudflare") ||
            lower.contains("cf-browser-verification")
    }

    internal suspend fun parseHtml(
        pageUrl: String,
        html: String,
        baseHeaders: Map<String, String> = mapOf("Referer" to pageUrl),
        allowEmbedFollow: Boolean = false
    ): ResolvedMedia? = coroutineScope {
        if (html.isBlank()) return@coroutineScope null
        val doc = Jsoup.parse(html, pageUrl)
        val found = LinkedHashMap<String, MediaStream>()
        var counter = 0
        var customTitle: String? = null
        var customThumb: String? = null
        var pageDurationSec: Long = 0L
        var authoritativeFound = false

        fun effectiveHeaders(): Map<String, String> =
            if (pageDurationSec > 0L) baseHeaders + ("X-Internal-Duration-Seconds" to pageDurationSec.toString())
            else baseHeaders

        fun addStream(
            rawUrl: String,
            type: MediaType,
            container: String,
            label: String,
            width: Int? = null,
            height: Int? = null,
            bitrateKbps: Int? = null,
            sizeBytes: Long? = null,
            isHls: Boolean = false,
            authoritative: Boolean = false,
            extraHeaders: Map<String, String> = emptyMap()
        ) {
            val trimmedRaw = unescapeUrl(rawUrl.trim())
            if (trimmedRaw.contains("THUMBNUM", ignoreCase = true)) return
            if (type != MediaType.IMAGE && MediaSniffer.isHexCipherUrl(trimmedRaw)) return
            val resolved = UrlUtils.resolveUrl(pageUrl, trimmedRaw)
            val cleaned = MediaSniffer.cleanMediaUrl(resolved)
            if (!cleaned.startsWith("http")) return
            if (type != MediaType.IMAGE && (MediaSniffer.isHexCipherUrl(cleaned) || MediaSniffer.isPreviewOrAdUrl(cleaned))) return

            val key = MediaSniffer.dedupeKey(cleaned)
            val inferredHeight = height?.takeIf { it > 0 } ?: UrlUtils.inferResolution(cleaned, label)?.takeIf { it > 0 }
            val finalLabel = when {
                type == MediaType.IMAGE -> if (label.isNotBlank() && label != "Video") label else "Photo (HD)"
                label != "Video" && label.isNotBlank() -> label
                inferredHeight != null && inferredHeight >= 1080 -> "${inferredHeight}p FHD"
                inferredHeight != null && inferredHeight >= 720 -> "${inferredHeight}p HD"
                inferredHeight != null -> "${inferredHeight}p"
                else -> label
            }

            val mergedHeaders = LinkedHashMap<String, String>().apply {
                putAll(effectiveHeaders())
                putAll(extraHeaders)
                if (authoritative) put("X-Internal-Authoritative", "true")
            }

            val existing = found[key]
            if (existing != null) {
                if ((existing.height == null && inferredHeight != null) ||
                    (existing.sizeBytes == null && sizeBytes != null)
                ) {
                    found[key] = existing.copy(
                        height = inferredHeight ?: existing.height,
                        sizeBytes = sizeBytes ?: existing.sizeBytes,
                        headers = mergedHeaders
                    )
                }
                return
            }

            if (authoritative) authoritativeFound = true
            found[key] = MediaStream(
                id = "html-${counter++}",
                url = cleaned,
                type = type,
                container = container,
                label = finalLabel,
                width = width?.takeIf { it > 0 },
                height = inferredHeight,
                bitrateKbps = bitrateKbps,
                sizeBytes = sizeBytes,
                isHls = isHls,
                headers = mergedHeaders
            )
        }

        suspend fun addExpandedHls(rawHlsUrl: String, heightHint: Int? = null, authoritative: Boolean = false) {
            val trimmedRaw = unescapeUrl(rawHlsUrl.trim())
            if (MediaSniffer.isHexCipherUrl(trimmedRaw)) return
            val cleaned = MediaSniffer.cleanMediaUrl(UrlUtils.resolveUrl(pageUrl, trimmedRaw))
            if (!cleaned.startsWith("http") || MediaSniffer.isHexCipherUrl(cleaned) || MediaSniffer.isPreviewOrAdUrl(cleaned)) return
            val sanitizedHint = heightHint?.takeIf { it > 0 }
            val expanded = runCatching { hlsExtractor.extract(cleaned, effectiveHeaders(), sanitizedHint) }.getOrNull()
            if (expanded != null && expanded.streams.isNotEmpty()) {
                if (authoritative) authoritativeFound = true
                expanded.streams.forEach { variant ->
                    val k = MediaSniffer.dedupeKey(variant.url)
                    if (!found.containsKey(k)) {
                        val merged = LinkedHashMap<String, String>().apply {
                            putAll(effectiveHeaders())
                            putAll(variant.headers)
                            if (authoritative) put("X-Internal-Authoritative", "true")
                        }
                        found[k] = variant.copy(id = "html-${counter++}", headers = merged)
                    }
                }
            } else {
                addStream(
                    rawUrl = cleaned,
                    type = MediaType.VIDEO,
                    container = "ts",
                    label = if (sanitizedHint != null) "${sanitizedHint}p" else "HLS (auto)",
                    height = sanitizedHint,
                    isHls = true,
                    authoritative = authoritative
                )
            }
        }

        // Strip related-video arrays & VAST ad blocks before scanning inline scripts
        val cleanedScripts = relatedNoiseRegex.replace(html, " ")

        // ---- 1. JSON-LD Schema.org VideoObject / AudioObject metadata (unwrapping nested graphs up to depth 5) ----
        var jsonLdTitle: String? = null
        var jsonLdThumb: String? = null
        doc.select("script[type=application/ld+json]").forEach { script ->
            runCatching {
                val rawJson = script.data().trim()
                val objects = mutableListOf<JSONObject>()
                if (rawJson.startsWith("[")) {
                    val arr = JSONArray(rawJson)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { collectJsonLdObjects(it, 0, objects) }
                    }
                } else if (rawJson.startsWith("{")) {
                    collectJsonLdObjects(JSONObject(rawJson), 0, objects)
                }
                for (obj in objects) {
                    if (jsonLdTitle == null) jsonLdTitle = obj.optString("name").takeIf { it.isNotBlank() }
                    if (jsonLdThumb == null) {
                        val thumbVal = obj.opt("thumbnailUrl")
                        jsonLdThumb = when (thumbVal) {
                            is String -> thumbVal.takeIf { it.startsWith("http") }
                            is JSONArray -> thumbVal.optString(0).takeIf { it.startsWith("http") }
                            else -> null
                        }
                    }
                    parseIsoDurationSeconds(obj.optString("duration"))?.let {
                        if (it > pageDurationSec) pageDurationSec = it
                    }
                    val contentUrl = obj.optString("contentUrl").takeIf { it.startsWith("http") }
                    val encoding = obj.optString("encodingFormat").lowercase()
                    val width = obj.optInt("width", 0).takeIf { it > 0 }
                    val height = obj.optInt("height", 0).takeIf { it > 0 }
                    if (contentUrl != null && !MediaSniffer.isPreviewOrAdUrl(contentUrl)) {
                        when {
                            contentUrl.contains(".m3u8") || encoding.contains("mpegurl") ->
                                addExpandedHls(contentUrl, height, authoritative = true)
                            encoding.startsWith("audio/") ->
                                addStream(contentUrl, MediaType.AUDIO, containerFor(contentUrl, "m4a"), "Original Audio", authoritative = true)
                            else ->
                                addStream(contentUrl, MediaType.VIDEO, containerFor(contentUrl, "mp4"), "Video", width, height, authoritative = true)
                        }
                    }
                }
            }
        }

        // ---- 2. Pornhub (`var flashvars_<id> = {...}` + `get_media` remote MP4/HLS) ----
        extractBalancedJsonObjects(cleanedScripts, Regex("""var\s+flashvars(?:_\d+)?\s*=\s*\{""")).forEach { jsonStr ->
            runCatching {
                val fv = JSONObject(jsonStr)
                fv.optString("video_title").takeIf { it.isNotBlank() }?.let { customTitle = it }
                fv.optString("image_url").takeIf { it.startsWith("http") }?.let { customThumb = it }
                fv.optLong("video_duration", 0L).takeIf { it > 0L }?.let { pageDurationSec = it }

                val defs = fv.optJSONArray("mediaDefinitions")
                if (defs != null) {
                    val remoteEndpoints = mutableListOf<String>()
                    val pendingHls = mutableListOf<Pair<String, Int?>>()
                    for (i in 0 until defs.length()) {
                        val md = defs.optJSONObject(i) ?: continue
                        val vUrl = unescapeUrl(md.optString("videoUrl").trim())
                        if (!vUrl.startsWith("http")) continue
                        val isRemote = md.optBoolean("remote", false) ||
                            vUrl.contains("get_media") ||
                            vUrl.contains("/media/mp4") ||
                            vUrl.contains("/media/hls")
                        if (isRemote) {
                            remoteEndpoints += vUrl
                        } else {
                            val qHint = extractMediaDefQuality(md)
                            val fmt = md.optString("format").lowercase()
                            if (fmt == "hls" || vUrl.contains(".m3u8", ignoreCase = true)) {
                                pendingHls += (vUrl to qHint)
                            } else {
                                addStream(
                                    rawUrl = vUrl,
                                    type = MediaType.VIDEO,
                                    container = containerFor(vUrl, "mp4"),
                                    label = if (qHint != null) "${qHint}p" else "Video",
                                    height = qHint,
                                    authoritative = true
                                )
                            }
                        }
                    }

                    // Expand per-quality HLS endpoints in parallel instead of sequentially
                    if (pendingHls.isNotEmpty()) {
                        val expandedAll = pendingHls.distinctBy { it.first }.map { (hlsUrl, qHint) ->
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                val cleanedHls = MediaSniffer.cleanMediaUrl(UrlUtils.resolveUrl(pageUrl, hlsUrl))
                                if (!cleanedHls.startsWith("http") || MediaSniffer.isPreviewOrAdUrl(cleanedHls)) {
                                    Triple(cleanedHls, qHint, null)
                                } else {
                                    val res = runCatching {
                                        hlsExtractor.extract(cleanedHls, effectiveHeaders(), qHint?.takeIf { it > 0 })
                                    }.getOrNull()
                                    Triple(cleanedHls, qHint, res)
                                }
                            }
                        }.awaitAll()
                        for ((cleanedHls, qHint, expanded) in expandedAll) {
                            if (!cleanedHls.startsWith("http") || MediaSniffer.isPreviewOrAdUrl(cleanedHls)) continue
                            if (expanded != null && expanded.streams.isNotEmpty()) {
                                authoritativeFound = true
                                expanded.streams.forEach { variant ->
                                    val k = MediaSniffer.dedupeKey(variant.url)
                                    if (!found.containsKey(k)) {
                                        val merged = LinkedHashMap<String, String>().apply {
                                            putAll(effectiveHeaders())
                                            putAll(variant.headers)
                                            put("X-Internal-Authoritative", "true")
                                        }
                                        found[k] = variant.copy(id = "html-${counter++}", headers = merged)
                                    }
                                }
                            } else {
                                addStream(
                                    rawUrl = cleanedHls,
                                    type = MediaType.VIDEO,
                                    container = "ts",
                                    label = if (qHint != null) "${qHint}p" else "HLS (auto)",
                                    height = qHint,
                                    isHls = true,
                                    authoritative = true
                                )
                            }
                        }
                    }

                    // Resolve remote get_media JSON endpoints in parallel for direct MP4/HLS qualities
                    val remoteResults = remoteEndpoints.distinct().take(2).map { endpoint ->
                        async(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { http.fetchString(endpoint, effectiveHeaders()) }.getOrNull()
                        }
                    }.awaitAll()

                    val remoteHls = mutableListOf<Pair<String, Int?>>()
                    for (remoteJson in remoteResults) {
                        if (!remoteJson.isNullOrBlank() && remoteJson.trimStart().startsWith("[")) {
                            runCatching {
                                val arr = JSONArray(remoteJson.trim())
                                for (j in 0 until arr.length()) {
                                    val item = arr.optJSONObject(j) ?: continue
                                    if (item.optBoolean("remote", false)) continue
                                    val rUrl = unescapeUrl(item.optString("videoUrl").trim())
                                    if (!rUrl.startsWith("http")) continue
                                    val qHint = extractMediaDefQuality(item)
                                    val fmt = item.optString("format").lowercase()
                                    if (fmt == "hls" || rUrl.contains(".m3u8", ignoreCase = true)) {
                                        remoteHls += (rUrl to qHint)
                                    } else {
                                        addStream(
                                            rawUrl = rUrl,
                                            type = MediaType.VIDEO,
                                            container = containerFor(rUrl, "mp4"),
                                            label = if (qHint != null) "${qHint}p" else "Video",
                                            height = qHint,
                                            authoritative = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (remoteHls.isNotEmpty()) {
                        val expandedRemote = remoteHls.distinctBy { it.first }.map { (hlsUrl, qHint) ->
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                val cleanedHls = MediaSniffer.cleanMediaUrl(UrlUtils.resolveUrl(pageUrl, hlsUrl))
                                Triple(
                                    cleanedHls,
                                    qHint,
                                    runCatching { hlsExtractor.extract(cleanedHls, effectiveHeaders(), qHint?.takeIf { it > 0 }) }.getOrNull()
                                )
                            }
                        }.awaitAll()
                        for ((cleanedHls, qHint, expanded) in expandedRemote) {
                            if (!cleanedHls.startsWith("http") || MediaSniffer.isPreviewOrAdUrl(cleanedHls)) continue
                            if (expanded != null && expanded.streams.isNotEmpty()) {
                                authoritativeFound = true
                                expanded.streams.forEach { variant ->
                                    val k = MediaSniffer.dedupeKey(variant.url)
                                    if (!found.containsKey(k)) {
                                        val merged = LinkedHashMap<String, String>().apply {
                                            putAll(effectiveHeaders())
                                            putAll(variant.headers)
                                            put("X-Internal-Authoritative", "true")
                                        }
                                        found[k] = variant.copy(id = "html-${counter++}", headers = merged)
                                    }
                                }
                            } else {
                                addStream(cleanedHls, MediaType.VIDEO, "ts", if (qHint != null) "${qHint}p" else "HLS (auto)", height = qHint, isHls = true, authoritative = true)
                            }
                        }
                    }
                }
            }
        }

        // ---- 3. XVideos & XNXX (`html5player.setVideoHLS / setVideoUrlHigh / setVideoUrlLow`) ----
        xvTitleRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
            if (customTitle == null) customTitle = unescapeUrl(it)
        }
        (xvThumb169Regex.find(cleanedScripts) ?: xvThumbRegex.find(cleanedScripts))
            ?.groupValues?.getOrNull(2)?.takeIf { it.startsWith("http") && !it.contains("THUMBNUM", ignoreCase = true) }?.let {
                if (customThumb == null) customThumb = unescapeUrl(it)
            }
        xvHlsRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { hlsUrl ->
            addExpandedHls(hlsUrl, authoritative = true)
        }
        val xvHighUrl = xvHighRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        val xvLowUrl = xvLowRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        xvHighUrl?.let { highUrl ->
            val q = UrlUtils.inferResolution(highUrl) ?: 480
            addStream(highUrl, MediaType.VIDEO, containerFor(highUrl, "mp4"), "${q}p", height = q, authoritative = true)
        }
        xvLowUrl?.let { lowUrl ->
            if (xvHighUrl == null || MediaSniffer.dedupeKey(lowUrl) != MediaSniffer.dedupeKey(xvHighUrl)) {
                val q = UrlUtils.inferResolution(lowUrl) ?: 240
                addStream(lowUrl, MediaType.VIDEO, containerFor(lowUrl, "mp4"), "${q}p", height = q, authoritative = true)
            }
        }

        // ---- 4. xHamster (`window.initials = {...}` with 7-PRNG hex cipher & hostMap + preload H.264 upgrade) ----
        extractBalancedJsonObjects(html, Regex("""window\.initials\s*=\s*\{""")).forEach { jsonStr ->
            runCatching {
                val ini = JSONObject(jsonStr)
                val ve = ini.optJSONObject("videoEntity") ?: ini.optJSONObject("videoModel")
                if (ve != null) {
                    ve.optString("title").takeIf { it.isNotBlank() }?.let { customTitle = it }
                    (ve.optString("thumbBig").ifBlank { ve.optString("thumbURL") })
                        .takeIf { it.startsWith("http") }?.let { customThumb = it }
                    ve.optLong("duration", 0L).takeIf { it > 0L }?.let { pageDurationSec = it }
                }

                val xps = ini.optJSONObject("xplayerSettings")
                val hostMap = xps?.optJSONObject("hostMap") ?: ini.optJSONObject("hostMap")
                val sources = xps?.optJSONObject("sources") ?: ve?.optJSONObject("sources")

                fun decodeXhamsterUrl(raw: String?, mapKey: String? = null): String? {
                    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                    val decrypted = XhamsterCipher.decryptSourceUrl(unescapeUrl(s))
                    val mapped = XhamsterCipher.applyHostMap(decrypted, hostMap, mapKey)
                    val resolved = if (mapped.startsWith("//")) "https:$mapped" else mapped
                    return resolved.takeIf { it.startsWith("http") && !MediaSniffer.isHexCipherUrl(it) }
                }

                if (sources != null) {
                    val hlsObj = sources.optJSONObject("hls")
                    if (hlsObj != null) {
                        // Prefer h264 -> mp4 -> other non-av1 -> av1
                        val hlsKeys = buildList {
                            if (hlsObj.has("h264")) add("h264")
                            if (hlsObj.has("mp4")) add("mp4")
                            hlsObj.keys().forEach { k ->
                                if (k != "h264" && k != "mp4" && !k.contains("av1", ignoreCase = true)) add(k)
                            }
                            hlsObj.keys().forEach { k ->
                                if (k.contains("av1", ignoreCase = true)) add(k)
                            }
                        }
                        var addedHls = false
                        for (k in hlsKeys) {
                            val entry = hlsObj.opt(k)
                            val rawUrl = when (entry) {
                                is JSONObject -> entry.optString("url").ifBlank { entry.optString("fallback") }
                                is String -> entry
                                else -> ""
                            }
                            val hostKey = if (entry is JSONObject) entry.optString("host").ifBlank { k } else k
                            val decoded = decodeXhamsterUrl(rawUrl, hostKey) ?: decodeXhamsterUrl(rawUrl, "hls")
                            if (decoded != null) {
                                addExpandedHls(decoded, authoritative = true)
                                addedHls = true
                                if (!k.contains("av1", ignoreCase = true)) break
                            }
                        }
                    }

                    val stdObj = sources.optJSONObject("standard")
                    if (stdObj != null) {
                        val stdKeys = buildList {
                            if (stdObj.has("h264")) add("h264")
                            if (stdObj.has("mp4")) add("mp4")
                            stdObj.keys().forEach { k ->
                                if (k != "h264" && k != "mp4" && !k.contains("av1", ignoreCase = true)) add(k)
                            }
                            stdObj.keys().forEach { k ->
                                if (k.contains("av1", ignoreCase = true)) add(k)
                            }
                        }
                        for (k in stdKeys) {
                            val stdVal = stdObj.opt(k)
                            var handledNonEmpty = false
                            if (stdVal is JSONArray) {
                                for (i in 0 until stdVal.length()) {
                                    val item = stdVal.optJSONObject(i) ?: continue
                                    val rawPrimary = item.optString("url").ifBlank { item.optString("fallback") }
                                    val hostKey = item.optString("host").ifBlank { item.optString("quality").ifBlank { k } }
                                    val decoded = decodeXhamsterUrl(rawPrimary, hostKey) ?: decodeXhamsterUrl(rawPrimary, k)
                                    if (decoded != null) {
                                        handledNonEmpty = true
                                        val qLabel = item.optString("quality").ifBlank { item.optString("label") }
                                        val q = UrlUtils.inferResolution(decoded, qLabel)
                                        if (decoded.contains(".m3u8", ignoreCase = true) || qLabel.equals("auto", ignoreCase = true)) {
                                            addExpandedHls(decoded, q, authoritative = true)
                                        } else {
                                            addStream(
                                                rawUrl = decoded,
                                                type = MediaType.VIDEO,
                                                container = containerFor(decoded, "mp4"),
                                                label = qLabel.ifBlank { if (q != null) "${q}p" else "Video" },
                                                height = q,
                                                authoritative = true
                                            )
                                        }
                                    }
                                }
                            } else if (stdVal is JSONObject) {
                                val keysIter = stdVal.keys()
                                while (keysIter.hasNext()) {
                                    val qKey = keysIter.next()
                                    val qEntry = stdVal.opt(qKey)
                                    val rawPrimary = when (qEntry) {
                                        is String -> qEntry
                                        is JSONObject -> qEntry.optString("url").ifBlank { qEntry.optString("fallback") }
                                        else -> ""
                                    }
                                    val decoded = decodeXhamsterUrl(rawPrimary, qKey) ?: decodeXhamsterUrl(rawPrimary, k)
                                    if (decoded != null) {
                                        handledNonEmpty = true
                                        val q = UrlUtils.inferResolution(decoded, qKey)
                                        if (decoded.contains(".m3u8", ignoreCase = true) || qKey.equals("auto", ignoreCase = true)) {
                                            addExpandedHls(decoded, q, authoritative = true)
                                        } else {
                                            addStream(
                                                rawUrl = decoded,
                                                type = MediaType.VIDEO,
                                                container = containerFor(decoded, "mp4"),
                                                label = if (q != null) "${q}p" else qKey,
                                                height = q,
                                                authoritative = true
                                            )
                                        }
                                    }
                                }
                            }
                            if (handledNonEmpty && !k.contains("av1", ignoreCase = true)) break
                        }
                    }
                }
            }
        }
        doc.select("link[rel=preload][href*=.m3u8]").forEach { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.startsWith("http") && !authoritativeFound) {
                if (href.contains(".av1.", ignoreCase = true) || href.contains("_av1", ignoreCase = true)) {
                    val h264Candidate = href
                        .replace(".av1.", ".h264.", ignoreCase = true)
                        .replace("_av1", "_h264", ignoreCase = true)
                    val upgraded = runCatching { hlsExtractor.extract(h264Candidate, effectiveHeaders()) }.getOrNull()
                    if (upgraded != null && upgraded.streams.isNotEmpty()) {
                        addExpandedHls(h264Candidate, authoritative = true)
                    } else {
                        addExpandedHls(href, authoritative = true)
                    }
                } else {
                    addExpandedHls(href, authoritative = true)
                }
            }
        }

        // ---- 5. Arabsex & KVS (`kt_player` `function/0/...` + `license_code`) ----
        val kvsLicense = kvsLicenseRegex.find(cleanedScripts)?.groupValues?.getOrNull(1)
        val kvsLabels = HashMap<String, String>()
        kvsTextFieldRegex.findAll(cleanedScripts).forEach { m ->
            kvsLabels[m.groupValues[1]] = m.groupValues[2]
        }
        val kvsRedirects = HashSet<String>()
        kvsRedirectFieldRegex.findAll(cleanedScripts).forEach { m ->
            kvsRedirects.add(m.groupValues[1])
        }
        kvsUrlFieldRegex.findAll(cleanedScripts).forEach { m ->
            val fieldName = m.groupValues[1]
            if (fieldName in kvsRedirects) return@forEach
            val rawKvsUrl = m.groupValues[2]
            val decrypted = decryptKvsUrl(unescapeUrl(rawKvsUrl), kvsLicense)
            val labelText = kvsLabels["${fieldName}_text"].orEmpty()
            val q = inferKvsResolution(decrypted, labelText, fieldName)
            if (decrypted.contains(".m3u8", ignoreCase = true)) {
                addExpandedHls(decrypted, q, authoritative = true)
            } else {
                addStream(
                    rawUrl = decrypted,
                    type = MediaType.VIDEO,
                    container = containerFor(decrypted, "mp4"),
                    label = labelText.ifBlank { if (q != null) "${q}p" else "Video" },
                    height = q,
                    authoritative = true
                )
            }
        }

        // ---- 6. JWPlayer / FluidPlayer / Plyr / Video.js `sources: [{file/src, label/res}]` ----
        playerSourceObjRegex.findAll(cleanedScripts).forEach { match ->
            val block = match.value
            val rawSrc = match.groupValues[1]
            val cleanedSrc = unescapeUrl(rawSrc)
            if (MediaSniffer.isPreviewOrAdUrl(cleanedSrc)) return@forEach
            val labelHint = playerLabelAttrRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val q = UrlUtils.inferResolution(cleanedSrc, labelHint)
            if (cleanedSrc.contains(".m3u8", ignoreCase = true)) {
                addExpandedHls(cleanedSrc, q, authoritative = true)
            } else if (cleanedSrc.contains(".mpd", ignoreCase = true)) {
                val expanded = runCatching { dashExtractor.extract(cleanedSrc, effectiveHeaders()) }.getOrNull()
                expanded?.streams?.forEach { variant ->
                    val k = MediaSniffer.dedupeKey(variant.url)
                    if (!found.containsKey(k)) {
                        found[k] = variant.copy(id = "html-${counter++}", headers = effectiveHeaders() + variant.headers)
                    }
                }
            } else {
                classifyByExtension(cleanedSrc)?.let { (t, c) ->
                    if (t != MediaType.IMAGE) {
                        addStream(
                            rawUrl = cleanedSrc,
                            type = t,
                            container = c,
                            label = labelHint.ifBlank { if (q != null) "${q}p" else defaultLabel(t) },
                            height = q,
                            authoritative = true
                        )
                    }
                }
            }
        }

        // ---- 6b. Dean Edwards packed JS, PlayerJS/JWPlayer/Plyr configs, Streamtape robotlink & atob/hex ----
        val unpackedDirectBlocks = JsUnpacker.unpackAll(html)
        val combinedDirectText = if (unpackedDirectBlocks.isNotEmpty()) {
            buildString {
                append(cleanedScripts)
                unpackedDirectBlocks.forEach { block ->
                    append('\n')
                    append(block)
                }
            }
        } else {
            cleanedScripts
        }
        val directPlayerCfg = JsUnpacker.extractPlayerConfigs(combinedDirectText)
        directPlayerCfg.durationSec?.takeIf { it > pageDurationSec }?.let { pageDurationSec = it }
        if (customThumb == null && !directPlayerCfg.posterUrl.isNullOrBlank()) {
            customThumb = directPlayerCfg.posterUrl
        }
        directPlayerCfg.sources.forEach { src ->
            if (src.url.contains(".m3u8", ignoreCase = true)) {
                addExpandedHls(src.url, src.height, authoritative = true)
            } else if (src.url.contains(".mpd", ignoreCase = true)) {
                val expanded = runCatching { dashExtractor.extract(src.url, effectiveHeaders()) }.getOrNull()
                expanded?.streams?.forEach { variant ->
                    val k = MediaSniffer.dedupeKey(variant.url)
                    if (!found.containsKey(k)) {
                        found[k] = variant.copy(id = "html-${counter++}", headers = effectiveHeaders() + variant.headers)
                    }
                }
            } else {
                val container = containerFor(src.url, "mp4")
                addStream(
                    rawUrl = src.url,
                    type = MediaType.VIDEO,
                    container = container,
                    label = src.label.ifBlank { if (src.height != null) "${src.height}p" else "Video" },
                    width = src.width,
                    height = src.height,
                    authoritative = true
                )
            }
        }
        JsUnpacker.extractStreamtapeUrls(combinedDirectText).forEach { stUrl ->
            addStream(stUrl, MediaType.VIDEO, "mp4", "HD Video", authoritative = true)
        }
        if (!authoritativeFound) {
            JsUnpacker.decodeObfuscatedUrls(combinedDirectText).forEach { obfUrl ->
                if (obfUrl.contains(".m3u8", ignoreCase = true)) {
                    addExpandedHls(obfUrl, authoritative = true)
                } else {
                    addStream(obfUrl, MediaType.VIDEO, containerFor(obfUrl, "mp4"), "Video", authoritative = true)
                }
            }
        }

        // ---- 7. Explicit HTML5 media elements (filtering out hover-preview / ad nodes) ----
        doc.select("video source[src], video source[data-src], source[src]").forEach { el ->
            if (isPreviewOrAdElement(el)) return@forEach
            val src = el.absUrl("src").ifBlank { el.absUrl("data-src") }.ifBlank { el.attr("src") }
            val type = el.attr("type").lowercase()
            val qLabel = qualityAttr(el, "")
            when {
                type.contains("mpegurl") || src.contains(".m3u8", ignoreCase = true) ->
                    addExpandedHls(src, UrlUtils.inferResolution(src, qLabel), authoritative = true)
                type.startsWith("video/") ->
                    addStream(src, MediaType.VIDEO, containerFor(src, type.substringAfter('/')), qLabel.ifBlank { "Video" }, authoritative = true)
                type.startsWith("audio/") ->
                    addStream(src, MediaType.AUDIO, containerFor(src, type.substringAfter('/')), qLabel.ifBlank { "Audio" }, authoritative = true)
                else -> classifyByExtension(src)?.let { (t, c) ->
                    if (t != MediaType.IMAGE) {
                        addStream(src, t, c, qLabel.ifBlank { defaultLabel(t) }, authoritative = true)
                    }
                }
            }
        }
        if (!authoritativeFound) {
            doc.select("video[src], video[data-src]").forEach { el ->
                if (isPreviewOrAdElement(el)) return@forEach
                val src = el.absUrl("src").ifBlank { el.absUrl("data-src") }
                if (src.contains(".m3u8", ignoreCase = true)) {
                    addExpandedHls(src, authoritative = true)
                } else {
                    addStream(src, MediaType.VIDEO, containerFor(src, "mp4"), qualityAttr(el, "Video"), authoritative = true)
                }
            }
        }
        doc.select("audio[src], audio source[src]").forEach { el ->
            val src = el.absUrl("src")
            addStream(src, MediaType.AUDIO, containerFor(src, "mp3"), qualityAttr(el, "Audio"), authoritative = true)
        }

        // ---- 8. Open Graph / Twitter video & audio meta tags ----
        val ogWidth = doc.select("meta[property=og:video:width]").attr("content").toIntOrNull()
        val ogHeight = doc.select("meta[property=og:video:height]").attr("content").toIntOrNull()
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream]")
            .forEach {
                val content = it.attr("abs:content").ifBlank { it.attr("content") }
                if (content.startsWith("http") &&
                    !content.contains("/embed/", ignoreCase = true) &&
                    !content.contains("youtube.com/v/", ignoreCase = true) &&
                    (content.contains(".mp4", ignoreCase = true) || content.contains(".m3u8", ignoreCase = true) || !authoritativeFound)
                ) {
                    if (content.contains(".m3u8", ignoreCase = true)) {
                        addExpandedHls(content, ogHeight)
                    } else {
                        addStream(content, MediaType.VIDEO, containerFor(content, "mp4"), "Video", ogWidth, ogHeight)
                    }
                }
            }
        doc.select("meta[property=og:audio], meta[property=og:audio:url], meta[property=og:audio:secure_url]")
            .forEach {
                val content = it.attr("abs:content").ifBlank { it.attr("content") }
                addStream(content, MediaType.AUDIO, containerFor(content, "mp3"), "Audio")
            }

        // ---- 9. Generic script URL scan (only if authoritative player config wasn't already found) ----
        if (!authoritativeFound) {
            val unescapedScripts = unescapeUrl(cleanedScripts)
            val hlsUrls = m3u8Regex.findAll(unescapedScripts)
                .map { it.value }
                .filterNot { MediaSniffer.isPreviewOrAdUrl(it) }
                .distinct()
                .take(4)
                .toList()
            val mpdUrls = mpdRegex.findAll(unescapedScripts)
                .map { it.value }
                .filterNot { MediaSniffer.isPreviewOrAdUrl(it) }
                .distinct()
                .take(2)
                .toList()

            if (hlsUrls.isNotEmpty() || mpdUrls.isNotEmpty()) {
                val headersSnapshot = effectiveHeaders()
                val (expandedHlsList, expandedMpdList) = withTimeoutOrNull(IFRAME_FALLBACK_TIMEOUT_MS) {
                    withCancellableHttpCalls(timeoutMs = IFRAME_FALLBACK_TIMEOUT_MS) {
                        val hlsJobs = hlsUrls.map { rawHlsUrl ->
                            async(Dispatchers.IO) {
                                val trimmedRaw = unescapeUrl(rawHlsUrl.trim())
                                if (MediaSniffer.isHexCipherUrl(trimmedRaw)) return@async rawHlsUrl to null
                                val cleaned = MediaSniffer.cleanMediaUrl(UrlUtils.resolveUrl(pageUrl, trimmedRaw))
                                if (!cleaned.startsWith("http") || MediaSniffer.isHexCipherUrl(cleaned) || MediaSniffer.isPreviewOrAdUrl(cleaned)) {
                                    return@async cleaned to null
                                }
                                cleaned to runCatching { hlsExtractor.extract(cleaned, headersSnapshot, null) }.getOrNull()
                            }
                        }
                        val mpdJobs = mpdUrls.map { mpdUrl ->
                            async(Dispatchers.IO) {
                                runCatching { dashExtractor.extract(mpdUrl, headersSnapshot) }.getOrNull()
                            }
                        }
                        hlsJobs.awaitAll() to mpdJobs.awaitAll()
                    }
                } ?: (emptyList<Pair<String, ResolvedMedia?>>() to emptyList<ResolvedMedia?>())

                for ((cleanedHls, expanded) in expandedHlsList) {
                    if (!cleanedHls.startsWith("http") || MediaSniffer.isHexCipherUrl(cleanedHls) || MediaSniffer.isPreviewOrAdUrl(cleanedHls)) continue
                    if (expanded != null && expanded.streams.isNotEmpty()) {
                        expanded.streams.forEach { variant ->
                            val k = MediaSniffer.dedupeKey(variant.url)
                            if (!found.containsKey(k)) {
                                found[k] = variant.copy(id = "html-${counter++}", headers = headersSnapshot + variant.headers)
                            }
                        }
                    } else {
                        addStream(
                            rawUrl = cleanedHls,
                            type = MediaType.VIDEO,
                            container = "ts",
                            label = "HLS (auto)",
                            isHls = true
                        )
                    }
                }

                for (expanded in expandedMpdList) {
                    expanded?.streams?.forEach { variant ->
                        val k = MediaSniffer.dedupeKey(variant.url)
                        if (!found.containsKey(k)) {
                            found[k] = variant.copy(id = "html-${counter++}", headers = headersSnapshot + variant.headers)
                        }
                    }
                }
            }

            directRegex.findAll(unescapedScripts).forEach { match ->
                val raw = match.value
                if (MediaSniffer.isPreviewOrAdUrl(raw)) return@forEach
                classifyByExtension(raw)?.let { (t, c) ->
                    if (t != MediaType.IMAGE) {
                        val q = UrlUtils.inferResolution(raw)
                        val label = when {
                            q != null -> "${q}p"
                            raw.contains("hd", ignoreCase = true) -> "High quality (720p)"
                            raw.contains("sd", ignoreCase = true) -> "Fast (360p)"
                            else -> defaultLabel(t)
                        }
                        addStream(raw, t, c, label, height = q)
                    }
                }
            }
        }

        // ---- 10. Streaming portal unwrapping (watch/download tabs, multi-server AJAX, up to 3-hop embeds) ----
        val hasVideoOrAudio = found.values.any { it.type == MediaType.VIDEO || it.type == MediaType.AUDIO }
        if (!hasVideoOrAudio && allowEmbedFollow && !UrlUtils.isDedicatedSocialPlatformUrl(pageUrl)) {
            val portalRes = portalResolver.resolvePortalAndEmbeds(
                pageUrl = pageUrl,
                html = html,
                doc = doc,
                baseHeaders = baseHeaders
            )
            if (portalRes.streams.isNotEmpty()) {
                val rankedVideos = StreamCandidateRanker.filterAndRankVideos(
                    portalRes.streams.filter { it.type == MediaType.VIDEO }
                )
                val collapsedPortalVideos = rankedVideos
                    .groupBy { it.height }
                    .flatMap { (h, group) ->
                        if (h != null) {
                            val best = StreamCandidateRanker.pickBestForResolution(group)
                            if (best != null) {
                                val bestHost = UrlUtils.host(best.url)?.lowercase()
                                val backup = group.firstOrNull { alt ->
                                    alt.url != best.url &&
                                        (bestHost == null || UrlUtils.host(alt.url)?.lowercase() != bestHost)
                                } ?: group.firstOrNull { it.url != best.url }
                                listOf(attachBackupMirror(best, backup))
                            } else {
                                emptyList()
                            }
                        } else {
                            if (rankedVideos.any { it.height != null }) emptyList() else collapseNullHeightMirrors(group)
                        }
                    }
                    .sortedWith(
                        compareByDescending<MediaStream> { it.height ?: it.bitrateKbps ?: 0 }
                            .thenByDescending { it.sizeBytes ?: 0L }
                    )
                val portalNonVideos = portalRes.streams.filter { it.type != MediaType.VIDEO }
                val finalPortalStreams = collapsedPortalVideos + portalNonVideos
                if (finalPortalStreams.isNotEmpty()) {
                    val thumbnail = HtmlThumbnailExtractor.extractThumbnail(
                        doc = doc,
                        pageUrl = pageUrl,
                        rawHtml = html,
                        preferredThumb = customThumb,
                        jsonLdThumb = jsonLdThumb,
                        playerPoster = portalRes.posterUrl
                    )
                    val title = HtmlThumbnailExtractor.extractTitle(
                        doc = doc,
                        pageUrl = pageUrl,
                        preferredTitle = customTitle ?: jsonLdTitle
                    )
                    return@coroutineScope ResolvedMedia(
                        sourceUrl = pageUrl,
                        title = title,
                        thumbnailUrl = thumbnail,
                        streams = finalPortalStreams,
                        extractor = name
                    )
                }
            }

            // Fallback for specialized tube embeds (KVS/Pornhub/XVideos inside an iframe) raced in parallel
            val iframeUrls = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                .mapNotNull { el ->
                    val raw = el.absUrl("src")
                        .ifBlank { el.absUrl("data-src") }
                        .ifBlank { el.absUrl("data-lazy-src") }
                    raw.takeIf {
                        it.startsWith("http") &&
                            !AdBlocker.isAd(it) &&
                            !MediaSniffer.isPreviewOrAdUrl(it) &&
                            !it.contains("googletagmanager", ignoreCase = true) &&
                            !it.contains("recaptcha", ignoreCase = true)
                    }
                }
                .distinct()
                .take(2)

            if (iframeUrls.isNotEmpty()) {
                val iframeCh = Channel<ResolvedMedia?>(capacity = iframeUrls.size)
                val iframeJobs = iframeUrls.map { iframeUrl ->
                    launch(Dispatchers.IO) {
                        val res = try {
                            withTimeoutOrNull(IFRAME_FALLBACK_TIMEOUT_MS) {
                                withCancellableHttpCalls(timeoutMs = IFRAME_FALLBACK_TIMEOUT_MS) {
                                    val fetchHeaders = buildPageHeaders(iframeUrl, baseHeaders + ("Referer" to pageUrl))
                                    val iframeHtml = runCatching { http.fetchString(iframeUrl, fetchHeaders) }.getOrNull().orEmpty()
                                    if (iframeHtml.isNotBlank()) {
                                        val iframeStreamHeaders = buildPageHeaders(iframeUrl, emptyMap())
                                        parseHtml(iframeUrl, iframeHtml, iframeStreamHeaders, allowEmbedFollow = false)
                                    } else null
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                        iframeCh.trySend(res)
                    }
                }
                var winningEmbedded: ResolvedMedia? = null
                try {
                    for (i in iframeUrls.indices) {
                        val candidate = iframeCh.receiveCatching().getOrNull() ?: continue
                        if (candidate.streams.isNotEmpty()) {
                            winningEmbedded = candidate
                            break
                        }
                    }
                } finally {
                    iframeJobs.forEach { it.cancel() }
                    iframeCh.close()
                }
                if (winningEmbedded != null) {
                    val embedded = winningEmbedded!!
                    val thumbnail = HtmlThumbnailExtractor.extractThumbnail(
                        doc = doc,
                        pageUrl = pageUrl,
                        rawHtml = html,
                        preferredThumb = customThumb,
                        jsonLdThumb = jsonLdThumb,
                        playerPoster = embedded.thumbnailUrl
                    )
                    val title = HtmlThumbnailExtractor.extractTitle(
                        doc = doc,
                        pageUrl = pageUrl,
                        preferredTitle = customTitle ?: jsonLdTitle
                    ).let { extracted ->
                        if (HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(extracted, pageUrl) &&
                            !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(embedded.title, pageUrl)
                        ) {
                            embedded.title
                        } else {
                            extracted
                        }
                    }
                    return@coroutineScope embedded.copy(
                        sourceUrl = pageUrl,
                        title = title,
                        thumbnailUrl = thumbnail
                    )
                }
            }
        }

        // Only surface og:image as a downloadable Photo if the page has NO video/audio streams at all
        if (found.isEmpty()) {
            doc.select("meta[property=og:image], meta[property=og:image:url], meta[property=og:image:secure_url], meta[name=twitter:image], meta[name=twitter:image:src]")
                .forEach {
                    val content = it.attr("abs:content").ifBlank { it.attr("content") }
                    if (content.startsWith("http")) {
                        addStream(content, MediaType.IMAGE, containerFor(content, "jpg"), "Photo (HD)")
                    }
                }
        }

        if (found.isEmpty()) return@coroutineScope null

        val thumbnail = HtmlThumbnailExtractor.extractThumbnail(
            doc = doc,
            pageUrl = pageUrl,
            rawHtml = html,
            preferredThumb = customThumb,
            jsonLdThumb = jsonLdThumb,
            playerPoster = directPlayerCfg.posterUrl
        )
        val title = HtmlThumbnailExtractor.extractTitle(
            doc = doc,
            pageUrl = pageUrl,
            preferredTitle = customTitle ?: jsonLdTitle
        )

        // ---- 11. Probe direct streams concurrently with bounded fast timeout to reject dead/HTML/image/tiny bumpers ----
        val rawStreams = found.values.toList()
        val probeResults = withCancellableHttpCalls(timeoutMs = DIRECT_PROBE_TIMEOUT_MS) {
            rawStreams.mapIndexed { i, stream ->
                async(Dispatchers.IO) {
                    if (!stream.isHls && stream.type != MediaType.IMAGE && i < MAX_SIZE_PROBES) {
                        try {
                            val info = http.probe(stream.url, stream.headers)
                            if (info.code !in 200..299) {
                                null to null
                            } else {
                                val ct = info.contentType?.lowercase().orEmpty()
                                if (ct.startsWith("text/html") ||
                                    ct.startsWith("application/xhtml") ||
                                    ct.startsWith("application/json") ||
                                    ct.startsWith("image/")
                                ) {
                                    null to null
                                } else if (info.contentLength in 1..40_000L) {
                                    null to null
                                } else {
                                    val updatedHeaders = if (info.acceptsRanges) {
                                        stream.headers + ("X-Internal-Accepts-Ranges" to "true")
                                    } else {
                                        stream.headers
                                    }
                                    val finalUrl = info.finalUrl.takeIf { it.startsWith("http") }
                                        ?.let { MediaSniffer.cleanMediaUrl(it) }
                                        ?: stream.url
                                    val finalStream = stream.copy(
                                        url = finalUrl,
                                        sizeBytes = info.contentLength.takeIf { it > 0 } ?: stream.sizeBytes,
                                        headers = updatedHeaders
                                    )
                                    finalStream to null
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: com.melmeligy.mediadownloader.core.MediaException) {
                            if (e.error == com.melmeligy.mediadownloader.core.AppError.NO_MEDIA_FOUND ||
                                e.error == com.melmeligy.mediadownloader.core.AppError.EXPIRED_LINK ||
                                e.error == com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED
                            ) {
                                null to null
                            } else {
                                // Transient network/timeout error -> keep only if zero live streams survive
                                null to stream
                            }
                        } catch (_: Exception) {
                            null to stream
                        }
                    } else {
                        stream to null
                    }
                }
            }.awaitAll()
        }

        val liveProbed = probeResults.mapNotNull { it.first }
        val probedStreams = if (liveProbed.isNotEmpty()) {
            liveProbed
        } else {
            probeResults.mapNotNull { it.second }
        }

        if (probedStreams.isEmpty()) return@coroutineScope null

        // Filter out pre-roll ads & short 5-45s intro bumpers when a primary feature exists, then deduplicate
        val videos = StreamCandidateRanker.filterAndRankVideos(
            probedStreams.filter { it.type == MediaType.VIDEO }
        )
        val collapsedVideos = videos
            .groupBy { it.height }
            .flatMap { (h, group) ->
                if (h != null) {
                    listOfNotNull(StreamCandidateRanker.pickBestForResolution(group))
                } else {
                    if (videos.any { it.height != null }) emptyList() else collapseNullHeightMirrors(group)
                }
            }
            .sortedWith(
                compareByDescending<MediaStream> { it.height ?: it.bitrateKbps ?: 0 }
                    .thenByDescending { it.sizeBytes ?: 0L }
            )

        val nonVideos = probedStreams.filter { it.type != MediaType.VIDEO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }

        val finalStreams = collapsedVideos + nonVideos
        if (finalStreams.isEmpty()) return@coroutineScope null

        ResolvedMedia(
            sourceUrl = pageUrl,
            title = title,
            thumbnailUrl = thumbnail,
            streams = finalStreams,
            extractor = name
        )
    }

    private fun collectJsonLdObjects(obj: JSONObject, depth: Int, out: MutableList<JSONObject>) {
        if (depth > 5) return
        out.add(obj)
        if (depth == 5) return
        val nestedKeys = listOf("@graph", "mainEntity", "subjectOf", "video", "hasPart", "itemListElement")
        for (key in nestedKeys) {
            val child = obj.opt(key) ?: continue
            when (child) {
                is JSONObject -> collectJsonLdObjects(child, depth + 1, out)
                is JSONArray -> {
                    for (i in 0 until child.length()) {
                        child.optJSONObject(i)?.let { collectJsonLdObjects(it, depth + 1, out) }
                    }
                }
            }
        }
    }

    private fun inferKvsResolution(url: String, labelText: String, fieldName: String): Int? {
        UrlUtils.inferResolution(url, labelText)?.let { return it }
        val lower = labelText.trim().lowercase()
        when {
            lower.contains("full hd") || lower.contains("fhd") -> return 1080
            lower == "hd" || lower.contains(" hd") || lower.startsWith("hd ") -> return 720
            lower == "sd" || lower == "hq" || lower.contains(" sd") || lower.startsWith("sd ") ||
                lower.contains("standard") || lower.contains("normal") -> return 480
            lower == "lq" || lower.contains("low") -> return 360
        }
        if (url.contains("/get_file/", ignoreCase = true) && fieldName == "video_url") {
            return 480
        }
        return null
    }

    private fun collapseNullHeightMirrors(group: List<MediaStream>): List<MediaStream> {
        if (group.size <= 1) return group
        val kept = mutableListOf<MediaStream>()
        val seenSizes = HashSet<Long>()
        val seenFiles = HashSet<String>()
        val ordered = group.sortedWith(
            compareByDescending<MediaStream> { it.headers["X-Internal-Authoritative"] == "true" }
                .thenByDescending { it.sizeBytes ?: 0L }
        )
        for (s in ordered) {
            val sz = s.sizeBytes?.takeIf { it > 0L }
            val fname = UrlUtils.fileNameFromUrl(s.url).lowercase()
            val dupSize = sz != null && sz in seenSizes
            val dupFile = fname != "download" && fname.contains('.') && fname in seenFiles
            if (!dupSize && !dupFile) {
                kept += s
                if (sz != null) seenSizes += sz
                if (fname != "download" && fname.contains('.')) seenFiles += fname
            }
        }
        return kept
    }

    /**
     * Decrypts Kernel Video Sharing (`kt_player`) `function/0/https://...` URLs using `license_code`.
     */
    internal fun decryptKvsUrl(rawUrl: String, licenseCode: String?): String {
        if (!rawUrl.startsWith("function/0/")) return rawUrl
        val stripped = rawUrl.removePrefix("function/0/")
        val lic = licenseCode?.replace("$", "")?.trim().orEmpty()
        if (lic.length < 6 || !lic.all { it.isDigit() }) return stripped

        return runCatching {
            val licValues = IntArray(lic.length) { idx ->
                val d = lic[idx].digitToInt()
                if (d != 0) d else 1
            }
            val modLic = lic.replace('0', '1')
            val center = modLic.length / 2
            val front = modLic.substring(0, center + 1).toLong()
            val back = modLic.substring(center).toLong()
            val modStr = (4L * abs(front - back)).toString().take(center + 1)

            val token = IntArray(modStr.length * 4)
            var tIdx = 0
            for (i in modStr.indices) {
                val cur = modStr[i].digitToInt()
                for (off in 0 until 4) {
                    val lv = licValues[(i + off) % licValues.size]
                    token[tIdx++] = (lv + cur) % 16
                }
            }

            val parts = stripped.split('/').toMutableList()
            val hex32Prefix = Regex("^[0-9a-fA-F]{32}")
            for (p in 3 until parts.size) {
                if (hex32Prefix.containsMatchIn(parts[p])) {
                    val hashChars = parts[p].substring(0, 32).toCharArray()
                    val tail = parts[p].substring(32)
                    for (hi in 31 downTo 0) {
                        var sum = 0
                        for (ti in hi until token.size) sum += token[ti]
                        val swap = (hi + sum) % 32
                        val tmp = hashChars[hi]
                        hashChars[hi] = hashChars[swap]
                        hashChars[swap] = tmp
                    }
                    parts[p] = String(hashChars) + tail
                    break
                }
            }
            parts.joinToString("/")
        }.getOrDefault(stripped)
    }

    private fun extractMediaDefQuality(md: JSONObject): Int? {
        val qVal = md.opt("quality") ?: return null
        return when (qVal) {
            is Number -> qVal.toInt().takeIf { it in UrlUtils.KNOWN_HEIGHTS }
            is String -> UrlUtils.inferResolution("", qVal)
            is JSONArray -> {
                // In Pornhub, an array of multiple heights indicates an HLS master manifest -> return null so all variants expand
                if (qVal.length() == 1) UrlUtils.inferResolution("", qVal.optString(0)) else null
            }
            else -> null
        }
    }

    private fun extractBalancedJsonObjects(source: String, startPattern: Regex): List<String> {
        val results = mutableListOf<String>()
        for (match in startPattern.findAll(source)) {
            val openBrace = match.range.last
            if (openBrace < 0 || openBrace >= source.length || source[openBrace] != '{') continue
            var depth = 0
            var inString = false
            var quoteChar = '"'
            var escaped = false
            for (i in openBrace until source.length) {
                val c = source[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (inString) {
                    if (c == '\\') escaped = true
                    else if (c == quoteChar) inString = false
                } else {
                    when (c) {
                        '"', '\'' -> {
                            inString = true
                            quoteChar = c
                        }
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                results += source.substring(openBrace, i + 1)
                                break
                            }
                        }
                    }
                }
            }
        }
        return results
    }

    private fun parseIsoDurationSeconds(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""", RegexOption.IGNORE_CASE).find(iso) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val s = m.groupValues[3].toLongOrNull() ?: 0L
        return (h * 3600L + min * 60L + s).takeIf { it > 0L }
    }

    private fun isPreviewOrAdElement(el: Element): Boolean {
        val vid = if (el.tagName().equals("source", ignoreCase = true)) el.parent() else el
        if (vid != null && vid.hasAttr("loop") && vid.hasAttr("muted") && !vid.hasAttr("controls")) {
            return true
        }
        var cur: Element? = el
        var depth = 0
        while (cur != null && depth < 6) {
            val cls = (cur.className() + " " + cur.id()).lowercase()
            if (cls.contains("related") || cls.contains("preview") || cls.contains("vast") ||
                cls.contains("preroll") || cls.contains("thumb-block") || cls.contains("video-thumb")
            ) {
                return true
            }
            cur = cur.parent()
            depth++
        }
        return false
    }

    internal fun buildPageHeaders(url: String, customHeaders: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged["User-Agent"] = HeadlessWebExtractor.lastWebViewUserAgent
        merged["Referer"] = url
        cookieProvider(url)?.takeIf { it.isNotBlank() }?.let {
            merged["Cookie"] = it
        }
        merged.putAll(customHeaders)
        return merged
    }

    internal fun markCloudflareChallengeHint(host: String) {
        val key = host.trim().lowercase().removePrefix("www.")
        if (key.isNotEmpty()) {
            cfChallengeHints[key] = clockMs() + CF_HINT_TTL_MS
        }
    }

    internal fun clearCloudflareChallengeHint(host: String) {
        val key = host.trim().lowercase().removePrefix("www.")
        if (key.isNotEmpty()) {
            cfChallengeHints.remove(key)
        }
    }

    internal fun isCloudflareChallengeHintActive(host: String): Boolean {
        val key = host.trim().lowercase().removePrefix("www.")
        if (key.isEmpty()) return false
        val expiresAt = cfChallengeHints[key] ?: return false
        if (clockMs() >= expiresAt) {
            cfChallengeHints.remove(key, expiresAt)
            return false
        }
        return true
    }

    private fun qualityAttr(el: Element, fallback: String): String {
        val raw = el.attr("label")
            .ifBlank { el.attr("res") }
            .ifBlank { el.attr("size") }
            .ifBlank { el.attr("data-res") }
            .ifBlank { el.attr("data-quality") }
            .ifBlank { el.attr("title") }
            .trim()
        if (raw.isBlank()) {
            if (el.hasAttr("data-fluid-hd")) return "720p"
            return fallback
        }
        return if (raw.all { it.isDigit() }) "${raw}p" else raw
    }

    private fun unescapeUrl(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")

    private fun containerFor(url: String, fallback: String): String =
        UrlUtils.fileExtension(url) ?: fallback

    private fun classifyByExtension(url: String): Pair<MediaType, String>? {
        val ext = UrlUtils.fileExtension(url) ?: return null
        val type = UrlUtils.mediaTypeForExtension(ext) ?: return null
        return type to ext
    }

    private fun attachBackupMirror(primary: MediaStream, backup: MediaStream?): MediaStream {
        if (backup == null || backup.url == primary.url) return primary
        val cleanBackupHeaders = backup.headers.filterKeys { !it.startsWith("X-Internal-", ignoreCase = true) }
        val extra = LinkedHashMap<String, String>(primary.headers).apply {
            put("X-Internal-Fallback-Url", backup.url)
            put("X-Internal-Fallback-IsHls", backup.isHls.toString())
            if (backup.container.isNotBlank()) {
                put("X-Internal-Fallback-Container", backup.container)
            }
            if (cleanBackupHeaders.isNotEmpty()) {
                put("X-Internal-Fallback-Headers", com.melmeligy.mediadownloader.core.util.JsonUtils.mapToJson(cleanBackupHeaders))
            }
        }
        return primary.copy(headers = extra)
    }

    private fun defaultLabel(type: MediaType): String = when (type) {
        MediaType.VIDEO -> "Video"
        MediaType.AUDIO -> "Audio"
        MediaType.IMAGE -> "Image"
    }

    companion object {
        private const val MAX_SIZE_PROBES = 10
        private const val INITIAL_PAGE_TIMEOUT_MS = 6_000L
        private const val IFRAME_FALLBACK_TIMEOUT_MS = 3_500L
        private const val DIRECT_PROBE_TIMEOUT_MS = 2_000L
        internal const val CF_HINT_TTL_MS = 180_000L
        private val cfChallengeHints = java.util.concurrent.ConcurrentHashMap<String, Long>()

        internal fun clearAllCloudflareHintsForTests() {
            cfChallengeHints.clear()
        }
    }
}
