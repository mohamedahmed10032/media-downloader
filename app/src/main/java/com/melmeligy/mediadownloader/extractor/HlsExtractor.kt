package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import kotlin.math.roundToLong
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

    private val extInfRegex = Regex("""#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val resRegex = Regex("(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE)

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
            val rawVariants = mutableListOf<RawVariant>()
            val rawAudioRenditions = mutableListOf<Map<String, String>>()

            while (index < lines.size) {
                val line = lines[index].trim()
                if (line.startsWith("#EXT-X-MEDIA")) {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    if (attrs["TYPE"]?.uppercase() == "AUDIO") {
                        rawAudioRenditions += attrs
                    }
                } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    var uriLine = index + 1
                    while (uriLine < lines.size &&
                        (lines[uriLine].trim().isEmpty() || lines[uriLine].trim().startsWith("#"))
                    ) uriLine++
                    if (uriLine < lines.size) {
                        val variantUrl = UrlUtils.resolveUrl(url, lines[uriLine].trim())
                        rawVariants += RawVariant(variantUrl = variantUrl, attrs = attrs)
                        index = uriLine
                    }
                }
                index++
            }

            // Probe first variant playlist to calculate total duration from #EXTINF segments
            val firstVariantUrl = rawVariants.firstOrNull()?.variantUrl
            val variantText = if (firstVariantUrl != null) {
                runCatching { http.fetchString(firstVariantUrl, headers) }.getOrNull()
            } else null

            val totalDurationSeconds = if (!variantText.isNullOrBlank()) {
                extInfRegex.findAll(variantText).sumOf {
                    it.groupValues[1].toDoubleOrNull() ?: 0.0
                }.roundToLong()
            } else 0L

            val streamHeaders = if (totalDurationSeconds > 0L) {
                headers + ("X-Internal-Duration-Seconds" to totalDurationSeconds.toString())
            } else {
                headers
            }

            rawVariants.forEachIndexed { variantIdx, v ->
                val resolution = v.attrs["RESOLUTION"]
                var width = resolution?.substringBefore('x')?.toIntOrNull()
                var height = resolution?.substringAfter('x')?.toIntOrNull()
                val bandwidth = v.attrs["BANDWIDTH"]?.toIntOrNull()
                val avgBandwidth = v.attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull()
                val effectiveBandwidth = avgBandwidth ?: bandwidth

                if (height == null) {
                    val nameHeight = v.attrs["NAME"]?.let { resRegex.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    val urlHeight = resRegex.find(v.variantUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val bwHeight = when {
                        effectiveBandwidth == null -> null
                        effectiveBandwidth >= 2_500_000 -> 1080
                        effectiveBandwidth >= 1_500_000 -> 720
                        effectiveBandwidth >= 700_000 -> 480
                        effectiveBandwidth >= 300_000 -> 360
                        else -> 240
                    }
                    height = nameHeight ?: urlHeight ?: bwHeight
                    if (height != null) {
                        width = (height * 16) / 9
                    }
                }

                val label = when {
                    height != null -> "${height}p"
                    effectiveBandwidth != null -> "${effectiveBandwidth / 1000} kbps"
                    else -> "Variant ${variantIdx + 1}"
                }

                val sizeBytes = if (effectiveBandwidth != null && totalDurationSeconds > 0L) {
                    (effectiveBandwidth.toLong() * totalDurationSeconds) / 8L
                } else null

                streams += MediaStream(
                    id = "hls-$variantIdx",
                    url = v.variantUrl,
                    type = MediaType.VIDEO,
                    container = "ts",
                    label = label,
                    width = width,
                    height = height,
                    bitrateKbps = effectiveBandwidth?.div(1000),
                    sizeBytes = sizeBytes,
                    isHls = true,
                    headers = streamHeaders
                )
            }

            // Expose dedicated audio tracks if advertised via #EXT-X-MEDIA:TYPE=AUDIO
            rawAudioRenditions.forEachIndexed { audioIdx, attrs ->
                val uri = attrs["URI"]
                if (!uri.isNullOrBlank()) {
                    val audioUrl = UrlUtils.resolveUrl(url, uri)
                    val name = attrs["NAME"] ?: attrs["GROUP-ID"] ?: "Audio"
                    val lang = attrs["LANGUAGE"]
                    val label = if (!lang.isNullOrBlank()) "$name ($lang)" else name
                    val audioBw = attrs["BANDWIDTH"]?.toIntOrNull() ?: 128_000
                    val audioSizeBytes = if (totalDurationSeconds > 0L) {
                        (audioBw.toLong() * totalDurationSeconds) / 8L
                    } else null

                    streams += MediaStream(
                        id = "hls-audio-$audioIdx",
                        url = audioUrl,
                        type = MediaType.AUDIO,
                        container = "m4a",
                        label = label,
                        bitrateKbps = audioBw / 1000,
                        sizeBytes = audioSizeBytes,
                        isHls = true,
                        headers = streamHeaders
                    )
                }
            }
        }

        if (streams.isEmpty()) {
            if (text.contains("#EXTINF")) {
                val totalDurationSeconds = extInfRegex.findAll(text).sumOf {
                    it.groupValues[1].toDoubleOrNull() ?: 0.0
                }.roundToLong()

                val inferredHeight = resRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val inferredWidth = inferredHeight?.let { (it * 16) / 9 }

                // Find first segment
                val lines = text.lines()
                var seg0Url: String? = null
                var seg0Duration: Double? = null
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXTINF")) {
                        seg0Duration = extInfRegex.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                        var j = i + 1
                        while (j < lines.size && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) {
                            j++
                        }
                        if (j < lines.size) {
                            seg0Url = UrlUtils.resolveUrl(url, lines[j].trim())
                        }
                        break
                    }
                }

                var sizeBytes: Long? = null
                var bitrateKbps: Int? = null

                if (seg0Url != null) {
                    val probe = runCatching { http.probe(seg0Url, headers) }.getOrNull()
                    val seg0Length = probe?.contentLength?.takeIf { it > 0 }
                    if (seg0Length != null && seg0Duration != null && seg0Duration > 0.0 && totalDurationSeconds > 0L) {
                        val byteRate = seg0Length.toDouble() / seg0Duration
                        sizeBytes = (byteRate * totalDurationSeconds).toLong()
                        bitrateKbps = ((byteRate * 8) / 1000).toInt()
                    }
                }

                if (sizeBytes == null && totalDurationSeconds > 0L) {
                    val estBitrate = when (inferredHeight) {
                        null -> 1000
                        2160 -> 12000
                        1440 -> 6000
                        1080 -> 2500
                        720 -> 1500
                        480 -> 800
                        360 -> 450
                        else -> 300
                    }
                    bitrateKbps = estBitrate
                    sizeBytes = (estBitrate * 1000L * totalDurationSeconds) / 8L
                }

                val streamHeaders = if (totalDurationSeconds > 0L) {
                    headers + ("X-Internal-Duration-Seconds" to totalDurationSeconds.toString())
                } else {
                    headers
                }

                val label = when {
                    inferredHeight != null -> "${inferredHeight}p"
                    else -> "Auto (HLS)"
                }

                streams += MediaStream(
                    id = "hls-auto",
                    url = url,
                    type = MediaType.VIDEO,
                    container = "ts",
                    label = label,
                    width = inferredWidth,
                    height = inferredHeight,
                    bitrateKbps = bitrateKbps,
                    sizeBytes = sizeBytes,
                    isHls = true,
                    headers = streamHeaders
                )
            } else {
                return null
            }
        }

        val sortedVideos = streams.filter { it.type == MediaType.VIDEO }
            .sortedByDescending { it.height ?: it.bitrateKbps ?: 0 }
        val sortedAudios = streams.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: 0 }
        val sorted = sortedVideos + sortedAudios
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

    private data class RawVariant(
        val variantUrl: String,
        val attrs: Map<String, String>
    )
}
