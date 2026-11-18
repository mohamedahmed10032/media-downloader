package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the live WebView/JS interception layer and static/API HTML extraction into a
 * unified, ad-free multi-quality resolver.
 *
 * Recovers live session headers (`Cookie`, `Referer`, `User-Agent`, `Authorization`) from
 * [MediaInterceptionEngine], expands HLS/DASH manifests, merges page-level multi-quality
 * definitions from [HtmlMediaExtractor] when the WebView has only requested a single stream
 * so far, and filters out VAST prerolls, hover previews, and site intro bumpers.
 */
@Singleton
class InterceptedMediaExtractor : MediaExtractor {

    private val engine: MediaInterceptionEngine
    private val http: ExtractorHttp
    private val hlsExtractor: HlsExtractor
    private val dashExtractor: DashExtractor
    private val htmlMediaExtractor: HtmlMediaExtractor?

    @Inject
    constructor(
        engine: MediaInterceptionEngine,
        http: ExtractorHttp,
        hlsExtractor: HlsExtractor,
        dashExtractor: DashExtractor,
        htmlMediaExtractor: HtmlMediaExtractor
    ) {
        this.engine = engine
        this.http = http
        this.hlsExtractor = hlsExtractor
        this.dashExtractor = dashExtractor
        this.htmlMediaExtractor = htmlMediaExtractor
    }

    constructor(
        engine: MediaInterceptionEngine,
        http: ExtractorHttp,
        hlsExtractor: HlsExtractor,
        dashExtractor: DashExtractor
    ) {
        this.engine = engine
        this.http = http
        this.hlsExtractor = hlsExtractor
        this.dashExtractor = dashExtractor
        this.htmlMediaExtractor = null
    }

    override val name = "Live Stream"
    override val priority = 120

