package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves HLS (.m3u8) playlists into selectable quality variants.
 *
 * Master playlists expose one stream per `#EXT-X-STREAM-INF` variant; media playlists
 * expose a single "Auto" stream. Actual segment download/concatenation happens in the
 * download engine (unencrypted MPEG-TS only — encrypted/DRM streams are declined there).
 */
@Singleton
class HlsExtractor @Inject constructor(
    private val http: ExtractorHttp
) : MediaExtractor {

    override val name = "HLS stream"
    override val priority = 90

    override fun canHandle(url: String): Boolean {
        val ext = UrlUtils.fileExtension(url)
        return ext == "m3u8" || url.contains(".m3u8", ignoreCase = true)
    }

    override suspend fun extract(url: String): ResolvedMedia? = extract(url, emptyMap())

    /**
     * Header-aware variant used by the interception layer. [headers] are the live request
     * headers captured from the WebView and are attached to every resolved variant so the
     * download engine can replay the request.
     */
    suspend fun extract(url: String, headers: Map<String, String>): ResolvedMedia? {
        val text = http.fetchString(url, headers)
        if (!text.contains("#EXTM3U")) return null

        val streams = mutableListOf<MediaStream>()

        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var index = 0
            var variant = 0
            while (index < lines.size) {
                val line = lines[index].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    var uriLine = index + 1
                    while (uriLine < lines.size &&
                        (lines[uriLine].trim().isEmpty() || lines[uriLine].trim().startsWith("#"))
                    ) uriLine++
                    if (uriLine < lines.size) {
                        val variantUrl = UrlUtils.resolveUrl(url, lines[uriLine].trim())
                        val resolution = attrs["RESOLUTION"]
                        val width = resolution?.substringBefore('x')?.toIntOrNull()
                        val height = resolution?.substringAfter('x')?.toIntOrNull()
                        val bandwidth = attrs["BANDWIDTH"]?.toIntOrNull()
                        val label = when {
                            height != null -> "${height}p"
                            bandwidth != null -> "${bandwidth / 1000} kbps"
                            else -> "Variant ${variant + 1}"
                        }
                        streams += MediaStream(
                            id = "hls-$variant",
                            url = variantUrl,
                            type = MediaType.VIDEO,
                            container = "ts",
                            label = label,
                            width = width,
                            height = height,
                            bitrateKbps = bandwidth?.div(1000),
                            isHls = true,
                            headers = headers
                        )
                        variant++
                        index = uriLine
                    }
                }
                index++
            }
        }

        if (streams.isEmpty()) {
            if (text.contains("#EXTINF")) {
                streams += MediaStream(
                    id = "hls-auto",
                    url = url,
                    type = MediaType.VIDEO,
                    container = "ts",
                    label = "Auto (HLS)",
                    isHls = true,
                    headers = headers
                )
            } else {
                return null
            }
        }

        val sorted = streams.sortedByDescending { it.height ?: it.bitrateKbps ?: 0 }
        val title = UrlUtils.host(url)?.let { "HLS video ($it)" } ?: "HLS video"
        return ResolvedMedia(sourceUrl = url, title = title, streams = sorted, extractor = name)
    }

    /** Parses `KEY=VALUE,KEY="quoted,value"` attribute lists, respecting quotes. */
    private fun parseAttributes(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        regex.findAll(raw).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2].trim('"')
        }
        return result
    }
}
