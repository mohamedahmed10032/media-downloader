package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprehensive web-page media extractor: parses HTML5 <video>/<audio>/<source> tags
 * (including resolution attributes), Schema.org JSON-LD (VideoObject / AudioObject),
 * Open Graph & Twitter Player cards, and embedded progressive/HLS/DASH URLs.
 *
 * Automatically expands embedded HLS master playlists into individual resolution options
 * and probes direct stream sizes in parallel.
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
    private val resHintRegex = Regex("(?:^|[^0-9])(2160|1440|1080|720|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE)

    override fun canHandle(url: String): Boolean = url.startsWith("http")

    override suspend fun extract(url: String): ResolvedMedia? = coroutineScope {
        val html = runCatching { http.fetchString(url) }.getOrNull().orEmpty()
        if (html.isBlank()) {
            return@coroutineScope runCatching { headlessWebExtractor.extract(url) }.getOrNull()
        }
        val doc = Jsoup.parse(html, url)
        val found = LinkedHashMap<String, MediaStream>()
        val pageHeaders = mapOf("Referer" to url)
        var counter = 0

        fun add(
            rawUrl: String,
            type: MediaType,
            container: String,
            label: String,
            width: Int? = null,
            height: Int? = null,
            bitrateKbps: Int? = null,
            isHls: Boolean = false
        ) {
            if (type == MediaType.IMAGE) return
            val cleaned = com.melmeligy.mediadownloader.intercept.MediaSniffer.cleanMediaUrl(unescapeUrl(rawUrl.trim()))
            if (!cleaned.startsWith("http") || found.containsKey(cleaned.substringBefore('?'))) return
            val inferredHeight = height ?: inferResolution(cleaned, label)
            val finalLabel = when {
                label != "Video" && label.isNotBlank() -> label
                inferredHeight != null && inferredHeight >= 1080 -> "${inferredHeight}p FHD"
                inferredHeight != null && inferredHeight >= 720 -> "${inferredHeight}p HD"
                inferredHeight != null -> "${inferredHeight}p"
                else -> label
            }
            found[cleaned.substringBefore('?')] = MediaStream(
                id = "html-${counter++}",
                url = cleaned,
                type = type,
                container = container,
                label = finalLabel,
                width = width,
                height = inferredHeight,
                bitrateKbps = bitrateKbps,
                isHls = isHls,
                headers = pageHeaders
            )
        }

        // 1. JSON-LD Schema.org VideoObject / AudioObject metadata
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
                    val contentUrl = obj.optString("contentUrl").takeIf { it.startsWith("http") }
                    val encoding = obj.optString("encodingFormat").lowercase()
                    val width = obj.optInt("width", 0).takeIf { it > 0 }
                    val height = obj.optInt("height", 0).takeIf { it > 0 }
                    if (jsonLdTitle == null) jsonLdTitle = obj.optString("name").takeIf { it.isNotBlank() }
                    if (jsonLdThumb == null) jsonLdThumb = obj.optString("thumbnailUrl").takeIf { it.startsWith("http") }
                    if (contentUrl != null) {
                        when {
                            contentUrl.contains(".m3u8") || encoding.contains("mpegurl") ->
                                add(contentUrl, MediaType.VIDEO, "ts", "HLS (auto)", width, height, isHls = true)
                            encoding.startsWith("audio/") ->
                                add(contentUrl, MediaType.AUDIO, containerFor(contentUrl, "m4a"), "Original Audio")
                            else ->
                                add(contentUrl, MediaType.VIDEO, containerFor(contentUrl, "mp4"), "Video", width, height)
                        }
                    }
                }
            }
        }

        // 2. Explicit HTML5 media elements with quality attributes
        doc.select("video[src]").forEach { el ->
            val src = el.absUrl("src")
            add(src, MediaType.VIDEO, containerFor(src, "mp4"), qualityAttr(el, "Video"))
        }
        doc.select("video source[src]").forEach { el ->
            val src = el.absUrl("src")
            add(src, MediaType.VIDEO, containerFor(src, "mp4"), qualityAttr(el, "Video"))
        }
        doc.select("audio[src]").forEach { el ->
            val src = el.absUrl("src")
            add(src, MediaType.AUDIO, containerFor(src, "mp3"), qualityAttr(el, "Audio"))
        }
        doc.select("audio source[src]").forEach { el ->
            val src = el.absUrl("src")
            add(src, MediaType.AUDIO, containerFor(src, "mp3"), qualityAttr(el, "Audio"))
        }
        doc.select("source[src]").forEach { el ->
            val src = el.absUrl("src")
            val type = el.attr("type").lowercase()
            val qLabel = qualityAttr(el, "")
            when {
                type.contains("mpegurl") || src.contains(".m3u8") ->
                    add(src, MediaType.VIDEO, "ts", qLabel.ifBlank { "HLS (auto)" }, isHls = true)
                type.startsWith("video/") ->
                    add(src, MediaType.VIDEO, containerFor(src, type.substringAfter('/')), qLabel.ifBlank { "Video" })
                type.startsWith("audio/") ->
                    add(src, MediaType.AUDIO, containerFor(src, type.substringAfter('/')), qLabel.ifBlank { "Audio" })
                else -> classifyByExtension(src)?.let { (t, c) -> add(src, t, c, qLabel.ifBlank { defaultLabel(t) }) }
            }
        }

        // 3. Open Graph / Twitter card media (video & audio only; ignore iframe /embed/ HTML pages)
        val ogWidth = doc.select("meta[property=og:video:width]").attr("content").toIntOrNull()
        val ogHeight = doc.select("meta[property=og:video:height]").attr("content").toIntOrNull()
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream]")
            .forEach {
                val content = it.attr("abs:content").ifBlank { it.attr("content") }
                if (!content.contains("/embed/", ignoreCase = true) && !content.contains("youtube.com/v/", ignoreCase = true)) {
                    add(content, MediaType.VIDEO, containerFor(content, "mp4"), "Video", ogWidth, ogHeight)
                }
            }
        doc.select("meta[property=og:audio], meta[property=og:audio:url], meta[property=og:audio:secure_url]")
            .forEach {
                val content = it.attr("abs:content").ifBlank { it.attr("content") }
                add(content, MediaType.AUDIO, containerFor(content, "mp3"), "Audio")
            }

        // 4. Embedded URLs in scripts/attributes (including JSON-escaped URLs)
        val unescapedHtml = unescapeUrl(html)
        val hlsUrls = m3u8Regex.findAll(unescapedHtml).map { it.value }.distinct().take(3).toList()
        for (hlsUrl in hlsUrls) {
            val expanded = runCatching { hlsExtractor.extract(hlsUrl, pageHeaders) }.getOrNull()
            if (expanded != null && expanded.streams.isNotEmpty()) {
                expanded.streams.forEach { variant ->
                    val k = com.melmeligy.mediadownloader.intercept.MediaSniffer.dedupeKey(variant.url)
                    if (!found.containsKey(k)) {
                        found[k] = variant.copy(id = "html-${counter++}", headers = pageHeaders)
                    }
                }
            } else {
                add(hlsUrl, MediaType.VIDEO, "ts", "HLS (auto)", isHls = true)
            }
        }

        val mpdUrls = mpdRegex.findAll(unescapedHtml).map { it.value }.distinct().take(2).toList()
        for (mpdUrl in mpdUrls) {
            val expanded = runCatching { dashExtractor.extract(mpdUrl, pageHeaders) }.getOrNull()
            expanded?.streams?.forEach { variant ->
                val k = com.melmeligy.mediadownloader.intercept.MediaSniffer.dedupeKey(variant.url)
                if (!found.containsKey(k)) {
                    found[k] = variant.copy(id = "html-${counter++}", headers = pageHeaders)
                }
            }
        }

        directRegex.findAll(unescapedHtml).forEach { match ->
            classifyByExtension(match.value)?.let { (t, c) ->
                val label = when {
                    match.value.contains("hd", ignoreCase = true) -> "High quality (720p)"
                    match.value.contains("sd", ignoreCase = true) -> "Fast (360p)"
                    else -> defaultLabel(t)
                }
                add(match.value, t, c, label)
            }
        }

        if (found.isEmpty()) {
            // Static HTML had no video/audio streams -> run Headless WebView to catch JS/GraphQL streams!
            return@coroutineScope runCatching { headlessWebExtractor.extract(url) }.getOrNull()
        }

        val thumbnail = doc.select("meta[property=og:image]").attr("abs:content")
            .ifBlank { doc.select("meta[name=twitter:image]").attr("abs:content") }
            .ifBlank { jsonLdThumb.orEmpty() }
            .ifBlank { null }
        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { jsonLdTitle.orEmpty() }
            .ifBlank { doc.title() }
            .ifBlank { UrlUtils.host(url) ?: "Media" }

        // 5. Enrich direct streams with size estimates concurrently and discard HTML embed pages
        val rawStreams = found.values.toList()
        val streams = rawStreams.mapIndexed { i, stream ->
            async {
                if (!stream.isHls && i < MAX_SIZE_PROBES) {
                    runCatching {
                        val info = http.probe(stream.url, stream.headers)
                        val ct = info.contentType?.lowercase().orEmpty()
                        if (ct.startsWith("text/html") || ct.startsWith("application/xhtml") || ct.startsWith("application/json")) {
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

        if (streams.isEmpty()) {
            return@coroutineScope runCatching { headlessWebExtractor.extract(url) }.getOrNull()
        }

        val sortedStreams = streams.sortedWith(
            compareBy<MediaStream> { it.type.ordinal }
                .thenByDescending { it.height ?: it.bitrateKbps ?: 0 }
                .thenByDescending { it.sizeBytes ?: 0L }
        )

        ResolvedMedia(
            sourceUrl = url,
            title = title,
            thumbnailUrl = thumbnail,
            streams = sortedStreams,
            extractor = name
        )
    }

    private fun qualityAttr(el: org.jsoup.nodes.Element, fallback: String): String {
        val raw = el.attr("label")
            .ifBlank { el.attr("res") }
            .ifBlank { el.attr("size") }
            .ifBlank { el.attr("data-res") }
            .ifBlank { el.attr("data-quality") }
            .ifBlank { el.attr("title") }
            .trim()
        if (raw.isBlank()) return fallback
        return if (raw.all { it.isDigit() }) "${raw}p" else raw
    }

    private fun inferResolution(url: String, label: String): Int? {
        resHintRegex.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        resHintRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
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
        const val MAX_SIZE_PROBES = 8
    }
}

