package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * High-priority universal extractor for major social video platforms:
 * 1. Instagram (Reels, Posts, TV, Stories) via Snaptube's Polaris GraphQL + doc_id + embed DASH & video_versions.
 * 2. Facebook (Videos, Watch, Reels, fb.watch) via DASH manifest + browser_native_hd/sd + playable_url.
 * 3. TikTok (Videos, Photos, Audio) via canonical resolution + tikwm API + Universal Hydration JSON.
 * 4. X / Twitter via vxtwitter & fxtwitter APIs.
 *
 * Automatically falls back to [HeadlessWebExtractor] for any unrecognized or JS-heavy web page,
 * ensuring clean, deduplicated Music and Video format lists with exact byte sizes.
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

    override fun canHandle(url: String): Boolean {
        val host = UrlUtils.host(url)?.lowercase() ?: return false
        return host.contains("instagram.com") ||
            host.contains("tiktok.com") ||
            host.contains("twitter.com") ||
            host.contains("x.com") ||
            host.contains("facebook.com") ||
            host.contains("fb.watch") ||
            host.contains("fb.com")
    }

    override suspend fun extract(url: String): ResolvedMedia? {
        val host = UrlUtils.host(url)?.lowercase().orEmpty()
        val fastResult = runCatching {
            when {
                host.contains("instagram.com") -> extractInstagram(url)
                host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.com") -> extractFacebook(url)
                host.contains("twitter.com") || host.contains("x.com") -> extractTwitter(url)
                host.contains("tiktok.com") -> extractTikTok(url)
                else -> null
            }
        }.getOrNull()

        if (fastResult != null && fastResult.streams.isNotEmpty()) {
            return fastResult
        }

        // Fallback to Headless WebView to execute JS/GraphQL and intercept live CDN streams
        return runCatching { headlessWebExtractor.extract(url) }.getOrNull()
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

        // Method 1: Snaptube Polaris LoggedOut Desktop GraphQL API
        if (mediaId != null) {
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
                parseMediaPayload(polarisJson)
            }
        }

        // Method 2: GraphQL queries with doc_id 8845758582119845
        if (rawStreams.isEmpty()) {
            runCatching {
                val docHeaders = mapOf(
                    "X-IG-App-ID" to "1217981644879628",
                    "Referer" to "https://www.instagram.com/"
                )
                val docUrl = "https://www.instagram.com/graphql/query/?doc_id=8845758582119845&variables=" +
                    URLEncoder.encode("{\"shortcode\":\"$shortcode\"}", "UTF-8")
                val docJson = http.fetchString(docUrl, docHeaders)
                parseMediaPayload(docJson)
            }
        }

        // Method 3: Embed page captioned
        if (rawStreams.isEmpty()) {
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
            val html = runCatching { http.fetchString(embedUrl, defaultHeaders) }.getOrNull().orEmpty()
            if (html.isNotBlank()) {
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
            listOf(
                bestAudio.copy(
                    id = "ig-audio-fast",
                    container = "m4a",
                    label = "Fast",
                    headers = bestAudio.headers + mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "ig-audio-mp3",
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

        ResolvedMedia(
            sourceUrl = rawUrl,
            title = capturedTitle ?: "Instagram Reel ($shortcode)",
            thumbnailUrl = capturedThumb,
            streams = videos + audios,
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

        if (rawStreams.isEmpty()) return@coroutineScope null

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
            listOf(
                bestAudio.copy(
                    id = "fb-audio-fast",
                    container = "m4a",
                    label = "Fast",
                    headers = bestAudio.headers + mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "fb-audio-mp3",
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
        val canonicalUrl = if (rawUrl.contains("vt.tiktok.com", ignoreCase = true) || rawUrl.contains("vm.tiktok.com", ignoreCase = true) || rawUrl.contains("/t/")) {
            runCatching { http.probe(rawUrl).finalUrl }.getOrDefault(rawUrl)
        } else {
            rawUrl
        }

        // Try Method 1: tikwm API with HD enabled
        val encoded = URLEncoder.encode(canonicalUrl, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
        val jsonText = runCatching { http.fetchString(apiUrl) }.getOrNull()
        if (!jsonText.isNullOrBlank()) {
            val root = runCatching { JSONObject(jsonText) }.getOrNull()
            val data = root?.optJSONObject("data")
            if (data != null) {
                val streams = mutableListOf<MediaStream>()
                val hdPlay = data.optString("hdplay").takeIf { it.startsWith("http") }
                val play = data.optString("play").takeIf { it.startsWith("http") }
                val music = data.optString("music").takeIf { it.startsWith("http") }
                val hdSize = data.optLong("hd_size", 0L).takeIf { it > 0L }
                val playSize = data.optLong("size", 0L).takeIf { it > 0L }

                if (hdPlay != null) {
                    streams += MediaStream(
                        id = "tt-hd",
                        url = hdPlay,
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = "1080p FHD (No Watermark)",
                        height = 1080,
                        sizeBytes = hdSize,
                        isHls = false,
                        headers = mapOf("X-Internal-Quick" to "true")
                    )
                }
                if (play != null && play != hdPlay) {
                    streams += MediaStream(
                        id = "tt-sd",
                        url = play,
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = "720p HD (No Watermark)",
                        height = 720,
                        sizeBytes = playSize,
                        isHls = false,
                        headers = mapOf("X-Internal-Quick" to "true")
                    )
                }
                if (music != null) {
                    val mInfo = runCatching { http.probe(music) }.getOrNull()
                    streams += MediaStream(
                        id = "tt-audio-fast",
                        url = music,
                        type = MediaType.AUDIO,
                        container = "m4a",
                        label = "Fast",
                        sizeBytes = mInfo?.contentLength?.takeIf { it > 0 },
                        isHls = false,
                        headers = mapOf(
                            "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                            "X-Internal-Quick" to "true"
                        )
                    )
                    streams += MediaStream(
                        id = "tt-audio-mp3",
                        url = music,
                        type = MediaType.AUDIO,
                        container = "mp3",
                        label = "Classic MP3",
                        sizeBytes = mInfo?.contentLength?.takeIf { it > 0 },
                        isHls = false,
                        headers = mapOf(
                            "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                            "X-Internal-Quick" to "true"
                        )
                    )
                }

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

        // Method 2: Canonical HTML parsing for __UNIVERSAL_DATA_FOR_REHYDRATION__
        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
        val html = runCatching { http.fetchString(canonicalUrl, desktopHeaders) }.getOrNull().orEmpty()
        if (html.isNotBlank()) {
            val playAddrRegex = Regex("\"playAddr\"\\s*:\\s*\"([^\"]+)\"")
            val playUrl = playAddrRegex.find(html)?.groupValues?.getOrNull(1)?.let { unescape(it) }
            val musicRegex = Regex("\"playUrl\"\\s*:\\s*\"([^\"]+)\"")
            val musicUrl = musicRegex.find(html)?.groupValues?.getOrNull(1)?.let { unescape(it) }
            val titleRegex = Regex("<title>([^<]+)</title>")
            val title = titleRegex.find(html)?.groupValues?.getOrNull(1) ?: "TikTok Video"

            val streams = mutableListOf<MediaStream>()
            if (playUrl != null && playUrl.startsWith("http")) {
                val info = runCatching { http.probe(playUrl, desktopHeaders) }.getOrNull()
                streams += MediaStream(
                    id = "tt-page-v",
                    url = playUrl,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "High quality (720p HD)",
                    height = 720,
                    sizeBytes = info?.contentLength?.takeIf { it > 0 },
                    isHls = false
                )
            }
            if (musicUrl != null && musicUrl.startsWith("http")) {
                val mInfo = runCatching { http.probe(musicUrl, desktopHeaders) }.getOrNull()
                streams += MediaStream(
                    id = "tt-page-a",
                    url = musicUrl,
                    type = MediaType.AUDIO,
                    container = "m4a",
                    label = "Fast",
                    sizeBytes = mInfo?.contentLength?.takeIf { it > 0 },
                    isHls = false,
                    headers = mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
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
    // 4. X / TWITTER (vxtwitter + fxtwitter APIs)
    // =========================================================================

    private suspend fun extractTwitter(rawUrl: String): ResolvedMedia? = coroutineScope {
        val match = twitterStatusRegex.find(rawUrl) ?: return@coroutineScope null
        val user = match.groupValues[1]
        val statusId = match.groupValues[2]

        val apis = listOf(
            "https://api.vxtwitter.com/$user/status/$statusId",
            "https://api.fxtwitter.com/$user/status/$statusId"
        )

        for (apiUrl in apis) {
            val jsonText = runCatching { http.fetchString(apiUrl) }.getOrNull() ?: continue
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val mediaArr = root.optJSONArray("media_extended")
                ?: root.optJSONObject("tweet")?.optJSONObject("media")?.optJSONArray("videos")
                ?: continue

            val streams = mutableListOf<MediaStream>()
            var thumb: String? = null
            for (i in 0 until mediaArr.length()) {
                val item = mediaArr.optJSONObject(i) ?: continue
                val mType = item.optString("type").lowercase()
                val mUrl = item.optString("url")
                if (thumb == null) thumb = item.optString("thumbnail_url").takeIf { it.startsWith("http") }
                if ((mType == "video" || mType == "gif") && mUrl.startsWith("http")) {
                    val sizeObj = item.optJSONObject("size")
                    val w = sizeObj?.optInt("width", 0)?.takeIf { it > 0 }
                    val h = sizeObj?.optInt("height", 0)?.takeIf { it > 0 }
                    val info = runCatching { http.probe(mUrl) }.getOrNull()
                    val label = when {
                        h != null && h >= 1080 -> "${h}p FHD"
                        h != null && h >= 720 -> "${h}p HD"
                        h != null -> "${h}p"
                        else -> "HD Video"
                    }
                    streams += MediaStream(
                        id = "tw-$i",
                        url = mUrl,
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = label,
                        width = w,
                        height = h,
                        sizeBytes = info?.contentLength?.takeIf { it > 0 },
                        isHls = false
                    )
                }
            }

            if (streams.isNotEmpty()) {
                val title = root.optString("text").takeIf { it.isNotBlank() }?.take(80)
                    ?: root.optJSONObject("tweet")?.optString("text")?.take(80)
                    ?: "X Video (@$user)"

                return@coroutineScope ResolvedMedia(
                    sourceUrl = rawUrl,
                    title = title,
                    thumbnailUrl = thumb,
                    streams = streams,
                    extractor = "X / Twitter"
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
}
