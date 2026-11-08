package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.StreamType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the interception layer into the extractor chain.
 *
 * When the WebView or the OkHttp sniffer has already seen a URL, its captured request
 * headers (Cookie / User-Agent / Referer / Authorization) are recovered from the engine and
 * attached to every resolved [MediaStream]. Without this, manifests behind a Referer or
 * session-cookie check resolve in the browser but fail with 403 in the download engine.
 *
 * This extractor runs before all others so intercepted headers always win.
 */
@Singleton
class InterceptedMediaExtractor @Inject constructor(
    private val engine: MediaInterceptionEngine,
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val dashExtractor: DashExtractor
) : MediaExtractor {

    override val name = "Live Stream"
    override val priority = 120

    private val resRegex = Regex("(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE)

    override fun canHandle(url: String): Boolean {
        val host = UrlUtils.host(url)?.lowercase().orEmpty()
        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            return false // Always let YouTubeExtractor resolve full InnerTube formats
        }
        return engine.payloadsFor(url).any { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
    }

    override suspend fun extract(url: String): ResolvedMedia? = coroutineScope {
        val candidates = engine.payloadsFor(url)
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .distinctBy { MediaSniffer.dedupeKey(it.url) }

        if (candidates.isEmpty()) return@coroutineScope null

        var pageTitle: String? = null

        val resolvedStreams = candidates.take(10).mapIndexed { idx, payload ->
            async {
                if (pageTitle == null && !payload.pageTitle.isNullOrBlank()) {
                    pageTitle = payload.pageTitle
                }
                val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
                val headers = payload.headersMap
                when (payload.streamType) {
                    StreamType.HLS -> runCatching { hlsExtractor.extract(cleanUrl, headers)?.streams }.getOrNull().orEmpty()
                    StreamType.DASH -> runCatching { dashExtractor.extract(cleanUrl, headers)?.streams }.getOrNull().orEmpty()
                    StreamType.PROGRESSIVE -> listOfNotNull(progressiveStream(idx, payload.copy(url = cleanUrl), headers))
                    StreamType.BLOB, StreamType.UNKNOWN -> emptyList()
                }
            }
        }.awaitAll().flatten()

        // Deduplicate by canonical key and filter out tiny tracking fragments (< 15 KB if size is known)
        val filtered = resolvedStreams
            .filter { it.type == MediaType.VIDEO || it.type == MediaType.AUDIO }
            .filter { (it.sizeBytes ?: Long.MAX_VALUE) > 15_000L }
            .distinctBy { MediaSniffer.dedupeKey(it.url) }

        if (filtered.isEmpty()) return@coroutineScope null

        val rawAudios = filtered.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val bestAudio = rawAudios.firstOrNull()

        val rawVideos = filtered.filter { it.type == MediaType.VIDEO }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }

        val videos = rawVideos.mapIndexed { idx, s ->
            val label = when {
                s.height != null && s.height >= 720 -> "High quality (${s.height}p)"
                s.height != null -> "Fast (${s.height}p)"
                videosCountLabel(idx, rawVideos.size) != null -> videosCountLabel(idx, rawVideos.size)!!
                else -> s.label
            }
            val needsAudio = bestAudio != null && !s.isHls && s.headers["X-Internal-Companion-Audio"] == null
            val vBytes = s.sizeBytes ?: 0L
            val aBytes = bestAudio?.sizeBytes ?: 0L
            val combinedSize = if (needsAudio && vBytes > 0 && aBytes > 0) vBytes + aBytes else s.sizeBytes
            val extraHeaders = LinkedHashMap<String, String>(s.headers).apply {
                if (idx < 2) put("X-Internal-Quick", "true")
                if (needsAudio && bestAudio != null) {
                    put("X-Internal-Companion-Audio", bestAudio.url)
                    if (vBytes > 0) put("X-Internal-Video-Bytes", vBytes.toString())
                    if (aBytes > 0) put("X-Internal-Audio-Bytes", aBytes.toString())
                }
            }
            s.copy(id = "int-v-$idx", label = label, sizeBytes = combinedSize, headers = extraHeaders)
        }

        val audios = if (bestAudio != null) {
            listOf(
                bestAudio.copy(
                    id = "int-a-fast",
                    container = "m4a",
                    label = "Fast",
                    headers = bestAudio.headers + mapOf(
                        "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "int-a-mp3",
                    container = "mp3",
                    label = "Classic MP3",
                    headers = bestAudio.headers + mapOf(
                        "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true",
                        "X-Internal-Default" to "true"
                    )
                )
            )
        } else {
            emptyList()
        }

        val finalStreams = videos + audios
        val title = pageTitle?.takeIf { it.isNotBlank() }
            ?: UrlUtils.host(url)?.replaceFirstChar { it.uppercase() }
            ?: UrlUtils.fileNameFromUrl(url)

        ResolvedMedia(
            sourceUrl = url,
            title = title,
            streams = finalStreams,
            extractor = name
        )
    }

    private fun videosCountLabel(index: Int, totalVideos: Int): String? {
        if (totalVideos <= 1) return "High quality (720p)"
        return when (index) {
            0 -> "High quality (720p)"
            1 -> "Fast (360p)"
            else -> "Fast (${index + 1})"
        }
    }

    private fun progressiveStream(
        idx: Int,
        payload: com.melmeligy.mediadownloader.intercept.MediaStreamPayload,
        headers: Map<String, String>
    ): MediaStream? {
        val url = payload.url
        val info = runCatching { http.probe(url, headers) }.getOrNull()
        val contentType = info?.contentType ?: payload.mimeType.orEmpty()
        if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) return null

        val isAudio = MediaSniffer.isAudioTrackUrl(url, contentType)
        val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
        val container = MediaSniffer.containerFor(StreamType.PROGRESSIVE, url, contentType)
        val sizeBytes = info?.contentLength?.takeIf { it > 0 } ?: payload.sizeBytes?.takeIf { it > 0 }
        val inferredHeight = resRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val label = when {
            type == MediaType.AUDIO -> "Original Audio (${container.uppercase()})"
            inferredHeight != null && inferredHeight >= 720 -> "${inferredHeight}p HD"
            inferredHeight != null -> "${inferredHeight}p"
            else -> "HD Video"
        }

        return MediaStream(
            id = "intercepted-$idx",
            url = info?.finalUrl?.let { MediaSniffer.cleanMediaUrl(it) } ?: url,
            type = type,
            container = container,
            label = label,
            height = inferredHeight,
            sizeBytes = sizeBytes,
            isHls = false,
            headers = headers
        )
    }
}
