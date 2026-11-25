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
import kotlinx.coroutines.withContext
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

    override val name = "Browser Stream"
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

    override suspend fun extract(url: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        extractOnIo(url)
    }

    private suspend fun extractOnIo(url: String): ResolvedMedia? = coroutineScope {
        val rawCandidates = engine.payloadsFor(url)
            .filter { it.streamType != StreamType.BLOB && it.streamType != StreamType.UNKNOWN }
            .filterNot { MediaSniffer.isHexCipherUrl(it.url) || MediaSniffer.isPreviewOrAdUrl(it.url) }
            .distinctBy { "${it.streamType}|${MediaSniffer.dedupeKey(it.url)}|${it.qualityHint?.takeIf { q -> q > 0 } ?: 0}" }
            .sortedWith(
                compareByDescending<MediaStreamPayload> { it.streamType == StreamType.HLS && looksLikeMasterPlaylistUrl(it.url) }
                    .thenByDescending { it.isAuthoritativePlayer }
                    .thenByDescending { it.streamType == StreamType.HLS }
                    .thenByDescending { it.detectedAt }
            )

        if (rawCandidates.isEmpty()) return@coroutineScope null

        val sameActivePage = runCatching {
            engine.isSamePage(engine.currentPageUrl, url)
        }.getOrDefault(false)
        var pageTitle: String? = rawCandidates.firstNotNullOfOrNull {
            it.pageTitle?.takeIf { t -> !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(t, url) }
        } ?: if (sameActivePage) {
            runCatching { engine.currentPageTitle }.getOrNull()?.takeIf {
                !HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(it, url)
            }
        } else null
        var pageThumb: String? = rawCandidates.firstNotNullOfOrNull {
            HtmlThumbnailExtractor.resolveValidImageCandidate(it.thumbnailUrl, url)
        } ?: if (sameActivePage) {
            HtmlThumbnailExtractor.resolveValidImageCandidate(
                runCatching { engine.currentPageThumbnail }.getOrNull(),
                url
            )
        } else null
        val pageDurationSec: Long? = rawCandidates.firstNotNullOfOrNull { it.durationSeconds?.takeIf { d -> d > 0L } }

        val sharedUserAgent = rawCandidates.firstNotNullOfOrNull { p ->
            p.headersMap.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
        }
        val pageFetchHeaders = LinkedHashMap<String, String>().apply {
            put("Referer", url)
            if (sharedUserAgent != null) put("User-Agent", sharedUserAgent)
            val pageRoot = rootDomain(UrlUtils.host(url))
            rawCandidates.firstOrNull { p ->
                rootDomain(UrlUtils.host(p.url)) == pageRoot && !p.headersMap["Cookie"].isNullOrBlank()
            }?.headersMap?.get("Cookie")?.let { put("Cookie", it) }
            if (pageDurationSec != null && pageDurationSec > 0L) {
                put("X-Internal-Duration-Seconds", pageDurationSec.toString())
            }
        }

        // Probe the freshest candidates concurrently with a strict wall-clock ceiling so dead/expired
        // URLs never stall the sheet on repeated attempts.
        val outcomes = withTimeoutOrNull(INTERCEPTED_WALL_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = CANDIDATE_PROBE_TIMEOUT_MS) {
                rawCandidates.take(MAX_INTERCEPTED_CANDIDATES).mapIndexed { idx, payload ->
                    async(Dispatchers.IO) {
                        val cleanUrl = MediaSniffer.cleanMediaUrl(payload.url)
                        val sanitizedHint = payload.qualityHint?.takeIf { it > 0 }
                        val headers = isolateCandidateHeaders(
                            payload = payload,
                            pageUrl = url,
                            sharedUserAgent = sharedUserAgent,
                            pageDurationSec = pageDurationSec
                        )
                        withTimeoutOrNull(PER_CANDIDATE_WALL_TIMEOUT_MS) {
                            when (payload.streamType) {
                                StreamType.HLS -> {
                                    val verifiedAttempt = runCatching {
                                        hlsExtractor.extractVerifiedDetailed(cleanUrl, headers, sanitizedHint)
                                    }
                                    val resolvedMedia = if (verifiedAttempt.isSuccess) {
                                        verifiedAttempt.getOrNull()?.media
                                    } else {
                                        // Only reached when a strict MockK test stubs `extract()` without `extractVerifiedDetailed()`.
                                        runCatching {
                                            if (sanitizedHint != null) {
                                                hlsExtractor.extract(cleanUrl, headers, sanitizedHint)
                                            } else {
                                                hlsExtractor.extract(cleanUrl, headers)
                                            }
                                        }.getOrNull()
                                    }
                                    val list = resolvedMedia?.streams?.map { s ->
                                        val cleanH = s.headers.filterKeys { !it.equals("X-Internal-Default", ignoreCase = true) }
                                        if (payload.isAuthoritativePlayer) {
                                            s.copy(headers = cleanH + ("X-Internal-Authoritative" to "true"))
                                        } else {
                                            s.copy(headers = cleanH)
                                        }
                                    }.orEmpty()
                                    if (list.isEmpty() && verifiedAttempt.isSuccess) {
                                        val reason = verifiedAttempt.getOrNull()?.failureReason.orEmpty()
                                        if (reason.contains("HTTP 4") ||
                                            reason.contains("HTTP 5") ||
                                            reason.contains("HTML", ignoreCase = true) ||
                                            reason.contains("DRM", ignoreCase = true) ||
                                            reason.contains("not #EXTM3U", ignoreCase = true)
                                        ) {
                                            engine.evictPayloadUrl(payload.url)
                                        }
                                    }
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
                        } ?: ProbeOutcome(emptyList(), null, payload.isAuthoritativePlayer)
                    }
                }.awaitAll()
            }
        }.orEmpty()

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

        // Skip redundant HTML re-scraping when a verified HLS stream or >= 2 distinct authoritative heights are already intercepted
        val hasVerifiedHls = liveStreamsAll.any { it.type == MediaType.VIDEO && it.isHls }
        val distinctAuthHeights = survivingAuth
            .filter { it.type == MediaType.VIDEO }
            .mapNotNull { it.height?.takeIf { h -> h > 0 } }
            .toSet()

        val ext = UrlUtils.fileExtension(url)?.lowercase()
        val isWebPage = ext == null ||
            (ext !in UrlUtils.VIDEO_EXTENSIONS && ext !in UrlUtils.AUDIO_EXTENSIONS && ext != "m3u8" && ext != "mpd")
        val isPortalWatchPage = isPortalWatchOrDownloadUrl(url)
        val liveOnlyHasTinyProgressiveClips = liveResolved.isNotEmpty() && liveResolved.all { s ->
            !s.isHls &&
                s.sizeBytes != null &&
                s.sizeBytes in 1L..MAX_PORTAL_AD_CLIP_BYTES &&
                (s.headers["X-Internal-Duration-Seconds"]?.toLongOrNull() ?: pageDurationSec).let { d ->
                    d == null || d <= MAX_PORTAL_AD_CLIP_DURATION_SEC
                }
        }

        var htmlResolved: ResolvedMedia? = null
        if (isWebPage &&
            htmlMediaExtractor != null &&
            !hasVerifiedHls &&
            distinctAuthHeights.size < 2 &&
            !UrlUtils.isDedicatedSocialPlatformUrl(url)
        ) {
            val enrichTimeoutMs = if (isPortalWatchPage || liveOnlyHasTinyProgressiveClips) {
                PORTAL_HTML_ENRICH_TIMEOUT_MS
            } else {
                HTML_ENRICH_TIMEOUT_MS
            }
            htmlResolved = withTimeoutOrNull(enrichTimeoutMs) {
                withCancellableHttpCalls(timeoutMs = enrichTimeoutMs) {
                    runCatching {
                        htmlMediaExtractor.extractWithHeaders(url, pageFetchHeaders, allowHeadlessFallback = false)
                    }.getOrNull()
                }
            }
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
            withTimeoutOrNull(METADATA_FETCH_TIMEOUT_MS) {
                withCancellableHttpCalls(timeoutMs = METADATA_FETCH_TIMEOUT_MS) {
                    runCatching {
                        val htmlText = http.fetchString(url, pageFetchHeaders)
                        if (htmlText.isNotBlank()) {
                            val doc = Jsoup.parse(htmlText, url)
                            if (pageThumb.isNullOrBlank()) {
                                val playerPoster = JsUnpacker.extractPlayerConfigs(htmlText, baseUrl = url).posterUrl
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
            }
        }

        if (HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(pageTitle, url)) {
            HtmlThumbnailExtractor.humanReadableTitleFromUrl(url)?.let { pageTitle = it }
        }
        if (sameActivePage && (!pageTitle.isNullOrBlank() || !pageThumb.isNullOrBlank())) {
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

        // On movie/episode portal watch/download pages, unconditionally drop tiny cross-host
        // progressive ad/promo clips (< 2.5 MB, unknown or <= 30s duration) even if no main stream
        // survived so ExtractorRegistry cleanly falls through to HtmlMediaExtractor / mirror failover.
        val portalSanitized = dropPortalTinyAdClips(
            pageUrl = url,
            streams = filtered,
            pageDurationSec = pageDurationSec
        )
        if (portalSanitized.isEmpty()) return@coroutineScope null

        val hasFullLengthMainVideo = (pageDurationSec != null && pageDurationSec >= 45L) ||
            portalSanitized.any { it.type == MediaType.VIDEO && (it.sizeBytes ?: 0L) >= 5_000_000L }

        val cleanFiltered = if (hasFullLengthMainVideo) {
            portalSanitized.filterNot { s ->
                s.type == MediaType.VIDEO &&
                    !s.isHls &&
                    s.headers["X-Internal-Authoritative"] != "true" &&
                    s.sizeBytes != null &&
                    s.sizeBytes in 1..1_200_000L
            }.ifEmpty { portalSanitized }
        } else {
            portalSanitized
        }

        val rawAudios = cleanFiltered.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: (it.sizeBytes?.div(1024)?.toInt() ?: 0) }
        val bestAudio = rawAudios.firstOrNull()

        val rawVideos = cleanFiltered.filter { it.type == MediaType.VIDEO }
            .map { v ->
                val durSec = v.headers["X-Internal-Duration-Seconds"]?.toLongOrNull() ?: pageDurationSec
                val validW = v.width?.takeIf { it > 0 }
                val validH = com.melmeligy.mediadownloader.core.util.FormatUtils.inferStandardQualityFromMetadata(
                    url = v.url,
                    label = v.label,
                    width = validW,
                    height = v.height,
                    bitrateKbps = v.bitrateKbps,
                    sizeBytes = v.sizeBytes,
                    durationSeconds = durSec
                )
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
            val durSec = s.headers["X-Internal-Duration-Seconds"]?.toLongOrNull() ?: pageDurationSec
            val h = com.melmeligy.mediadownloader.core.util.FormatUtils.inferStandardQualityFromMetadata(
                url = s.url,
                label = s.label,
                width = s.width,
                height = s.height,
                bitrateKbps = s.bitrateKbps,
                sizeBytes = s.sizeBytes,
                durationSeconds = durSec
            )
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
            // Strictly attach companion audio ONLY when the video track is explicitly marked DASH video-only
            val needsAudio = bestAudio != null &&
                !s.isHls &&
                s.headers["X-Internal-Companion-Audio"] == null &&
                s.headers["X-Internal-Dash-VideoOnly"] == "true"
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
            // Explicit HTTP 401/403/404/410 -> dead stream, drop & evict so repeated attempts don't re-probe it
            if (e.error == AppError.NO_MEDIA_FOUND ||
                e.error == AppError.EXPIRED_LINK ||
                e.error == AppError.ACCESS_DENIED ||
                e.error == AppError.GEO_BLOCKED
            ) {
                engine.evictPayloadUrl(url)
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
            // Explicit HTTP 400..599 (e.g. 502, 504) -> dead stream, drop & evict completely!
            engine.evictPayloadUrl(url)
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

        val finalStreamUrl = if (shouldPreserveRedirectorUrl(url, info?.finalUrl)) {
            url
        } else {
            info?.finalUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?.let { MediaSniffer.cleanMediaUrl(it) }
                ?: url
        }

        return MediaStream(
            id = "intercepted-$idx",
            url = finalStreamUrl,
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

    companion object {
        private const val MAX_INTERCEPTED_CANDIDATES = 8
        private const val INTERCEPTED_WALL_TIMEOUT_MS = 4_500L
        private const val PER_CANDIDATE_WALL_TIMEOUT_MS = 3_200L
        private const val CANDIDATE_PROBE_TIMEOUT_MS = 2_800L
        private const val HTML_ENRICH_TIMEOUT_MS = 3_000L
        private const val PORTAL_HTML_ENRICH_TIMEOUT_MS = 5_500L
        private const val METADATA_FETCH_TIMEOUT_MS = 2_500L
        internal const val MAX_PORTAL_AD_CLIP_BYTES = 2_500_000L
        internal const val MAX_PORTAL_AD_CLIP_DURATION_SEC = 30L

        internal fun isPortalWatchOrDownloadUrl(url: String): Boolean {
            if (UrlUtils.isDedicatedSocialPlatformUrl(url)) return false
            val host = UrlUtils.host(url)?.lowercase().orEmpty()
            if (host.contains("topcima") || host.contains("topcinema") ||
                host.contains("wecima") || host.contains("mycima") ||
                host.contains("arabseed") || host.contains("cimawbas") ||
                host.contains("faselhd") || host.contains("akwam") ||
                host.contains("egybest") || host.contains("shahid")
            ) {
                return true
            }
            val path = runCatching {
                java.net.URI(UrlUtils.encodeNonAsciiUrl(url)).path.orEmpty().lowercase()
            }.getOrDefault(url.lowercase())
            return path.contains("/watch") ||
                path.contains("/download") ||
                path.startsWith("/episode/") ||
                path.startsWith("/film/") ||
                path.startsWith("/movie/") ||
                path.startsWith("/series/")
        }

        internal fun dropPortalTinyAdClips(
            pageUrl: String,
            streams: List<MediaStream>,
            pageDurationSec: Long? = null
        ): List<MediaStream> {
            if (!isPortalWatchOrDownloadUrl(pageUrl)) return streams
            val pageRoot = rootDomainStatic(UrlUtils.host(pageUrl))
            return streams.filterNot { s ->
                if (s.type != MediaType.VIDEO || s.isHls) return@filterNot false
                val size = s.sizeBytes ?: return@filterNot false
                if (size !in 1L..MAX_PORTAL_AD_CLIP_BYTES) return@filterNot false
                val dur = s.headers["X-Internal-Duration-Seconds"]?.toLongOrNull() ?: pageDurationSec
                val shortOrUnknownDur = dur == null || dur <= MAX_PORTAL_AD_CLIP_DURATION_SEC
                if (!shortOrUnknownDur) return@filterNot false
                val streamRoot = rootDomainStatic(UrlUtils.host(s.url))
                val isCrossHost = pageRoot == null || streamRoot == null || streamRoot != pageRoot
                isCrossHost && s.headers["X-Internal-Authoritative"] != "true"
            }
        }

        private fun rootDomainStatic(host: String?): String? {
            val clean = host?.lowercase()?.removePrefix("www.")?.trimEnd('.') ?: return null
            val parts = clean.split('.')
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else clean
        }

        /**
         * Isolates per-candidate HTTP headers so cookies, Referer, and Origin from one host
         * never bleed into an unrelated candidate's probe or download request.
         */
        internal fun isolateCandidateHeaders(
            payload: MediaStreamPayload,
            pageUrl: String,
            sharedUserAgent: String?,
            pageDurationSec: Long?
        ): Map<String, String> {
            return LinkedHashMap<String, String>().apply {
                putAll(payload.headersMap)
                remove("X-Internal-Default")
                if (keys.none { it.equals("Referer", ignoreCase = true) }) {
                    val fallbackRef = payload.pageUrl?.takeIf { it.isNotBlank() } ?: pageUrl
                    if (fallbackRef.isNotBlank()) put("Referer", fallbackRef)
                }
                if (keys.none { it.equals("User-Agent", ignoreCase = true) } && !sharedUserAgent.isNullOrBlank()) {
                    put("User-Agent", sharedUserAgent)
                }
                val dur = payload.durationSeconds?.takeIf { it > 0L } ?: pageDurationSec?.takeIf { it > 0L }
                if (dur != null) {
                    put("X-Internal-Duration-Seconds", dur.toString())
                }
                if (payload.isAuthoritativePlayer) {
                    put("X-Internal-Authoritative", "true")
                }
            }
        }

        /**
         * Returns true when [originalUrl] is a tokenized or cross-host redirector whose
         * resolved [finalUrl] may be single-use or IP/session-bound and therefore must NOT
         * replace [originalUrl].
         */
        internal fun shouldPreserveRedirectorUrl(originalUrl: String, finalUrl: String?): Boolean {
            if (finalUrl.isNullOrBlank() || finalUrl == originalUrl) return true
            val lower = originalUrl.lowercase()
            if (lower.contains("/get_video") ||
                lower.contains("/d/") ||
                lower.contains("token=") ||
                lower.contains("expires=") ||
                lower.contains("md5=")
            ) {
                return true
            }
            val origHost = UrlUtils.host(originalUrl)?.lowercase()?.removePrefix("www.")
            val finalHost = UrlUtils.host(finalUrl)?.lowercase()?.removePrefix("www.")
            return origHost != null && finalHost != null && origHost != finalHost
        }

        internal fun looksLikeMasterPlaylistUrl(url: String): Boolean {
            val lower = url.substringBefore('?').lowercase()
            return lower.contains("master") ||
                lower.contains("playlist") ||
                lower.contains("manifest") ||
                lower.endsWith("/index.m3u8")
        }
    }
}
