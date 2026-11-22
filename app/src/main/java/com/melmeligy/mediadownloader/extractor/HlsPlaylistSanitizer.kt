package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import java.net.URI

enum class HlsEncryptionMethod {
    NONE,
    AES_128,
    UNSUPPORTED_DRM
}

data class HlsKeySpec(
    val method: HlsEncryptionMethod = HlsEncryptionMethod.NONE,
    val uri: String? = null,
    val ivHex: String? = null,
    val keyFormat: String? = null
)

data class HlsParsedSegment(
    val url: String,
    val durationSec: Double,
    val byteRangeOffset: Long? = null,
    val byteRangeLength: Long? = null,
    val isInitMap: Boolean = false,
    val keySpec: HlsKeySpec = HlsKeySpec(),
    val mediaSequence: Long = 0L,
    val discontinuityBlock: Int = 0
)

data class SanitizedHlsPlaylist(
    val segments: List<HlsParsedSegment>,
    val removedAdBlocks: Int,
    val removedAdSegments: Int,
    val totalDurationSec: Double,
    val hasUnsupportedDrm: Boolean
)

/**
 * Parses HLS media playlists and sanitizes injected `#EXT-X-DISCONTINUITY` ad/bumper blocks
 * before sizing, downloading, and muxing.
 *
 * Preserves each surviving segment's original `mediaSequence` so RFC 8216 clear `AES-128`
 * implicit IV computation stays valid even when preceding ad blocks are removed, while
 * strictly flagging DRM (`SAMPLE-AES*`, non-identity `KEYFORMAT`) as unsupported.
 */
object HlsPlaylistSanitizer {

