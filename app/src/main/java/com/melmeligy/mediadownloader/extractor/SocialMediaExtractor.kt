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
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * High-priority extractor for major social video platforms (Instagram Reels/Posts/TV,
 * TikTok, X/Twitter, Facebook/Watch) with automatic fallback to [HeadlessWebExtractor]
 * so shared links resolve real Video and Audio formats with exact byte sizes immediately
 * inside the floating Share sheet.
 */
@Singleton
class SocialMediaExtractor @Inject constructor(
    private val http: ExtractorHttp,
    private val headlessWebExtractor: HeadlessWebExtractor
) : MediaExtractor {

    override val name = "Social Media"
    override val priority = 95

    private val igShortcodeRegex =
        Regex("instagram\\.com/(?:[^/]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
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

    // ---- Instagram (Embed + DASH manifest + video_versions + GraphQL) ----

    private suspend fun extractInstagram(rawUrl: String): ResolvedMedia? = coroutineScope {
        val resolvedUrl = if (rawUrl.contains("/share/", ignoreCase = true)) {
            runCatching { http.probe(rawUrl).finalUrl }.getOrDefault(rawUrl)
        } else {
            rawUrl
        }

        val shortcode = igShortcodeRegex.find(resolvedUrl)?.groupValues?.getOrNull(1)
            ?: return@coroutineScope null

        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
        val headers = mapOf(
            "Referer" to "https://www.instagram.com/",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        val html = runCatching { http.fetchString(embedUrl, headers) }.getOrNull().orEmpty()
        if (html.isBlank()) return@coroutineScope null

        val doc = Jsoup.parse(html, embedUrl)
        val rawStreams = LinkedHashMap<String, MediaStream>()
        var counter = 0

        fun addCandidate(
            candidateUrl: String,
            type: MediaType,
            container: String,
            label: String,
            width: Int? = null,
            height: Int? = null,
            bitrateKbps: Int? = null
        ) {
            val cleaned = MediaSniffer.cleanMediaUrl(unescape(candidateUrl))
            if (!cleaned.startsWith("http")) return
            val key = cleaned.substringBefore('?')
            if (rawStreams.containsKey(key)) return
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
                headers = headers
            )
        }

        // 1. Parse embedded dash_manifest XML if present (provides separate Video resolutions + M4A Audio!)
        val unescapedHtml = unescape(html)
        val dashRegex = Regex("<MPD[\\s\\S]*?</MPD>", RegexOption.IGNORE_CASE)
        dashRegex.findAll(unescapedHtml).forEach { match ->
            parseDashXml(match.value).forEach { s ->
                addCandidate(s.url, s.type, s.container, s.label, s.width, s.height, s.bitrateKbps)
            }
        }

        // 2. Parse video_versions JSON array [{width, height, url}]
        val versionsRegex = Regex("\"video_versions\"\\s*:\\s*(\\[[^\\]]+\\])")
        versionsRegex.findAll(unescapedHtml).forEach { match ->
            runCatching {
                val arr = JSONArray(match.groupValues[1])
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val vUrl = obj.optString("url")
                    val w = obj.optInt("width", 0).takeIf { it > 0 }
                    val h = obj.optInt("height", 0).takeIf { it > 0 }
                    val label = when {
                        h != null && h >= 1080 -> "${h}p FHD"
                        h != null && h >= 720 -> "${h}p HD"
                        h != null -> "${h}p"
                        i == 0 -> "HD Video"
                        else -> "SD Video"
                    }
                    addCandidate(vUrl, MediaType.VIDEO, "mp4", label, w, h)
                }
            }
        }

        // 3. Parse direct video_url fields
        val videoUrlRegex = Regex("\"video_url\"\\s*:\\s*\"([^\"]+)\"")
        videoUrlRegex.findAll(unescapedHtml).forEach { match ->
            addCandidate(match.groupValues[1], MediaType.VIDEO, "mp4", "HD Video")
        }

        // 4. Parse explicit <video class="EmbeddedMediaVideo" src="...">
        doc.select("video[src], video source[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            addCandidate(src, MediaType.VIDEO, "mp4", "HD Video")
        }

        if (rawStreams.isEmpty()) return@coroutineScope null

        // Probe exact file sizes in parallel
        val probed = rawStreams.values.take(8).map { stream ->
            async {
                runCatching {
                    val info = http.probe(stream.url, headers)
                    if (info.contentLength > 0) stream.copy(sizeBytes = info.contentLength) else stream
                }.getOrDefault(stream)
            }
        }.awaitAll().filter { (it.sizeBytes ?: Long.MAX_VALUE) > 10_000L }

        if (probed.isEmpty()) return@coroutineScope null

        val videos = probed.filter { it.type == MediaType.VIDEO }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val audios = probed.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }

        val title = doc.select(".CaptionUsername").text().takeIf { it.isNotBlank() }?.let { "Instagram Reel (@$it)" }
            ?: doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?: "Instagram Reel ($shortcode)"

        val thumb = doc.select("img.EmbeddedMediaImage").attr("src")
            .ifBlank { doc.select("meta[property=og:image]").attr("content") }
            .let { unescape(it) }
            .takeIf { it.startsWith("http") }

        ResolvedMedia(
            sourceUrl = rawUrl,
            title = title,
            thumbnailUrl = thumb,
            streams = videos + audios,
            extractor = "Instagram"
        )
    }

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
            val baseUrl = firstChildText(rep, "BaseURL") ?: continue

            val isAudio = mime.startsWith("audio") || codecs.contains("mp4a") || MediaSniffer.isAudioTrackUrl(baseUrl, mime)
            val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
            val container = if (isAudio) "m4a" else "mp4"
            val width = rep.getAttribute("width").toIntOrNull()?.takeIf { it > 0 }
            val height = rep.getAttribute("height").toIntOrNull()?.takeIf { it > 0 }
            val bandwidth = rep.getAttribute("bandwidth").toIntOrNull()?.takeIf { it > 0 }
            val bitrateKbps = bandwidth?.div(1000)

            val label = when {
                type == MediaType.AUDIO && bitrateKbps != null -> "Audio (${bitrateKbps} kbps)"
                type == MediaType.AUDIO -> "Original Audio (M4A)"
                height != null && height >= 1080 -> "${height}p FHD"
                height != null && height >= 720 -> "${height}p HD"
                height != null -> "${height}p"
                else -> "Video"
            }

            list += MediaStream(
                id = "ig-dash-$i",
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

    // ---- X / Twitter (via api.vxtwitter.com JSON API) ----

    private suspend fun extractTwitter(rawUrl: String): ResolvedMedia? = coroutineScope {
        val match = twitterStatusRegex.find(rawUrl) ?: return@coroutineScope null
        val user = match.groupValues[1]
        val statusId = match.groupValues[2]
        val apiUrl = "https://api.vxtwitter.com/$user/status/$statusId"

        val jsonText = runCatching { http.fetchString(apiUrl) }.getOrNull() ?: return@coroutineScope null
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@coroutineScope null
        val mediaArr = root.optJSONArray("media_extended") ?: return@coroutineScope null

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

        if (streams.isEmpty()) return@coroutineScope null
        val title = root.optString("text").takeIf { it.isNotBlank() }?.take(80) ?: "X Video (@$user)"

        ResolvedMedia(
            sourceUrl = rawUrl,
            title = title,
            thumbnailUrl = thumb,
            streams = streams,
            extractor = "X / Twitter"
        )
    }

    // ---- TikTok (tikwm API + universal hydration JSON) ----

    private suspend fun extractTikTok(rawUrl: String): ResolvedMedia? = coroutineScope {
        val encoded = java.net.URLEncoder.encode(rawUrl, "UTF-8")
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
                        isHls = false
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
                        isHls = false
                    )
                }
                if (music != null) {
                    val mInfo = runCatching { http.probe(music) }.getOrNull()
                    streams += MediaStream(
                        id = "tt-audio",
                        url = music,
                        type = MediaType.AUDIO,
                        container = "mp3",
                        label = "Original Audio (MP3)",
                        sizeBytes = mInfo?.contentLength?.takeIf { it > 0 },
                        isHls = false
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
        null
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
