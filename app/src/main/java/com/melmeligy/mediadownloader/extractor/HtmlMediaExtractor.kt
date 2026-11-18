package com.melmeligy.mediadownloader.extractor

import android.webkit.CookieManager
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val headlessWebExtractor: HeadlessWebExtractor
) : MediaExtractor {

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

    private val playerSourceObjRegex = Regex(
        """\{[^{}]{0,420}?(?:file|src|"file"|"src")\s*:\s*['"](https?://[^'"]+)['"][^{}]{0,420}?\}""",
        RegexOption.IGNORE_CASE
    )
    private val playerLabelAttrRegex = Regex(
        """(?:label|res|size|title|quality|"label"|"res"|"size"|"quality")\s*:\s*['"]?([0-9]{3,4}p?|4k|2k|hd|sd)['"]?""",
        RegexOption.IGNORE_CASE
    )

    override fun canHandle(url: String): Boolean = url.startsWith("http")

    override suspend fun extract(url: String): ResolvedMedia? =
        extractWithHeaders(url, emptyMap(), allowHeadlessFallback = true)

    suspend fun extractWithHeaders(
        url: String,
        customHeaders: Map<String, String> = emptyMap(),
        allowHeadlessFallback: Boolean = true
    ): ResolvedMedia? = coroutineScope {
        val pageHeaders = buildPageHeaders(url, customHeaders)
        val html = runCatching { http.fetchString(url, pageHeaders) }.getOrNull().orEmpty()
        if (html.isBlank()) {
            return@coroutineScope if (allowHeadlessFallback) {
                runCatching { headlessWebExtractor.extract(url) }.getOrNull()
            } else null
        }

        val parsed = parseHtml(
            pageUrl = url,
            html = html,
            baseHeaders = pageHeaders,
            allowEmbedFollow = true
        )
        if (parsed != null && parsed.streams.isNotEmpty()) {
            return@coroutineScope parsed
        }
        if (allowHeadlessFallback) {
            runCatching { headlessWebExtractor.extract(url) }.getOrNull()
        } else {
            null
        }
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
            val resolved = UrlUtils.resolveUrl(pageUrl, unescapeUrl(rawUrl.trim()))
            val cleaned = MediaSniffer.cleanMediaUrl(resolved)
            if (!cleaned.startsWith("http")) return
            if (type != MediaType.IMAGE && MediaSniffer.isPreviewOrAdUrl(cleaned)) return

            val key = MediaSniffer.dedupeKey(cleaned)
            val inferredHeight = height ?: UrlUtils.inferResolution(cleaned, label)
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
                width = width,
                height = inferredHeight,
                bitrateKbps = bitrateKbps,
                sizeBytes = sizeBytes,
                isHls = isHls,
                headers = mergedHeaders
            )
        }

        suspend fun addExpandedHls(rawHlsUrl: String, heightHint: Int? = null, authoritative: Boolean = false) {
            val cleaned = MediaSniffer.cleanMediaUrl(UrlUtils.resolveUrl(pageUrl, unescapeUrl(rawHlsUrl.trim())))
            if (!cleaned.startsWith("http") || MediaSniffer.isPreviewOrAdUrl(cleaned)) return
            val expanded = runCatching { hlsExtractor.extract(cleaned, effectiveHeaders(), heightHint) }.getOrNull()
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
                    label = if (heightHint != null) "${heightHint}p" else "HLS (auto)",
                    height = heightHint,
                    isHls = true,
                    authoritative = authoritative
                )
            }
        }

        // Strip related-video arrays & VAST ad blocks before scanning inline scripts
        val cleanedScripts = relatedNoiseRegex.replace(html, " ")

        // ---- 1. JSON-LD Schema.org VideoObject / AudioObject metadata ----
        var jsonLdTitle: String? = null
        var jsonLdThumb: String? = null
        doc.select("script[type=application/ld+json]").forEach { script ->
            runCatching {
                val rawJson = script.data().trim()
                val objects = mutableListOf<JSONObject>()
                if (rawJson.startsWith("[")) {
                    val arr = JSONArray(rawJson)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { objects.add(it) }
                    }
                } else if (rawJson.startsWith("{")) {
                    objects.add(JSONObject(rawJson))
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
                                addExpandedHls(vUrl, qHint, authoritative = true)
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
                    // Resolve remote get_media JSON endpoints for direct MP4 qualities (240p..1080p)
                    for (endpoint in remoteEndpoints.distinct().take(2)) {
                        val remoteJson = runCatching { http.fetchString(endpoint, effectiveHeaders()) }.getOrNull()
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
                                        addExpandedHls(rUrl, qHint, authoritative = true)
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
                }
            }
        }

        // ---- 3. XVideos & XNXX (`html5player.setVideoHLS / setVideoUrlHigh / setVideoUrlLow`) ----
        xvTitleRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
            if (customTitle == null) customTitle = unescapeUrl(it)
        }
        (xvThumb169Regex.find(cleanedScripts) ?: xvThumbRegex.find(cleanedScripts))
            ?.groupValues?.getOrNull(2)?.takeIf { it.startsWith("http") }?.let {
                if (customThumb == null) customThumb = unescapeUrl(it)
            }
        xvHlsRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { hlsUrl ->
            addExpandedHls(hlsUrl, authoritative = true)
        }
        xvHighRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { highUrl ->
            val q = UrlUtils.inferResolution(highUrl) ?: 480
            addStream(highUrl, MediaType.VIDEO, containerFor(highUrl, "mp4"), "${q}p", height = q, authoritative = true)
        }
        xvLowRegex.find(cleanedScripts)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { lowUrl ->
            val q = UrlUtils.inferResolution(lowUrl) ?: 240
            addStream(lowUrl, MediaType.VIDEO, containerFor(lowUrl, "mp4"), "${q}p", height = q, authoritative = true)
        }

        // ---- 4. xHamster (`window.initials = {...}` + `<link rel="preload" href="...m3u8">`) ----
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

                val sources = ini.optJSONObject("xplayerSettings")?.optJSONObject("sources")
                    ?: ve?.optJSONObject("sources")
                if (sources != null) {
                    val hlsObj = sources.optJSONObject("hls")
                    if (hlsObj != null) {
                        val preferredHls = hlsObj.optJSONObject("h264")?.optString("url")
                            ?.takeIf { it.startsWith("http") }
                            ?: hlsObj.optJSONObject("mp4")?.optString("url")?.takeIf { it.startsWith("http") }
                            ?: hlsObj.keys().asSequence().mapNotNull { k ->
                                hlsObj.optJSONObject(k)?.optString("url")?.takeIf { it.startsWith("http") }
                            }.firstOrNull()
                        if (preferredHls != null) {
                            addExpandedHls(preferredHls, authoritative = true)
                        }
                    }

                    val stdObj = sources.optJSONObject("standard")
                    if (stdObj != null) {
                        val stdArr = stdObj.optJSONArray("h264")
                            ?: stdObj.optJSONArray("mp4")
                            ?: stdObj.keys().asSequence().mapNotNull { k -> stdObj.optJSONArray(k) }.firstOrNull()
                        if (stdArr != null) {
                            for (i in 0 until stdArr.length()) {
                                val item = stdArr.optJSONObject(i) ?: continue
                                val primaryUrl = item.optString("url").ifBlank { item.optString("fallback") }
                                if (primaryUrl.startsWith("http")) {
                                    val qLabel = item.optString("quality").ifBlank { item.optString("label") }
                                    val q = UrlUtils.inferResolution(primaryUrl, qLabel)
                                    addStream(
                                        rawUrl = primaryUrl,
                                        type = MediaType.VIDEO,
                                        container = containerFor(primaryUrl, "mp4"),
                                        label = qLabel.ifBlank { if (q != null) "${q}p" else "Video" },
                                        height = q,
                                        authoritative = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        doc.select("link[rel=preload][href*=.m3u8]").forEach { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.startsWith("http") && !authoritativeFound) {
                addExpandedHls(href, authoritative = true)
            }
        }

        // ---- 5. Arabsex & KVS (`kt_player` `function/0/...` + `license_code`) ----
        val kvsLicense = kvsLicenseRegex.find(cleanedScripts)?.groupValues?.getOrNull(1)
        val kvsLabels = HashMap<String, String>()
        kvsTextFieldRegex.findAll(cleanedScripts).forEach { m ->
            kvsLabels[m.groupValues[1]] = m.groupValues[2]
        }
        kvsUrlFieldRegex.findAll(cleanedScripts).forEach { m ->
            val fieldName = m.groupValues[1]
            val rawKvsUrl = m.groupValues[2]
            val decrypted = decryptKvsUrl(unescapeUrl(rawKvsUrl), kvsLicense)
            val labelText = kvsLabels["${fieldName}_text"].orEmpty()
            val q = UrlUtils.inferResolution(decrypted, labelText)
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
            for (hlsUrl in hlsUrls) {
                addExpandedHls(hlsUrl)
            }

            val mpdUrls = mpdRegex.findAll(unescapedScripts)
                .map { it.value }
                .filterNot { MediaSniffer.isPreviewOrAdUrl(it) }
                .distinct()
                .take(2)
                .toList()
            for (mpdUrl in mpdUrls) {
                val expanded = runCatching { dashExtractor.extract(mpdUrl, effectiveHeaders()) }.getOrNull()
                expanded?.streams?.forEach { variant ->
                    val k = MediaSniffer.dedupeKey(variant.url)
                    if (!found.containsKey(k)) {
                        found[k] = variant.copy(id = "html-${counter++}", headers = effectiveHeaders() + variant.headers)
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

        // ---- 10. Follow 1-hop player iframe embeds (e.g. Arabsex / tube embed wrappers) ----
        val hasVideoOrAudio = found.values.any { it.type == MediaType.VIDEO || it.type == MediaType.AUDIO }
        if (!hasVideoOrAudio && allowEmbedFollow) {
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

            for (iframeUrl in iframeUrls) {
                val embedHeaders = buildPageHeaders(iframeUrl, baseHeaders + ("Referer" to pageUrl))
                val iframeHtml = runCatching { http.fetchString(iframeUrl, embedHeaders) }.getOrNull().orEmpty()
                if (iframeHtml.isNotBlank()) {
                    val embedded = parseHtml(iframeUrl, iframeHtml, embedHeaders, allowEmbedFollow = false)
                    if (embedded != null && embedded.streams.isNotEmpty()) {
                        val thumbnail = customThumb
                            ?: doc.select("meta[property=og:image]").attr("abs:content").ifBlank { null }
                            ?: embedded.thumbnailUrl
                        val title = customTitle
                            ?: doc.select("meta[property=og:title]").attr("content").ifBlank { null }
                            ?: jsonLdTitle
                            ?: doc.title().ifBlank { null }
                            ?: embedded.title
                        return@coroutineScope embedded.copy(
                            sourceUrl = pageUrl,
                            title = title,
                            thumbnailUrl = thumbnail
                        )
                    }
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

        val thumbnail = customThumb
            ?: doc.select("meta[property=og:image]").attr("abs:content")
                .ifBlank { doc.select("meta[name=twitter:image]").attr("abs:content") }
                .ifBlank { jsonLdThumb.orEmpty() }
                .ifBlank { null }
        val title = customTitle
            ?: doc.select("meta[property=og:title]").attr("content")
                .ifBlank { jsonLdTitle.orEmpty() }
                .ifBlank { doc.title() }
                .ifBlank { UrlUtils.host(pageUrl) ?: "Media" }

        // ---- 11. Probe direct streams concurrently for exact sizeBytes & reject HTML/tiny bumpers ----
        val rawStreams = found.values.toList()
        val probedStreams = rawStreams.mapIndexed { i, stream ->
            async {
                if (!stream.isHls && stream.type != MediaType.IMAGE && i < MAX_SIZE_PROBES) {
                    runCatching {
                        val info = http.probe(stream.url, stream.headers)
                        val ct = info.contentType?.lowercase().orEmpty()
                        if (ct.startsWith("text/html") || ct.startsWith("application/xhtml") || ct.startsWith("application/json")) {
                            null
                        } else if (info.contentLength in 1..40_000L) {
                            null
                        } else if (info.contentLength > 0) {
                            stream.copy(sizeBytes = info.contentLength)
                        } else {
                            stream
                        }
                    }.getOrDefault(stream)
                } else stream
            }
        }.awaitAll().filterNotNull()

        if (probedStreams.isEmpty()) return@coroutineScope null

        // Deduplicate same-height video variants: prefer direct MP4 with valid size over HLS at the same height
        val videos = probedStreams.filter { it.type == MediaType.VIDEO }
        val collapsedVideos = videos
            .groupBy { it.height }
            .flatMap { (h, group) ->
                if (h != null) {
                    val best = group.maxWithOrNull(
                        compareBy<MediaStream> { !it.isHls && (it.sizeBytes ?: 0L) >= 500_000L }
                            .thenBy { !it.isHls && group.none { g -> (g.sizeBytes ?: 0L) > 0L } }
                            .thenBy { it.sizeBytes ?: 0L }
                    )
                    listOfNotNull(best)
                } else {
                    if (videos.any { it.height != null }) emptyList() else group
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

    private fun buildPageHeaders(url: String, customHeaders: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged["Referer"] = url
        runCatching {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                merged["Cookie"] = it
            }
        }
        merged.putAll(customHeaders)
        return merged
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

    private fun defaultLabel(type: MediaType): String = when (type) {
        MediaType.VIDEO -> "Video"
        MediaType.AUDIO -> "Audio"
        MediaType.IMAGE -> "Image"
    }

    private companion object {
        const val MAX_SIZE_PROBES = 10
    }
}