    override fun canHandle(url: String): Boolean {
        val host = UrlUtils.host(url)?.lowercase().orEmpty()
        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            return false // Always let YouTubeExtractor resolve full InnerTube formats
        }
        return engine.payloadsFor(url).any {
            it.streamType != StreamType.BLOB &&
                it.streamType != StreamType.UNKNOWN &&
                !MediaSniffer.isPreviewOrAdUrl(it.url)
        }
    }

    override suspend fun extract(url: String): ResolvedMedia? = coroutineScope {
        val rawCandidates = engine.payloadsFor(url)
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .filterNot { MediaSniffer.isPreviewOrAdUrl(it.url) }
            .distinctBy { "${it.streamType}|${MediaSniffer.dedupeKey(it.url)}|${it.qualityHint ?: 0}" }

        if (rawCandidates.isEmpty()) return@coroutineScope null

        val hasAuthoritative = rawCandidates.any { it.isAuthoritativePlayer }
        val authoritativeHosts = rawCandidates
            .filter { it.isAuthoritativePlayer }
            .mapNotNull { rootDomain(UrlUtils.host(it.url)) }
            .toSet()

        // When authoritative player streams exist, drop unverified third-party progressive clips (prerolls/bumpers)
        val candidates = if (hasAuthoritative) {
            rawCandidates.filter { payload ->
                payload.isAuthoritativePlayer ||
                    payload.streamType == StreamType.HLS ||
                    payload.streamType == StreamType.DASH ||
                    rootDomain(UrlUtils.host(payload.url)) in authoritativeHosts
            }
        } else {
            rawCandidates
        }

        var pageTitle: String? = candidates.firstNotNullOfOrNull { it.pageTitle?.takeIf { t -> t.isNotBlank() } }
        var pageThumb: String? = candidates.firstNotNullOfOrNull { it.thumbnailUrl?.takeIf { t -> t.startsWith("http") } }
        val pageDurationSec: Long? = candidates.firstNotNullOfOrNull { it.durationSeconds?.takeIf { d -> d > 0L } }

        val bestHeaders = LinkedHashMap<String, String>().apply {
            put("Referer", url)
            candidates.firstOrNull { it.headersMap.isNotEmpty() }?.headersMap?.let { putAll(it) }
            candidates.firstNotNullOfOrNull { it.headersMap["Cookie"] }?.let { put("Cookie", it) }
            if (pageDurationSec != null && pageDurationSec > 0L) {
                put("X-Internal-Duration-Seconds", pageDurationSec.toString())
            }
        }

        val htmlDeferred = async {
            val ext = UrlUtils.fileExtension(url)?.lowercase()
            val isWebPage = ext == null || (ext !in UrlUtils.VIDEO_EXTENSIONS && ext !in UrlUtils.AUDIO_EXTENSIONS && ext != "m3u8" && ext != "mpd")
            if (htmlMediaExtractor != null && isWebPage) {
                runCatching {
                    htmlMediaExtractor.extractWithHeaders(url, bestHeaders, allowHeadlessFallback = false)
                }.getOrNull()
            } else null
        }

        val liveResolved = candidates.take(16).mapIndexed { idx, payload ->
            async {
                val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
                val headers = LinkedHashMap<String, String>().apply {
                    putAll(bestHeaders)
                    putAll(payload.headersMap)
                    if (payload.durationSeconds != null && payload.durationSeconds > 0L) {
                        put("X-Internal-Duration-Seconds", payload.durationSeconds.toString())
                    }
                    if (payload.isAuthoritativePlayer) {
                        put("X-Internal-Authoritative", "true")
                    }
                }
                when (payload.streamType) {
                    StreamType.HLS -> runCatching {
                        val res = if (payload.qualityHint != null) {
                            hlsExtractor.extract(cleanUrl, headers, payload.qualityHint)
                        } else {
                            hlsExtractor.extract(cleanUrl, headers)
                        }
                        res?.streams?.map { s ->
                            if (payload.isAuthoritativePlayer) {
                                s.copy(headers = s.headers + ("X-Internal-Authoritative" to "true"))
                            } else s
                        }
                    }.getOrNull().orEmpty()
                    StreamType.DASH -> runCatching { dashExtractor.extract(cleanUrl, headers)?.streams }.getOrNull().orEmpty()
                    StreamType.PROGRESSIVE -> listOfNotNull(progressiveStream(idx, payload.copy(url = cleanUrl), headers))
                    StreamType.BLOB, StreamType.UNKNOWN -> emptyList()
                }
            }
        }.awaitAll().flatten()

        val htmlResolved = htmlDeferred.await()
        if (pageTitle.isNullOrBlank() && !htmlResolved?.title.isNullOrBlank()) {
            pageTitle = htmlResolved?.title
        }
        if (pageThumb.isNullOrBlank() && !htmlResolved?.thumbnailUrl.isNullOrBlank()) {
            pageThumb = htmlResolved?.thumbnailUrl
        }

        val combinedRaw = buildList {
            addAll(liveResolved)
            htmlResolved?.streams?.forEach { hs ->
                if (hs.type == MediaType.VIDEO || hs.type == MediaType.AUDIO) {
                    add(hs)
                }
            }
        }

        // Filter out tiny tracking fragments (< 40 KB when size is known) and ad/preview URLs
        val filtered = combinedRaw
            .filter { it.type == MediaType.VIDEO || it.type == MediaType.AUDIO }
            .filterNot { MediaSniffer.isPreviewOrAdUrl(it.url) }
            .filter { (it.sizeBytes ?: Long.MAX_VALUE) > 40_000L }
            .distinctBy { MediaSniffer.dedupeKey(it.url) }

        if (filtered.isEmpty()) return@coroutineScope null

        // Drop suspiciously tiny non-authoritative progressive video bumpers (< 1.2 MB) when main video is >= 45s or >= 5 MB
        val hasFullLengthMainVideo = (pageDurationSec != null && pageDurationSec >= 45L) ||
            filtered.any { it.type == MediaType.VIDEO && (it.sizeBytes ?: 0L) >= 5_000_000L }

        val cleanFiltered = if (hasFullLengthMainVideo) {
            filtered.filterNot { s ->
                s.type == MediaType.VIDEO &&
                    !s.isHls &&
                    s.headers["X-Internal-Authoritative"] != "true" &&
                    s.sizeBytes != null &&
                    s.sizeBytes in 1..1_200_000L
            }.ifEmpty { filtered }
        } else {
            filtered
        }

        val rawAudios = cleanFiltered.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val bestAudio = rawAudios.firstOrNull()

        val rawVideos = cleanFiltered.filter { it.type == MediaType.VIDEO }
            .map { v ->
                val inferredH = v.height ?: UrlUtils.inferResolution(v.url, v.label)
                if (inferredH != v.height) v.copy(height = inferredH) else v
            }

        // Collapse duplicate resolutions: prefer authoritative streams, direct MP4s with known >= 500 KB size, then largest sizeBytes
        val distinctVideos = rawVideos
            .groupBy { it.height }
            .flatMap { (height, group) ->
                if (height != null) {
                    val best = group.maxWithOrNull(
                        compareBy<MediaStream> { it.headers["X-Internal-Authoritative"] == "true" }
                            .thenBy { !it.isHls && (it.sizeBytes ?: 0L) >= 500_000L }
                            .thenBy { it.sizeBytes ?: 0L }
                    )
                    listOfNotNull(best)
                } else {
                    if (rawVideos.any { it.height != null }) emptyList() else group
                }
            }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }

        val videos = distinctVideos.mapIndexed { idx, s ->
            val h = s.height ?: UrlUtils.inferResolution(s.url, s.label)
            val label = when {
                h != null && h >= 2160 -> "High quality (4K)"
                h != null && h >= 1440 -> "High quality (2K)"
                h != null && h >= 1080 -> "1080p FHD"
                h != null && h >= 720 -> "High quality (${h}p)"
                h != null && h == 480 -> "480p"
                h != null && h <= 360 -> "Fast (${h}p)"
                h != null -> "${h}p"
                s.label.isNotBlank() && !s.label.startsWith("Variant") && !s.label.startsWith("Fast (") && s.label != "Auto (HLS)" -> s.label
                else -> "HD Video"
            }
            val needsAudio = bestAudio != null &&
                !s.isHls &&
                s.headers["X-Internal-Companion-Audio"] == null &&
                (s.headers["X-Internal-Dash-VideoOnly"] == "true" || s.headers["X-Internal-Authoritative"] != "true")
            val vBytes = s.sizeBytes ?: 0L
            val aBytes = bestAudio?.sizeBytes ?: 0L
            val combinedSize = if (needsAudio && vBytes > 0 && aBytes > 0) vBytes + aBytes else s.sizeBytes
            val extraHeaders = LinkedHashMap<String, String>(s.headers).apply {
                if (idx < 2) put("X-Internal-Quick", "true")
                if (pageDurationSec != null && pageDurationSec > 0L && !containsKey("X-Internal-Duration-Seconds")) {
                    put("X-Internal-Duration-Seconds", pageDurationSec.toString())
                }
                if (needsAudio && bestAudio != null) {
                    put("X-Internal-Companion-Audio", bestAudio.url)
                    if (vBytes > 0) put("X-Internal-Video-Bytes", vBytes.toString())
                    if (aBytes > 0) put("X-Internal-Audio-Bytes", aBytes.toString())
                }
            }
            s.copy(
                id = "int-v-$idx",
                label = label,
                height = h ?: s.height,
                sizeBytes = combinedSize,
                headers = extraHeaders
            )
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
            thumbnailUrl = pageThumb,
            streams = finalStreams,
            extractor = name
        )
    }

    private fun progressiveStream(
        idx: Int,
        payload: MediaStreamPayload,
        headers: Map<String, String>
    ): MediaStream? {
        val url = payload.url
        val info = runCatching { http.probe(url, headers) }.getOrNull()
        val contentType = (info?.contentType ?: payload.mimeType.orEmpty()).lowercase()
        if (contentType.startsWith("text/html") ||
            contentType.startsWith("application/json") ||
            contentType.contains("javascript") ||
            contentType.startsWith("image/")
        ) {
            return null
        }

        val isAudio = MediaSniffer.isAudioTrackUrl(url, contentType)
        val type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO
        val container = MediaSniffer.containerFor(StreamType.PROGRESSIVE, url, contentType)
        val sizeBytes = info?.contentLength?.takeIf { it > 0 } ?: payload.sizeBytes?.takeIf { it > 0 }
        val inferredHeight = payload.qualityHint ?: UrlUtils.inferResolution(url)

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

    private fun rootDomain(host: String?): String? {
        val clean = host?.lowercase()?.removePrefix("www.")?.trimEnd('.') ?: return null
        val parts = clean.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else clean
    }
}
