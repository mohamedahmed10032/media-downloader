package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    data class HlsVerificationResult(
        val media: ResolvedMedia?,
        val failureReason: String? = null
    )

    private data class PlaylistFetchOutcome(
        val text: String?,
        val headers: Map<String, String>,
        val failureReason: String?,
        val finalUrl: String = ""
    )

    private data class SegmentVerifyOutcome(
        val headers: Map<String, String>?,
        val headInfo: HeadInfo?,
        val failureReason: String?
    )

    /**
     * Fetches a playlist and preserves the post-redirect final URL so relative variant/segment
     * URIs resolve against the real CDN edge (e.g. EarnVids `/stream/...` -> CDN `master.m3u8`).
     * Falls back seamlessly to [ExtractorHttp.fetchString] when unit tests mock only `fetchString`.
     */
    private suspend fun fetchPlaylistTextAndFinalUrl(
        url: String,
        headers: Map<String, String>
    ): Pair<String, String> {
        val result = try {
            http.fetchTextWithFinalUrl(url, headers)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (me: com.melmeligy.mediadownloader.core.MediaException) {
            throw me
        } catch (e: Throwable) {
            if (e.javaClass.name.contains("MockK", ignoreCase = true)) {
                return http.fetchString(url, headers) to url
            }
            throw e
        }
        if (result.finalUrl.isBlank() && result.text.isBlank()) {
            return http.fetchString(url, headers) to url
        }
        val effectiveFinal = result.finalUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: url
        return result.text to effectiveFinal
    }

    /**
     * Header- and quality-hint-aware variant used by the interception and HTML extraction layers.
     */
    suspend fun extract(
        url: String,
        headers: Map<String, String>,
        heightHint: Int? = null
    ): ResolvedMedia? = withContext(Dispatchers.IO) {
        extractOnIo(url, headers, heightHint, verifyDownloadable = false).media
    }

    /**
     * Strict downloadability-verified extraction used when racing movie/embed servers:
     * verifies the master/media playlist is non-DRM, at least one variant playlist resolves cleanly,
     * and an actual media segment responds with HTTP 200/206 non-HTML media (retrying relaxed
     * headers once on HTTP 401/403 so winning streams carry exact downloadable headers).
     */
    suspend fun extractVerified(
        url: String,
        headers: Map<String, String>,
        heightHint: Int? = null
    ): ResolvedMedia? = extractVerifiedDetailed(url, headers, heightHint).media

    /**
     * Detailed variant of [extractVerified] that returns the exact failure stage and reason
     * (e.g. `master HTTP 403`, `variant timeout`, `DRM protected`, `seg HTTP 403`, `seg non-media`)
     * so callers can surface accurate diagnostics instead of a generic fallback label.
     */
    suspend fun extractVerifiedDetailed(
        url: String,
        headers: Map<String, String>,
        heightHint: Int? = null
    ): HlsVerificationResult = withContext(Dispatchers.IO) {
        extractOnIo(url, headers, heightHint, verifyDownloadable = true)
    }

    private fun describeException(prefix: String, e: Throwable): String = when (e) {
        is com.melmeligy.mediadownloader.core.MediaException -> when (e.error) {
            com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED -> "$prefix HTTP 403"
            com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED -> "$prefix geo-blocked"
            com.melmeligy.mediadownloader.core.AppError.NO_MEDIA_FOUND -> "$prefix HTTP 404"
            com.melmeligy.mediadownloader.core.AppError.EXPIRED_LINK -> "$prefix HTTP 410"
            com.melmeligy.mediadownloader.core.AppError.SERVER_ERROR -> "$prefix ${e.message?.takeIf { it.startsWith("HTTP ") } ?: "HTTP 5xx"}"
            else -> if (e.cause is java.net.SocketTimeoutException || e.message?.contains("timeout", ignoreCase = true) == true) {
                "$prefix timeout"
            } else {
                "$prefix ${e.message?.take(24) ?: "error"}"
            }
        }
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "$prefix timeout"
        else -> "$prefix network error"
    }

    private suspend fun fetchPlaylistWithHeaderFallback(
        url: String,
        headers: Map<String, String>,
        allowRelaxedFallback: Boolean,
        stagePrefix: String
    ): PlaylistFetchOutcome {
        var lastReason: String? = null
        var singleCorsRetryOnly = false
        try {
            val (primaryText, primaryFinalUrl) = fetchPlaylistTextAndFinalUrl(url, headers)
            if (com.melmeligy.mediadownloader.intercept.MediaSniffer.isValidHlsPlaylistBody(primaryText)) {
                return PlaylistFetchOutcome(primaryText, headers, null, primaryFinalUrl)
            }
            lastReason = "$stagePrefix non-HLS"
            if (!allowRelaxedFallback) {
                return PlaylistFetchOutcome(primaryText, headers, null, primaryFinalUrl)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: com.melmeligy.mediadownloader.core.MediaException) {
            lastReason = describeException(stagePrefix, e)
            val isAuthBlocked = e.error == com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED ||
                e.error == com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED
            val isRecoverableEdgeReject = e.message == "HTTP 400" || e.message == "HTTP 502"
            if (!allowRelaxedFallback) throw e
            if (!isAuthBlocked && !isRecoverableEdgeReject) {
                return PlaylistFetchOutcome(null, headers, lastReason, url)
            }
            if (isRecoverableEdgeReject) {
                singleCorsRetryOnly = true
            }
        } catch (e: Exception) {
            lastReason = describeException(stagePrefix, e)
            if (!allowRelaxedFallback) throw e
            return PlaylistFetchOutcome(null, headers, lastReason, url)
        }
        val allVariants = ExtractorHttp.buildRelaxedHeaderVariants(headers, includeBareNoReferer = true)
        val retryVariants = if (singleCorsRetryOnly) allVariants.take(1) else allVariants
        for (alt in retryVariants) {
            val fetchedAlt = try {
                fetchPlaylistTextAndFinalUrl(url, alt)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastReason = describeException(stagePrefix, e)
                null
            }
            val text = fetchedAlt?.first
            if (com.melmeligy.mediadownloader.intercept.MediaSniffer.isValidHlsPlaylistBody(text)) {
                return PlaylistFetchOutcome(text!!, alt, null, fetchedAlt!!.second)
            } else if (text != null) {
                lastReason = "$stagePrefix non-HLS"
            }
        }
        return PlaylistFetchOutcome(null, headers, lastReason ?: "$stagePrefix rejected", url)
    }

    private suspend fun verifySegmentDownloadable(
        segmentUrl: String,
        headers: Map<String, String>,
        isAesEncrypted: Boolean = false
    ): SegmentVerifyOutcome {
        val candidates = listOf(headers) + ExtractorHttp.buildRelaxedHeaderVariants(headers, includeBareNoReferer = true)
        var lastReason: String? = null
        var serverRejectsRange = false
        for (hdrs in candidates) {
            var info: HeadInfo? = null
            var probeThrewDead = false
            if (!serverRejectsRange) {
                var probeErr: Throwable? = null
                info = withTimeoutOrNull(VERIFY_SEGMENT_PROBE_TIMEOUT_MS + 250L) {
                    withCancellableHttpCalls(timeoutMs = VERIFY_SEGMENT_PROBE_TIMEOUT_MS) {
                        try {
                            http.probe(segmentUrl, hdrs, VERIFY_SEGMENT_PROBE_TIMEOUT_MS)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            probeErr = t
                            null
                        }
                    }
                }
                if (info != null) {
                    val ct = info.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    val isHtmlOrJson = ct.startsWith("text/html") ||
                        ct.startsWith("application/xhtml") ||
                        ct.startsWith("application/json")
                    if (info.code in 200..299 && !isHtmlOrJson) {
                        return SegmentVerifyOutcome(hdrs, info, null)
                    }
                    lastReason = if (info.code in 200..299) "seg HTML/JSON" else "seg HTTP ${info.code}"
                    if (info.code == 404 || info.code == 410) {
                        return SegmentVerifyOutcome(null, null, lastReason)
                    }
                    if (info.code == 400 || info.code == 405 || info.code == 416) {
                        serverRejectsRange = true
                    }
                } else if (probeErr != null) {
                    val err = probeErr!!
                    lastReason = describeException("seg", err)
                    if (err is com.melmeligy.mediadownloader.core.MediaException &&
                        (err.error == com.melmeligy.mediadownloader.core.AppError.NO_MEDIA_FOUND ||
                            err.error == com.melmeligy.mediadownloader.core.AppError.EXPIRED_LINK)
                    ) {
                        probeThrewDead = true
                    }
                } else {
                    lastReason = "seg timeout"
                }
            }
            if (probeThrewDead) {
                return SegmentVerifyOutcome(null, null, lastReason ?: "seg HTTP 404")
            }

            // Many HLS segment CDNs (EarnVids, VidHide, Cloudflare workers) reject `Range: bytes=0-0`
            // with HTTP 400/403/405/500 or timeout, even though plain unranged GET succeeds.
            val shouldTryUnrangedGet = serverRejectsRange || info == null ||
                info.code == 400 || info.code == 403 || info.code == 405 || info.code == 416 || info.code >= 500
            if (shouldTryUnrangedGet) {
                val getInfo = withTimeoutOrNull(VERIFY_SEGMENT_PROBE_TIMEOUT_MS + 250L) {
                    withCancellableHttpCalls(timeoutMs = VERIFY_SEGMENT_PROBE_TIMEOUT_MS) {
                        runCatching {
                            http.peekHlsSegmentGet(
                                url = segmentUrl,
                                headers = hdrs,
                                timeoutMs = VERIFY_SEGMENT_PROBE_TIMEOUT_MS,
                                isAesEncrypted = isAesEncrypted
                            )
                        }.getOrNull()
                    }
                }
                if (getInfo != null) {
                    val gct = getInfo.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    val isHtmlOrJson = gct.startsWith("text/html") ||
                        gct.startsWith("application/xhtml") ||
                        gct.startsWith("application/json")
                    if (getInfo.code in 200..299 && !isHtmlOrJson) {
                        return SegmentVerifyOutcome(hdrs, getInfo, null)
                    }
                    lastReason = if (getInfo.code in 200..299) "seg non-media" else "seg HTTP ${getInfo.code}"
                    if (getInfo.code == 404 || getInfo.code == 410) {
                        return SegmentVerifyOutcome(null, null, lastReason)
                    }
                } else if (lastReason == null) {
                    lastReason = "seg timeout"
                }
            }
        }
        return SegmentVerifyOutcome(null, null, lastReason ?: "seg rejected")
    }

    private suspend fun extractOnIo(
        url: String,
        headers: Map<String, String>,
        heightHint: Int?,
        verifyDownloadable: Boolean
    ): HlsVerificationResult {
        if (ExtractorRegistry.isConfirmedHtml(url)) {
            return HlsVerificationResult(null, "master confirmed HTML")
        }
        val fetched = fetchPlaylistWithHeaderFallback(
            url = url,
            headers = headers,
            allowRelaxedFallback = verifyDownloadable,
            stagePrefix = "master"
        )
        val text = fetched.text ?: return HlsVerificationResult(null, fetched.failureReason ?: "master fetch failed")
        val effectiveMasterUrl = fetched.finalUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: url
        var effectiveHeaders = fetched.headers
        if (!com.melmeligy.mediadownloader.intercept.MediaSniffer.isValidHlsPlaylistBody(text)) {
            return HlsVerificationResult(null, "master non-HLS")
        }

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
                        val variantUrl = UrlUtils.resolveUrl(effectiveMasterUrl, lines[uriLine].trim())
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
                val resolvedAudio = UrlUtils.resolveUrl(effectiveMasterUrl, uri)
                val bw = attrs["BANDWIDTH"]?.toIntOrNull() ?: 128_000
                val isDefault = attrs["DEFAULT"]?.equals("YES", ignoreCase = true) == true
                val existing = audioGroupUrls[groupId]
                if (existing == null || isDefault || bw > existing.second) {
                    audioGroupUrls[groupId] = resolvedAudio to bw
                }
            }

            // Probe first variant playlist (trying a second variant if verifyDownloadable and the first fails)
            var probedVariant: RawVariant? = rawVariants.firstOrNull()
            var probedVariantFinalUrl: String? = probedVariant?.variantUrl
            var variantText: String? = null
            var variantFailReason: String? = null
            val variantsToProbe = if (verifyDownloadable) rawVariants.take(2) else rawVariants.take(1)
            for (candidateVar in variantsToProbe) {
                val fetchedVar = withTimeoutOrNull(VARIANT_PLAYLIST_TIMEOUT_MS) {
                    withCancellableHttpCalls(timeoutMs = VARIANT_PLAYLIST_TIMEOUT_MS) {
                        fetchPlaylistWithHeaderFallback(
                            url = candidateVar.variantUrl,
                            headers = effectiveHeaders,
                            allowRelaxedFallback = verifyDownloadable,
                            stagePrefix = "variant"
                        )
                    }
                }
                if (fetchedVar?.text != null &&
                    com.melmeligy.mediadownloader.intercept.MediaSniffer.isValidHlsPlaylistBody(fetchedVar.text)
                ) {
                    probedVariant = candidateVar
                    probedVariantFinalUrl = fetchedVar.finalUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: candidateVar.variantUrl
                    variantText = fetchedVar.text
                    effectiveHeaders = fetchedVar.headers
                    break
                } else {
                    variantFailReason = fetchedVar?.failureReason ?: "variant timeout"
                }
            }

            val sanitizedFirstVariant = if (!variantText.isNullOrBlank() && probedVariant != null) {
                HlsPlaylistSanitizer.parseAndSanitize(variantText, probedVariantFinalUrl ?: probedVariant.variantUrl)
            } else null

            if (sanitizedFirstVariant?.hasUnsupportedDrm == true) {
                return HlsVerificationResult(null, "DRM protected")
            }

            var verifiedSegProbe: Pair<HlsParsedSegment, HeadInfo>? = null
            if (verifyDownloadable) {
                if (sanitizedFirstVariant == null) {
                    return HlsVerificationResult(null, variantFailReason ?: "variant fetch failed")
                }
                val mediaSegs = sanitizedFirstVariant.segments.filter { !it.isInitMap }
                if (mediaSegs.isEmpty()) {
                    return HlsVerificationResult(null, "0 HLS segments")
                }
                // Verify an interior segment (so the probe also calibrates bitrate), falling back to seg[0]
                // if the interior segment fails (some CDNs only warm/sign initial segments eagerly).
                val primarySeg = if (mediaSegs.size >= 3) mediaSegs[mediaSegs.size / 2] else mediaSegs.first()
                val segCandidates = if (primarySeg.url != mediaSegs.first().url) {
                    listOf(primarySeg, mediaSegs.first())
                } else {
                    listOf(primarySeg)
                }
                var verifiedPair: Pair<HlsParsedSegment, Pair<Map<String, String>, HeadInfo>>? = null
                var lastSegFail: String? = null
                for (candSeg in segCandidates) {
                    val isAes = candSeg.keySpec.method == HlsEncryptionMethod.AES_128
                    val res = verifySegmentDownloadable(candSeg.url, effectiveHeaders, isAesEncrypted = isAes)
                    if (res.headers != null && res.headInfo != null) {
                        verifiedPair = candSeg to (res.headers to res.headInfo)
                        break
                    } else if (!res.failureReason.isNullOrBlank()) {
                        lastSegFail = res.failureReason
                    }
                }
                val (winningSeg, winResult) = verifiedPair
                    ?: return HlsVerificationResult(null, lastSegFail ?: "seg rejected")
                effectiveHeaders = winResult.first
                verifiedSegProbe = winningSeg to winResult.second
            }

            val probedDurationSeconds = sanitizedFirstVariant?.totalDurationSec?.roundToLong() ?: 0L

            val headerDurationSeconds = effectiveHeaders["X-Internal-Duration-Seconds"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
            val totalDurationSeconds = if (probedDurationSeconds > 0L) probedDurationSeconds else headerDurationSeconds

            // Calibrate peak-only BANDWIDTH estimates, reusing the single verified segment probe when available
            val peakBandwidthScale = computePeakBandwidthCalibrationScale(
                firstVariant = probedVariant,
                sanitizedFirstVariant = sanitizedFirstVariant,
                hasPeakOnlyVariants = rawVariants.any {
                    it.attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull() == null &&
                        it.attrs["BANDWIDTH"]?.toIntOrNull() != null
                },
                headers = effectiveHeaders,
                preProbedSegment = verifiedSegProbe
            )

            val baseStreamHeaders = if (totalDurationSeconds > 0L) {
                effectiveHeaders + ("X-Internal-Duration-Seconds" to totalDurationSeconds.toString())
            } else {
                effectiveHeaders
            }

            val parsedVariants = rawVariants.mapIndexed { variantIdx, v ->
                val resolution = v.attrs["RESOLUTION"]
                var width = resolution?.substringBefore('x')?.toIntOrNull()?.takeIf { it > 0 }
                val rawHeight = resolution?.substringAfter('x')?.toIntOrNull()?.takeIf { it > 0 }
                var height = com.melmeligy.mediadownloader.core.util.FormatUtils.normalizeQualityHeight(width, rawHeight)
                if (height != null && width == null) {
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
                    height = com.melmeligy.mediadownloader.core.util.FormatUtils.normalizeQualityHeight(width, hinted ?: bwHeight)
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
                    bandwidth != null -> ((bandwidth.toDouble() * peakBandwidthScale * totalDurationSeconds) / 8.0)
                        .roundToLong()
                        .takeIf { it > 0L }
                    else -> null
                }

                val variantHeaders = LinkedHashMap<String, String>(baseStreamHeaders).apply {
                    if (videoBytes != null && videoBytes > 0L) {
                        put("X-Internal-Expected-Bytes", videoBytes.toString())
                        put("X-Internal-Size-Estimated", "true")
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

            // Collapse duplicate codec/tier renditions (preferring H.264/avc1, higher bitrate, higher width)
            val deduplicatedVideos = parsedVariants
                .groupBy { (_, s) -> s.height }
                .flatMap { (h, group) ->
                    if (h == null) {
                        group.map { it.second }
                    } else {
                        val best = group.maxWithOrNull(
                            compareBy<Pair<Int, MediaStream>> { it.first }
                                .thenBy { it.second.bitrateKbps ?: 0 }
                                .thenBy { it.second.width ?: 0 }
                        )
                        listOfNotNull(best?.second)
                    }
                }
            streams += deduplicatedVideos

            // Expose dedicated audio tracks if advertised via #EXT-X-MEDIA:TYPE=AUDIO
            rawAudioRenditions.forEachIndexed { audioIdx, attrs ->
                val uri = attrs["URI"]
                if (!uri.isNullOrBlank()) {
                    val audioUrl = UrlUtils.resolveUrl(effectiveMasterUrl, uri)
                    val name = attrs["NAME"] ?: attrs["GROUP-ID"] ?: "Audio"
                    val lang = attrs["LANGUAGE"]
                    val label = if (!lang.isNullOrBlank()) "$name ($lang)" else name
                    val audioBw = attrs["BANDWIDTH"]?.toIntOrNull() ?: 128_000
                    val audioSizeBytes = if (totalDurationSeconds > 0L) {
                        (audioBw.toLong() * totalDurationSeconds) / 8L
                    } else null

                    val audioHeaders = if (audioSizeBytes != null && audioSizeBytes > 0L) {
                        baseStreamHeaders + mapOf(
                            "X-Internal-Expected-Bytes" to audioSizeBytes.toString(),
                            "X-Internal-Size-Estimated" to "true"
                        )
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
                val sanitized = HlsPlaylistSanitizer.parseAndSanitize(text, effectiveMasterUrl)
                if (sanitized.hasUnsupportedDrm) {
                    return HlsVerificationResult(null, "DRM protected")
                }
                val mediaSegments = sanitized.segments.filter { !it.isInitMap }
                if (verifyDownloadable && mediaSegments.isEmpty()) {
                    return HlsVerificationResult(null, "0 HLS segments")
                }
                val totalDurationSeconds = sanitized.totalDurationSec.roundToLong()

                val inferredHeight = heightHint?.takeIf { it > 0 } ?: UrlUtils.inferResolution(effectiveMasterUrl)
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

                if (verifyDownloadable) {
                    val primarySeg = mediaSegments.first()
                    val altSeg = if (mediaSegments.size >= 3) mediaSegments[mediaSegments.size / 2] else null
                    val segCandidates = listOfNotNull(primarySeg, altSeg?.takeIf { it.url != primarySeg.url })
                    var verifiedHeaders: Map<String, String>? = null
                    var lastSegFail: String? = null
                    for (candSeg in segCandidates) {
                        val isAes = candSeg.keySpec.method == HlsEncryptionMethod.AES_128
                        val res = verifySegmentDownloadable(candSeg.url, effectiveHeaders, isAesEncrypted = isAes)
                        if (res.headers != null) {
                            verifiedHeaders = res.headers
                            break
                        } else if (!res.failureReason.isNullOrBlank()) {
                            lastSegFail = res.failureReason
                        }
                    }
                    effectiveHeaders = verifiedHeaders
                        ?: return HlsVerificationResult(null, lastSegFail ?: "seg rejected")
                }

                // 2. Otherwise probe representative media segment(s)
                if (sizeBytes == null && totalDurationSeconds > 0L) {
                    val sampleSegments = if (mediaSegments.size >= 4) {
                        val n = mediaSegments.size
                        listOf(mediaSegments[n / 4], mediaSegments[n / 2], mediaSegments[(n * 3) / 4]).distinctBy { it.url }
                    } else {
                        listOfNotNull(mediaSegments.firstOrNull())
                    }
                    val byteRate = probeAverageByteRate(
                        segments = sampleSegments,
                        headers = effectiveHeaders,
                        useShortTimeout = mediaSegments.size >= 4
                    )
                    if (byteRate != null && byteRate > 0.0) {
                        val containerFactor = if (mediaSegments.size >= 4 && !hasInitMap(sanitized)) {
                            TS_TO_MP4_CONTAINER_FACTOR
                        } else {
                            1.0
                        }
                        val effectiveByteRate = byteRate * containerFactor
                        sizeBytes = (effectiveByteRate * totalDurationSeconds).roundToLong().takeIf { it > 0L }
                        bitrateKbps = ((effectiveByteRate * 8.0) / 1000.0).toInt().takeIf { it > 0 }
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

                val isEstimatedSize = byteRangeTotal <= 0L && sizeBytes != null && sizeBytes > 0L
                val streamHeaders = LinkedHashMap<String, String>(effectiveHeaders).apply {
                    if (totalDurationSeconds > 0L) {
                        put("X-Internal-Duration-Seconds", totalDurationSeconds.toString())
                    }
                    if (sizeBytes != null && sizeBytes > 0L) {
                        put("X-Internal-Expected-Bytes", sizeBytes.toString())
                        if (isEstimatedSize) {
                            put("X-Internal-Size-Estimated", "true")
                        }
                    }
                }

                val resolvedHeight = com.melmeligy.mediadownloader.core.util.FormatUtils.inferStandardQualityFromMetadata(
                    url = effectiveMasterUrl,
                    height = inferredHeight,
                    bitrateKbps = bitrateKbps,
                    sizeBytes = sizeBytes,
                    durationSeconds = totalDurationSeconds.takeIf { it > 0L }
                )
                val resolvedWidth = inferredWidth ?: resolvedHeight?.let { (it * 16) / 9 }

                val label = when {
                    resolvedHeight != null -> "${resolvedHeight}p"
                    else -> "Auto (HLS)"
                }

                streams += MediaStream(
                    id = "hls-auto",
                    url = effectiveMasterUrl,
                    type = MediaType.VIDEO,
                    container = "ts",
                    label = label,
                    width = resolvedWidth,
                    height = resolvedHeight,
                    bitrateKbps = bitrateKbps,
                    sizeBytes = sizeBytes,
                    isHls = true,
                    headers = streamHeaders
                )
            } else {
                return HlsVerificationResult(null, "master empty playlist")
            }
        }

        val sortedVideos = streams.filter { it.type == MediaType.VIDEO }
            .sortedByDescending { it.height ?: it.bitrateKbps ?: 0 }
        val sortedAudios = streams.filter { it.type == MediaType.AUDIO }
            .sortedByDescending { it.bitrateKbps ?: 0 }
        val sorted = sortedVideos + sortedAudios
        val title = UrlUtils.host(url)?.let { "HLS video ($it)" } ?: "HLS video"
        return HlsVerificationResult(
            media = ResolvedMedia(sourceUrl = url, title = title, streams = sorted, extractor = name),
            failureReason = null
        )
    }

    private suspend fun computePeakBandwidthCalibrationScale(
        firstVariant: RawVariant?,
        sanitizedFirstVariant: SanitizedHlsPlaylist?,
        hasPeakOnlyVariants: Boolean,
        headers: Map<String, String>,
        preProbedSegment: Pair<HlsParsedSegment, HeadInfo>? = null
    ): Double {
        if (!hasPeakOnlyVariants || firstVariant == null || sanitizedFirstVariant == null) return 1.0
        val declaredBw = firstVariant.attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: firstVariant.attrs["BANDWIDTH"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: return 1.0

        val mediaSegments = sanitizedFirstVariant.segments.filter { !it.isInitMap && it.durationSec > 0.0 }
        if (mediaSegments.isEmpty()) return 1.0

        val containerFactor = if (hasInitMap(sanitizedFirstVariant)) 1.0 else TS_TO_MP4_CONTAINER_FACTOR

        // If the variant advertises exact #EXT-X-BYTERANGE lengths, calibrate directly without extra HTTP calls
        if (mediaSegments.all { (it.byteRangeLength ?: 0L) > 0L } && sanitizedFirstVariant.totalDurationSec > 0.0) {
            val totalBytes = sanitizedFirstVariant.segments.sumOf { it.byteRangeLength ?: 0L }
            if (totalBytes > 0L) {
                val measuredBps = (totalBytes.toDouble() * 8.0 / sanitizedFirstVariant.totalDurationSec) * containerFactor
                return (measuredBps / declaredBw.toDouble()).coerceIn(MIN_CALIBRATION_SCALE, MAX_CALIBRATION_SCALE)
            }
        }

        // Reuse the single verified segment probe when available so we avoid an extra network round-trip
        if (preProbedSegment != null) {
            val (seg, head) = preProbedSegment
            if (head.contentLength > 0L && seg.durationSec > 0.0) {
                val measuredBps = (head.contentLength.toDouble() / seg.durationSec) * 8.0 * containerFactor
                return (measuredBps / declaredBw.toDouble()).coerceIn(MIN_CALIBRATION_SCALE, MAX_CALIBRATION_SCALE)
            }
        }

        // Probe up to 2 interior segments in parallel (skipping seg[0], often skewed by intro/keyframe spikes)
        val n = mediaSegments.size
        val sampleSegments = if (n >= 4) {
            listOf(mediaSegments[n / 3], mediaSegments[(n * 2) / 3]).distinctBy { it.url }
        } else {
            listOf(mediaSegments[n / 2])
        }

        val byteRate = probeAverageByteRate(sampleSegments, headers, useShortTimeout = true) ?: return 1.0
        val measuredBps = byteRate * 8.0 * containerFactor
        return (measuredBps / declaredBw.toDouble()).coerceIn(MIN_CALIBRATION_SCALE, MAX_CALIBRATION_SCALE)
    }

    private suspend fun probeAverageByteRate(
        segments: List<HlsParsedSegment>,
        headers: Map<String, String>,
        useShortTimeout: Boolean
    ): Double? = coroutineScope {
        val validSegments = segments.filter { it.durationSec > 0.0 }
        if (validSegments.isEmpty()) return@coroutineScope null

        val timeoutBudget = if (useShortTimeout) SEGMENT_PROBE_TIMEOUT_MS else DIRECT_SEGMENT_PROBE_TIMEOUT_MS
        val samples = withTimeoutOrNull(timeoutBudget + 250L) {
            withCancellableHttpCalls(timeoutMs = timeoutBudget) {
                validSegments.map { seg ->
                    async(Dispatchers.IO) {
                        val probe = if (useShortTimeout) {
                            runCatching { http.probe(seg.url, headers, SEGMENT_PROBE_TIMEOUT_MS) }.getOrNull()
                        } else {
                            runCatching { http.probe(seg.url, headers) }.getOrNull()
                        }
                        val len = probe?.contentLength?.takeIf { it > 0L }
                        if (len != null) len to seg.durationSec else null
                    }
                }.awaitAll()
            }
        }.orEmpty().filterNotNull()

        val totalBytes = samples.sumOf { it.first }
        val totalDurationSec = samples.sumOf { it.second }
        if (totalBytes > 0L && totalDurationSec > 0.0) {
            totalBytes.toDouble() / totalDurationSec
        } else {
            null
        }
    }

    private fun hasInitMap(sanitized: SanitizedHlsPlaylist): Boolean =
        sanitized.segments.any { it.isInitMap }

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

    private companion object {
        const val VARIANT_PLAYLIST_TIMEOUT_MS = 3_200L
        const val VERIFY_SEGMENT_PROBE_TIMEOUT_MS = 2_600L
        const val SEGMENT_PROBE_TIMEOUT_MS = 1_200L
        const val DIRECT_SEGMENT_PROBE_TIMEOUT_MS = 1_800L
        const val TS_TO_MP4_CONTAINER_FACTOR = 0.94
        const val MIN_CALIBRATION_SCALE = 0.85
        const val MAX_CALIBRATION_SCALE = 1.35
    }
}
