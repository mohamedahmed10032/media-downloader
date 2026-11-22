package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
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
 * Master playlists expose one stream per `#EXT-X-STREAM-INF` variant (preferring H.264/AVC
 * when multiple codecs share a resolution for universal Android MediaMuxer compatibility,
 * and attaching companion audio renditions when `#EXT-X-MEDIA:TYPE=AUDIO` is separate);
 * media playlists expose exact byte sizes from `#EXT-X-BYTERANGE` when present or probe
 * segment bitrates.
 */
@Singleton
class HlsExtractor @Inject constructor(
    private val http: ExtractorHttp
) : MediaExtractor {

    override val name = "HLS stream"
    override val priority = 90

    private val extInfRegex = Regex("""#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val byteRangeRegex = Regex("""#EXT-X-BYTERANGE:\s*(\d+)(?:@(\d+))?""", RegexOption.IGNORE_CASE)
    private val mapByteRangeRegex = Regex("""BYTERANGE="(\d+)(?:@(\d+))?"""", RegexOption.IGNORE_CASE)

    override fun canHandle(url: String): Boolean {
        if (ExtractorRegistry.isConfirmedHtml(url)) return false
        val ext = UrlUtils.fileExtension(url)
        return ext == "m3u8" || url.contains(".m3u8", ignoreCase = true)
    }

    override suspend fun extract(url: String): ResolvedMedia? = extract(url, emptyMap(), null)

    suspend fun extract(url: String, headers: Map<String, String>): ResolvedMedia? =
        extract(url, headers, null)

    /**
     * Header- and quality-hint-aware variant used by the interception and HTML extraction layers.
     */
    suspend fun extract(
        url: String,
        headers: Map<String, String>,
        heightHint: Int? = null
    ): ResolvedMedia? {
        if (ExtractorRegistry.isConfirmedHtml(url)) return null
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

            // Build lookup of audio group ID -> best audio rendition URI
            val audioGroupUrls = LinkedHashMap<String, Pair<String, Int>>()
            rawAudioRenditions.forEach { attrs ->
                val groupId = attrs["GROUP-ID"] ?: return@forEach
                val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: return@forEach
                val resolvedAudio = UrlUtils.resolveUrl(url, uri)
                val bw = attrs["BANDWIDTH"]?.toIntOrNull() ?: 128_000
                val isDefault = attrs["DEFAULT"]?.equals("YES", ignoreCase = true) == true
                val existing = audioGroupUrls[groupId]
                if (existing == null || isDefault || bw > existing.second) {
                    audioGroupUrls[groupId] = resolvedAudio to bw
                }
            }

            // Probe first variant playlist to calculate sanitized duration (excluding injected discontinuity ad blocks)
            val firstVariantUrl = rawVariants.firstOrNull()?.variantUrl
            val variantText = if (firstVariantUrl != null) {
                runCatching { http.fetchString(firstVariantUrl, headers) }.getOrNull()
            } else null

            val probedDurationSeconds = if (!variantText.isNullOrBlank() && firstVariantUrl != null) {
                HlsPlaylistSanitizer.parseAndSanitize(variantText, firstVariantUrl)
                    .totalDurationSec
                    .roundToLong()
            } else 0L

            val headerDurationSeconds = headers["X-Internal-Duration-Seconds"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
            val totalDurationSeconds = if (probedDurationSeconds > 0L) probedDurationSeconds else headerDurationSeconds

            val baseStreamHeaders = if (totalDurationSeconds > 0L) {
                headers + ("X-Internal-Duration-Seconds" to totalDurationSeconds.toString())
            } else {
                headers
            }

            val parsedVariants = rawVariants.mapIndexed { variantIdx, v ->
                val resolution = v.attrs["RESOLUTION"]
                var width = resolution?.substringBefore('x')?.toIntOrNull()?.takeIf { it > 0 }
                var height = resolution?.substringAfter('x')?.toIntOrNull()?.takeIf { it > 0 }
                if (width != null && height != null) {
                    height = JsUnpacker.normalizeVideoHeight(width, height)
                } else if (height != null && width == null) {
                    width = (height * 16) / 9
                }

                val bandwidth = v.attrs["BANDWIDTH"]?.toIntOrNull()?.takeIf { it > 0 }
                val avgBandwidth = v.attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull()?.takeIf { it > 0 }
                val effectiveBandwidth = avgBandwidth ?: bandwidth

                if (height == null) {
                    val hinted = UrlUtils.inferResolution(v.variantUrl, v.attrs["NAME"].orEmpty())
                    val bwHeight = when {
                        effectiveBandwidth == null -> null
                        effectiveBandwidth >= 5_500_000 -> 1440
                        effectiveBandwidth >= 2_500_000 -> 1080
                        effectiveBandwidth >= 1_500_000 -> 720
                        effectiveBandwidth >= 700_000 -> 480
                        effectiveBandwidth >= 300_000 -> 360
                        else -> 240
                    }
                    height = hinted ?: bwHeight
                    if (height != null && width == null) {
                        width = (height * 16) / 9
                    }
                }

                val label = when {
                    height != null -> "${height}p"
                    effectiveBandwidth != null -> "${effectiveBandwidth / 1000} kbps"
                    else -> "Variant ${variantIdx + 1}"
                }

                val audioGroupId = v.attrs["AUDIO"]
                val companionAudio = if (audioGroupId != null) audioGroupUrls[audioGroupId] else null

                val videoBytes = when {
                    totalDurationSeconds <= 0L -> null
                    avgBandwidth != null -> (avgBandwidth.toLong() * totalDurationSeconds) / 8L
                    bandwidth != null -> (bandwidth.toDouble() * 0.72 * totalDurationSeconds / 8.0).roundToLong()
                        .takeIf { it > 0L }
                    else -> null
                }

                val variantHeaders = LinkedHashMap<String, String>(baseStreamHeaders).apply {
                    if (videoBytes != null && videoBytes > 0L) {
                        put("X-Internal-Expected-Bytes", videoBytes.toString())
                    }
                    if (companionAudio != null && companionAudio.first != v.variantUrl) {
                        put("X-Internal-Companion-Audio", companionAudio.first)
                        put("X-Internal-Companion-Audio-Hls", "true")
                        if (videoBytes != null && videoBytes > 0L) {
                            put("X-Internal-Video-Bytes", videoBytes.toString())
                        }
                        if (totalDurationSeconds > 0L) {
                            val aBytes = (companionAudio.second.toLong() * totalDurationSeconds) / 8L
                            put("X-Internal-Audio-Bytes", aBytes.toString())
                        }
                    }
                }

                val codecs = v.attrs["CODECS"].orEmpty().lowercase()
                val codecRank = when {
                    codecs.contains("avc1") || v.variantUrl.contains("h264", ignoreCase = true) -> 3
                    codecs.isEmpty() -> 2
                    codecs.contains("mp4a") && !codecs.contains("av01") && !codecs.contains("vp09") -> 2
                    codecs.contains("hev1") || codecs.contains("hvc1") -> 1
                    else -> 0 // av01 / vp9
                }

                codecRank to MediaStream(
                    id = "hls-$variantIdx",
                    url = v.variantUrl,
                    type = MediaType.VIDEO,
                    container = "ts",
                    label = label,
                    width = width,
                    height = height,
                    bitrateKbps = effectiveBandwidth?.div(1000),
                    sizeBytes = videoBytes,
                    isHls = true,
                    headers = variantHeaders
                )
            }

            // Collapse duplicate codec renditions of the exact same resolution (preferring H.264/avc1)
            val deduplicatedVideos = parsedVariants
                .groupBy { (_, s) -> s.height }
                .flatMap { (h, group) ->
                    if (h == null) {
                        group.map { it.second }
                    } else {
                        val best = group.maxWithOrNull(
                            compareBy<Pair<Int, MediaStream>> { it.first }
                                .thenBy { it.second.bitrateKbps ?: 0 }
                        )
                        listOfNotNull(best?.second)
                    }
                }
            streams += deduplicatedVideos

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

                    val audioHeaders = if (audioSizeBytes != null && audioSizeBytes > 0L) {
                        baseStreamHeaders + ("X-Internal-Expected-Bytes" to audioSizeBytes.toString())
                    } else {
                        baseStreamHeaders
                    }

                    streams += MediaStream(
                        id = "hls-audio-$audioIdx",
                        url = audioUrl,
                        type = MediaType.AUDIO,
                        container = "m4a",
                        label = label,
                        bitrateKbps = audioBw / 1000,
                        sizeBytes = audioSizeBytes,
                        isHls = true,
                        headers = audioHeaders
                    )
                }
            }
        }

        if (streams.isEmpty()) {
            if (text.contains("#EXTINF")) {
                val sanitized = HlsPlaylistSanitizer.parseAndSanitize(text, url)
                val mediaSegments = sanitized.segments.filter { !it.isInitMap }
                val totalDurationSeconds = sanitized.totalDurationSec.roundToLong()

                val inferredHeight = heightHint?.takeIf { it > 0 } ?: UrlUtils.inferResolution(url)
                val inferredWidth = inferredHeight?.let { (it * 16) / 9 }

                // 1. Check if sanitized playlist advertises exact #EXT-X-BYTERANGE segment lengths (e.g. xHamster/fMP4 HLS)
                val allHaveRanges = mediaSegments.isNotEmpty() && mediaSegments.all { (it.byteRangeLength ?: 0L) > 0L }
                val byteRangeTotal = if (allHaveRanges) {
                    sanitized.segments.sumOf { it.byteRangeLength ?: 0L }
                } else {
                    0L
                }

                var sizeBytes: Long? = byteRangeTotal.takeIf { it > 0L }
                var bitrateKbps: Int? = if (sizeBytes != null && totalDurationSeconds > 0L) {
                    ((sizeBytes * 8L) / (totalDurationSeconds * 1000L)).toInt()
                } else null

                // 2. Otherwise probe first non-ad media segment
                if (sizeBytes == null) {
                    val seg0 = mediaSegments.firstOrNull()
                    val seg0Url = seg0?.url
                    val seg0Duration = seg0?.durationSec

                    if (seg0Url != null) {
                        val probe = runCatching { http.probe(seg0Url, headers) }.getOrNull()
                        val seg0Length = probe?.contentLength?.takeIf { it > 0 }
                        if (seg0Length != null && seg0Duration != null && seg0Duration > 0.0 && totalDurationSeconds > 0L) {
                            val byteRate = seg0Length.toDouble() / seg0Duration
                            sizeBytes = (byteRate * totalDurationSeconds).toLong()
                            bitrateKbps = ((byteRate * 8) / 1000).toInt()
                        }
                    }
                }

                if (sizeBytes == null && totalDurationSeconds > 0L) {
                    val estBitrate = when {
                        inferredHeight == null -> 1000
                        inferredHeight >= 2160 -> 12000
                        inferredHeight >= 1440 -> 6000
                        inferredHeight >= 1080 -> 2500
                        inferredHeight >= 720 -> 1500
                        inferredHeight >= 480 -> 800
                        inferredHeight >= 360 -> 450
                        else -> 300
                    }
                    bitrateKbps = estBitrate
                    sizeBytes = (estBitrate * 1000L * totalDurationSeconds) / 8L
                }

                val streamHeaders = LinkedHashMap<String, String>(headers).apply {
                    if (totalDurationSeconds > 0L) {
                        put("X-Internal-Duration-Seconds", totalDurationSeconds.toString())
                    }
                    if (sizeBytes != null && sizeBytes > 0L) {
                        put("X-Internal-Expected-Bytes", sizeBytes.toString())
                    }
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

    private fun sumByteRanges(playlistText: String): Long {
        var segCount = 0
        var rangeCount = 0
        var sum = 0L
        var mapBytes = 0L
        for (raw in playlistText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP:")) {
                val m = mapByteRangeRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (m != null && mapBytes == 0L) mapBytes = m
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                val len = byteRangeRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (len != null && len > 0L) {
                    sum += len
                    rangeCount++
                }
            } else if (!line.startsWith("#")) {
                segCount++
            }
        }
        return if (segCount > 0 && rangeCount == segCount) sum + mapBytes else 0L
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
