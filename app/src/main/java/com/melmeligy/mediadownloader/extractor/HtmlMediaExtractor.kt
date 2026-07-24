package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic web-page scraper: parses HTML for <video>/<audio>/<source> tags, Open Graph
 * media metadata, and direct media/HLS URLs embedded in the page. This is the fallback
 * that gives the app broad coverage of ordinary sites that embed or link media the user
 * is entitled to download. It does not log in, bypass paywalls, or defeat access controls.
 */
@Singleton
class HtmlMediaExtractor @Inject constructor(
    private val http: ExtractorHttp
) : MediaExtractor {

    override val name = "Web page"
    override val priority = 50

    private val m3u8Regex = Regex("https?://[^\"'\\s<>]+?\\.m3u8[^\"'\\s<>]*")
    private val directRegex =
        Regex("https?://[^\"'\\s<>]+?\\.(mp4|m4a|mp3|webm|aac|ogg|mov)([^\"'\\s<>]*)")

    override fun canHandle(url: String): Boolean = url.startsWith("http")

    override suspend fun extract(url: String): ResolvedMedia? {
        val html = http.fetchString(url)
        val doc = Jsoup.parse(html, url)
        val found = LinkedHashMap<String, MediaStream>()
        var counter = 0

        fun add(rawUrl: String, type: MediaType, container: String, label: String, isHls: Boolean = false) {
            val abs = rawUrl.trim()
            if (!abs.startsWith("http") || found.containsKey(abs)) return
            found[abs] = MediaStream(
                id = "html-${counter++}",
                url = abs,
                type = type,
                container = container,
                label = label,
                isHls = isHls
            )
        }

        // Explicit media elements.
        doc.select("video[src]").forEach { add(it.absUrl("src"), MediaType.VIDEO, containerFor(it.absUrl("src"), "mp4"), "Video") }
        doc.select("video source[src]").forEach { add(it.absUrl("src"), MediaType.VIDEO, containerFor(it.absUrl("src"), "mp4"), "Video") }
        doc.select("audio[src]").forEach { add(it.absUrl("src"), MediaType.AUDIO, containerFor(it.absUrl("src"), "mp3"), "Audio") }
        doc.select("audio source[src]").forEach { add(it.absUrl("src"), MediaType.AUDIO, containerFor(it.absUrl("src"), "mp3"), "Audio") }
        doc.select("source[src]").forEach { el ->
            val src = el.absUrl("src")
            val type = el.attr("type").lowercase()
            when {
                type.contains("mpegurl") || src.contains(".m3u8") ->
                    add(src, MediaType.VIDEO, "ts", "HLS (auto)", isHls = true)
                type.startsWith("video/") -> add(src, MediaType.VIDEO, containerFor(src, type.substringAfter('/')), "Video")
                type.startsWith("audio/") -> add(src, MediaType.AUDIO, containerFor(src, type.substringAfter('/')), "Audio")
                else -> classifyByExtension(src)?.let { (t, c) -> add(src, t, c, defaultLabel(t)) }
            }
        }

        // Open Graph / Twitter card media.
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream]")
            .forEach { add(it.attr("abs:content"), MediaType.VIDEO, containerFor(it.attr("abs:content"), "mp4"), "Video") }
        doc.select("meta[property=og:image], meta[property=og:image:url], meta[name=twitter:image]")
            .forEach { add(it.attr("abs:content"), MediaType.IMAGE, containerFor(it.attr("abs:content"), "jpg"), "Image") }

        // Embedded URLs in scripts/attributes.
        m3u8Regex.findAll(html).forEach { add(it.value, MediaType.VIDEO, "ts", "HLS (auto)", isHls = true) }
        directRegex.findAll(html).forEach { match ->
            classifyByExtension(match.value)?.let { (t, c) -> add(match.value, t, c, defaultLabel(t)) }
        }

        if (found.isEmpty()) return null

        val thumbnail = doc.select("meta[property=og:image]").attr("abs:content")
            .ifBlank { doc.select("meta[name=twitter:image]").attr("abs:content") }
            .ifBlank { null }
        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }
            .ifBlank { UrlUtils.host(url) ?: "Media" }

        // Enrich a handful of direct streams with size estimates for the quality screen.
        val streams = found.values.toList().mapIndexed { i, stream ->
            if (!stream.isHls && i < MAX_SIZE_PROBES) {
                runCatching {
                    val info = http.probe(stream.url)
                    if (info.contentLength > 0) stream.copy(sizeBytes = info.contentLength) else stream
                }.getOrDefault(stream)
            } else stream
        }

        return ResolvedMedia(
            sourceUrl = url,
            title = title,
            thumbnailUrl = thumbnail,
            streams = streams,
            extractor = name
        )
    }

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
        const val MAX_SIZE_PROBES = 6
    }
}
