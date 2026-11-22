package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the live WebView/JS interception layer and static/API HTML extraction into a
 * unified, ad-free multi-quality resolver.
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
            return false
        }
        return engine.payloadsFor(url).any {
            it.streamType != StreamType.BLOB &&
                it.streamType != StreamType.UNKNOWN &&
                !MediaSniffer.isHexCipherUrl(it.url) &&
                !MediaSniffer.isPreviewOrAdUrl(it.url)
        }
    }

    private data class ProbeOutcome(
        val streams: List<MediaStream>,
        val timedOutFallback: MediaStream? = null,
        val isAuthoritative: Boolean = false
    )

    override suspend fun extract(url: String): ResolvedMedia? = coroutineScope {
        val rawCandidates = engine.payloadsFor(url)
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .filterNot { MediaSniffer.isHexCipherUrl(it.url) || MediaSniffer.isPreviewOrAdUrl(it.url) }
            .distinctBy { "${it.streamType}|${MediaSniffer.dedupeKey(it.url)}|${it.qualityHint?.takeIf { q -> q > 0 } ?: 0}" }

        if (rawCandidates.isEmpty()) return@coroutineScope null

        var pageTitle: String? = rawCandidates.firstNotNullOfOrNull {
            it.pageTitle?.takeIf { t -> !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(t, url) }
        } ?: runCatching { engine.currentPageTitle }.getOrNull()?.takeIf {
            !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, url)
        }
        var pageThumb: String? = rawCandidates.firstNotNullOfOrNull {
            HtmlThumbnailExtractor.resolveValidImageCandidate(it.thumbnailUrl, url)
        } ?: HtmlThumbnailExtractor.resolveValidImageCandidate(
            runCatching { engine.currentPageThumbnail }.getOrNull(),
            url
        )
        val pageDurationSec: Long? = rawCandidates.firstNotNullOfOrNull { it.durationSeconds?.takeIf { d -> d > 0L } }

        val bestHeaders = LinkedHashMap<String, String>().apply {
            put("Referer", url)
            rawCandidates.firstOrNull { it.headersMap.isNotEmpty() }?.headersMap?.let { putAll(it) }
            rawCandidates.firstNotNullOfOrNull { it.headersMap["Cookie"] }?.let { put("Cookie", it) }
            if (pageDurationSec != null && pageDurationSec > 0L) {
                put("X-Internal-Duration-Seconds", pageDurationSec.toString())
            }
        }

        // Probe all candidates concurrently on IO (OkHttp probeClient enforces real wall-clock timeouts)
        val outcomes = rawCandidates.take(16).mapIndexed { idx, payload ->
            async(Dispatchers.IO) {
                val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
                val sanitizedHint = payload.qualityHint?.takeIf { it > 0 }
                val headers = LinkedHashMap<String, String>().apply {
                    putAll(bestHeaders)
                    putAll(payload.headersMap)
                    remove("X-Internal-Default")
                    if (payload.durationSeconds != null && payload.durationSeconds > 0L) {
                        put("X-Internal-Duration-Seconds", payload.durationSeconds.toString())
                    }
                    if (payload.isAuthoritativePlayer) {
                        put("X-Internal-Authoritative", "true")
                    }
                }
                when (payload.streamType) {
                    StreamType.HLS -> {
                        val list = runCatching {
                            val res = if (sanitizedHint != null) {
                                hlsExtractor.extract(cleanUrl, headers, sanitizedHint)
                            } else {
                                hlsExtractor.extract(cleanUrl, headers)
                            }
                            res?.streams?.map { s ->
                                val cleanH = s.headers.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
                                if (payload.isAuthoritativePlayer) {
                                    s.copy(headers = cleanH + ("X-Internal-Authoritative" to "true"))
                                } else {
                                    s.copy(headers = cleanH)
                                }
                            }
                        }.getOrNull().orEmpty()
                        ProbeOutcome(list, null, payload.isAuthoritativePlayer)
                    }
                    StreamType.DASH -> {
                        val list = runCatching {
                            dashExtractor.extract(cleanUrl, headers)?.streams?.map { s ->
                                val cleanH = s.headers.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
                                if (payload.isAuthoritativePlayer) {
                                    s.copy(headers = cleanH + ("X-Internal-Authoritative" to "true"))
                                } else {
                                    s.copy(headers = cleanH)
                                }
                            }
                        }.getOrNull().orEmpty()
                        ProbeOutcome(list, null, payload.isAuthoritativePlayer)
                    }
                    StreamType.PROGRESSIVE -> probeProgressive(idx, payload.copy(url = cleanUrl, qualityHint = sanitizedHint), headers)
                    StreamType.BLOB, StreamType.UNKNOWN -> ProbeOutcome(emptyList())
                }
            }
        }.awaitAll()

        val liveStreamsAll = outcomes.flatMap { it.streams }
        val timedOutFallbacks = outcomes.mapNotNull { it.timedOutFallback }

        // Post-probe authoritative filtering: ONLY drop third-party bumpers if at least one authoritative stream survived probing!
        val survivingAuth = liveStreamsAll.filter { it.headers["X-Internal-Authoritative"] == "true" }
        val liveResolved = if (survivingAuth.isNotEmpty()) {
            val authHosts = survivingAuth.mapNotNull { rootDomain(UrlUtils.host(it.url)) }.toSet()
            liveStreamsAll.filter { s ->
                s.headers["X-Internal-Authoritative"] == "true" ||
                    s.isHls ||
                    rootDomain(UrlUtils.host(s.url)) in authHosts
            }
        } else if (liveStreamsAll.isNotEmpty()) {
            liveStreamsAll
        } else {
            // Single-stream offline timeout fallback (never used when explicit HTTP 4xx/5xx dropped the stream)
            timedOutFallbacks
        }

        // Decide whether to enrich via HTML:
        // Skip redundant HTML re-fetch when >= 2 distinct authoritative video heights AND thumbnail are already present.
        val distinctAuthHeights = survivingAuth
            .filter { it.type == MediaType.VIDEO }
            .mapNotNull { it.height?.takeIf { h -> h > 0 } }
            .toSet()

        val ext = UrlUtils.fileExtension(url)?.lowercase()
        val isWebPage = ext == null ||
            (ext !in UrlUtils.VIDEO_EXTENSIONS && ext !in UrlUtils.AUDIO_EXTENSIONS && ext != "m3u8" && ext != "mpd")

        var htmlResolved: ResolvedMedia? = null
        if (isWebPage && htmlMediaExtractor != null && distinctAuthHeights.size < 2) {
            htmlResolved = runCatching {
                htmlMediaExtractor.extractWithHeaders(url, bestHeaders, allowHeadlessFallback = false)
            }.getOrNull()
        }

        if (HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(pageTitle, url) &&
            !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(htmlResolved?.title, url)
        ) {
            pageTitle = htmlResolved?.title
        }
        if (pageThumb.isNullOrBlank() && !htmlResolved?.thumbnailUrl.isNullOrBlank()) {
            pageThumb = htmlResolved?.thumbnailUrl
        }

        if (isWebPage &&
            (pageThumb.isNullOrBlank() || HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(pageTitle, url))
        ) {
            // Recover artwork & title directly from page HTML even when static HTML had zero direct video streams
            runCatching {
                val htmlText = http.fetchString(url, bestHeaders)
                if (htmlText.isNotBlank()) {
                    val doc = Jsoup.parse(htmlText, url)
                    if (pageThumb.isNullOrBlank()) {
                        val playerPoster = JsUnpacker.extractPlayerConfigs(htmlText).posterUrl
                        val extractedThumb = HtmlThumbnailExtractor.extractThumbnail(
                            doc = doc,
                            pageUrl = url,
                            rawHtml = htmlText,
                            playerPoster = playerPoster
                        )
                        if (!extractedThumb.isNullOrBlank()) pageThumb = extractedThumb
                    }
                    if (HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(pageTitle, url)) {
                        val extractedTitle = HtmlThumbnailExtractor.extractTitle(doc, url)
                        if (!HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(extractedTitle, url)) {
                            pageTitle = extractedTitle
                        }
                    }
                }
            }
        }

        if (HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(pageTitle, url)) {
            HtmlThumbnailExtractor.humanReadableTitleFromUrl(url)?.let { pageTitle = it }
        }
        if (!pageTitle.isNullOrBlank() || !pageThumb.isNullOrBlank()) {
            runCatching { engine.updatePageMetadata(pageTitle, pageThumb, url) }
        }

        val combinedRaw = buildList {
            addAll(liveResolved)
            htmlResolved?.streams?.forEach { hs ->
                if (hs.type == MediaType.VIDEO || hs.type == MediaType.AUDIO) {
                    add(hs)
                }
            }
        }

        // Filter out tiny tracking fragments (< 40 KB when size is known), hex URLs, and ad/preview URLs
        val filtered = combinedRaw
            .filter { it.type == MediaType.VIDEO || it.type == MediaType.AUDIO }
            .filterNot { MediaSniffer.isHexCipherUrl(it.url) || MediaSniffer.isPreviewOrAdUrl(it.url) }
            .filter { (it.sizeBytes ?: Long.MAX_VALUE) > 40_000L }
            .distinctBy { MediaSniffer.dedupeKey(it.url) }

        if (filtered.isEmpty()) return@coroutineScope null

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
                val validH = v.height?.takeIf { it > 0 } ?: UrlUtils.inferResolution(v.url, v.label)?.takeIf { it > 0 }
                val validW = v.width?.takeIf { it > 0 }
                v.copy(width = validW, height = validH)
            }

        // Collapse duplicate resolutions and null-height mirrors (identical size or filename)
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
                    if (rawVideos.any { it.height != null }) {
                        emptyList()
                    } else {
                        collapseNullHeightMirrors(group)
                    }
                }
            }
            .sortedByDescending { it.height ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }

        val videos = distinctVideos.mapIndexed { idx, s ->
            val h = s.height?.takeIf { it > 0 } ?: UrlUtils.inferResolution(s.url, s.label)?.takeIf { it > 0 }
            val label = when {
                h != null && h >= 2160 -> "High quality (4K)"
                h != null && h >= 1440 -> "High quality (2K)"
                h != null && h >= 1080 -> "1080p FHD"
                h != null && h >= 720 -> "High quality (${h}p)"
                h != null && h == 480 -> "480p"
                h != null && h <= 360 -> "Fast (${h}p)"
                h != null -> "${h}p"
                s.label.isNotBlank() && !s.label.startsWith("Variant") && !s.label.startsWith("Fast (") &&
                    s.label != "Auto (HLS)" && s.label != "0p" -> s.label
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
                remove("X-Internal-Default")
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
                height = h,
                sizeBytes = combinedSize,
                headers = extraHeaders
            )
        }

        val audios = if (bestAudio != null) {
            val cleanAudioHeaders = bestAudio.headers.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
            val srcBytes = bestAudio.sizeBytes?.takeIf { it > 0L }
            val srcKbps = bestAudio.bitrateKbps?.takeIf { it in 24..512 } ?: 128
            val mp3Size = com.melmeligy.mediadownloader.core.util.FormatUtils.estimateTranscodedAudioBytes(
                targetBitrateKbps = 160,
                sourceSizeBytes = srcBytes,
                sourceBitrateKbps = srcKbps
            )
            val srcBytesHeader = if (srcBytes != null) mapOf("X-Internal-Video-Bytes" to srcBytes.toString()) else emptyMap()
            listOf(
                bestAudio.copy(
                    id = "int-a-fast",
                    container = "m4a",
                    label = "Fast",
                    bitrateKbps = srcKbps,
                    headers = cleanAudioHeaders + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "M4A (${srcKbps}K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                ),
                bestAudio.copy(
                    id = "int-a-mp3",
                    container = "mp3",
                    label = "Classic MP3",
                    bitrateKbps = 160,
                    sizeBytes = mp3Size,
                    headers = cleanAudioHeaders + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true"
                    )
                )
            )
        } else {
            emptyList()
        }

        val finalStreams = videos + audios
        if (finalStreams.isEmpty()) return@coroutineScope null

        val title = pageTitle?.takeIf {
            !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, url)
        } ?: HtmlThumbnailExtractor.humanReadableTitleFromUrl(url)
            ?: pageTitle?.takeIf { it.isNotBlank() }
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

    private fun probeProgressive(
        idx: Int,
        payload: MediaStreamPayload,
        headers: Map<String, String>
    ): ProbeOutcome {
        val url = payload.url
        val info: HeadInfo? = try {
            http.probe(url, headers)
        } catch (e: MediaException) {
            // Explicit HTTP 401/403/404/410 -> dead stream, drop completely (never fall back)
            if (e.error == AppError.NO_MEDIA_FOUND ||
                e.error == AppError.EXPIRED_LINK ||
                e.error == AppError.GEO_BLOCKED
            ) {
                return ProbeOutcome(emptyList(), null, payload.isAuthoritativePlayer)
            }
            // Network/timeout IOException -> allow fallback only if zero live streams survive
            val fallback = buildProgressiveStream(idx, payload, headers, null)
            return ProbeOutcome(emptyList(), fallback, payload.isAuthoritativePlayer)
        } catch (_: Exception) {
            val fallback = buildProgressiveStream(idx, payload, headers, null)
            return ProbeOutcome(emptyList(), fallback, payload.isAuthoritativePlayer)
        }

        if (info == null || info.code !in 200..299) {
            // Explicit HTTP 400..599 (e.g. 502, 504) -> dead stream, drop completely!
            return ProbeOutcome(emptyList(), null, payload.isAuthoritativePlayer)
        }

        val stream = buildProgressiveStream(idx, payload, headers, info)
            ?: return ProbeOutcome(emptyList(), null, payload.isAuthoritativePlayer)
        return ProbeOutcome(listOf(stream), null, payload.isAuthoritativePlayer)
    }

    private fun buildProgressiveStream(
        idx: Int,
        payload: MediaStreamPayload,
        headers: Map<String, String>,
        info: HeadInfo?
    ): MediaStream? {
        val url = payload.url
        val contentType = (info?.contentType ?: payload.mimeType.orEmpty()).lowercase()
        if (contentType.startsWith("text/html") ||
            contentType.startsWith("application/xhtml") ||
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
        val inferredHeight = payload.qualityHint?.takeIf { it > 0 } ?: UrlUtils.inferResolution(url)?.takeIf { it > 0 }

        val label = when {
            type == MediaType.AUDIO -> "Original Audio (${container.uppercase()})"
            inferredHeight != null && inferredHeight >= 720 -> "${inferredHeight}p HD"
            inferredHeight != null -> "${inferredHeight}p"
            else -> "HD Video"
        }

        val cleanHeaders = LinkedHashMap<String, String>(headers).apply {
            remove("X-Internal-Default")
            if (info?.acceptsRanges == true) put("X-Internal-Accepts-Ranges", "true")
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
            headers = cleanHeaders
        )
    }

    private fun collapseNullHeightMirrors(group: List<MediaStream>): List<MediaStream> {
        if (group.size <= 1) return group
        val kept = mutableListOf<MediaStream>()
        val seenSizes = HashSet<Long>()
        val seenFiles = HashSet<String>()
        val ordered = group.sortedWith(
            compareByDescending<MediaStream> { it.headers["X-Internal-Authoritative"] == "true" }
                .thenByDescending { it.sizeBytes ?: 0L }
        )
        for (s in ordered) {
            val sz = s.sizeBytes?.takeIf { it > 0L }
            val fname = UrlUtils.fileNameFromUrl(s.url).lowercase()
            val dupSize = sz != null && sz in seenSizes
            val dupFile = fname != "download" && fname.contains('.') && fname in seenFiles
            if (!dupSize && !dupFile) {
                kept += s
                if (sz != null) seenSizes += sz
                if (fname != "download" && fname.contains('.')) seenFiles += fname
            }
        }
        return kept
    }

    private fun rootDomain(host: String?): String? {
        val clean = host?.lowercase()?.removePrefix("www.")?.trimEnd('.') ?: return null
        val parts = clean.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else clean
    }
}
