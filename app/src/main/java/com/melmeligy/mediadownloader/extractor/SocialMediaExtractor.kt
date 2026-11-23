package com.melmeligy.mediadownloader.extractor

import android.util.Base64
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * High-priority universal extractor for major social platforms (videos, single photos,
 * multi-image carousels/albums/slideshows, and mixed photo+video carousels):
 * 1. Instagram (Reels, Posts, Carousels, TV) via FastDL + Polaris GraphQL + doc_id + Embed JSON.
 * 2. Facebook (Videos, Watch, Reels, fb.watch, Single & Multi-Photo Albums).
 * 3. TikTok (Videos, Photo Mode Slideshows, Audio) via tikwm API + Hydration JSON.
 * 4. X / Twitter (Single/Multi-Image, Mixed, Videos) via vxtwitter + fxtwitter + syndication.
 * 5. Pinterest (Single Pins, Carousel Pins, Idea/Story Pins, Pin Videos, pin.it).
 * 6. Threads (Single/Multi-Image, Mixed Carousels, Videos).
 * 7. Reddit (Image Galleries, Single Images, Crossposts, Reddit Videos, redd.it).
 */
@Singleton
class SocialMediaExtractor @Inject constructor(
    private val http: ExtractorHttp,
    private val headlessWebExtractor: HeadlessWebExtractor
) : MediaExtractor {

    override val name = "Social Media"
    override val priority = 95

    private val igShortcodeRegex =
        Regex("(?:reel|reels|p|tv|share/reel|share/p)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
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

    override suspend fun extract(url: String): ResolvedMedia? {
        if (!canHandle(url) && !UrlUtils.isShortOrRedirectUrl(url)) return null
        val workingUrl = if (UrlUtils.isShortOrRedirectUrl(url)) {
            resolveRedirectUrl(url)
        } else {
            url
        }
        if (!canHandle(workingUrl) && !canHandle(url)) return null
        val host = UrlUtils.host(workingUrl)?.lowercase().orEmpty()
        val timeoutMs = if (host.contains("pinterest.") || host.contains("pin.it")) 16_000L else 12_000L
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
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }

        if (fastResult != null && (fastResult.streams.isNotEmpty() || fastResult.gallerySlides.isNotEmpty())) {
            return fastResult
        }

        // For Instagram, Pinterest, Threads, Reddit do NOT fall back to video-only Headless WebView
        if (host.contains("instagram.com") || host.contains("instagr.am") ||
            host.contains("pinterest.") || host.contains("pin.it") ||
            host.contains("threads.net") || host.contains("threads.com") ||
            host.contains("reddit.com") || host.contains("redd.it")
        ) {
            return null
        }

        // Fallback to Headless WebView to execute JS/GraphQL and intercept live CDN streams
        return try {
            headlessWebExtractor.extract(workingUrl)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveRedirectUrl(rawUrl: String): String {
        val probeFinal = runCatching { http.probe(rawUrl).finalUrl }.getOrNull()
        if (!probeFinal.isNullOrBlank() && probeFinal.startsWith("http") && probeFinal != rawUrl) {
            return UrlUtils.cleanSocialUrl(probeFinal)
        }
        val getFinal = runCatching { http.resolveFinalUrl(rawUrl) }.getOrNull()
        if (!getFinal.isNullOrBlank() && getFinal.startsWith("http") && getFinal != rawUrl) {
            return UrlUtils.cleanSocialUrl(getFinal)
        }
        return UrlUtils.cleanSocialUrl(probeFinal?.takeIf { it.startsWith("http") } ?: rawUrl)
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

    private suspend fun extractInstagram(rawUrl: String): ResolvedMedia? = coroutineScope {
        val resolvedUrl = if (rawUrl.contains("/share/", ignoreCase = true)) {
            runCatching { http.probe(rawUrl).finalUrl }.getOrDefault(rawUrl)
        } else {
            rawUrl
        }

        val shortcode = igShortcodeRegex.find(resolvedUrl)?.groupValues?.getOrNull(1)
            ?: return@coroutineScope null

        val mediaId = shortcodeToMediaId(shortcode)

        val rawStreams = LinkedHashMap<String, MediaStream>()
        var counter = 0
        var capturedTitle: String? = null
        var capturedThumb: String? = null

        val defaultHeaders = mapOf(
            "Referer" to "https://www.instagram.com/",
            "Accept-Language" to "en-US,en;q=0.9"
        )

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

        fun parseMediaPayload(jsonText: String) {
            if (jsonText.isBlank()) return
            val unescaped = unescape(jsonText)

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
            val thumbRegex = Regex("\"(?:display_url|thumbnail_src)\"\\s*:\\s*\"([^\"]+)\"")
            thumbRegex.find(unescaped)?.groupValues?.getOrNull(1)?.let {
                if (capturedThumb == null && it.startsWith("http")) capturedThumb = it
            }
        }

        // Method 0: High-speed FastDL resolver (supports carousels, mixed slides, single photos & Reels)
        runCatching {
            val fastDlHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to "https://fastdl.to",
                "Referer" to "https://fastdl.to/en",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*"
            )
            val canonicalUrl = if (rawUrl.contains("/p/", ignoreCase = true)) {
                "https://www.instagram.com/p/$shortcode/"
            } else {
                "https://www.instagram.com/reel/$shortcode/"
            }
            val postBody = "q=" + URLEncoder.encode(canonicalUrl, "UTF-8") + "&t=media&lang=en&v=v2"
            val respJson = http.postForm("https://fastdl.to/api/ajaxSearch", postBody, fastDlHeaders)
            val jsonRoot = JSONObject(respJson)
            if (jsonRoot.optString("status") == "ok") {
                val dataHtml = jsonRoot.optString("data")
                if (dataHtml.isNotBlank()) {
                    val parsedFastDl = SocialMediaGalleryParsers.parseInstagramFastDlHtml(
                        dataHtml = dataHtml,
                        shortcode = shortcode,
                        headers = defaultHeaders
                    )
                    if (parsedFastDl != null) {
                        if (parsedFastDl.slides.isNotEmpty()) {
                            return@coroutineScope buildGalleryResolvedMedia(rawUrl, "Instagram", parsedFastDl)
                        }
                        if (capturedThumb == null && !parsedFastDl.thumbnailUrl.isNullOrBlank()) {
                            capturedThumb = parsedFastDl.thumbnailUrl
                        }
                        parsedFastDl.videoStreams.forEach { v ->
                            addCandidate(v.url, MediaType.VIDEO, v.container, v.label, v.width, v.height)
                        }
                    }
                }
            }
        }

        // Method 1: Polaris LoggedOut Desktop GraphQL API (full carousel_media / edge_sidecar_to_children + DASH)
        if (rawStreams.isEmpty() && mediaId != null) {
            runCatching {
                val polarisHeaders = mapOf(
                    "X-IG-App-ID" to "1217981644879628",
                    "X-ASBD-ID" to "198387",
                    "Origin" to "https://www.instagram.com",
                    "Referer" to "https://www.instagram.com/",
                    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
                )
                val polarisBody = "av=0&__d=www&__user=0&dpr=1&lsd=AVqb_dummy&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery&variables=" +
                    URLEncoder.encode("{\"media_id\":\"$mediaId\"}", "UTF-8") +
                    "&doc_id=27130156389949648"
                val polarisJson = http.postForm("https://www.instagram.com/api/graphql", polarisBody, polarisHeaders)
                val parsed = SocialMediaGalleryParsers.parseInstagramGraphQl(polarisJson, shortcode, defaultHeaders)
                if (parsed != null && parsed.slides.isNotEmpty()) {
                    return@coroutineScope buildGalleryResolvedMedia(rawUrl, "Instagram", parsed)
                }
                parseMediaPayload(polarisJson)
            }
        }

        // Method 2: GraphQL query with doc_id 8845758582119845
        if (rawStreams.isEmpty()) {
            runCatching {
                val docHeaders = mapOf(
                    "X-IG-App-ID" to "1217981644879628",
                    "Referer" to "https://www.instagram.com/"
                )
                val docUrl = "https://www.instagram.com/graphql/query/?doc_id=8845758582119845&variables=" +
                    URLEncoder.encode("{\"shortcode\":\"$shortcode\"}", "UTF-8")
                val docJson = http.fetchString(docUrl, docHeaders)
                val parsed = SocialMediaGalleryParsers.parseInstagramGraphQl(docJson, shortcode, defaultHeaders)
                if (parsed != null && parsed.slides.isNotEmpty()) {
                    return@coroutineScope buildGalleryResolvedMedia(rawUrl, "Instagram", parsed)
                }
                parseMediaPayload(docJson)
            }
        }

        // Method 3: Embed page captioned (supports embedded sidecar JSON, single photos & videos)
        if (rawStreams.isEmpty()) {
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
            val html = runCatching { http.fetchString(embedUrl, defaultHeaders) }.getOrNull().orEmpty()
            if (html.isNotBlank()) {
                val parsedEmbed = SocialMediaGalleryParsers.parseInstagramEmbedHtml(html, shortcode, defaultHeaders)
                if (parsedEmbed != null && parsedEmbed.slides.isNotEmpty()) {
                    return@coroutineScope buildGalleryResolvedMedia(rawUrl, "Instagram", parsedEmbed)
                }
                parseMediaPayload(html)
                val doc = Jsoup.parse(html, embedUrl)
                doc.select("video[src], video source[src]").forEach { el ->
                    val src = el.absUrl("src").ifBlank { el.attr("src") }
                    addCandidate(src, MediaType.VIDEO, "mp4", "High quality (720p)")
                }
                capturedTitle = doc.select(".CaptionUsername").text().takeIf { it.isNotBlank() }?.let { "Instagram Reel (@$it)" }
                    ?: doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
                capturedThumb = doc.select("img.EmbeddedMediaImage").attr("src")
                    .ifBlank { doc.select("meta[property=og:image]").attr("content") }
                    .let { unescape(it) }
                    .takeIf { it.startsWith("http") }
            }
        }

        if (rawStreams.isEmpty()) return@coroutineScope null

        // Probe exact file sizes in parallel
        val probed = rawStreams.values.take(10).map { stream ->
            async {
                runCatching {
                    val info = http.probe(stream.url, defaultHeaders)
                    if (info.contentLength > 0) stream.copy(sizeBytes = info.contentLength) else stream
                }.getOrDefault(stream)
            }
        }.awaitAll().filter { (it.sizeBytes ?: Long.MAX_VALUE) > 10_000L }

        if (probed.isEmpty()) return@coroutineScope null

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

        ResolvedMedia(
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
            runCatching { http.probe(rawUrl).finalUrl }.getOrDefault(rawUrl)
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

        // Probe exact sizes
        val probed = rawStreams.values.take(10).map { stream ->
            async {
                runCatching {
                    val info = http.probe(stream.url, desktopHeaders)
                    if (info.contentLength > 0) stream.copy(sizeBytes = info.contentLength) else stream
                }.getOrDefault(stream)
            }
        }.awaitAll().filter { (it.sizeBytes ?: Long.MAX_VALUE) > 10_000L }

        if (probed.isEmpty()) return@coroutineScope null

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
    // 3. TIKTOK (Canonical Redirect + tikwm API + Universal Hydration JSON)
    // =========================================================================

    private suspend fun extractTikTok(rawUrl: String): ResolvedMedia? = coroutineScope {
        val canonicalUrl = UrlUtils.cleanSocialUrl(
            if (UrlUtils.isShortOrRedirectUrl(rawUrl)) {
                resolveRedirectUrl(rawUrl)
            } else {
                rawUrl
            }
        )

        val desktopHeaders = mapOf(
            "User-Agent" to TIKTOK_DESKTOP_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        val tikTokBaseCdnHeaders = LinkedHashMap<String, String>().apply {
            put("User-Agent", TIKTOK_DESKTOP_UA)
            put("Referer", "https://www.tiktok.com/")
            put("Origin", "https://www.tiktok.com")
            http.cookieHeaderFor(canonicalUrl)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }

        // Try Method 1: tikwm API with HD enabled
        val encoded = URLEncoder.encode(canonicalUrl, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
        val jsonText = runCatching { http.fetchString(apiUrl) }.getOrNull()
        if (!jsonText.isNullOrBlank()) {
            val root = runCatching { JSONObject(jsonText) }.getOrNull()
            val data = root?.optJSONObject("data")
            if (data != null) {
                val apiMusicUrl = data.optString("music").takeIf { it.startsWith("http") }
                val apiMusicProbe = if (apiMusicUrl != null) {
                    withTimeoutOrNull(4_500L) { runCatching { http.probe(apiMusicUrl) }.getOrNull() }
                } else null

                // Photo Mode slideshow (`data.images[]`) -> Multi-Image Bottom Sheet (with accompanying audio)
                val parsedPhotos = SocialMediaGalleryParsers.parseTikTokPhotoFromApiData(
                    data = data,
                    postId = UrlUtils.extractPostId(canonicalUrl),
                    musicProbe = apiMusicProbe,
                    headers = mapOf("Referer" to "https://www.tiktok.com/")
                )
                if (parsedPhotos != null && parsedPhotos.slides.isNotEmpty()) {
                    return@coroutineScope buildGalleryResolvedMedia(rawUrl, "TikTok", parsedPhotos)
                }

                val hdPlay = data.optString("hdplay").takeIf { it.startsWith("http") }
                val play = data.optString("play").takeIf { it.startsWith("http") }
                val apiHdSize = data.optLong("hd_size", 0L).takeIf { it > 0L }
                val apiPlaySize = data.optLong("size", 0L).takeIf { it > 0L }
                val knownSameApiSize = apiHdSize != null && apiPlaySize != null && apiHdSize == apiPlaySize

                val hdProbeDef = if (hdPlay != null && !knownSameApiSize && (apiHdSize == null || play != null)) {
                    async { withTimeoutOrNull(4_500L) { runCatching { http.probe(hdPlay) }.getOrNull() } }
                } else null
                val playProbeDef = if (play != null && !knownSameApiSize && (apiPlaySize == null || hdPlay != null)) {
                    async { withTimeoutOrNull(4_500L) { runCatching { http.probe(play) }.getOrNull() } }
                } else null

                val streams = SocialMediaGalleryParsers.buildTikTokVideoAndAudioStreams(
                    data = data,
                    hdProbe = hdProbeDef?.await(),
                    playProbe = playProbeDef?.await(),
                    musicProbe = apiMusicProbe
                )

                if (streams.isNotEmpty()) {
                    val title = data.optString("title").takeIf { it.isNotBlank() } ?: "TikTok Video"
                    val cover = data.optString("cover").takeIf { it.startsWith("http") }
                    return@coroutineScope ResolvedMedia(
                        sourceUrl = rawUrl,
                        title = title,
                        thumbnailUrl = cover,
                        streams = streams,
                        extractor = "TikTok"
                    )
                }
            }
        }

        // Method 2: Independent HTML __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON + session Cookie + Referer
        val fetchedHtml = runCatching { http.fetchHtmlWithCookies(canonicalUrl, desktopHeaders) }.getOrNull()
        val html = fetchedHtml?.html
            ?: runCatching { http.fetchString(canonicalUrl, desktopHeaders) }.getOrNull().orEmpty()
        if (html.isNotBlank()) {
            val cdnHeaders = LinkedHashMap<String, String>(tikTokBaseCdnHeaders).apply {
                val cookie = fetchedHtml?.cookieHeader?.takeIf { it.isNotBlank() }
                    ?: http.cookieHeaderFor(fetchedHtml?.finalUrl ?: canonicalUrl)
                if (!cookie.isNullOrBlank()) {
                    put("Cookie", cookie)
                }
            }

            // Photo Mode Slideshow from Hydration JSON (desc title, cover, all slides + music audio, CDN headers)
            val parsedPhotoHtml = SocialMediaGalleryParsers.parseTikTokPhotoFromHydrationHtml(
                html = html,
                postId = UrlUtils.extractPostId(canonicalUrl),
                headers = cdnHeaders
            )
            if (parsedPhotoHtml != null && parsedPhotoHtml.slides.isNotEmpty()) {
                val probedAudio = if (parsedPhotoHtml.audioStreams.isNotEmpty()) {
                    val primaryAudioUrl = parsedPhotoHtml.audioStreams.first().url
                    val musicDuration = parsedPhotoHtml.audioStreams.first().headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
                    val mProbe = withTimeoutOrNull(4_500L) { runCatching { http.probe(primaryAudioUrl, cdnHeaders) }.getOrNull() }
                    if (mProbe != null) {
                        SocialMediaGalleryParsers.buildTikTokAudioStreamsFromUrl(
                            musicUrl = primaryAudioUrl,
                            musicDurationSec = musicDuration,
                            musicProbe = mProbe,
                            baseHeaders = cdnHeaders,
                            idPrefix = "tt-photo-a"
                        )
                    } else parsedPhotoHtml.audioStreams
                } else emptyList()
                return@coroutineScope buildGalleryResolvedMedia(
                    rawUrl = rawUrl,
                    extractorName = "TikTok",
                    parsed = parsedPhotoHtml.copy(audioStreams = probedAudio)
                )
            }

            // Full structured __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON video + multi-quality bitrateInfo + music
            val parsedVideoPost = SocialMediaGalleryParsers.parseTikTokVideoFromHydrationHtml(html, cdnHeaders)
            if (parsedVideoPost != null) {
                val videoProbeDefs = parsedVideoPost.videoStreams.mapIndexed { idx, stream ->
                    if (idx < 3 && (stream.sizeBytes == null || !stream.headers.containsKey("X-Internal-Accepts-Ranges"))) {
                        async { withTimeoutOrNull(4_500L) { runCatching { http.probe(stream.url, cdnHeaders) }.getOrNull() } }
                    } else null
                }
                val firstAudio = parsedVideoPost.audioStreams.firstOrNull()
                val audioProbeDef = if (firstAudio != null) {
                    async { withTimeoutOrNull(4_500L) { runCatching { http.probe(firstAudio.url, cdnHeaders) }.getOrNull() } }
                } else null

                val probedVideos = parsedVideoPost.videoStreams.mapIndexed { idx, stream ->
                    val info = videoProbeDefs[idx]?.await()
                    if (info != null) {
                        val updatedHeaders = LinkedHashMap<String, String>(stream.headers).apply {
                            if (info.acceptsRanges) put("X-Internal-Accepts-Ranges", "true")
                        }
                        stream.copy(
                            url = info.finalUrl.takeIf { it.startsWith("http") } ?: stream.url,
                            sizeBytes = info.contentLength.takeIf { it > 0L } ?: stream.sizeBytes,
                            headers = updatedHeaders
                        )
                    } else {
                        stream
                    }
                }
                val probedAudios = if (firstAudio != null) {
                    val mProbe = audioProbeDef?.await()
                    if (mProbe != null) {
                        val dur = firstAudio.headers["X-Internal-Duration-Seconds"]?.toLongOrNull()
                        SocialMediaGalleryParsers.buildTikTokAudioStreamsFromUrl(
                            musicUrl = firstAudio.url,
                            musicDurationSec = dur,
                            musicProbe = mProbe,
                            baseHeaders = cdnHeaders,
                            idPrefix = "tt-page-a"
                        )
                    } else parsedVideoPost.audioStreams
                } else emptyList()

                val allStreams = probedVideos + probedAudios
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

            // Legacy surface fallback when __UNIVERSAL_DATA_FOR_REHYDRATION__ wrapper is absent
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

            val videoProbeDef = if (playUrl != null && playUrl.startsWith("http")) {
                async { withTimeoutOrNull(4_500L) { runCatching { http.probe(playUrl, cdnHeaders) }.getOrNull() } }
            } else null
            val musicProbeDef = if (musicUrl != null && musicUrl.startsWith("http")) {
                async { withTimeoutOrNull(4_500L) { runCatching { http.probe(musicUrl, cdnHeaders) }.getOrNull() } }
            } else null

            val vInfo = videoProbeDef?.await()
            val mInfo = musicProbeDef?.await()

            val streams = mutableListOf<MediaStream>()
            if (playUrl != null && playUrl.startsWith("http")) {
                val streamHeaders = LinkedHashMap<String, String>(cdnHeaders).apply {
                    if (vInfo?.acceptsRanges == true) put("X-Internal-Accepts-Ranges", "true")
                }
                streams += MediaStream(
                    id = "tt-page-v",
                    url = vInfo?.finalUrl?.takeIf { it.startsWith("http") } ?: playUrl,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "High quality (720p HD)",
                    height = 720,
                    sizeBytes = vInfo?.contentLength?.takeIf { it > 0 },
                    isHls = false,
                    headers = streamHeaders
                )
            }
            if (musicUrl != null && musicUrl.startsWith("http")) {
                streams += SocialMediaGalleryParsers.buildTikTokAudioStreamsFromUrl(
                    musicUrl = musicUrl,
                    musicProbe = mInfo,
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
        }

        null
    }

    // =========================================================================
    // 4. X / TWITTER (vxtwitter + fxtwitter + syndication — Photos, Mixed & Videos)
    // =========================================================================

    private suspend fun extractTwitter(rawUrl: String): ResolvedMedia? = coroutineScope {
        val match = twitterStatusRegex.find(rawUrl) ?: return@coroutineScope null
        val user = match.groupValues[1]
        val statusId = match.groupValues[2]

        val apis = listOf(
            "https://api.vxtwitter.com/$user/status/$statusId",
            "https://api.fxtwitter.com/$user/status/$statusId",
            "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=a"
        )

        for (apiUrl in apis) {
            val jsonText = runCatching { http.fetchString(apiUrl) }.getOrNull() ?: continue
            val parsed = SocialMediaGalleryParsers.parseTwitterApiJson(jsonText, user, statusId) ?: continue

            if (parsed.slides.isNotEmpty()) {
                return@coroutineScope buildGalleryResolvedMedia(rawUrl, "X / Twitter", parsed)
            }

            if (parsed.videoStreams.isNotEmpty()) {
                val probedVideos = parsed.videoStreams.map { stream ->
                    async {
                        val info = runCatching { http.probe(stream.url) }.getOrNull()
                        if (info != null && info.contentLength > 0) stream.copy(sizeBytes = info.contentLength) else stream
                    }
                }.awaitAll()
                return@coroutineScope ResolvedMedia(
                    sourceUrl = rawUrl,
                    title = parsed.title ?: "X Video (@$user)",
                    thumbnailUrl = parsed.thumbnailUrl,
                    streams = probedVideos,
                    extractor = "X / Twitter",
                    postId = statusId
                )
            }
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

        // Tier 2: PinResource/get API fallback — ALWAYS runs when HTML yielded <= 1 image
        // because top-level og:image / images.orig on Carousel & Story Pins only carry the 1 cover.
        if (needsMultiSlideUpgrade() && !pinId.isNullOrBlank()) {
            val fieldSets = listOf("detailed", "unauth_react_main_pin", "auth_web_main_pin")
            val apiHeaders = desktopHeaders + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-Pinterest-AppState" to "active",
                "X-Pinterest-PWS-Handler" to "www/pin/[id].js",
                "Referer" to canonicalUrl,
                "Accept" to "application/json, text/javascript, */*, q=0.01"
            )
            for (fieldSet in fieldSets) {
                if (!needsMultiSlideUpgrade()) break
                runCatching {
                    val dataJson = "{\"options\":{\"id\":\"$pinId\",\"field_set_key\":\"$fieldSet\"}}"
                    val dataParam = URLEncoder.encode(dataJson, "UTF-8")
                    val apiUrl = "https://www.pinterest.com/resource/PinResource/get/?source_url=/pin/$pinId/&data=$dataParam"
                    val apiJson = http.fetchString(apiUrl, apiHeaders)
                    val apiParsed = SocialMediaGalleryParsers.parsePinterestHtml(apiJson, pinId, desktopHeaders)
                    bestParsed = SocialMediaGalleryParsers.preferRicherPinterestPost(bestParsed, apiParsed)
                }
            }
        }

        // Tier 3: Pinterest v3/pins endpoint fallback when PinResource omits carousel/story slots
        if (needsMultiSlideUpgrade() && !pinId.isNullOrBlank()) {
            runCatching {
                val v3Headers = desktopHeaders + mapOf(
                    "Accept" to "application/json",
                    "Referer" to canonicalUrl
                )
                val v3Url = "https://api.pinterest.com/v3/pins/$pinId/?fields=pin.id,pin.title,pin.images,pin.carousel_data,pin.story_pin_data,pin.videos"
                val v3Json = http.fetchString(v3Url, v3Headers)
                val v3Parsed = SocialMediaGalleryParsers.parsePinterestHtml(v3Json, pinId, desktopHeaders)
                bestParsed = SocialMediaGalleryParsers.preferRicherPinterestPost(bestParsed, v3Parsed)
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
            val probed = parsed.videoStreams.map { v ->
                async {
                    val info = runCatching { http.probe(v.url, headers) }.getOrNull()
                    if (info != null && info.contentLength > 0) v.copy(sizeBytes = info.contentLength) else v
                }
            }.awaitAll()
            return@coroutineScope ResolvedMedia(
                sourceUrl = rawUrl,
                title = parsed.title ?: "Threads Video",
                thumbnailUrl = parsed.thumbnailUrl,
                streams = probed,
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
    }
}