    private val MAP_URI_REGEX = Regex("""URI="([^"]+)"""")
    private val MAP_RANGE_REGEX = Regex("""BYTERANGE="(\d+)(?:@(\d+))?"""")
    private val BYTE_RANGE_REGEX = Regex("""#EXT-X-BYTERANGE:\s*(\d+)(?:@(\d+))?""", RegexOption.IGNORE_CASE)
    private val EXTINF_REGEX = Regex("""#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val MEDIA_SEQ_REGEX = Regex("""#EXT-X-MEDIA-SEQUENCE:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val ATTR_REGEX = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private val AD_SEGMENT_PATH_MARKERS = listOf(
        "/ads/",
        "/ad/",
        "/adbreak",
        "/preroll",
        "/midroll",
        "/postroll",
        "/bumper",
        "/sponsor",
        "doubleclick",
        "googlesyndication",
        "vast-ad",
        "_ad_",
        "-ad-"
    )

    fun parseAndSanitize(mediaText: String, baseUrl: String): SanitizedHlsPlaylist {
        val rawSegments = mutableListOf<HlsParsedSegment>()
        var mediaSeq = 0L
        var pendingDuration = 0.0
        var pendingRangeLen: Long? = null
        var pendingRangeOffset: Long? = null
        val nextImplicitOffsetByUri = HashMap<String, Long>()
        var currentKey = HlsKeySpec()
        var currentBlock = 0
        var lastInitKeyByBlock = HashMap<Int, String>()
        var sawUnsupportedDrm = false

        for (rawLine in mediaText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    MEDIA_SEQ_REGEX.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
                        mediaSeq = it
                    }
                }
                line.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) &&
                    !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE", ignoreCase = true) -> {
                    currentBlock++
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    val method = attrs["METHOD"]?.uppercase().orEmpty()
                    val keyFormat = attrs["KEYFORMAT"]?.takeIf { it.isNotBlank() }
                    val uri = attrs["URI"]?.takeIf { it.isNotBlank() }?.let { UrlUtils.resolveUrl(baseUrl, it) }
                    val ivHex = attrs["IV"]?.trim()?.removePrefix("0x")?.removePrefix("0X")?.takeIf { it.isNotEmpty() }

                    currentKey = when {
                        method.isEmpty() || method == "NONE" -> HlsKeySpec(HlsEncryptionMethod.NONE)
                        method == "AES-128" && (keyFormat == null || keyFormat.equals("identity", ignoreCase = true)) ->
                            HlsKeySpec(
                                method = HlsEncryptionMethod.AES_128,
                                uri = uri,
                                ivHex = ivHex,
                                keyFormat = keyFormat
                            )
                        else -> {
                            sawUnsupportedDrm = true
                            HlsKeySpec(
                                method = HlsEncryptionMethod.UNSUPPORTED_DRM,
                                uri = uri,
                                ivHex = ivHex,
                                keyFormat = keyFormat
                            )
                        }
                    }
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val mapUri = MAP_URI_REGEX.find(line)?.groupValues?.getOrNull(1)
                    if (!mapUri.isNullOrBlank()) {
                        val resolvedMap = UrlUtils.resolveUrl(baseUrl, mapUri)
                        val rangeMatch = MAP_RANGE_REGEX.find(line)
                        val rLen = rangeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                        val rOff = rangeMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                            ?: if (rLen != null) 0L else null
                        val initKey = "$resolvedMap|${rOff ?: -1}|${rLen ?: -1}"
                        if (lastInitKeyByBlock[currentBlock] != initKey) {
                            lastInitKeyByBlock[currentBlock] = initKey
                            rawSegments.add(
                                HlsParsedSegment(
                                    url = resolvedMap,
                                    durationSec = 0.0,
                                    byteRangeOffset = rOff,
                                    byteRangeLength = rLen,
                                    isInitMap = true,
                                    keySpec = currentKey,
                                    mediaSequence = mediaSeq,
                                    discontinuityBlock = currentBlock
                                )
                            )
                        }
                    }
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingDuration = EXTINF_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    val m = BYTE_RANGE_REGEX.find(line)
                    pendingRangeLen = m?.groupValues?.getOrNull(1)?.toLongOrNull()
                    pendingRangeOffset = m?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                }
                !line.startsWith("#") -> {
                    val resolvedUri = UrlUtils.resolveUrl(baseUrl, line)
                    val len = pendingRangeLen
                    val offset = if (len != null) {
                        val explicit = pendingRangeOffset ?: nextImplicitOffsetByUri[resolvedUri] ?: 0L
                        nextImplicitOffsetByUri[resolvedUri] = explicit + len
                        explicit
                    } else null

                    rawSegments.add(
                        HlsParsedSegment(
                            url = resolvedUri,
                            durationSec = pendingDuration,
                            byteRangeOffset = offset,
                            byteRangeLength = len,
                            isInitMap = false,
                            keySpec = currentKey,
                            mediaSequence = mediaSeq,
                            discontinuityBlock = currentBlock
                        )
                    )
                    mediaSeq++
                    pendingDuration = 0.0
                    pendingRangeLen = null
                    pendingRangeOffset = null
                }
            }
        }

        if (rawSegments.isEmpty()) {
            return SanitizedHlsPlaylist(
                segments = emptyList(),
                removedAdBlocks = 0,
                removedAdSegments = 0,
                totalDurationSec = 0.0,
                hasUnsupportedDrm = sawUnsupportedDrm
            )
        }

        val blocks = rawSegments.groupBy { it.discontinuityBlock }.toSortedMap()
        if (blocks.size <= 1) {
            val filtered = rawSegments.filterNot { !it.isInitMap && isExplicitAdSegment(it.url) }
            val kept = if (filtered.any { !it.isInitMap }) filtered else rawSegments
            val removedSegs = rawSegments.count { !it.isInitMap } - kept.count { !it.isInitMap }
            val drmInKept = sawUnsupportedDrm || kept.any { it.keySpec.method == HlsEncryptionMethod.UNSUPPORTED_DRM }
            return SanitizedHlsPlaylist(
                segments = kept,
                removedAdBlocks = 0,
                removedAdSegments = removedSegs,
                totalDurationSec = kept.filter { !it.isInitMap }.sumOf { it.durationSec },
                hasUnsupportedDrm = drmInKept
            )
        }

        data class BlockStats(
            val id: Int,
            val allSegments: List<HlsParsedSegment>,
            val mediaSegments: List<HlsParsedSegment>,
            val durationSec: Double,
            val hostFamily: String,
            val pathPrefix: String,
            val hasExplicitAd: Boolean
        )

        val stats = blocks.map { (blockId, segs) ->
            val media = segs.filter { !it.isInitMap }
            val dur = media.sumOf { it.durationSec }
            val hostFam = media.map { hostFamily(it.url) }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                .orEmpty()
            val prefix = media.firstOrNull()?.let { pathPrefix(it.url) }.orEmpty()
            val explicitAd = media.any { isExplicitAdSegment(it.url) }
            BlockStats(blockId, segs, media, dur, hostFam, prefix, explicitAd)
        }.filter { it.mediaSegments.isNotEmpty() }

        if (stats.isEmpty()) {
            return SanitizedHlsPlaylist(
                segments = rawSegments,
                removedAdBlocks = 0,
                removedAdSegments = 0,
                totalDurationSec = 0.0,
                hasUnsupportedDrm = sawUnsupportedDrm
            )
        }

        val nonAdStats = stats.filterNot { it.hasExplicitAd }
        val durationByHost = (nonAdStats.ifEmpty { stats })
            .groupBy { it.hostFamily }
            .mapValues { (_, list) -> list.sumOf { it.durationSec } }
        val primaryHostFamily = durationByHost.maxByOrNull { it.value }?.key.orEmpty()
        val primaryTotalDuration = durationByHost[primaryHostFamily] ?: 0.0
        val primaryBlock = (nonAdStats.ifEmpty { stats }).maxByOrNull { it.durationSec }
        val primaryPathPrefix = primaryBlock?.pathPrefix.orEmpty()
        val firstBlockId = stats.first().id
        val lastBlockId = stats.last().id

        val droppedBlockIds = HashSet<Int>()
        for (b in stats) {
            val differentHost = primaryHostFamily.isNotEmpty() &&
                b.hostFamily.isNotEmpty() &&
                b.hostFamily != primaryHostFamily
            val isEdgeBlock = b.id == firstBlockId || b.id == lastBlockId
            val distinctPath = primaryPathPrefix.isNotEmpty() &&
                b.pathPrefix.isNotEmpty() &&
                b.pathPrefix != primaryPathPrefix

            val drop = when {
                b.hasExplicitAd -> true
                differentHost && primaryTotalDuration >= 60.0 && b.durationSec <= 60.0 -> true
                differentHost && b.durationSec > 0.0 && primaryTotalDuration >= b.durationSec * 3.0 -> true
                b.durationSec in 0.1..45.0 && b.mediaSegments.size <= 8 && primaryTotalDuration >= 120.0 &&
                    distinctPath && (isEdgeBlock || primaryTotalDuration >= b.durationSec * 3.0) -> true
                else -> false
            }
            if (drop) droppedBlockIds.add(b.id)
        }

        val survivingStats = stats.filterNot { it.id in droppedBlockIds }
        val finalBlocks = if (survivingStats.isNotEmpty()) survivingStats else listOfNotNull(primaryBlock)
        val keptSegments = finalBlocks.flatMap { it.allSegments }
        val removedBlocks = (stats.size - finalBlocks.size).coerceAtLeast(0)
        val removedSegments = (rawSegments.count { !it.isInitMap } - keptSegments.count { !it.isInitMap }).coerceAtLeast(0)
        val drmInKept = keptSegments.any { it.keySpec.method == HlsEncryptionMethod.UNSUPPORTED_DRM }

        return SanitizedHlsPlaylist(
            segments = keptSegments,
            removedAdBlocks = removedBlocks,
            removedAdSegments = removedSegments,
            totalDurationSec = keptSegments.filter { !it.isInitMap }.sumOf { it.durationSec },
            hasUnsupportedDrm = drmInKept
        )
    }

    internal fun isExplicitAdSegment(url: String): Boolean {
        if (AdBlocker.isAd(url)) return true
        if (MediaSniffer.isPreviewOrAdUrl(url)) return true
        val lower = url.lowercase()
        return AD_SEGMENT_PATH_MARKERS.any { lower.contains(it) }
    }

    private fun hostFamily(url: String): String {
        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
            .removePrefix("www.")
        if (host.isEmpty()) return ""
        val parts = host.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    }

    private fun pathPrefix(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash > 0) path.substring(0, lastSlash).lowercase() else ""
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(raw).forEach { match ->
            result[match.groupValues[1].uppercase()] = match.groupValues[2].trim('"')
        }
        return result
    }
}
