package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/**
 * Resolves MPEG-DASH manifests (`.mpd`, `application/dash+xml`) into selectable variants.
 *
 * Only self-contained representations are offered: a `Representation` is downloadable when
 * it exposes a `BaseURL` (single-file DASH). Representations that rely on
 * `SegmentTemplate`/`SegmentList` are declined with a typed error rather than producing a
 * task that the download engine cannot fulfil. Encrypted manifests
 * (`ContentProtection`) are always declined.
 */
@Singleton
class DashExtractor @Inject constructor(
    private val http: ExtractorHttp
) : MediaExtractor {

    override val name = "DASH stream"
    override val priority = 88

    private val isoDurationRegex = Regex(
        """PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""",
        RegexOption.IGNORE_CASE
    )

    override fun canHandle(url: String): Boolean {
        if (ExtractorRegistry.isConfirmedHtml(url)) return false
        val ext = UrlUtils.fileExtension(url)
        return ext == "mpd" || url.contains(".mpd", ignoreCase = true)
    }

    override suspend fun extract(url: String): ResolvedMedia? = extract(url, emptyMap())

    /** Header-aware variant used by the interception layer. */
    suspend fun extract(url: String, headers: Map<String, String>): ResolvedMedia? {
        if (ExtractorRegistry.isConfirmedHtml(url)) return null
        val xml = http.fetchString(url, headers)
        if (!xml.contains("<MPD", ignoreCase = true)) return null

        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null

        if (document.getElementsByTagName("ContentProtection").length > 0) {
            throw MediaException(AppError.UNSUPPORTED_SITE, "DRM-protected DASH manifest")
        }

        val mpdDurationSec = parseIsoDurationSeconds(
            document.documentElement?.getAttribute("mediaPresentationDuration")
        )
        val headerDurationSec = headers["X-Internal-Duration-Seconds"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
        val totalDurationSec = if (mpdDurationSec > 0L) mpdDurationSec else headerDurationSec

        val manifestBase = document.documentElement
            ?.let { firstChildText(it, "BaseURL") }
            ?.let { UrlUtils.resolveUrl(url, it) }
            ?: url

        val streams = mutableListOf<MediaStream>()
        var templateOnly = false
        val representations = document.getElementsByTagName("Representation")

        for (index in 0 until representations.length) {
            val representation = representations.item(index) as? Element ?: continue
            val adaptation = representation.parentNode as? Element

            val mime = representation.getAttribute("mimeType").ifBlank {
                adaptation?.getAttribute("mimeType").orEmpty()
            }.lowercase()

            val type = when {
                mime.startsWith("video") -> MediaType.VIDEO
                mime.startsWith("audio") -> MediaType.AUDIO
                else -> continue
            }

            val baseUrl = firstChildText(representation, "BaseURL")
            if (baseUrl.isNullOrBlank()) {
                templateOnly = true
                continue
            }

            var width = representation.getAttribute("width").toIntOrNull()?.takeIf { it > 0 }
            var height = representation.getAttribute("height").toIntOrNull()?.takeIf { it > 0 }
            if (type == MediaType.VIDEO) {
                if (width != null && height != null && height > width) {
                    // Portrait video (e.g. 720x1280 -> 720p)
                    height = width
                } else if (height != null && width == null) {
                    width = (height * 16) / 9
                }
            }
            val bandwidth = representation.getAttribute("bandwidth").toIntOrNull()?.takeIf { it > 0 }
            val estSizeBytes = if (bandwidth != null && totalDurationSec > 0L) {
                (bandwidth.toLong() * totalDurationSec) / 8L
            } else null

            val container = when {
                mime.contains("webm") -> "webm"
                type == MediaType.AUDIO -> "m4a"
                else -> "mp4"
            }
            val label = when {
                type == MediaType.AUDIO && bandwidth != null -> "Audio ${bandwidth / 1000} kbps"
                type == MediaType.AUDIO -> "Audio"
                height != null -> "${height}p"
                bandwidth != null -> "${bandwidth / 1000} kbps"
                else -> "Variant ${index + 1}"
            }

            val streamHeaders = LinkedHashMap<String, String>(headers).apply {
                if (totalDurationSec > 0L) put("X-Internal-Duration-Seconds", totalDurationSec.toString())
                if (estSizeBytes != null && estSizeBytes > 0L) put("X-Internal-Expected-Bytes", estSizeBytes.toString())
            }

            streams += MediaStream(
                id = "dash-$index",
                url = UrlUtils.resolveUrl(manifestBase, baseUrl.trim()),
                type = type,
                container = container,
                label = label,
                width = width,
                height = height,
                bitrateKbps = bandwidth?.div(1000),
                sizeBytes = estSizeBytes,
                isHls = false,
                headers = streamHeaders
            )
        }

        if (streams.isEmpty()) {
            if (templateOnly) {
                throw MediaException(
                    AppError.UNSUPPORTED_SITE,
                    "DASH manifest uses segment templates, which this build cannot assemble"
                )
            }
            return null
        }

        val mp4Audio = streams.filter { it.type == MediaType.AUDIO && it.container != "webm" }
            .maxByOrNull { it.bitrateKbps ?: 0 }
        val webmAudio = streams.filter { it.type == MediaType.AUDIO && it.container == "webm" }
            .maxByOrNull { it.bitrateKbps ?: 0 }
        val anyAudio = mp4Audio ?: webmAudio

        val enrichedStreams = streams.map { s ->
            if (s.type == MediaType.VIDEO) {
                val companion = if (s.container == "webm") webmAudio else (mp4Audio ?: anyAudio)
                if (companion != null) {
                    val extra = LinkedHashMap<String, String>(s.headers)
                    extra["X-Internal-Companion-Audio"] = companion.url
                    s.sizeBytes?.let { extra["X-Internal-Video-Bytes"] = it.toString() }
                    companion.sizeBytes?.let { extra["X-Internal-Audio-Bytes"] = it.toString() }
                    s.copy(headers = extra)
                } else s
            } else {
                s
            }
        }

        val sorted = enrichedStreams.sortedByDescending { it.height ?: it.bitrateKbps ?: 0 }
        val title = UrlUtils.host(url)?.let { "DASH video ($it)" } ?: "DASH video"
        return ResolvedMedia(sourceUrl = url, title = title, streams = sorted, extractor = name)
    }

    private fun parseIsoDurationSeconds(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val m = isoDurationRegex.find(raw.trim()) ?: return 0L
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return (h * 3600.0 + min * 60.0 + s).roundToLong().coerceAtLeast(0L)
    }

    /** Returns the text of the first direct child named [tag], or null. */
    private fun firstChildText(parent: Element, tag: String): String? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName.equals(tag, ignoreCase = true)) {
                return node.textContent?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
