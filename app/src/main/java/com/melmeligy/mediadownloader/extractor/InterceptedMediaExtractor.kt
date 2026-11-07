package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.StreamType
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

    override fun canHandle(url: String): Boolean =
        engine.payloadsFor(url).any { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }

    override suspend fun extract(url: String): ResolvedMedia? = kotlinx.coroutines.coroutineScope {
        val candidates = engine.payloadsFor(url)
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .distinctBy { MediaSniffer.cleanMediaUrl(it.url).substringBefore('?') }

        if (candidates.isEmpty()) return@coroutineScope null

        val resolvedStreams = mutableListOf<MediaStream>()
        var pageTitle: String? = null

        val deferredList = candidates.take(10).mapIndexed { idx, payload ->
            kotlinx.coroutines.async {
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
        }

        deferredList.forEach { d -> resolvedStreams.addAll(d.await()) }

        // Deduplicate by bare URL and filter out tiny tracking fragments (< 15 KB if size is known)
        val filtered = resolvedStreams
            .filter { it.type == MediaType.VIDEO || it.type == MediaType.AUDIO }
            .filter { (it.sizeBytes ?: Long.MAX_VALUE) > 15_000L }
            .distinctBy { it.url.substringBefore('?') }

        if (filtered.isEmpty()) return@coroutineScope null

        // Assign clean, distinct labels when multiple video or audio resolutions exist
        val videos = filtered.filter { it.type == MediaType.VIDEO }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
            .mapIndexed { idx, s ->
                val label = when {
                    s.height != null && s.height >= 1080 -> "${s.height}p FHD"
                    s.height != null && s.height >= 720 -> "${s.height}p HD"
                    s.height != null -> "${s.height}p"
                    videosCountLabel(idx, filtered.count { it.type == MediaType.VIDEO }) != null ->
                        videosCountLabel(idx, filtered.count { it.type == MediaType.VIDEO })!!
                    else -> s.label
                }
                s.copy(id = "int-v-$idx", label = label)
            }

        val audios = filtered.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
            .mapIndexed { idx, s ->
                val label = when {
                    s.bitrateKbps != null -> "Audio (${s.bitrateKbps} kbps)"
                    idx == 0 -> "Original Audio (${s.container.uppercase()})"
                    else -> "Audio Track ${idx + 1} (${s.container.uppercase()})"
                }
                s.copy(id = "int-a-$idx", label = label)
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
        if (totalVideos <= 1) return "HD Video"
        return when (index) {
            0 -> "HD Video (High Quality)"
            1 -> "SD Video (Medium Quality)"
            else -> "Video Quality ${index + 1}"
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
